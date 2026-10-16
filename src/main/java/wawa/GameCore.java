package wawa;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;

/**
 * Vertical slice of wild.apk: the area maps (assets/eng/map, drawn with the real tiles)
 * with grid movement, monsters, turn-based combat and saved slots.
 * Combat mirrors libwawa.so: charge() grants the item then writes the slot.
 *
 * <p>Platform neutral: the desktop ({@link Game}) and the browser (WebMain) drive it with
 * {@link #tick}, {@link #render} and the input calls, and give it its files and save slots.
 */
final class GameCore {

	private static final int SCALE = 2;
	private static final int CELL = 20;
	private static final int MAP_X = 8;
	private static final int MAP_Y = 26;

	private final Assets assets;
	private final Storage storage;
	private ElfImage so;
	private SlotScreen slotScreen;
	/**
	 * Set once {@link #loadWorld} has built everything past the opening. The logos and title run
	 * while it loads (several seconds in the browser), and Play waits for it.
	 */
	private volatile boolean ready;
	/** True while the slot screen is up; the world neither ticks nor draws until a slot opens. */
	private boolean selecting = true;
	/** The logo and title screens before the slot screen; null once Play is chosen. */
	private TitleScreen opening;
	private int slotIndex;
	/** Time played in this slot; slots store it in DrawPopupSlot's 1/12 s ticks. */
	private long playMillis;
	private String mapCode = MAP_CODES[0];
	private Img heroPortrait;
	private MapData map;
	private Tiles tiles;
	private final List<Monster> monsters = new ArrayList<>();
	private final Random rng = new Random(20111017L);
	private static final int TILE = 16;
	private static final int VIEW_W = 20;
	private static final int VIEW_H = 12;
	/** The fixed canvas the game draws on; each platform scales it to fit. */
	static final int W = VIEW_W * CELL + 16;
	static final int H = MAP_Y + VIEW_H * CELL + 46;
	/** The title, slot and DrawPhase screens are 480x320; they are drawn this big, across the canvas. */
	private static final int OVERLAY_H = W * TalkBox.H / TalkBox.W;
	/** DrawPhase's 480x320 layer, cleared and redrawn every frame. */
	private final Img overlay = new Img(TalkBox.W, TalkBox.H);
	private boolean quit;

	/** All map codes the game ships, in the order the map files use. */
	private static final String[] MAP_CODES = {
		"a01", "b01", "c01", "e01", "f01", "g01", "h01", "i01", "j01", "n01", "t01", "z01"
	};

	private AreaInfo area;
	private MonsterTable monsterTable;
	private MonsterSprite monsterSprites;
	private MonsterStats monsterStats;
	private int heroX = 10;
	private int heroY = 12;
	/** The hero record InitHero / InitHeroState build; derived values are recomputed every tick. */
	private Hero hero;
	/** The event interpreter, its text box, and the stage it moves ({@link Stage}). */
	private TalkBox talk;
	private EventRunner events;
	private Gsac imoticon;
	/**
	 * DrawFade's value (wawamain+0x2ec0): 1..7 darken by v/8, 8 and up cover the view, negative
	 * values lighten from |v|/8; every drawn frame adds one while v is below 8. Black or white.
	 */
	private int fadeV;
	private boolean fadeWhite;
	/** 45 0b/0c: terrain-only darkening in 1/8 steps (+0x2ec8) and its direction (+0x2ecc, 1 up to 4, 0 down). */
	private int dim;
	private int dimUp;
	/** 45 06..08: game speed (+0x2ed8, negative = g_anSpeedSlowest) and its frame count (+0x2ee0). */
	private int speed;
	private int speedFrames;
	/** 45 09/0a: the world freeze byte (+0x3e6); the hero and units neither move nor animate. */
	private boolean frozen;
	/** AddSwing / CalcSwing: pattern (+0x5a74), countdown (+0x5a78) and this frame's camera offset in px. */
	private int swingPat;
	private int swingCnt = -1;
	private int swingX;
	private int swingY;
	/** g_aptSwing: {s8 dx, s8 dy} for pattern n (1..10) and countdown 3..0. */
	private static final int SWING = 0xba76c;
	/**
	 * CalcCamera: the view origin in world px (16 per tile) and the follow mode DAT_01408a22:
	 * -1 the port's free-play camera (centred on the hero at once), 0xff stopped, 0/1 the hero
	 * leading/centred, 2/4 a unit leading/centred, 3 a point. camScrolling is map+0x13d4, which
	 * holds result 5 while the view still moves.
	 */
	private int camX;
	private int camY;
	private int camMode = -1;
	private int camPX;
	private int camPY;
	private Monster camUnit;
	private boolean camScrolling;
	/** Hero +0x1d: fade step like units' +0x42, -3..-1 fading in, 1..3 fading out, 4 and up unseen. */
	private int heroFade;
	/**
	 * Hero +7 act, +0x1b step, +0x1c frames the step has been held, +0x45 cycles left (b3's p as
	 * s8) and +0x44 bit 8, set while a b3 action plays.
	 */
	private int heroAct;
	private int heroStep;
	private int heroHeld;
	private int heroActLeft;
	private boolean heroActRun;
	/** Px moved into the next cell (CollHeroPath: 8 a frame straight, 6 diagonal). */
	private int heroPx;
	/** A scripted hero walk (83, e3): target cell and the arrival direction fixed at command time. */
	private int heroGoX = -1;
	private int heroGoY;
	private int heroGoDir;
	/** AddImoticon entries: target (null = hero), kind, frames drawn. */
	private final List<Emote> emotes = new ArrayList<>();
	private int frames;
	/** The CalcGamePlay timer's delay, wawamain+0x20 (g_anSpeed[5] = 62 ms, Slowest 83 ms). */
	private int delay = 62;
	/**
	 * The hero record's integers from +0xa4 that scripts read and write (kind 4) and this port has
	 * no field for; level (2) and HP (3) map onto {@link Hero}. Saved with the slot like the record.
	 */
	private int[] heroInts = new int[0x100];
	private Item items;
	private HeroAnim heroAnim;
	/**
	 * The hero record's direction byte (+5), numbered like units (CollMonsPath): 0 up, 1 down,
	 * 2 left, 3 right, 4..7 diagonals; kept 0..7 and drawn by HeroAnim's row rules. The t01 script
	 * walks the hero left with arrival direction 2 ({@code 83 0b 0f 02}), which fixes 2 as left.
	 * InitHero faces a new hero right (3).
	 */
	private int heroDir = 3;
	/** g_aDirX / g_aDirY (0xb9848 / 0xb9854) per direction; 8 is no offset. */
	private static final int[][] DIR_STEP = {
		{ 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 }, { 1, -1 }, { -1, 1 }, { -1, -1 }, { 1, 1 }, { 0, 0 }
	};
	private int gold = 300;
	private int potions = 3;
	/**
	 * The weapon a new hero fights with. A new game loads map t01 (KeyPressGOrderNewGame); its event
	 * script grants sword 168 with the equip flag, but GetGOrderEquipLevel refuses it below level 6.
	 * The t02 script ("You can use my sword") then grants item 297 (level 1, value 5) with the same
	 * flag, and that is the first weapon the hero actually equips.
	 */
	private static final int STARTING_WEAPON = 297;
	private String log = "";

	/** Reads the native library (tables) and builds the title screen; the rest waits for {@link #loadWorld}. */
	GameCore(Assets assets, Storage storage) throws IOException {
		this.assets = assets;
		this.storage = storage;
		so = ElfImage.read(assets);
		opening = new TitleScreen(assets);
	}

	/** Everything the slot screen and the field need; may run while the logos play. */
	void loadWorld() throws IOException {
		Gsac face = Gsac.load(assets, "assets/img/face00.png");
		heroPortrait = face.frame(0);
		items = new Item(so);
		newHero(0, "");

		// The world behind the slot screen is not drawn until a slot is opened, but the rest of
		// the port expects a loaded map, so a01 stands loaded underneath.
		loadMap(MAP_CODES[0]);
		heroAnim = new HeroAnim(assets, so);
		talk = new TalkBox(assets, so);
		events = new EventRunner(new Stage(), talk);
		events.global = EventScript.global(assets);
		imoticon = Gsac.loadCompanion(assets, "assets/img/imoticon.png");
		slotScreen = new SlotScreen(assets, so, items, readSummaries());
		ready = true;
	}

	boolean ready() {
		return ready;
	}

	/** True once the player asked to leave (ESC in the field, Quit on the slot screen). */
	boolean quit() {
		return quit;
	}

	/** The CalcGamePlay timer's current delay in ms: 62, or 83 while 45 07 slows the game. */
	int delay() {
		return delay;
	}

	/**
	 * One CalcGamePlay timer tick: InitHeroState -> CalcHeroAct -> CalcMons -> CalcHeroRun ->
	 * Post*Value -> phase pop. Nothing runs while the title or slot screen is up.
	 */
	void tick() {
		if (!selecting) {
			playMillis += delay;
			gameTick();
		}
	}

	/** KEY_TYPED and committed input method text; only the slot screen's name box takes it. */
	void typed(char c) {
		if (ready && selecting) {
			slotScreen.typed(c);
		}
	}

	/** A mouse press at canvas pixel (x, y). */
	void click(int x, int y) {
		if (ready && !selecting && !events.active()) {
			onClick(x, y);
		}
	}

	/** InitHero for a class, holding the weapon a new game first equips. */
	private void newHero(int heroClass, String name) {
		hero = new Hero(so, heroClass);
		hero.name = name;
		hero.weapon = items.create(STARTING_WEAPON, 0, rng);
		heroDir = 3;
		heroInts = new int[0x100];
		hero.recompute(rng);
		hero.hp = hero.maxHp;
	}

	/** Applies what the slot screen asked for. */
	private void onSlotAction(SlotScreen.Action action) {
		if (action instanceof SlotScreen.Action.Continue c) {
			slotIndex = c.slot();
			load();
			selecting = false;
		} else if (action instanceof SlotScreen.Action.NewGame n) {
			// KeyPressGOrderNewGame: InitHero(class), LoadGameMap("t01", 2), SaveSlot(slot), then
			// ExitShiftMap's SearchPhase starts t01's opening event.
			slotIndex = n.slot();
			newHero(n.heroClass(), n.name());
			newEvents();
			try {
				loadMap("t01");
			} catch (IOException e) {
				log = "맵 로드 실패: " + e;
			}
			playMillis = 0;
			save();
			selecting = false;
			enterEvents();
		} else if (action instanceof SlotScreen.Action.Delete d) {
			storage.delete(slotKey(d.slot()));
			slotScreen.slotDeleted(d.slot());
		} else if (action instanceof SlotScreen.Action.Quit) {
			quit = true;
		}
	}

	private SlotScreen.Summary[] readSummaries() {
		SlotScreen.Summary[] out = new SlotScreen.Summary[SlotScreen.SLOTS];
		for (int i = 0; i < out.length; i++) {
			Properties p = slot(i);
			if (p == null) {
				continue;
			}
			try {
				out[i] = new SlotScreen.Summary(p.getProperty("name", ""),
						Integer.parseInt(p.getProperty("class", "0")), Integer.parseInt(p.getProperty("level", "1")),
						Integer.parseInt(p.getProperty("mode", "0")), Integer.parseInt(p.getProperty("playTime", "0")),
						// The slot hero wears what this port's hero always holds: the starting weapon.
						HeroAnim.wearing(items, items.create(STARTING_WEAPON, 0, rng), 1, HeroAnim.ACT_STAND));
			} catch (NumberFormatException e) {
				out[i] = null;
			}
		}
		return out;
	}

	/** Loads one of the shipped maps by its 3-character code, as LoadGameMap does. */
	private void loadMap(String code) throws IOException {
		mapCode = code;
		String letter = code.substring(0, 1);
		map = MapData.load(assets, "assets/eng/map/" + letter + ".png", code);
		try {
			area = AreaInfo.load(assets, "assets/eng/mob/" + letter + ".png", code);
		} catch (IOException e) {
			area = AreaInfo.absent(code);
		}
		tiles = Tiles.load(assets, map);
		String mobFile = "assets/eng/mob/" + letter + ".png";
		try {
			monsterTable = MonsterTable.load(assets, mobFile, code, map.width, map.height);
		} catch (IOException e) {
			monsterTable = MonsterTable.empty(code);
		}
		if (monsterSprites == null) {
			monsterSprites = MonsterSprite.load(assets, so);
			monsterStats = MonsterStats.load(so);
		}
		heroX = map.width / 2;
		heroY = map.height / 2;
		monsters.clear();
		spawnMonsters();
		camMode = -1;
		camUnit = null;
		camScrolling = false;
		// FuncMoveMap: hero +0x1d = 0.
		heroFade = 0;
		heroGoX = -1;
		changeHeroAct(HeroAnim.ACT_STAND);
		heroActRun = false;
		emotes.clear();
		log = area.name + " (" + map.width + "x" + map.height + ") 진입.";
	}

	/** InitSelectSlot: script variables start from zero for the slot being opened. */
	private void newEvents() {
		EventScript.Table global = events.global;
		events = new EventRunner(new Stage(), talk);
		events.global = global;
	}

	/** LoadGameMap with a non-zero mode, then ExitShiftMap's SearchPhase. */
	private void enterEvents() {
		events.loadMap(EventScript.map(assets, mapCode));
		events.searchPhase();
	}

	/** One CalcGamePlay tick in the original order. */
	private void gameTick() {
		frames++;
		drawnFrame();
		tickEffects();
		calcCamera();
		if (events.active()) {
			// CalcGamePlay keeps moving the hero and units while a phase runs; monsters stand still
			// (SetPhaseHeroStop sets their 0x2000 bit) and the player's input goes to the phase.
			scriptMoves();
			events.calcPhase();
			events.frame();
		} else {
			// No phase: the freeze lifts, and this port's hero has no act of its own.
			frozen = false;
			heroGoX = -1;
			heroActRun = false;
			if (heroAct != HeroAnim.ACT_STAND) {
				changeHeroAct(HeroAnim.ACT_STAND);
			}
			events.frame();
			initHeroState();
			monsterTick();
			calcHeroRun();
			postValues();
			for (Monster m : monsters) {
				if (m.atkOnce && (m.x != heroX || m.y != heroY)) {
					m.atkOnce = false;
				}
			}
		}
		drawSetup();
	}

	/** InitHeroState: the original recomputes the hero's derived stats every tick. */
	private void initHeroState() {
		hero.recompute(rng);
	}

	/** CalcHeroRun: commit movement, clamped to the map. */
	private void calcHeroRun() {
		heroX = Math.max(0, Math.min(map.width - 1, heroX));
		heroY = Math.max(0, Math.min(map.height - 1, heroY));
	}

	/** PostHeroValue/PostMonsValue: write the pending stat changes into the records. */
	private void postValues() {
		for (Monster m : monsters) {
			if (m.hp < 0) {
				m.hp = 0;
			}
		}
	}

	/**
	 * LoadMonsToMap: one monster per record of the area's .mbk. Position, facing, name and sprite
	 * come from the shipped data, and InitMonsAbility's stats at level = area base level + the
	 * record's level nibble wherever the id has a stat row; ids past the tables fall back to this
	 * port's tiers. The record number is the unit id scripts address (+0x4e); ids from 0xa6 up
	 * are NPCs (ChangeMonsDir's test), which stand in their ani_* pose and never fight.
	 */
	private void spawnMonsters() {
		int[] hpTier = { 30, 45, 60, 80, 140 };
		int[] atkTier = { 6, 9, 12, 15, 24 };
		for (int i = 0; i < monsterTable.records.size(); i++) {
			MonsterTable.Record r = monsterTable.records.get(i);
			int level = area.baseLevel + r.level;
			MonsterStats.Ability ab = monsterStats == null ? null : monsterStats.ability(r.monsterId, level);
			int tier = Math.min(hpTier.length - 1, (r.monsterId % 210) / 42);
			boolean npc = r.monsterId >= NPC_ID;
			String name = monsterSprites == null ? null : monsterSprites.name(r.monsterId);
			Monster m = new Monster(r.cellX, r.cellY, name != null ? name : "몬스터#" + r.monsterId,
					ab != null ? ab.hp() : hpTier[tier], ab != null ? ab.attack() : atkTier[tier],
					r.monsterId, !npc && tier >= 2, Math.max(0, r.cellX - 2), Math.max(0, r.cellY - 2),
					r.cellX + 2, r.cellY + 2);
			m.facing = r.direction;
			m.level = level;
			m.uid = i;
			if (npc) {
				m.npc = true;
				changeDir(m, 1, r.direction == 8 ? rng.nextInt(8) : r.direction);
			}
			if (ab != null) {
				m.defence = ab.defence();
				m.accuracy = ab.accuracy();
				m.dodge = ab.dodge();
				m.crit = ab.crit();
			}
			monsters.add(m);
		}
	}

	/**
	 * One monster tick, ported from CalcMonsAct/CalcMonsActMove: aggressive types walk the
	 * hero down, passive ones stay inside their patrol window, and Rand100() &lt; 0x32 puts
	 * a monster on hold half the time.
	 */
	private void monsterTick() {
		for (Monster m : monsters) {
			if (m.hp <= 0 || m.npc) {
				continue;
			}
			int dx = Integer.compare(heroX, m.x);
			int dy = Integer.compare(heroY, m.y);
			int dist = Math.abs(heroX - m.x) + Math.abs(heroY - m.y);
			if (m.aggressive && dist <= 5) {
				m.state = 0;
				m.idle = false;
				if (dist == 1) {
					monsterStrike(m);
				} else if (dx != 0 && monsterAt(m.x + dx, m.y) == null) {
					m.x += dx;
					m.facing = dx > 0 ? 1 : 3;
				} else if (dy != 0 && monsterAt(m.x, m.y + dy) == null) {
					m.y += dy;
					m.facing = dy > 0 ? 2 : 0;
				}
				continue;
			}
			m.state = 2;
			if (rng.nextInt(100) < 0x32) {
				m.idle = true;
				continue;
			}
			m.idle = false;
			if (m.x <= m.pixelLoX() / 16) {
				m.facing = 1;
			} else if (m.x >= m.pixelHiX() / 16) {
				m.facing = 3;
			} else if (m.y <= m.pixelLoY() / 16) {
				m.facing = 2;
			} else if (m.y >= m.pixelHiY() / 16) {
				m.facing = 0;
			} else {
				m.facing = 1 + rng.nextInt(4);
			}
			int px = m.x * CELL + (m.facing % 2 == 0 ? 0 : m.facing == 1 ? CELL : -CELL);
			int py = m.y * CELL + (m.facing < 2 ? 0 : m.facing == 2 ? CELL : -CELL);
			int nx = m.facing % 2 == 0 ? m.x : Math.floorDiv(px + CELL / 2, CELL);
			int ny = m.facing < 2 ? m.y : Math.floorDiv(py + CELL / 2, CELL);
			nx = Math.max(m.pixelLoX() / 16, Math.min(m.pixelHiX() / 16 - 1, nx));
			ny = Math.max(m.pixelLoY() / 16, Math.min(m.pixelHiY() / 16 - 1, ny));
			if (nx != m.x && monsterAt(nx, m.y) == null && (nx != heroX || ny != m.y)) {
				m.x = nx;
			} else if (ny != m.y && monsterAt(m.x, ny) == null && (m.x != heroX || ny != heroY)) {
				m.y = ny;
			}
		}
	}

	private void monsterStrike(Monster m) {
		if (m.atkOnce) {
			return;
		}
		m.atkOnce = true;
		Combat.Result r = Combat.monsterHits(m.atk, Combat.swingScale(rng), m.accuracy, m.crit,
				hero.armor, hero.dodge, rng);
		hero.hp -= r.damage();
		log = m.kind + "에게 " + r.damage() + " 피해를 입었다." + (r.missed() ? " 빗나감." : "");
		knockedOutCheck();
	}

	private void knockedOutCheck() {
		if (hero.hp <= 0) {
			hero.hp = hero.maxHp;
			gold = Math.max(0, gold - 50);
			heroX = 10;
			heroY = 12;
			log = "쓰러졌다. 골드 50을 잃고 마을로 복귀.";
		}
	}

	/** keyPressed with a {@link Keys} code. */
	void key(int code) {
		if (opening != null) {
			// Play leads to the slot screen only once loadWorld has built it.
			if (opening.key(code) && ready) {
				opening = null;
			}
			return;
		}
		if (selecting) {
			SlotScreen.Action action = slotScreen.key(code);
			if (action != null) {
				onSlotAction(action);
			}
			return;
		}
		// KeyPressGamePlay hands every key to KeyPressPhase first; a running phase eats it.
		if (events.key(code)) {
			return;
		}
		switch (code) {
			case Keys.LEFT, Keys.A -> step(-1, 0);
			case Keys.RIGHT, Keys.D -> step(1, 0);
			case Keys.UP, Keys.W -> step(0, -1);
			case Keys.DOWN, Keys.S -> step(0, 1);
			case Keys.ENTER, Keys.SPACE -> talkToNpc();
			case Keys.K1 -> attackNearest();
			case Keys.K2 -> drinkPotion();
			case Keys.F5 -> save();
			case Keys.F9 -> load();
			case Keys.ESCAPE -> quit = true;
			case Keys.K3, Keys.K4, Keys.K5, Keys.K6, Keys.K7, Keys.K8, Keys.K9, Keys.K0, Keys.MINUS,
					Keys.EQUALS, Keys.BACK_QUOTE, Keys.OPEN_BRACKET -> {
				int idx = keyToMapIndex(code);
				if (idx >= 0 && idx < MAP_CODES.length) {
					try {
						loadMap(MAP_CODES[idx]);
						enterEvents();
					} catch (IOException e) {
						log = "맵 로드 실패: " + e;
					}
				}
			}
			default -> {
			}
		}
	}

	private static int keyToMapIndex(int code) {
		return switch (code) {
			case Keys.K3 -> 0;
			case Keys.K4 -> 1;
			case Keys.K5 -> 2;
			case Keys.K6 -> 3;
			case Keys.K7 -> 4;
			case Keys.K8 -> 5;
			case Keys.K9 -> 6;
			case Keys.K0 -> 7;
			case Keys.MINUS -> 8;
			case Keys.EQUALS -> 9;
			case Keys.BACK_QUOTE -> 10;
			default -> 11;
		};
	}

	private void onClick(int x, int y) {
		// Canvas pixel -> world pixel (the camera is in 16 px tile units) -> cell.
		int cx = x - MAP_X;
		int cy = y - MAP_Y;
		int gx = (camX + cx * TILE / CELL) / TILE;
		int gy = (camY + cy * TILE / CELL) / TILE;
		if (!map.inBounds(gx, gy)) {
			return;
		}
		Monster target = monsterAt(gx, gy);
		if (target != null && !target.npc && Math.abs(gx - heroX) <= 1 && Math.abs(gy - heroY) <= 1) {
			attack(target);
		} else {
			walkTo(gx, gy);
		}
	}

	private void step(int dx, int dy) {
		int nx = heroX + dx;
		int ny = heroY + dy;
		if (!map.inBounds(nx, ny)) {
			return;
		}
		if (monsterAt(nx, ny) != null) {
			log = ".mon스터가 길을 막고 있다.";
			return;
		}
		heroDir = dy < 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3;
		heroX = nx;
		heroY = ny;
	}

	/** One step per frame towards the target cell, so movement reads as walking. */
	private void walkTo(int gx, int gy) {
		int dx = Integer.compare(gx, heroX);
		int dy = Integer.compare(gy, heroY);
		if (dx != 0 && monsterAt(heroX + dx, heroY) == null) {
			step(dx, 0);
		} else if (dy != 0 && monsterAt(heroX, heroY + dy) == null) {
			step(0, dy);
		} else {
			log = "더 이상 갈 수 없다.";
		}
	}

	private Monster monsterAt(int x, int y) {
		for (Monster m : monsters) {
			if (m.x == x && m.y == y) {
				return m;
			}
		}
		return null;
	}

	private void attackNearest() {
		Monster best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Monster m : monsters) {
			int d = Math.abs(m.x - heroX) + Math.abs(m.y - heroY);
			if (d <= 1 && d < bestDist && !m.npc) {
				best = m;
				bestDist = d;
			}
		}
		if (best == null) {
			log = "인접한 몬스터가 없다.";
		} else {
			attack(best);
		}
	}

	private void attack(Monster m) {
		Combat.Result r = Combat.heroHits(hero.power(), hero.accuracy, hero.crit, m.defence, m.dodge, rng);
		int dealt = r.damage();
		m.hp -= dealt;
		log = m.kind + "에게 " + dealt + " 피해." + (r.crit() ? " 크리티!" : "") + (r.missed() ? " 빗나감." : "");
		if (m.hp <= 0) {
			monsters.remove(m);
			int reward = 20 + rng.nextInt(40);
			gold += reward;
			int levels = hero.gainExp(hero.expFor(m.level, false), rng);
			if (rng.nextInt(3) == 0) {
				potions++;
			}
			log += " 처치. 골드 +" + reward + (levels > 0 ? " 레벨 " + hero.level + " 달성!" : "");
		} else {
			Combat.Result back = Combat.monsterHits(m.atk, Combat.swingScale(rng), m.accuracy, m.crit,
					hero.armor, hero.dodge, rng);
			hero.hp -= back.damage();
			log += " 반격 " + back.damage() + (back.missed() ? " (회피)" : "") + ".";
			knockedOutCheck();
		}
	}

	private void drinkPotion() {
		if (potions <= 0) {
			log = "회복 물약이 없다.";
			return;
		}
		potions--;
		int healed = Math.min(40, hero.maxHp - hero.hp);
		hero.hp += healed;
		log = "물약 사용. HP +" + healed;
	}

	/** Draws the current frame on a {@link #W} x {@link #H} canvas. */
	void render(Gfx g) {
		if (selecting) {
			// The title and slot screens are laid out on the original 480x320; fit them to the width.
			g.fillRect(0, 0, W, H, 0xff000000);
			g.drawImage(opening != null ? opening.render() : slotScreen.render(), 0, 0, W, OVERLAY_H);
			return;
		}
		g.fillRect(0, 0, W, H, 0xff0a0a0e);

		drawTiles(g);

		g.drawImage(heroPortrait, 2, 2, 22, 22);
		int from = hero.levelExp(hero.level);
		String expText = hero.level >= Hero.MAX_LEVEL ? "MAX"
				: (hero.exp - from) + "/" + (hero.levelExp(hero.level + 1) - from);
		// The original bitmap font is wider than the old 12 pt one: double spaces keep this on one line.
		g.drawString("Lv" + hero.level + " (" + expText + "EXP)  HP " + hero.hp + "/" + hero.maxHp
				+ "  ATK " + hero.power() + "  골드 " + gold + "  물약 " + potions, 28, 5, 0xffe6dcc8);
		int foot = MAP_Y + VIEW_H * CELL;
		g.drawString(area.name + "  밝기 " + area.brightness + (area.rainy() ? "  비" : ""),
				MAP_X, foot - 17, 0xffb4d2f0);
		// Below the 480x320 overlay's scaled bottom edge (OVERLAY_H = 277), so phases do not cover them.
		g.drawString(log, MAP_X, OVERLAY_H, 0xfffae696);
		// One line of the 16 px font is all that fits under the log; the map keys (3..[) stay unlisted.
		g.drawString("Enter 대화 1 공격 2 물약 F5 저장 F9 불러오기 ESC 종료", MAP_X, OVERLAY_H + 18, 0xffaaa596);
		if (events.overlay()) {
			// DrawPhase works on the original 480x320 screen; fit it like the slot screen.
			Arrays.fill(overlay.px, 0);
			events.draw(new Gfx(overlay));
			g.drawImage(overlay, 0, 0, W, OVERLAY_H);
		}
	}

	/** The view in world px, and InitZoomLCD's dead zone for it: e10 / e14 across, e12 / e16 down. */
	private static final int VIEW_PX_W = VIEW_W * TILE;
	private static final int VIEW_PX_H = VIEW_H * TILE;
	private static final int ZONE_L = (VIEW_PX_W - 80) / 2;
	private static final int ZONE_R = ZONE_L + 80;
	private static final int ZONE_T = (VIEW_PX_H - 76) / 2;
	private static final int ZONE_B = ZONE_T + 76;

	/** CalcCameraOnce(px, py, m): (px, py) goes at once where direction m leads it (8 = centre). */
	private void cameraOnce(int px, int py, int m) {
		camY = clampCam(py - (m == 0 ? ZONE_B : m == 1 ? ZONE_T : VIEW_PX_H / 2), map.height * TILE - VIEW_PX_H);
		camX = clampCam(px - (m == 2 ? ZONE_R : m == 3 ? ZONE_L : VIEW_PX_W / 2), map.width * TILE - VIEW_PX_W);
	}

	private static int clampCam(int v, int max) {
		return Math.max(0, Math.min(max, v));
	}

	/** CalcCamera's hero target: its position 20 px up. */
	private int[] heroTarget() {
		return new int[] { heroX * TILE + TILE / 2, heroY * TILE + TILE / 2 - 20 };
	}

	/** A unit's target: its position lifted by its kind's height (DAT_01419794, taken as DrawImoticon's 0xc08d4). */
	private int[] unitTarget(Monster m) {
		return new int[] { m.x * TILE + TILE / 2, m.y * TILE + TILE / 2 - unitHeight(m) };
	}

	private int unitHeight(Monster m) {
		return monsterSprites == null ? 0 : so.u8(UNIT_HEIGHT + monsterSprites.kind(m.variant));
	}

	/**
	 * CalcCamera: every frame the view moves toward its target by 2 px per started 8 px of offset
	 * past the screen point (or out of the dead zone for a leading direction), at most 32 px, kept
	 * on the map. The speed is taken to restart from 0 each frame [inferred: the traced formula
	 * diverges if it carries over]. Lead direction 9 (DAT_0140422d == 0) is not traced; the
	 * hero's own direction stands in.
	 */
	private void calcCamera() {
		int[] t;
		int lead = 8;
		switch (camMode) {
			case -1 -> {
				int[] h = heroTarget();
				cameraOnce(h[0], h[1], 8);
				camScrolling = false;
				return;
			}
			case 0, 1 -> {
				t = heroTarget();
				lead = camMode == 0 ? heroDir : 8;
			}
			case 2, 4 -> {
				if (!monsters.contains(camUnit)) {
					camScrolling = false;
					return;
				}
				t = unitTarget(camUnit);
				lead = camMode == 2 ? camUnit.dir & 3 : 8;
			}
			case 3 -> t = new int[] { camPX, camPY };
			default -> {
				camScrolling = false;
				return;
			}
		}
		int vx = lead == 8 ? pull(t[0] - camX, VIEW_PX_W / 2, VIEW_PX_W / 2)
				: lead == 2 ? pull(t[0] - camX, ZONE_R, ZONE_R) : lead == 3 ? pull(t[0] - camX, ZONE_L, ZONE_L)
				: pull(t[0] - camX, ZONE_L, ZONE_R);
		int vy = lead == 8 ? pull(t[1] - camY, VIEW_PX_H / 2, VIEW_PX_H / 2)
				: lead == 0 ? pull(t[1] - camY, ZONE_B, ZONE_B) : lead == 1 ? pull(t[1] - camY, ZONE_T, ZONE_T)
				: pull(t[1] - camY, ZONE_T, ZONE_B);
		int nx = clampCam(camX + Math.max(-32, Math.min(32, vx)), map.width * TILE - VIEW_PX_W);
		int ny = clampCam(camY + Math.max(-32, Math.min(32, vy)), map.height * TILE - VIEW_PX_H);
		camScrolling = nx != camX || ny != camY;
		camX = nx;
		camY = ny;
	}

	/** One CalcCamera axis: the speed for screen offset p to reach [lo, hi] (a single point allows 1 px). */
	private static int pull(int p, int lo, int hi) {
		int slack = lo == hi ? 1 : 0;
		if (p > hi + slack) {
			return 2 * ((p - hi + 7) >> 3);
		}
		if (p < lo - slack) {
			return -2 * ((lo - p + 7) >> 3);
		}
		return 0;
	}

	/**
	 * A sprite whose anchor goes on a cell centre, at the view scale. A fade step (hero +0x1d,
	 * unit +0x42) of 1..3 blends it; DrawSpriteFull's alphas are not recovered, 1 - |f| / 4 stands in.
	 */
	private static void drawPose(Gfx g, PartSprites.Pose pose, int cx, int cy, int fade) {
		Img spr = pose.image();
		g.drawImage(spr, cx - pose.anchorX() * CELL / TILE, cy - pose.anchorY() * CELL / TILE,
				Math.max(1, spr.w * CELL / TILE), Math.max(1, spr.h * CELL / TILE),
				255 * (4 - Math.min(3, Math.abs(fade))) / 4);
	}

	/** DrawFillAlpha's level l of 8 as an alpha byte; 8 and up is opaque. */
	private static int alpha8(int l) {
		return Math.min(255, l * 32);
	}

	private HeroAnim.Look heroLook() {
		return HeroAnim.wearing(items, hero.weapon, heroDir, heroAct);
	}

	/** The ani step a unit draws: its own step, counted back from the last for a reversed entry. */
	private int unitStep(Monster m) {
		if (!m.reverse) {
			return m.step;
		}
		int[] e = monsterSprites.animEntry(m.variant, m.ani);
		return Math.max(1, e == null ? 1 : e[2]) - 1 - m.step;
	}

	/**
	 * DrawGamePlay's world part: tiles with the DrawTile cell rule, the terrain darkening (45 0b),
	 * units, the hero, emote bubbles, then DrawFade over the view.
	 */
	private void drawTiles(Gfx g) {
		if (camMode < 0) {
			int[] h = heroTarget();
			cameraOnce(h[0], h[1], 8);
		}
		int[] clip = g.getClip();
		g.clipRect(MAP_X, MAP_Y, VIEW_W * CELL, VIEW_H * CELL);
		// CalcSwing shifts the camera, so the map, units and bubbles shake together; the fade does not.
		int vx = camX + swingX;
		int vy = camY + swingY;
		int ox = MAP_X - vx * CELL / TILE;
		int oy = MAP_Y - vy * CELL / TILE;
		int tx0 = Math.floorDiv(vx, TILE);
		int ty0 = Math.floorDiv(vy, TILE);
		for (int y = ty0; y <= ty0 + VIEW_H; y++) {
			for (int x = tx0; x <= tx0 + VIEW_W; x++) {
				if (!map.inBounds(x, y)) {
					continue;
				}
				int cell = map.cell(x, y);
				Img f = cell == 0xff ? null : tiles.patch(map.layer(x, y), cell);
				if (f != null) {
					g.drawImage(f, ox + x * CELL, oy + y * CELL, CELL, CELL);
					continue;
				}
				// The ground atlas the native build composes at run time is not recovered yet,
				// so shade the cell from its own byte: the pattern is real data, the art is not.
				g.fillRect(ox + x * CELL, oy + y * CELL, CELL, CELL, groundColour(cell));
			}
		}
		if (dim != 0) {
			g.fillRect(MAP_X, MAP_Y, VIEW_W * CELL, VIEW_H * CELL, alpha8(dim) << 24);
		}
		for (Monster m : monsters) {
			// DrawUnit draws a unit only while +0x42 is below 4.
			if (m.fade >= 4) {
				continue;
			}
			int cx = ox + m.x * CELL + CELL / 2;
			int cy = oy + m.y * CELL + CELL / 2;
			// InitMonsOne puts the monster's position at the cell centre and DrawMonsAnim draws every
			// part relative to it, so the pose's anchor goes there; the view scale carries over.
			PartSprites.Pose pose = monsterSprites == null ? null
					: m.ani >= 0 ? monsterSprites.pose(m.variant, m.ani, unitStep(m), m.mirror)
					: monsterSprites.standing(m.variant);
			if (pose != null) {
				drawPose(g, pose, cx, cy, m.fade);
			} else {
				g.fillRect(cx - CELL / 2 + 4, cy - CELL / 2 + 4, CELL - 8, CELL - 8, m.color());
			}
			if (!m.npc) {
				bar(g, cx - CELL / 2 + 2, cy + CELL / 2 - 5, (float) m.hp / m.maxHp, 0xffdc3c3c);
			}
		}
		int hx = ox + heroX * CELL + CELL / 2;
		int hy = oy + heroY * CELL + CELL / 2;
		if (heroFade < 4) {
			// DrawHeroAnim: the hero's position is the cell centre, as for monsters.
			try {
				PartSprites.Pose pose = heroAnim.pose(heroLook(), heroStep);
				if (pose != null) {
					drawPose(g, pose, hx, hy, heroFade);
				}
			} catch (IOException e) {
				log = "영웅 그림 실패: " + e;
			}
			if (!events.active()) {
				bar(g, hx - CELL / 2, hy + CELL / 2 - 5, (float) hero.hp / hero.maxHp, 0xff50dc78);
			}
		}
		for (Emote e : emotes) {
			if (e.target == null) {
				drawEmote(g, e, hx, hy - 0x28 * CELL / TILE);
			} else if (monsters.contains(e.target)) {
				Monster m = e.target;
				drawEmote(g, e, ox + m.x * CELL + CELL / 2, oy + m.y * CELL + CELL / 2 - unitHeight(m) * CELL / TILE);
			}
		}
		if (fadeV != 0) {
			int a = alpha8(Math.abs(fadeV)) << 24;
			g.fillRect(MAP_X, MAP_Y, VIEW_W * CELL, VIEW_H * CELL, fadeWhite ? a | 0xffffff : a);
		}
		g.setClip(clip);
	}

	/**
	 * DrawImoticon: a base clip, then from frame 3 the kind's overlay clip; clips are
	 * {s16 dx, s16 dy, s16 frame} into img/imoticon.
	 */
	private void drawEmote(Gfx g, Emote e, int x, int y) {
		int t = e.frame;
		int k = e.kind;
		clip(g, k == 0 || k == 2 ? (t < 2 ? t + 3 : 5) : (t < 2 ? t : 2), x, y);
		if (t > 2) {
			clip(g, so.u8(EMOTE_OVERLAY + k * 8 + Math.min(t - 3, 7)), x, y);
			if (k == 5 && t - 3 > 3) {
				clip(g, 20, x, y);
			}
			if (k == 5 && t - 3 > 6) {
				clip(g, 21, x, y);
			}
		}
	}

	private void clip(Gfx g, int c, int x, int y) {
		Img f = imoticon.frame(so.s16(EMOTE_CLIP + c * 8 + 4));
		if (f != null) {
			g.drawImage(f, x + so.s16(EMOTE_CLIP + c * 8) * CELL / TILE, y + so.s16(EMOTE_CLIP + c * 8 + 2) * CELL / TILE,
					f.w * CELL / TILE, f.h * CELL / TILE);
		}
	}

	/** Monster ids from here up are NPCs (ChangeMonsDir's 0xa6 test). */
	private static final int NPC_ID = 0xa6;
	/** ChangeMonsDir's ani table: s8 per (id * 10 + state) * 2 + row; -99 = no such state. */
	private static final int UNIT_ANI = 0xbec2c;
	/** Per-id flag byte; negative pins row 0. */
	private static final int UNIT_FLAGS = 0xc1574;
	/** DrawImoticon's lift above a unit, per kind. */
	private static final int UNIT_HEIGHT = 0xc08d4;
	private static final int EMOTE_OVERLAY = 0xdc108;
	private static final int EMOTE_CLIP = 0xe5194;
	/** CalcMonsRun's speed table: u8 per id * 5 + 4, plus frame &amp; 1 (NPCs 12, most mobs 6). */
	private static final int UNIT_SPEED = 0xbfca8;

	/**
	 * ChangeMonsDir(m, state, dir). State &lt; 0 keeps +0x20; NPCs fold 2 and 0x11 to 1 and 4 to 3,
	 * and a new state restarts the unit's step (+0x29 = +0x2a = 0). The effective state's ani entry
	 * is read for the row the direction picks (up 1, down 0, sideways keeps); an NPC standing (1)
	 * takes the state-2 idle entry when Rand1000 &lt;= 0x14c. The mirror flips for the other side
	 * (never for id 0x7f). Dir 8 keeps the direction; state 0xf faces dir ^ 1.
	 */
	private void changeDir(Monster m, int state, int dir) {
		if (state >= 0) {
			if (m.variant >= NPC_ID) {
				state = state == 2 || state == 0x11 ? 1 : state == 4 ? 3 : state;
			}
			if (state != m.aniState) {
				m.step = 0;
				m.held = 0;
			}
			m.aniState = state;
		}
		state = m.aniState;
		if (dir == 8) {
			dir = m.dir;
		}
		int s = state <= 9 ? Math.max(0, state) : state == 0xd ? 9 : state == 0xe || state == 0x11 || state == 0x12 ? 1
				: state == 0xf || state == 0x10 ? 8 : 3;
		if ((byte) so.u8(UNIT_FLAGS + m.variant) < 0) {
			m.row = 0;
		} else if (dir == 0 || dir == 4 || dir == 6) {
			m.row = 1;
		} else if (dir == 1 || dir == 5 || dir == 7) {
			m.row = 0;
		}
		int row = m.variant >= NPC_ID && s == 1 && rng.nextInt(1000) <= 0x14c ? 2 : s;
		int ani = (byte) so.u8(UNIT_ANI + (m.variant * 10 + row) * 2 + m.row);
		if (ani != -99) {
			m.ani = ani;
		}
		m.reverse = false;
		if (m.variant == 0x7f) {
			m.mirror = false;
		} else if (dir == 3 || dir == 4 || dir == 7) {
			m.mirror = m.row == 0;
		} else if (dir == 2 || dir == 5 || dir == 6) {
			m.mirror = m.row != 0;
		}
		m.dir = state == 0xf ? dir ^ 1 : dir;
	}

	/** ChangeHeroAct(n): a new act starts from its first step. */
	private void changeHeroAct(int act) {
		heroAct = act;
		heroStep = 0;
		heroHeld = 0;
	}

	/** 83 / e3: hero+0x44 |= 4 and act 1 (walking) toward a cell, arriving facing {@code dir}. */
	private void startHeroWalk(int x, int y, int dir) {
		heroGoX = x;
		heroGoY = y;
		heroGoDir = dir;
		heroPx = 0;
		changeHeroAct(HeroAnim.ACT_WALK);
	}

	/** hero+0x44 &amp;= ~4: a walk in progress stops where it is and the hero stands. */
	private void stopHeroWalk() {
		if (heroGoX >= 0) {
			heroGoX = -1;
			heroPx = 0;
			changeHeroAct(HeroAnim.ACT_STAND);
		}
	}

	/** LoadMonsToTemp trades ids 0xad..0xc8 through this u8 table (paired ids swap). */
	private static final int SPAWN_SWAP = 0xbcaf8;

	/** CalcGamePlay's speed reset 37 frames after 45 06/07 (+0x2ee0 past 0x24), and the emote lifetime. */
	private void tickEffects() {
		if (speed != 0 && ++speedFrames > 0x24) {
			speed = 0;
		}
		delay = speed < 0 ? 83 : 62;
		emotes.removeIf(e -> ++e.frame > 15);
	}

	/**
	 * What drawing the last frame advanced: DrawFade's value, and while the world is not frozen
	 * CalcHeroAnim / CalcMonsAnim (one step per drawn frame, each step held for its delay byte) and
	 * the hero's and units' fade steps. DrawUnit draws them only below fade 4, so they stop there.
	 */
	private void drawnFrame() {
		if (fadeV != 0 && fadeV < 8) {
			fadeV++;
		}
		if (frozen) {
			return;
		}
		if (heroFade < 4 && heroAnim != null) {
			try {
				HeroAnim.Look look = heroLook();
				if (heroHeld < heroAnim.delay(look, heroStep)) {
					heroHeld++;
				} else {
					heroHeld = 0;
					heroStep = (heroStep + 1) % heroAnim.steps(look);
				}
			} catch (IOException e) {
				log = "영웅 그림 실패: " + e;
			}
			if (heroFade != 0) {
				heroFade++;
			}
		}
		for (Monster m : monsters) {
			if (m.fade >= 4) {
				continue;
			}
			if (monsterSprites != null && m.ani >= 0) {
				int[] e = monsterSprites.animEntry(m.variant, m.ani);
				int n = e == null ? 1 : Math.max(1, e[2]);
				m.step %= n;
				if (m.held < monsterSprites.delay(m.variant, m.ani, unitStep(m))) {
					m.held++;
				} else {
					m.held = 0;
					if (++m.step >= n) {
						m.step = 0;
						m.wrapped = true;
					}
				}
			}
			if (m.fade != 0 && m.fade >= -3) {
				m.fade++;
			}
		}
	}

	/** The head of DrawGamePlay: CalcSwing's camera offset for this frame, and the terrain darkening step. */
	private void drawSetup() {
		if (swingPat == 0) {
			swingX = 0;
			swingY = 0;
		} else {
			int at = SWING + ((swingPat - 1) * 4 + swingCnt) * 2;
			swingX = so.s8(at);
			swingY = so.s8(at + 1);
			if (--swingCnt < 0) {
				swingPat = 0;
			}
		}
		if (dim != 0 || dimUp != 0) {
			if (dimUp == 0) {
				dim--;
			} else if (dim < 4) {
				dim++;
			}
		}
	}

	/** CalcHeroAct and CalcMons under a phase (ProcGame's part after CalcCamera); nothing while frozen. */
	private void scriptMoves() {
		if (frozen) {
			return;
		}
		heroScript();
		for (Monster m : monsters) {
			unitScript(m);
		}
	}

	/**
	 * CalcHeroAct's script branch. A walk (83, e3) moves 8 px a frame straight and 6 diagonally
	 * (CollHeroPath) and lands with act 0 facing its arrival direction. A b3 action counts its
	 * cycles: each time the animation is back at step 0 (CalcHeroAnim wrapped) p drops by one,
	 * and below 0 the action ends; act 1 (b3 0x23) walks straight ahead meanwhile. Outside an
	 * action the hero holds its pose: act 4 lies at step 8, acts 7 and 8 kneel as act 7 step 5, the
	 * rest stand. The route is a straight/diagonal line: this port has no collision map for
	 * GetHeroRouting's breadth-first search.
	 */
	private void heroScript() {
		if (heroGoX >= 0) {
			int dx = Integer.compare(heroGoX, heroX);
			int dy = Integer.compare(heroGoY, heroY);
			if (dx == 0 && dy == 0) {
				heroGoX = -1;
				heroPx = 0;
				heroDir = heroGoDir;
				changeHeroAct(HeroAnim.ACT_STAND);
			} else {
				heroDir = stepDir(dx, dy);
				heroPx += dx != 0 && dy != 0 ? 6 : 8;
				if (heroPx >= TILE) {
					heroPx -= TILE;
					heroX += dx;
					heroY += dy;
				}
			}
			return;
		}
		if (!heroActRun) {
			holdHeroPose();
			return;
		}
		if (heroAct == HeroAnim.ACT_WALK) {
			heroPx += heroDir >= 4 ? 6 : 8;
			if (heroPx >= TILE) {
				heroPx -= TILE;
				int nx = heroX + DIR_STEP[heroDir][0];
				int ny = heroY + DIR_STEP[heroDir][1];
				if (map.inBounds(nx, ny)) {
					heroX = nx;
					heroY = ny;
				}
			}
		}
		if (heroStep == 0 && heroHeld == 0 && --heroActLeft < 0) {
			heroActRun = false;
			heroPx = 0;
			holdHeroPose();
		}
	}

	private void holdHeroPose() {
		switch (heroAct) {
			case HeroAnim.ACT_STAND -> {
			}
			case HeroAnim.ACT_FALL -> {
				heroStep = 8;
				heroHeld = 0;
			}
			case 7, 8 -> {
				heroAct = 7;
				heroStep = 5;
				heroHeld = 0;
			}
			default -> changeHeroAct(HeroAnim.ACT_STAND);
		}
	}

	/**
	 * CalcMons / CalcMonsAct for a unit under a phase; nothing while +0x42 is 1 or more. An action
	 * lock (63, flag 0x8000) ends when the unit's animation is back at step 0: state 0 stays, state
	 * 9 lies on its last step, 1 / 3 / 0xb stand (1), the rest go to 0x11. A state-9 unit keeps its
	 * last step afterwards; a standing NPC draws its idle variant again after every cycle. A
	 * scripted walk moves v &gt;&gt; 1 px a frame straight and (7v + 4) / 20 diagonally with
	 * v = CalcMonsRun's speed + (frame &amp; 1), in a straight/diagonal line (no collision map for
	 * GetMonsRouting).
	 */
	private void unitScript(Monster m) {
		if (m.fade >= 1) {
			return;
		}
		if (m.lock) {
			if (m.step == 0 && m.held == 0) {
				m.lock = false;
				switch (m.aniState) {
					case 0 -> {
					}
					case 9 -> m.step = lastStep(m);
					case 1, 3, 0xb -> changeDir(m, 1, 8);
					default -> changeDir(m, 0x11, 8);
				}
			}
		} else if (m.aniState == 9) {
			m.step = lastStep(m);
			m.held = 0;
		} else if (m.wrapped && m.aniState == 1 && m.variant >= NPC_ID) {
			changeDir(m, -1, 8);
		}
		m.wrapped = false;
		if (m.goX < 0) {
			return;
		}
		int dx = Integer.compare(m.goX, m.x);
		int dy = Integer.compare(m.goY, m.y);
		if (dx == 0 && dy == 0) {
			m.goX = -1;
			m.goPx = 0;
			changeDir(m, 1, m.goDir);
			return;
		}
		changeDir(m, 0xc, stepDir(dx, dy));
		int v = Math.max(2, so.u8(UNIT_SPEED + m.variant * 5 + 4) + (frames & 1));
		m.goPx += dx != 0 && dy != 0 ? (v * 7 + 4) / 20 : v >> 1;
		if (m.goPx >= TILE) {
			m.goPx -= TILE;
			m.x += dx;
			m.y += dy;
		}
	}

	/** The last step of the unit's current ani entry. */
	private int lastStep(Monster m) {
		int[] e = monsterSprites == null ? null : monsterSprites.animEntry(m.variant, m.ani);
		return e == null ? 0 : Math.max(1, e[2]) - 1;
	}

	private static int stepDir(int dx, int dy) {
		for (int d = 0; d < DIR_STEP.length; d++) {
			if (DIR_STEP[d][0] == dx && DIR_STEP[d][1] == dy) {
				return d;
			}
		}
		return 1;
	}

	private Monster unit(int uid) {
		for (Monster m : monsters) {
			if (m.uid == uid) {
				return m;
			}
		}
		return null;
	}

	/** CheckHeroOnNPC: OK next to an NPC runs its type-4 event. */
	private void talkToNpc() {
		for (Monster m : monsters) {
			if (m.npc && m.fade <= 0 && Math.abs(m.x - heroX) <= 1 && Math.abs(m.y - heroY) <= 1) {
				changeDir(m, 1, stepDir(Integer.compare(heroX, m.x), Integer.compare(heroY, m.y)));
				npcName = m.kind;
				if (events.searchUnit(4, m.uid)) {
					return;
				}
			}
		}
	}

	private String npcName = "";

	/** An AddImoticon entry. */
	private static final class Emote {
		final Monster target;
		int kind;
		int frame;

		Emote(Monster target, int kind) {
			this.target = target;
			this.kind = kind;
		}
	}

	/** What the event interpreter moves: this game's hero, units, camera, screen and items. */
	private final class Stage implements EventRunner.World {
		@Override
		public void heroStop() {
			// SetPhaseHeroStop: acts 1, 0x1e and 7 go back to standing; gameTick already keeps the
			// monsters still under a phase. A scripted walk keeps its act 1 [inferred: CalcHeroActEMove owns it].
			if (heroGoX < 0 && (heroAct == HeroAnim.ACT_WALK || heroAct == 0x1e || heroAct == 7)) {
				changeHeroAct(HeroAnim.ACT_STAND);
			}
		}

		@Override
		public void heroPlace(int x, int y, int dir) {
			if (x != 0xff || y != 0xff) {
				heroX = x;
				heroY = y;
			}
			if (dir != 8) {
				heroDir = dir;
			}
			// 73 and 83 ff ff cancel a walk (hero+0x44 &= ~4).
			stopHeroWalk();
		}

		@Override
		public void heroWalk(int x, int y, int dir) {
			// ff ff only turns; on a black screen (wawamain+0x2ec0 >= 8) the hero is put there at once.
			if (x == 0xff && y == 0xff || fadeV >= 8) {
				heroPlace(x, y, dir);
				return;
			}
			// The arrival direction is fixed now: 8 means the direction the hero faces at the command.
			startHeroWalk(x, y, dir == 8 ? heroDir : dir);
		}

		@Override
		public int heroVisible(boolean visible, int x, int y, int dir) {
			boolean black = fadeV >= 8;
			if (visible) {
				// 93 places the hero without cancelling a walk. Its companion unit (DAT_01418338)
				// has no counterpart in this port.
				if (x != 0xff || y != 0xff) {
					heroX = x;
					heroY = y;
				}
				if (dir != 8) {
					heroDir = dir;
				}
				if (heroFade == 0) {
					return 0;
				}
				heroFade = black ? 0 : -3;
				return 4;
			}
			if (heroFade > 0) {
				return 0;
			}
			heroFade = black ? 4 : 1;
			return 4;
		}

		/**
		 * b3 dir act p (fp case 0xb): the acts that call ChangeHeroAct(n) start n and set
		 * hero+0x44 bit 8 until p + 1 animation cycles have played (p = 0xff plays one); the
		 * others only turn. 05 starts act 4 on its last step (lying), 06 plays it backwards (0x1d,
		 * getting up), 0x23 walks ahead (act 1). Attack timing (GetHeroAttackFrame), effects and
		 * sounds are not ported.
		 */
		@Override
		public int heroAct(int dir, int act, int repeat) {
			if (dir != 8) {
				heroDir = dir;
			}
			heroActLeft = (byte) repeat;
			int n = switch (act) {
				case 0x01 -> 9;
				case 0x02 -> 3;
				case 0x04, 0x05 -> HeroAnim.ACT_FALL;
				case 0x06 -> HeroAnim.ACT_RISE;
				case 0x07 -> 2;
				case 0x08 -> 10;
				case 0x09 -> 0xd;
				case 0x10 -> 7;
				case 0x11 -> 8;
				case 0x12 -> HeroAnim.ACT_SIT;
				case 0x13 -> 0x2a;
				case 0x14 -> 0x2b;
				case 0x23 -> HeroAnim.ACT_WALK;
				default -> -1;
			};
			if (n >= 0) {
				changeHeroAct(n);
				heroActRun = true;
				heroPx = 0;
				if (act == 0x05) {
					heroStep = 8;
				}
				if (act == 0x13 || act == 0x14) {
					notPorted("효과음 " + (act == 0x13 ? 0x28 : 0x2a));
				}
				return 0;
			}
			switch (act) {
				case 0x0a -> {
					// hero+0x47 = 6 asks InitHeroAttack for an attack; which one is not traced.
					heroActRun = true;
					notPorted("영웅 공격 요청 6");
				}
				case 0x0c, 0x0e, 0x0f -> {
					notPorted("영웅 효과 " + act);
					return 3;
				}
				case 0x0d -> {
					notPorted("영웅 효과 " + act);
					return 4;
				}
				default -> {
					if (act >= 0x15 && act <= 0x22) {
						notPorted("영웅 공격 요청 " + act);
					}
				}
			}
			return 0;
		}

		@Override
		public void heroToUnit(int uid, int side) {
			Monster m = GameCore.this.unit(uid);
			if (m == null) {
				return;
			}
			int x = m.x + DIR_STEP[Math.min(side, 8)][0];
			int y = m.y + DIR_STEP[Math.min(side, 8)][1];
			if (fadeV >= 8) {
				heroX = x;
				heroY = y;
				stopHeroWalk();
				return;
			}
			// e3 arrives facing the way the hero faces now (+0xa2 = +5).
			startHeroWalk(x, y, heroDir);
		}

		@Override
		public int unitSpawn(int a, int x, int y, int dir) {
			int[] kinds = events.phaseHeader();
			if (a >= kinds.length) {
				return 4;
			}
			// f74[depth] * 0x100 + a; this port's phase instance byte is always 1.
			int uid = 0x100 + a;
			Monster old = GameCore.this.unit(uid);
			if (old != null) {
				monsters.remove(old);
			}
			int id = kinds[a];
			if (id > 0xac && id < 0xc9) {
				id = so.u8(SPAWN_SWAP + id - 0xad);
			}
			String name = monsterSprites.name(id);
			Monster m = new Monster(x, y, name != null ? name : "#" + id, 1, 0, id, false, x, y, x, y);
			m.uid = uid;
			m.npc = true;
			changeDir(m, (byte) so.u8(UNIT_ANI + id * 20) == -99 ? 1 : 0, dir == 8 ? rng.nextInt(8) : dir);
			// LoadGameMonsOne: a new unit fades in over 3 frames, at once on a black screen.
			m.fade = fadeV >= 8 ? 0 : -3;
			monsters.add(m);
			return 4;
		}

		@Override
		public int unit(int hi, int uid, int x, int y, int dir) {
			Monster m = GameCore.this.unit(uid);
			if (m == null) {
				return 0;
			}
			boolean black = fadeV >= 8;
			switch (hi) {
				case 1 -> monsters.remove(m);
				case 2 -> {
					m.x = x;
					m.y = y;
					// 23 ends a waited walk (0x4000) and stands; otherwise it only turns, and an
					// unwaited walk goes on from the new cell.
					if (m.goX >= 0 && m.goHold) {
						m.goX = -1;
						m.goPx = 0;
						changeDir(m, 1, dir);
					} else {
						changeDir(m, -1, dir);
					}
				}
				case 3 -> {
					// 33 x y f: bit 7 is an unwaited walk (0x1000), which walks even on a black
					// screen; a waited one (0x4000) lands at once there.
					boolean hold = (dir & 0x80) == 0;
					if (hold && black) {
						m.x = x;
						m.y = y;
						m.goX = -1;
						changeDir(m, 1, dir & 0x7f);
					} else {
						m.goX = x;
						m.goY = y;
						m.goDir = dir & 0x7f;
						m.goPx = 0;
						m.goHold = hold;
					}
				}
				// +0x42 is the unit's fade step: 43 sets -3 (fading in over 3 frames) unless the unit
				// is fully shown, 53 sets 1 (fading out, unseen from 4) unless it already fades out.
				case 4 -> {
					if (m.fade == 0) {
						return 0;
					}
					m.fade = black ? 0 : -3;
					return 4;
				}
				case 5 -> {
					if (m.fade > 0) {
						return 0;
					}
					m.fade = black ? 4 : 1;
					return 4;
				}
				case 6 -> {
					return unitAct(m, x, y);
				}
				default -> {
				}
			}
			return 0;
		}

		/**
		 * 63 U d act (fp:289e8 l.218-436): the act's state, only when the id has that state (ani
		 * entry != -99), then the 0x8000 lock with +0x2a = 1. Act 5 starts on the last step, act 6
		 * plays state 9 backwards (+0x2c = ~ani). Boss acts and effects are not ported.
		 */
		private int unitAct(Monster m, int dir, int act) {
			if (m.hp < 1) {
				return 0;
			}
			if (act == 0xc || act == 0xd || act == 0xe || act == 0xf) {
				notPorted("유닛 효과 " + act);
				return act == 0xd ? 4 : 3;
			}
			int state = switch (act) {
				case 0 -> 0;
				case 1 -> 6;
				case 2 -> 8;
				case 3 -> 5;
				case 4, 5, 6 -> 9;
				case 0x23 -> 3;
				default -> -1;
			};
			if (state < 0) {
				notPorted("유닛 행동 " + act);
				return 0;
			}
			if ((byte) so.u8(UNIT_ANI + (m.variant * 10 + state) * 2) == -99) {
				return 0;
			}
			changeDir(m, act == 6 ? 0xd : state, dir);
			m.reverse = act == 6;
			if (act == 5) {
				m.step = lastStep(m);
			}
			m.held = 1;
			m.lock = true;
			return act == 3 ? -1 : 0;
		}

		@Override
		public boolean unitExists(int uid) {
			return GameCore.this.unit(uid) != null;
		}

		@Override
		public void mapObject(int obj, boolean visible) {
			notPorted("맵 오브젝트 " + obj);
		}

		@Override
		public boolean moving() {
			// map+0x13d4, hero+0x44 & 0xc, units' +6 & 0xc000.
			if (camScrolling || heroGoX >= 0 || heroActRun) {
				return true;
			}
			for (Monster m : monsters) {
				if (m.goX >= 0 && m.goHold || m.lock) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void arriveAll() {
			// CalcPhase result 5 on a black screen: waited walks land (ChangeMonsDir(1, +0x4a)) and
			// every unit's +6 &= 0x1fff; a hero walk lands with act 0, and hero+0x44 &= 0xf1.
			if (heroGoX >= 0) {
				heroX = heroGoX;
				heroY = heroGoY;
				heroDir = heroGoDir;
				stopHeroWalk();
			}
			heroActRun = false;
			for (Monster m : monsters) {
				if (m.goX >= 0 && m.goHold) {
					m.x = m.goX;
					m.y = m.goY;
					m.goX = -1;
					m.goPx = 0;
					changeDir(m, 1, m.goDir);
				}
				m.lock = false;
			}
		}

		@Override
		public void extraTick() {
			calcCamera();
			scriptMoves();
		}

		@Override
		public int fadeLevel() {
			return fadeV;
		}

		@Override
		public void fadeRestore() {
			if (fadeV >= 8) {
				fadeV = -7;
			} else if (fadeV > 0) {
				fadeV = 1 - fadeV;
			}
		}

		@Override
		public void moveMap(String code) {
			try {
				loadMap(code);
			} catch (IOException e) {
				log = "맵 로드 실패: " + e;
			}
		}

		@Override
		public void reloadEvents() {
			events.loadMap(EventScript.map(assets, mapCode));
		}

		/**
		 * 45 k (fp m_0x291e8 359-438). 00/03 darken from 1/8 over 8 frames, 01/04 lighten from 7/8
		 * over 6 (from opaque over 8 when fully covered), black or white; 02/05 cover at once. In
		 * the skip modes they jump to the end. 06/07/08 set the game speed (07 slowest, 37 frames),
		 * 09/0a freeze and release the world, 0b/0c darken the terrain up to 4/8 and back.
		 */
		@Override
		public int fade(int k, int mode) {
			switch (k) {
				case 0, 3 -> {
					fadeWhite = k == 3;
					if (mode != 0) {
						fadeV = 8;
						return 0;
					}
					fadeV = 1;
					return 8;
				}
				case 1, 4 -> {
					fadeWhite = k == 4;
					if (mode != 0) {
						fadeV = 0;
						return 0;
					}
					boolean covered = fadeV == 8;
					fadeV = covered ? -9 : -7;
					return covered ? 8 : 6;
				}
				case 2, 5 -> {
					fadeWhite = k == 5;
					fadeV = 8;
				}
				case 6, 7, 8 -> {
					// The frame counter restarting here is assumed [inferred].
					speed = k == 6 ? 1 : k == 7 ? -1 : 0;
					speedFrames = 0;
				}
				case 9, 0xa -> frozen = k == 9;
				case 0xb -> {
					dimUp = 1;
					return 4 - dim;
				}
				case 0xc -> {
					dimUp = 0;
					return dim;
				}
				default -> notPorted("화면 효과 " + k);
			}
			return 0;
		}

		@Override
		public void shake(int n) {
			// AddSwing: a new pattern takes over unless one just started with a lower number.
			// g_aptSwing holds patterns 1..10; anything past it does not shake.
			if (swingCnt < 2 || n < swingPat) {
				swingPat = n <= 10 ? n : 0;
				swingCnt = 3;
			}
		}

		@Override
		public void emote(int uid, int kind) {
			Monster m = uid == EventRunner.HERO ? null : GameCore.this.unit(uid);
			if (uid != EventRunner.HERO && m == null || kind >= 14) {
				return;
			}
			for (Emote e : emotes) {
				if (e.target == m) {
					if (e.kind != kind) {
						e.kind = kind;
						e.frame = 0;
					}
					return;
				}
			}
			if (emotes.size() < 10) {
				Emote e = new Emote(m, kind);
				// In the skip modes a new bubble starts at frame 14, two frames from its end.
				e.frame = events.mode != 0 ? 0xe : 0;
				emotes.add(e);
			}
		}

		/**
		 * 08 stops the camera; 18 jumps and 28 scrolls to the tile corner (x * 16, y * 16); 38 / 48
		 * jump to the hero or a unit and follow it, centred or leading in its direction, 58 / 68
		 * follow the same way by scrolling. A follow target is the hero 20 px up or the unit lifted
		 * by its kind's height. 0 is this port's return to the free-play camera.
		 */
		@Override
		public void camera(int op, int x, int y, int target) {
			switch (op) {
				case 0 -> {
					camMode = -1;
					camUnit = null;
				}
				case 0x10 -> camMode = 0xff;
				case 0x11, 0x12 -> {
					camPX = x * TILE;
					camPY = y * TILE;
					if (op == 0x11) {
						cameraOnce(camPX, camPY, 8);
					}
					camMode = 3;
					camUnit = null;
				}
				case 0x13, 0x14, 0x15, 0x16 -> {
					Monster u = target == EventRunner.HERO ? null : GameCore.this.unit(target);
					if (target != EventRunner.HERO && u == null) {
						return;
					}
					boolean lead = op == 0x14 || op == 0x16;
					camUnit = u;
					camMode = u == null ? (lead ? 0 : 1) : (lead ? 2 : 4);
					if (op <= 0x14) {
						int[] t = u == null ? heroTarget() : unitTarget(u);
						cameraOnce(t[0], t[1], !lead ? 8 : u == null ? heroDir : u.dir & 3);
					}
				}
				// SetCamping places map objects 0..4 and their collision bits; this port draws no
				// map objects and has no collision map (t02's 95 / a5 only).
				default -> notPorted("야영지 배치");
			}
		}

		@Override
		public boolean giveItem(int id, int count, int flags) {
			Item.Instance it = items.create(id, 0, rng);
			// GetGOrderEquipLevel refuses gear above the hero's level; there is no inventory to
			// hold anything else yet, so only equippable weapons stay with the hero.
			if ((flags & 2) != 0 && it.type() >= 1 && it.type() <= 3 && hero.level >= items.level(id)) {
				hero.weapon = it;
				hero.recompute(rng);
				log = "무기 장착: #" + id;
			} else {
				log = "아이템 획득: #" + id + " x" + count;
			}
			return true;
		}

		@Override
		public void takeItem(int id, int count) {
			if (hero.weapon != null && hero.weapon.id() == id) {
				hero.weapon = null;
				hero.recompute(rng);
			}
		}

		@Override
		public void save() {
			GameCore.this.save();
		}

		/**
		 * Kind-4 integers from hero+0xa4: 2 level (+0xac), 3 HP (+0xb0), 4 max HP (+0xb4), 6 max MP
		 * (+0xbc) — InitHeroState writes +0xb4 and +0xbc (max MP = pow112(INT) * 6 >> 4, as
		 * {@link Hero#maxMp}), and a01 sets HP to hero[4] / 5. The maxima are derived values, so
		 * writes to them do not stick; MP (+0xb8) and the rest have no field here and are kept as is.
		 */
		@Override
		public int heroInt(int i) {
			return switch (i) {
				case 2 -> hero.level;
				case 3 -> hero.hp;
				case 4 -> hero.maxHp;
				case 6 -> hero.maxMp;
				default -> heroInts[i & 0xff];
			};
		}

		@Override
		public void setHeroInt(int i, int v) {
			switch (i) {
				case 2 -> hero.level = v;
				case 3 -> hero.hp = Math.min(hero.maxHp, v);
				case 4, 6 -> {
				}
				default -> heroInts[i & 0xff] = v;
			}
		}

		@Override
		public int itemCount(int id) {
			return hero.weapon != null && hero.weapon.id() == id ? 1 : 0;
		}

		@Override
		public int systemValue(int i) {
			return switch (i) {
				case 0x14 -> (int) (playMillis * 12 / 1000);
				case 0x16, 0x17 -> hero.heroClass;
				case 0x1a -> (int) (System.currentTimeMillis() / 1000);
				default -> 0;
			};
		}

		@Override
		public String heroName() {
			return hero.name;
		}

		@Override
		public String npcName() {
			return npcName;
		}

		@Override
		public void notPorted(String what) {
			log = "[미포팅] " + what;
			if (notPortedSeen.add(what)) {
				System.out.println("[미포팅] " + what);
			}
		}
	}

	/** Every unported command a script reached, printed once each. */
	private final java.util.Set<String> notPortedSeen = new java.util.LinkedHashSet<>();

	/** Deterministic stand-in shading derived from the real cell byte. */
	private static int groundColour(int cell) {
		if (cell == 0xff) {
			return 0xff120e0a;
		}
		int v = (cell & 0x3f) * 4;
		return 0xff000000 | (46 + v / 2) << 16 | (40 + v / 3) << 8 | (30 + v / 4);
	}

	private void bar(Gfx g, int x, int y, float ratio, int argb) {
		int w = CELL * SCALE - 8;
		g.fillRect(x, y, w, 4, 0xa0000000);
		g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, ratio))), 4, argb);
	}

	private void save() {
		try {
			Properties p = new Properties();
			p.setProperty("name", hero.name);
			p.setProperty("class", String.valueOf(hero.heroClass));
			p.setProperty("mode", "0");
			p.setProperty("playTime", String.valueOf(playMillis * 12 / 1000));
			p.setProperty("map", mapCode);
			p.setProperty("x", String.valueOf(heroX));
			p.setProperty("y", String.valueOf(heroY));
			p.setProperty("hp", String.valueOf(hero.hp));
			p.setProperty("gold", String.valueOf(gold));
			p.setProperty("potions", String.valueOf(potions));
			p.setProperty("level", String.valueOf(hero.level));
			p.setProperty("exp", String.valueOf(hero.exp));
			p.setProperty("stats", hero.con + "," + hero.dex + "," + hero.str + "," + hero.intel);
			p.setProperty("vars", vars(events.vars));
			StringBuilder ints = new StringBuilder();
			for (int i = 0; i < heroInts.length; i++) {
				if (heroInts[i] != 0) {
					ints.append(i).append('=').append(heroInts[i]).append(';');
				}
			}
			p.setProperty("heroInts", ints.toString());
			// Units a phase spawned (uid 0x100 + n) live only as long as the scene; map units stay.
			p.setProperty("monsters", monsters.stream().filter(m -> m.uid < 0x100)
					.map(m -> String.join(",", String.valueOf(m.x), String.valueOf(m.y), m.kind,
							String.valueOf(m.hp), String.valueOf(m.atk), String.valueOf(m.variant),
							String.valueOf(m.aggressive), String.valueOf(m.loX), String.valueOf(m.loY),
							String.valueOf(m.hiX), String.valueOf(m.hiY), String.valueOf(m.idle),
							String.valueOf(m.state), String.valueOf(m.facing), String.valueOf(m.level),
							String.valueOf(m.defence), String.valueOf(m.accuracy), String.valueOf(m.dodge),
							String.valueOf(m.crit), String.valueOf(m.uid)))
					.reduce((a, b) -> a + ";" + b).orElse(""));
			storage.save(slotKey(slotIndex), propertiesText(p));
			log = "저장 완료: 슬롯 " + slotIndex;
		} catch (RuntimeException e) {
			log = "저장 실패: " + e;
		}
	}

	/**
	 * {@code p} in the .properties format {@link Properties#load} reads, non-ASCII as backslash-u escapes.
	 * TeaVM's {@code store} needs ISO-8859-1, which it lacks, so the text is written here.
	 */
	static String propertiesText(Properties p) {
		StringBuilder out = new StringBuilder();
		for (String key : new java.util.TreeSet<>(p.stringPropertyNames())) {
			escape(out, key, true);
			out.append('=');
			escape(out, p.getProperty(key), false);
			out.append('\n');
		}
		return out.toString();
	}

	private static void escape(StringBuilder out, String s, boolean key) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' || c == '=' || c == ':' || c == '#' || c == '!' || c == ' ' && (key || i == 0)) {
				out.append('\\').append(c);
			} else if (c < 0x20 || c > 0x7e) {
				out.append("\\u").append(Integer.toHexString(0x10000 | c).substring(1));
			} else {
				out.append(c);
			}
		}
	}

	/** LoadSlot: rebuilds the hero and the map from the current slot's file. */
	private void load() {
		Properties p = slot(slotIndex);
		if (p == null) {
			log = "저장 파일이 없다.";
			return;
		}
		try {
			newHero(Integer.parseInt(p.getProperty("class", "0")), p.getProperty("name", ""));
			playMillis = Long.parseLong(p.getProperty("playTime", "0")) * 1000 / 12;
			newEvents();
			restoreVars(events.vars, p.getProperty("vars", ""));
			for (String kv : p.getProperty("heroInts", "").split(";")) {
				if (!kv.isEmpty()) {
					String[] f = kv.split("=");
					heroInts[Integer.parseInt(f[0])] = Integer.parseInt(f[1]);
				}
			}
			loadMap(p.getProperty("map", MAP_CODES[0]));
			heroX = Integer.parseInt(p.getProperty("x", "10"));
			heroY = Integer.parseInt(p.getProperty("y", "12"));
			gold = Integer.parseInt(p.getProperty("gold", "300"));
			potions = Integer.parseInt(p.getProperty("potions", "3"));
			hero.level = Integer.parseInt(p.getProperty("level", "1"));
			hero.exp = Integer.parseInt(p.getProperty("exp", String.valueOf(hero.levelExp(hero.level))));
			String[] st = p.getProperty("stats", hero.con + "," + hero.dex + "," + hero.str
					+ "," + hero.intel).split(",");
			hero.con = Integer.parseInt(st[0]);
			hero.dex = Integer.parseInt(st[1]);
			hero.str = Integer.parseInt(st[2]);
			hero.intel = Integer.parseInt(st[3]);
			hero.recompute(rng);
			hero.hp = Math.min(hero.maxHp, Integer.parseInt(p.getProperty("hp", String.valueOf(hero.maxHp))));
			monsters.clear();
			String blob = p.getProperty("monsters", "");
			if (!blob.isBlank()) {
				for (String row : blob.split(";")) {
					String[] f = row.split(",");
					Monster m = new Monster(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f[2],
							Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
							Boolean.parseBoolean(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]),
							Integer.parseInt(f[9]), Integer.parseInt(f[10]));
					m.idle = Boolean.parseBoolean(f[11]);
					m.state = Integer.parseInt(f[12]);
					m.facing = Integer.parseInt(f[13]);
					if (f.length > 18) {
						m.level = Integer.parseInt(f[14]);
						m.defence = Integer.parseInt(f[15]);
						m.accuracy = Integer.parseInt(f[16]);
						m.dodge = Integer.parseInt(f[17]);
						m.crit = Integer.parseInt(f[18]);
					}
					if (f.length > 19) {
						m.uid = Integer.parseInt(f[19]);
					}
					if (m.variant >= NPC_ID) {
						m.npc = true;
						m.aggressive = false;
						changeDir(m, 1, 1);
					}
					monsters.add(m);
				}
			}
			log = "불러오기 완료.";
			// LoadSlot loads the map with mode 3: its events reload and SearchPhase runs.
			enterEvents();
		} catch (IOException | NumberFormatException e) {
			log = "불러오기 실패: " + e;
		}
	}

	/** The storage key of a slot, the port's stand-in for slot%d.dat. */
	private static String slotKey(int slot) {
		return "slot" + slot;
	}

	/** A slot's saved properties, or null when it is empty or unreadable. */
	private Properties slot(int i) {
		String text = storage.load(slotKey(i));
		if (text == null) {
			return null;
		}
		Properties p = new Properties();
		try {
			p.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
		return p;
	}

	/** SaveSlot's script-variable blocks (u8, 2-bit, 1-bit, system 0..0x13) as hex. */
	private static String vars(EventScript.Vars v) {
		StringBuilder s = new StringBuilder();
		for (byte[] block : new byte[][] { v.u8, v.two, v.one }) {
			for (byte b : block) {
				s.append(String.format("%02x", b & 0xff));
			}
		}
		for (int i : v.sys) {
			s.append(String.format("%08x", i));
		}
		return s.toString();
	}

	private static void restoreVars(EventScript.Vars v, String hex) {
		int at = 0;
		for (byte[] block : new byte[][] { v.u8, v.two, v.one }) {
			for (int i = 0; i < block.length && at + 2 <= hex.length(); i++, at += 2) {
				block[i] = (byte) Integer.parseInt(hex.substring(at, at + 2), 16);
			}
		}
		for (int i = 0; i < v.sys.length && at + 8 <= hex.length(); i++, at += 8) {
			v.sys[i] = (int) Long.parseLong(hex.substring(at, at + 8), 16);
		}
	}

	/**
	 * Monster state laid out from CalcMonsAct: x/y, type byte, flag word, facing, action
	 * state, idle flag and the patrol window that CalcMonsActMove reads at +0x42/+0x43
	 * and +0x85/+0x87 (each byte is a 16px tile bound).
	 */
	private static final class Monster {
		int x;
		int y;
		final String kind;
		final int maxHp;
		int hp;
		final int atk;
		final int variant;
		boolean aggressive;
		boolean idle;
		int state;
		boolean atkOnce;
		int facing;
		final int loX;
		final int loY;
		final int hiX;
		final int hiY;
		int level;
		/** InitMonsAbility's +0x74, +0x78, +0x7c and +0x80. */
		int defence;
		int accuracy;
		int dodge;
		int crit;
		/** +0x4e: the record number for map units, 0x100 + n for units a phase spawned. */
		int uid = -1;
		boolean npc;
		/** ChangeMonsDir's ani entry (+0x2c), row (+0x27), mirror (+0x28) and direction (+5). */
		int ani = -1;
		int row;
		boolean mirror;
		int dir = 1;
		/** A script walk (33): target cell, arrival direction, px into the next cell, waited on (0x4000). */
		int goX = -1;
		int goY;
		int goDir;
		int goPx;
		boolean goHold;
		/** +0x42: fade step, -3..-1 fading in, 1..3 fading out, 4 and up unseen and still. */
		int fade;
		/** The 0x8000 action lock (63), held until the action's animation cycle ends. */
		boolean lock;
		/** ChangeMonsDir's state (+0x20; -1 none yet), +0x29 step, +0x2a frames held, and +0x2c &lt; 0 (played backwards). */
		int aniState = -1;
		int step;
		int held;
		boolean reverse;
		/** The animation came back to step 0 since the last CalcMonsAct. */
		boolean wrapped;

		Monster(int x, int y, String kind, int hp, int atk, int variant, boolean aggressive,
				int loX, int loY, int hiX, int hiY) {
			this.x = x;
			this.y = y;
			this.kind = kind;
			this.hp = hp;
			this.maxHp = hp;
			this.atk = atk;
			this.variant = variant;
			this.aggressive = aggressive;
			this.state = aggressive ? 0 : 2;
			this.loX = loX;
			this.loY = loY;
			this.hiX = hiX;
			this.hiY = hiY;
		}

		int pixelLoX() {
			return loX * 16;
		}

		int pixelHiX() {
			return (loX + 1) * 16;
		}

		int pixelLoY() {
			return loY * 16;
		}

		int pixelHiY() {
			return (loY + 1) * 16;
		}

		/** Fallback marker colour, used only when the sprite archive cannot be decoded. */
		int color() {
			return 0xff404040 + (variant * 0x9e3779 & 0x7f7f7f);
		}
	}

	/** Where {@link #selfTest} puts its frames (the desktop writes them as PNG files). */
	interface FrameSink {
		void write(String name, Img frame) throws IOException;
	}

	/**
	 * Drives scripted input through the slot screen and a short fight, handing a frame of each
	 * stage to {@code sink}, so behaviour is verifiable without GUI automation.
	 */
	void selfTest(FrameSink sink) throws IOException {
		writeFrame(sink, "logo.png");
		for (int i = 0; i < 80; i++) {
			opening.render();
		}
		writeFrame(sink, "title.png");
		key(Keys.ENTER);
		key(Keys.DOWN);
		writeFrame(sink, "title-menu.png");
		key(Keys.UP);
		key(Keys.ENTER);
		writeFrame(sink, "slots.png");
		key(Keys.ENTER);
		writeFrame(sink, "newgame-default.png");
		key(Keys.ENTER);
		// The box starts with the default "크리스노아"; clear it and type another name.
		for (int i = 0; i < 5; i++) {
			key(Keys.BACK_SPACE);
		}
		for (char c : "와와".toCharArray()) {
			slotScreen.typed(c);
		}
		key(Keys.ENTER);
		key(Keys.RIGHT);
		writeFrame(sink, "newgame.png");
		key(Keys.ENTER);
		// The new game's opening phase: tick it like the 62 ms timer, press OK every 12 frames to
		// move the dialogue on, and keep a few frames of the scene.
		int[] shots = { 30, 200, 420, 700, 1000, 1400 };
		int tick = 0;
		int shotAt = 0;
		while (events.active() && tick < 8000) {
			gameTick();
			tick++;
			if (tick % 12 == 0) {
				key(Keys.ENTER);
			}
			if (shotAt < shots.length && tick == shots[shotAt]) {
				writeFrame(sink, "intro" + shotAt++ + ".png");
			}
		}
		writeFrame(sink, "intro-end.png");
		System.out.println("intro frames=" + tick + " running=" + events.active() + " map=" + mapCode
				+ " hero=" + heroX + "," + heroY + " var1=" + events.vars.get(1) + " notPorted=" + notPortedSeen);
		loadMap(MAP_CODES[0]);
		int[] keys = {
			Keys.RIGHT, Keys.RIGHT, Keys.DOWN, Keys.DOWN, Keys.RIGHT, Keys.RIGHT, Keys.RIGHT
		};
		for (int k : keys) {
			key(k);
		}
		Monster target = nearestMonster();
		int guard = 0;
		while (target != null && guard++ < 400) {
			int dist = Math.abs(target.x - heroX) + Math.abs(target.y - heroY);
			if (dist <= 1) {
				attack(target);
			} else {
				walkTo(target.x, target.y);
			}
			if (hero.level > 3) {
				break;
			}
			target = nearestMonster();
		}
		playMillis += 3_725_000;
		save();
		load();
		writeFrame(sink, "selftest.png");
		System.out.println("slot=" + slotIndex + " name=" + hero.name + " class=" + hero.heroClass
				+ " map=" + mapCode + " hero=" + heroX + "," + heroY + " lv=" + hero.level + " exp=" + hero.exp
				+ " hp=" + hero.hp + "/" + hero.maxHp + " atk=" + hero.power() + " gold=" + gold
				+ " monsters=" + monsters.size() + " log=" + log);
		new GameCore(assets, storage).writeFrame(sink, "slots-after.png");
	}

	private void writeFrame(FrameSink sink, String name) throws IOException {
		Img img = new Img(W, H);
		render(new Gfx(img));
		sink.write(name, img);
	}

	private Monster nearestMonster() {
		Monster best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Monster m : monsters) {
			int d = Math.abs(m.x - heroX) + Math.abs(m.y - heroY);
			if (d < bestDist) {
				best = m;
				bestDist = d;
			}
		}
		return best;
	}
}

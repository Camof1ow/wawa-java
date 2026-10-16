package wawa;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * Vertical slice of wild.apk: the area maps (assets/eng/map, drawn with the real tiles)
 * with grid movement, monsters, turn-based combat and a local save file.
 * Combat mirrors libwawa.so: charge() grants the item then writes the slot.
 */
public final class Game extends JFrame {

	private static final int SCALE = 2;
	private static final int CELL = 20;
	private static final int MAP_X = 8;
	private static final int MAP_Y = 26;

	private final ElfImage so;
	private final SlotScreen slotScreen;
	/** True while the slot screen is up; the world neither ticks nor draws until a slot opens. */
	private boolean selecting = true;
	/** The logo and title screens before the slot screen; null once Play is chosen. */
	private TitleScreen opening;
	private int slotIndex;
	/** Time played in this slot; slots store it in DrawPopupSlot's 1/12 s ticks. */
	private long playMillis;
	private String mapCode = MAP_CODES[0];
	private final BufferedImage heroPortrait;
	private MapData map;
	private Tiles tiles;
	private Path assetsRoot;
	private final List<Monster> monsters = new ArrayList<>();
	private final Random rng = new Random(20111017L);
	private static final int TILE = 16;
	private static final int VIEW_W = 20;
	private static final int VIEW_H = 12;

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
	/** Opcode 45's screen fade: 0 clear .. 8 full, the step per frame, and white or black. */
	private int fade;
	private int fadeStep;
	private boolean fadeWhite;
	/** AddSwing frames left. */
	private int shake;
	/** A script camera: the cell the view centres on, or a unit it follows; null follows the hero. */
	private int[] camCentre;
	private Monster camUnit;
	private boolean heroHidden;
	/** A scripted hero walk (83): target cell, arrival direction (8 keeps), frames to the next step. */
	private int heroGoX = -1;
	private int heroGoY;
	private int heroGoDir;
	private int heroGoWait;
	/** AddImoticon entries: target (null = hero), kind, frames drawn. */
	private final List<Emote> emotes = new ArrayList<>();
	private int frames;
	/**
	 * The hero record's integers from +0xa4 that scripts read and write (kind 4) and this port has
	 * no field for; level (2) and HP (3) map onto {@link Hero}. Saved with the slot like the record.
	 */
	private int[] heroInts = new int[0x100];
	private Item items;
	private HeroAnim heroAnim;
	/**
	 * The hero record's direction byte (+5), numbered like units (CollMonsPath): 0 up, 1 down,
	 * 2 left, 3 right (ani_hero draws 3 as 2 mirrored), 4..7 diagonals. The t01 script walks the hero
	 * left with arrival direction 2 ({@code 83 0b 0f 02}), which fixes 2 as left.
	 */
	private int heroDir = 1;
	private static final int[][] DIR_STEP = {
		{ 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 }, { 1, -1 }, { -1, 1 }, { -1, -1 }, { 1, 1 }
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

	public Game() throws IOException {
		super("Wawa - wild.apk Java port");
		Path assets = assetsDir();
		Gsac face = Gsac.load(assets.resolve("img/face00.png"));
		heroPortrait = face.frame(0);
		so = ElfImage.read(MonsterSprite.nativeLib(assets));
		items = new Item(so);
		newHero(0, "");

		// The world behind the slot screen is not drawn until a slot is opened, but the rest of
		// the port expects a loaded map, so a01 stands loaded underneath.
		loadMap(MAP_CODES[0]);
		heroAnim = new HeroAnim(assets, so);
		talk = new TalkBox(assets, so);
		events = new EventRunner(new Stage(), talk);
		events.global = EventScript.global(assets);
		imoticon = Gsac.loadCompanion(assets.resolve("img/imoticon.png"));
		slotScreen = new SlotScreen(assets, so, items, readSummaries());
		opening = new TitleScreen(assets);

		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		setResizable(false);
		setContentPane(new javax.swing.JPanel() {
			@Override
			protected void paintComponent(java.awt.Graphics g) {
				super.paintComponent(g);
				draw((Graphics2D) g);
			}
		});
		setSize(VIEW_W * CELL + 16, MAP_Y + VIEW_H * CELL + 46);
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				onKey(e.getKeyCode());
			}

			@Override
			public void keyTyped(KeyEvent e) {
				if (selecting) {
					slotScreen.typed(e.getKeyChar());
					repaint();
				}
			}
		});
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (!selecting && !events.active()) {
					onClick(e);
				}
			}
		});

		// CalcGamePlay runs on the native 62 ms timer with this order:
		// InitHeroState -> CalcHeroAct -> CalcMons -> CalcHeroRun -> Post*Value -> phase pop.
		javax.swing.Timer loop = new javax.swing.Timer(62, e -> {
			if (!selecting) {
				playMillis += 62;
				gameTick();
			}
			repaint();
		});
		loop.start();
	}

	/** InitHero for a class, holding the weapon a new game first equips. */
	private void newHero(int heroClass, String name) {
		hero = new Hero(so, heroClass);
		hero.name = name;
		hero.weapon = items.create(STARTING_WEAPON, 0, rng);
		heroInts = new int[0x100];
		hero.recompute(rng);
		hero.hp = hero.maxHp;
	}

	/** Applies what the slot screen asked for; false when the window should close. */
	private boolean onSlotAction(SlotScreen.Action action) {
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
			try {
				Files.deleteIfExists(saveFile(d.slot()));
			} catch (IOException e) {
				log = "삭제 실패: " + e;
			}
			slotScreen.slotDeleted(d.slot());
		} else if (action instanceof SlotScreen.Action.Quit) {
			dispose();
			return false;
		}
		return true;
	}

	private SlotScreen.Summary[] readSummaries() {
		SlotScreen.Summary[] out = new SlotScreen.Summary[SlotScreen.SLOTS];
		for (int i = 0; i < out.length; i++) {
			Path f = saveFile(i);
			if (!Files.exists(f)) {
				continue;
			}
			try (InputStream is = Files.newInputStream(f)) {
				Properties p = new Properties();
				p.load(is);
				out[i] = new SlotScreen.Summary(p.getProperty("name", ""),
						Integer.parseInt(p.getProperty("class", "0")), Integer.parseInt(p.getProperty("level", "1")),
						Integer.parseInt(p.getProperty("mode", "0")), Integer.parseInt(p.getProperty("playTime", "0")),
						// The slot hero wears what this port's hero always holds: the starting weapon.
						HeroAnim.wearing(items, items.create(STARTING_WEAPON, 0, rng), 1, HeroAnim.ACT_STAND));
			} catch (IOException | NumberFormatException e) {
				out[i] = null;
			}
		}
		return out;
	}

	/** Loads one of the shipped maps by its 3-character code, as LoadGameMap does. */
	private void loadMap(String code) throws IOException {
		assetsRoot = assetsDir();
		mapCode = code;
		String letter = code.substring(0, 1);
		map = MapData.load(assetsRoot.resolve("eng/map/" + letter + ".png"), code);
		try {
			area = AreaInfo.load(assetsRoot.resolve("eng/mob/" + letter + ".png"), code);
		} catch (IOException e) {
			area = AreaInfo.absent(code);
		}
		tiles = Tiles.load(assetsRoot, map);
		Path mobFile = assetsRoot.resolve("eng/mob/" + letter + ".png");
		try {
			monsterTable = MonsterTable.load(mobFile, code, map.width, map.height);
		} catch (IOException e) {
			monsterTable = MonsterTable.empty(code);
		}
		if (monsterSprites == null) {
			monsterSprites = MonsterSprite.load(assetsRoot);
			monsterStats = MonsterStats.load(MonsterSprite.nativeLib(assetsRoot));
		}
		heroX = map.width / 2;
		heroY = map.height / 2;
		monsters.clear();
		spawnMonsters();
		camCentre = null;
		camUnit = null;
		heroHidden = false;
		heroGoX = -1;
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
		events.loadMap(EventScript.map(assetsRoot, mapCode));
		events.searchPhase();
	}

	/** One CalcGamePlay tick in the original order. */
	private void gameTick() {
		frames++;
		tickEffects();
		if (events.active()) {
			// CalcGamePlay keeps moving the hero and units while a phase runs; monsters stand still
			// (SetPhaseHeroStop sets their 0x2000 bit) and the player's input goes to the phase.
			scriptMoves();
			events.calcPhase();
			events.frame();
			return;
		}
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

	private static Path assets;

	/**
	 * The assets directory: an extracted tree, or {@code wild.apk} itself, read in place through
	 * the zip file system (the browser build hands the APK over this way).
	 */
	private static Path assetsDir() {
		if (assets == null) {
			Path p = Path.of(System.getProperty("wawa.assets", "assets"));
			try {
				assets = p.toString().endsWith(".apk") ? FileSystems.newFileSystem(p).getPath("/assets") : p;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
		return assets;
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

	private void onKey(int code) {
		if (opening != null) {
			if (opening.key(code)) {
				opening = null;
			}
			repaint();
			return;
		}
		if (selecting) {
			SlotScreen.Action action = slotScreen.key(code);
			if (action == null || onSlotAction(action)) {
				repaint();
			}
			return;
		}
		// KeyPressGamePlay hands every key to KeyPressPhase first; a running phase eats it.
		if (events.key(code)) {
			repaint();
			return;
		}
		switch (code) {
			case KeyEvent.VK_LEFT, KeyEvent.VK_A -> step(-1, 0);
			case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> step(1, 0);
			case KeyEvent.VK_UP, KeyEvent.VK_W -> step(0, -1);
			case KeyEvent.VK_DOWN, KeyEvent.VK_S -> step(0, 1);
			case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> talkToNpc();
			case KeyEvent.VK_1 -> attackNearest();
			case KeyEvent.VK_2 -> drinkPotion();
			case KeyEvent.VK_F5 -> save();
			case KeyEvent.VK_F9 -> load();
			case KeyEvent.VK_ESCAPE -> dispose();
			case KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7,
					KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS,
					KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_OPEN_BRACKET -> {
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
		repaint();
	}

	private static int keyToMapIndex(int code) {
		return switch (code) {
			case KeyEvent.VK_3 -> 0;
			case KeyEvent.VK_4 -> 1;
			case KeyEvent.VK_5 -> 2;
			case KeyEvent.VK_6 -> 3;
			case KeyEvent.VK_7 -> 4;
			case KeyEvent.VK_8 -> 5;
			case KeyEvent.VK_9 -> 6;
			case KeyEvent.VK_0 -> 7;
			case KeyEvent.VK_MINUS -> 8;
			case KeyEvent.VK_EQUALS -> 9;
			case KeyEvent.VK_BACK_QUOTE -> 10;
			default -> 11;
		};
	}

	private void onClick(MouseEvent e) {
		int gx = (e.getX() - MAP_X) / (CELL * SCALE);
		int gy = (e.getY() - MAP_Y) / (CELL * SCALE);
		if (!map.inBounds(gx, gy)) {
			return;
		}
		Monster target = monsterAt(gx, gy);
		if (target != null && !target.npc && Math.abs(gx - heroX) <= 1 && Math.abs(gy - heroY) <= 1) {
			attack(target);
		} else {
			walkTo(gx, gy);
		}
		repaint();
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

	private void draw(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		if (selecting) {
			// The slot screen is laid out on the original 480x320; fit it to the window width.
			int w = VIEW_W * CELL + 16;
			int h = w * SlotScreen.H / SlotScreen.W;
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.drawImage(opening != null ? opening.render() : slotScreen.render(), 0, 0, w, h, null);
			return;
		}
		g.setColor(new Color(10, 10, 14));
		g.fillRect(0, 0, getWidth(), getHeight());

		drawTiles(g);

		g.drawImage(heroPortrait, 2, 2, 22, 22, null);
		g.setFont(new Font("SansSerif", Font.PLAIN, 12));
		g.setColor(new Color(230, 220, 200));
		int from = hero.levelExp(hero.level);
		String expText = hero.level >= Hero.MAX_LEVEL ? "MAX"
				: (hero.exp - from) + "/" + (hero.levelExp(hero.level + 1) - from);
		g.drawString("Lv" + hero.level + " (" + expText + "EXP)   HP " + hero.hp + "/" + hero.maxHp
				+ "   ATK " + hero.power() + "   골드 " + gold + "   물약 " + potions,
				28, 16);
		int foot = MAP_Y + VIEW_H * CELL;
		g.setColor(new Color(180, 210, 240));
		g.drawString(area.name + "  밝기 " + area.brightness + (area.rainy() ? "  비" : ""),
				MAP_X, foot - 4);
		g.setColor(new Color(250, 230, 150));
		g.drawString(log, MAP_X, foot + 14);
		g.setColor(new Color(170, 165, 150));
		g.drawString("[방향키] 이동  [Enter] 대화  [클릭] 이동/전투  [1] 공격  [2] 물약  [F5] 저장  [F9] 불러오기"
				+ "  [3~` ] 12개 지형 전환  [ESC] 종료",
				MAP_X, foot + 30);
		if (events.active()) {
			// DrawPhase works on the original 480x320 screen; fit it to the window like the slot screen.
			BufferedImage overlay = new BufferedImage(TalkBox.W, TalkBox.H, BufferedImage.TYPE_INT_ARGB);
			Graphics2D og = overlay.createGraphics();
			og.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			events.draw(og);
			og.dispose();
			int w = VIEW_W * CELL + 16;
			// Text loses strokes under nearest-neighbour downscaling, so this one scales smoothly.
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(overlay, 0, 0, w, w * TalkBox.H / TalkBox.W, null);
		}
	}

	/** The view origin in cells: a script camera or unit, else centred on the hero. */
	private int[] camera() {
		int cx = heroX;
		int cy = heroY;
		if (camUnit != null && monsters.contains(camUnit)) {
			cx = camUnit.x;
			cy = camUnit.y;
		} else if (camCentre != null) {
			cx = camCentre[0];
			cy = camCentre[1];
		}
		return new int[] {
			Math.max(0, Math.min(map.width - VIEW_W, cx - VIEW_W / 2)),
			Math.max(0, Math.min(map.height - VIEW_H, cy - VIEW_H / 2))
		};
	}

	/** A sprite whose anchor goes on a cell centre, at the view scale. */
	private static void drawPose(Graphics2D g, PartSprites.Pose pose, int cx, int cy) {
		BufferedImage spr = pose.image();
		g.drawImage(spr, cx - pose.anchorX() * CELL / TILE, cy - pose.anchorY() * CELL / TILE,
				Math.max(1, spr.getWidth() * CELL / TILE), Math.max(1, spr.getHeight() * CELL / TILE), null);
	}

	/** Draws the tile grid with the camera, using the DrawTile cell rule, then units and the hero. */
	private void drawTiles(Graphics2D g) {
		int[] cam = camera();
		int camX = cam[0];
		int camY = cam[1];
		// AddSwing: the amplitude and pattern are not traced; a 2 px side-to-side jolt stands in.
		int swing = shake > 0 ? (shake % 2 == 0 ? 2 : -2) : 0;
		g.translate(swing, 0);
		int ox = MAP_X + CELL / 2;
		int oy = MAP_Y + CELL / 2;
		for (int y = camY; y < camY + VIEW_H; y++) {
			for (int x = camX; x < camX + VIEW_W; x++) {
				int cell = map.cell(x, y);
				BufferedImage f = cell == 0xff ? null : tiles.patch(map.layer(x, y), cell);
				if (f != null) {
					g.drawImage(f, MAP_X + (x - camX) * CELL, MAP_Y + (y - camY) * CELL,
							CELL, CELL, null);
					continue;
				}
				// The ground atlas the native build composes at run time is not recovered yet,
				// so shade the cell from its own byte: the pattern is real data, the art is not.
				g.setColor(groundColour(cell));
				g.fillRect(MAP_X + (x - camX) * CELL, MAP_Y + (y - camY) * CELL, CELL, CELL);
			}
		}
		for (Monster m : monsters) {
			if (m.hidden || m.x < camX || m.y < camY || m.x >= camX + VIEW_W || m.y >= camY + VIEW_H) {
				continue;
			}
			int cx = ox + (m.x - camX) * CELL;
			int cy = oy + (m.y - camY) * CELL;
			// InitMonsOne puts the monster's position at the cell centre and DrawMonsAnim draws every
			// part relative to it, so the pose's anchor goes there; the view scale carries over.
			PartSprites.Pose pose = monsterSprites == null ? null
					: m.ani >= 0 ? monsterSprites.pose(m.variant, m.ani, frames, m.mirror)
					: monsterSprites.standing(m.variant);
			if (pose != null) {
				drawPose(g, pose, cx, cy);
			} else {
				g.setColor(m.color());
				g.fillOval(cx - CELL / 2 + 4, cy - CELL / 2 + 4, CELL - 8, CELL - 8);
			}
			if (!m.npc) {
				bar(g, cx - CELL / 2 + 2, cy + CELL / 2 - 5, (float) m.hp / m.maxHp, new Color(220, 60, 60));
			}
		}
		int hx = ox + (heroX - camX) * CELL;
		int hy = oy + (heroY - camY) * CELL;
		if (!heroHidden) {
			// DrawHeroAnim: the hero's position is the cell centre, as for monsters.
			try {
				PartSprites.Pose pose = heroAnim.pose(HeroAnim.wearing(items, hero.weapon, heroDir & 3,
						HeroAnim.ACT_STAND), frames / 2);
				if (pose != null) {
					drawPose(g, pose, hx, hy);
				}
			} catch (IOException e) {
				log = "영웅 그림 실패: " + e;
			}
			if (!events.active()) {
				bar(g, hx - CELL / 2, hy + CELL / 2 - 5, (float) hero.hp / hero.maxHp, new Color(80, 220, 120));
			}
		}
		for (Emote e : emotes) {
			if (e.target == null) {
				drawEmote(g, e, hx, hy - 0x28 * CELL / TILE);
			} else if (monsters.contains(e.target)) {
				Monster m = e.target;
				int lift = so.u8(UNIT_HEIGHT + monsterSprites.kind(m.variant));
				drawEmote(g, e, ox + (m.x - camX) * CELL, oy + (m.y - camY) * CELL - lift * CELL / TILE);
			}
		}
		g.translate(-swing, 0);
		if (fade > 0) {
			int a = Math.min(255, fade * 32);
			g.setColor(fadeWhite ? new Color(255, 255, 255, a) : new Color(0, 0, 0, a));
			g.fillRect(MAP_X, MAP_Y, VIEW_W * CELL, VIEW_H * CELL);
		}
	}

	/**
	 * DrawImoticon: a base clip, then from frame 3 the kind's overlay clip; clips are
	 * {s16 dx, s16 dy, s16 frame} into img/imoticon.
	 */
	private void drawEmote(Graphics2D g, Emote e, int x, int y) {
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

	private void clip(Graphics2D g, int c, int x, int y) {
		BufferedImage f = imoticon.frame(so.s16(EMOTE_CLIP + c * 8 + 4));
		if (f != null) {
			g.drawImage(f, x + so.s16(EMOTE_CLIP + c * 8) * CELL / TILE, y + so.s16(EMOTE_CLIP + c * 8 + 2) * CELL / TILE,
					f.getWidth() * CELL / TILE, f.getHeight() * CELL / TILE, null);
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
	/** CollMonsPath moves an NPC 6 px a frame, the hero 8: frames per 16 px cell. */
	private static final int UNIT_STEP_FRAMES = 3;
	private static final int HERO_STEP_FRAMES = 2;

	/**
	 * ChangeMonsDir(m, state, dir): the effective state's ani entry for the row the direction
	 * picks (up 1, down 0, sideways keeps), mirrored for the other side. Dir 8 keeps the direction.
	 */
	private void changeDir(Monster m, int state, int dir) {
		if (dir == 8) {
			dir = m.dir;
		}
		int s = state <= 9 ? state : state == 0xd ? 9 : state == 0xe || state == 0x11 || state == 0x12 ? 1
				: state == 0xf || state == 0x10 ? 8 : 3;
		if ((byte) so.u8(UNIT_FLAGS + m.variant) < 0) {
			m.row = 0;
		} else if (dir == 0 || dir == 4 || dir == 6) {
			m.row = 1;
		} else if (dir == 1 || dir == 5 || dir == 7) {
			m.row = 0;
		}
		int ani = (byte) so.u8(UNIT_ANI + (m.variant * 10 + s) * 2 + m.row);
		if (ani != -99) {
			m.ani = ani;
		}
		if (dir == 3 || dir == 4 || dir == 7) {
			m.mirror = m.row == 0;
		} else if (dir == 2 || dir == 5 || dir == 6) {
			m.mirror = m.row != 0;
		}
		m.dir = dir;
	}

	/** Per-frame decay of the fade, the shake and the emote bubbles (16 frames each). */
	private void tickEffects() {
		fade = Math.max(0, Math.min(8, fade + fadeStep));
		if (fade == 0 || fade == 8) {
			fadeStep = 0;
		}
		if (shake > 0) {
			shake--;
		}
		emotes.removeIf(e -> ++e.frame > 15);
	}

	/** CalcHeroAct / CalcMonsRun for script walks: one cell per step time, straight or diagonal. */
	private void scriptMoves() {
		if (heroGoX >= 0 && --heroGoWait <= 0) {
			int dx = Integer.compare(heroGoX, heroX);
			int dy = Integer.compare(heroGoY, heroY);
			if (dx == 0 && dy == 0) {
				if (heroGoDir != 8) {
					heroDir = heroGoDir & 3;
				}
				heroGoX = -1;
			} else {
				heroX += dx;
				heroY += dy;
				heroDir = dx < 0 ? 2 : dx > 0 ? 3 : dy < 0 ? 0 : 1;
				heroGoWait = HERO_STEP_FRAMES;
			}
		}
		for (Monster m : monsters) {
			if (m.lock > 0 && --m.lock == 0) {
				changeDir(m, 1, 8);
			}
			if (m.goX < 0 || --m.goWait > 0) {
				continue;
			}
			int dx = Integer.compare(m.goX, m.x);
			int dy = Integer.compare(m.goY, m.y);
			if (dx == 0 && dy == 0) {
				m.goX = -1;
				changeDir(m, 1, m.goDir);
				continue;
			}
			m.x += dx;
			m.y += dy;
			changeDir(m, 3, stepDir(dx, dy));
			m.goWait = UNIT_STEP_FRAMES;
		}
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
			if (m.npc && !m.hidden && Math.abs(m.x - heroX) <= 1 && Math.abs(m.y - heroY) <= 1) {
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
			// SetPhaseHeroStop freezes the hero and monsters; gameTick already skips both while a
			// phase runs, and a player walk is one cell per key, so nothing is left to stop.
		}

		@Override
		public void heroPlace(int x, int y, int dir) {
			if (x != 0xff || y != 0xff) {
				heroX = x;
				heroY = y;
			}
			if (dir != 8) {
				heroDir = dir & 3;
			}
			heroGoX = -1;
		}

		@Override
		public void heroWalk(int x, int y, int dir) {
			if (x == 0xff && y == 0xff) {
				heroPlace(x, y, dir);
				return;
			}
			if (events.mode == 2) {
				heroPlace(x, y, dir);
				return;
			}
			heroGoX = x;
			heroGoY = y;
			heroGoDir = dir;
			heroGoWait = 1;
		}

		@Override
		public void heroVisible(boolean visible, int x, int y, int dir) {
			heroHidden = !visible;
			heroPlace(x, y, dir);
		}

		@Override
		public void heroAct(int dir, int act) {
			if (dir != 8) {
				heroDir = dir & 3;
			}
			if (act != 0) {
				notPorted("영웅 행동 " + act);
			}
		}

		@Override
		public void heroToUnit(int uid, int side) {
			// The side offsets sit in a WIPI table (0x5b72c / 0x5b735) not located in libwawa.so.
			notPorted("영웅을 유닛 옆으로 이동");
		}

		@Override
		public int unitSpawn(int a, int x, int y, int dir) {
			int[] kinds = events.phaseHeader();
			if (a >= kinds.length) {
				return 4;
			}
			int uid = 0x100 + a;
			Monster old = Game.this.unit(uid);
			if (old != null) {
				monsters.remove(old);
			}
			int id = kinds[a];
			String name = monsterSprites.name(id);
			Monster m = new Monster(x, y, name != null ? name : "#" + id, 1, 0, id, false, x, y, x, y);
			m.uid = uid;
			m.npc = true;
			changeDir(m, (byte) so.u8(UNIT_ANI + id * 20) == -99 ? 1 : 0, dir == 8 ? rng.nextInt(8) : dir);
			monsters.add(m);
			return 4;
		}

		@Override
		public int unit(int hi, int uid, int x, int y, int dir) {
			Monster m = Game.this.unit(uid);
			if (m == null) {
				return -1;
			}
			switch (hi) {
				case 1 -> monsters.remove(m);
				case 2 -> {
					m.x = x;
					m.y = y;
					m.goX = -1;
					changeDir(m, 1, dir);
				}
				case 3 -> {
					if (events.mode == 2) {
						m.x = x;
						m.y = y;
						changeDir(m, 1, dir & 0x7f);
					} else {
						m.goX = x;
						m.goY = y;
						m.goDir = dir & 0x7f;
						m.goWait = 1;
						m.goHold = (dir & 0x80) == 0;
					}
				}
				// +0x42 is the unit's fade step, like the hero's: 43 sets -3 (fade in, as a new unit
				// does), 53 sets 1 (fade out). t01 sends units off the map edge and then 53s them.
				case 4 -> {
					m.hidden = false;
					return 4;
				}
				case 5 -> {
					m.hidden = true;
					return 4;
				}
				case 6 -> {
					// x and y carry 63's dir and act; only the acts t01/t02 use are mapped.
					int act = y;
					int state = switch (act) {
						case 0 -> 0;
						case 1 -> 6;
						case 2 -> 8;
						case 3 -> 5;
						case 4, 5 -> 9;
						case 6 -> 0xd;
						case 0x23 -> 3;
						default -> -1;
					};
					if (state < 0) {
						notPorted("유닛 행동 " + act);
						return 0;
					}
					changeDir(m, state, x);
					int[] entry = monsterSprites.animEntry(m.variant, m.ani);
					// The lock lasts until the animation ends; the port plays two frames per step.
					m.lock = entry == null ? 1 : Math.max(1, entry[2]) * 2;
					return act == 3 ? -1 : 0;
				}
				default -> {
				}
			}
			return 0;
		}

		@Override
		public void mapObject(int obj, boolean visible) {
			notPorted("맵 오브젝트 " + obj);
		}

		@Override
		public boolean moving() {
			if (heroGoX >= 0) {
				return true;
			}
			for (Monster m : monsters) {
				if (m.goX >= 0 && m.goHold || m.lock > 0) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void arriveAll() {
			if (heroGoX >= 0) {
				heroPlace(heroGoX, heroGoY, heroGoDir);
			}
			for (Monster m : monsters) {
				if (m.goX >= 0) {
					m.x = m.goX;
					m.y = m.goY;
					m.goX = -1;
					changeDir(m, 1, m.goDir);
				}
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
			events.loadMap(EventScript.map(assetsRoot, mapCode));
		}

		@Override
		public int fade(int k, int mode) {
			switch (k) {
				case 0, 3 -> {
					fadeWhite = k == 3;
					if (mode == 0) {
						fadeStep = 1;
						return 8;
					}
					fade = 8;
				}
				case 1, 4 -> {
					if (mode == 0) {
						fadeStep = -1;
						return fade == 8 ? 8 : 6;
					}
					fade = 0;
				}
				case 2, 5 -> {
					fadeWhite = k == 5;
					fade = 8;
					fadeStep = 0;
				}
				default -> notPorted("화면 효과 " + k);
			}
			return 0;
		}

		@Override
		public void shake(int n) {
			shake = n;
		}

		@Override
		public void emote(int uid, int kind) {
			Monster m = uid == EventRunner.HERO ? null : Game.this.unit(uid);
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
				emotes.add(new Emote(m, kind));
			}
		}

		@Override
		public void camera(int op, int x, int y, int target) {
			switch (op) {
				case 0 -> {
					camCentre = null;
					camUnit = null;
				}
				// 18 jumps and 28 scrolls to the cell corner (x * 16, y * 16); the port's camera is
				// cell-aligned, so both centre on cell (x, y).
				case 0x11, 0x12 -> {
					camCentre = new int[] { x, y };
					camUnit = null;
				}
				case 0x13, 0x14, 0x15, 0x16 -> {
					camCentre = null;
					camUnit = target == EventRunner.HERO ? null : Game.this.unit(target);
				}
				case 0x10 -> {
					// 08 stops following; the view stays where it is.
					int[] cam = Game.this.camera();
					camCentre = new int[] { cam[0] + VIEW_W / 2, cam[1] + VIEW_H / 2 };
					camUnit = null;
				}
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
			Game.this.save();
		}

		@Override
		public int heroInt(int i) {
			return switch (i) {
				case 2 -> hero.level;
				case 3 -> hero.hp;
				default -> heroInts[i & 0xff];
			};
		}

		@Override
		public void setHeroInt(int i, int v) {
			switch (i) {
				case 2 -> hero.level = v;
				case 3 -> hero.hp = Math.min(hero.maxHp, v);
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
	private static Color groundColour(int cell) {
		if (cell == 0xff) {
			return new Color(18, 14, 10);
		}
		int v = (cell & 0x3f) * 4;
		return new Color(46 + v / 2, 40 + v / 3, 30 + v / 4);
	}

	private void bar(Graphics2D g, int x, int y, float ratio, Color color) {
		int w = CELL * SCALE - 8;
		g.setColor(new Color(0, 0, 0, 160));
		g.fillRect(x, y, w, 4);
		g.setColor(color);
		g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, ratio))), 4);
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
			try (OutputStream os = Files.newOutputStream(saveFile(slotIndex))) {
				p.store(os, "wawa save slot " + slotIndex);
			}
			log = "저장 완료: " + saveFile(slotIndex);
		} catch (IOException e) {
			log = "저장 실패: " + e;
		}
	}

	/** LoadSlot: rebuilds the hero and the map from the current slot's file. */
	private void load() {
		Path file = saveFile(slotIndex);
		if (!Files.exists(file)) {
			log = "저장 파일이 없다.";
			return;
		}
		try {
			Properties p = new Properties();
			try (InputStream is = Files.newInputStream(file)) {
				p.load(is);
			}
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

	/** One properties file per slot, the port's stand-in for slot%d.dat. */
	private static Path saveFile(int slot) {
		return Path.of(System.getProperty("wawa.save", "wawa-slot") + slot + ".properties");
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
			v.sys[i] = Integer.parseUnsignedInt(hex.substring(at, at + 8), 16);
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
		/** A script walk (33): target cell, arrival direction, frames to the next step, waited on. */
		int goX = -1;
		int goY;
		int goDir;
		int goWait;
		boolean goHold;
		boolean hidden;
		/** Frames an action (63) holds the phase, as the 0x8000 lock does. */
		int lock;

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
		Color color() {
			return Color.getHSBColor((variant * 47 % 360) / 360f, 0.55f, 0.85f);
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length > 0) {
			System.setProperty("wawa.assets", args[0]);
		}
		if (args.length > 1 && "--selftest".equals(args[1])) {
			// On the event thread, so the 62 ms game timer cannot tick while the script runs.
			javax.swing.SwingUtilities.invokeAndWait(() -> {
				try {
					new Game().selfTest(new java.io.File("out/selftest.png"));
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
			System.exit(0);
		}
		Game g = new Game();
		g.setLocationRelativeTo(null);
		g.setVisible(true);
	}

	/**
	 * Drives scripted input through the slot screen and a short fight, writing a frame of each
	 * stage next to {@code shot}, so behaviour is verifiable without GUI automation.
	 */
	void selfTest(java.io.File shot) throws IOException {
		java.io.File dir = shot.getAbsoluteFile().getParentFile();
		writeFrame(new java.io.File(dir, "logo.png"));
		for (int i = 0; i < 80; i++) {
			opening.render();
		}
		writeFrame(new java.io.File(dir, "title.png"));
		onKey(KeyEvent.VK_ENTER);
		onKey(KeyEvent.VK_DOWN);
		writeFrame(new java.io.File(dir, "title-menu.png"));
		onKey(KeyEvent.VK_UP);
		onKey(KeyEvent.VK_ENTER);
		writeFrame(new java.io.File(dir, "slots.png"));
		onKey(KeyEvent.VK_ENTER);
		onKey(KeyEvent.VK_ENTER);
		for (char c : "와와".toCharArray()) {
			slotScreen.typed(c);
		}
		onKey(KeyEvent.VK_ENTER);
		onKey(KeyEvent.VK_RIGHT);
		writeFrame(new java.io.File(dir, "newgame.png"));
		onKey(KeyEvent.VK_ENTER);
		// The new game's opening phase: tick it like the 62 ms timer, press OK every 12 frames to
		// move the dialogue on, and keep a few frames of the scene.
		int[] shots = { 30, 200, 420, 700, 1000, 1400 };
		int tick = 0;
		int shotAt = 0;
		while (events.active() && tick < 8000) {
			gameTick();
			tick++;
			if (tick % 12 == 0) {
				onKey(KeyEvent.VK_ENTER);
			}
			if (shotAt < shots.length && tick == shots[shotAt]) {
				writeFrame(new java.io.File(dir, "intro" + shotAt++ + ".png"));
			}
		}
		writeFrame(new java.io.File(dir, "intro-end.png"));
		System.out.println("intro frames=" + tick + " running=" + events.active() + " map=" + mapCode
				+ " hero=" + heroX + "," + heroY + " var1=" + events.vars.get(1) + " notPorted=" + notPortedSeen);
		loadMap(MAP_CODES[0]);
		int[] keys = {
			KeyEvent.VK_RIGHT, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_DOWN,
			KeyEvent.VK_RIGHT, KeyEvent.VK_RIGHT, KeyEvent.VK_RIGHT
		};
		for (int k : keys) {
			onKey(k);
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
		writeFrame(shot);
		System.out.println("slot=" + slotIndex + " name=" + hero.name + " class=" + hero.heroClass
				+ " map=" + mapCode + " hero=" + heroX + "," + heroY + " lv=" + hero.level + " exp=" + hero.exp
				+ " hp=" + hero.hp + "/" + hero.maxHp + " atk=" + hero.power() + " gold=" + gold
				+ " monsters=" + monsters.size() + " log=" + log);
		Game again = new Game();
		again.writeFrame(new java.io.File(dir, "slots-after.png"));
	}

	private void writeFrame(java.io.File file) throws IOException {
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
				VIEW_W * CELL + 16, MAP_Y + VIEW_H * CELL + 46, java.awt.image.BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		draw(g2);
		g2.dispose();
		Files.write(file.toPath(), toPng(img));
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

	private static byte[] toPng(java.awt.image.BufferedImage img) throws IOException {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}
}

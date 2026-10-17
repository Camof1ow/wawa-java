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
 * with grid movement, monsters, real-time melee and saved slots.
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
	private MapData map;
	/** LoadGameBlock's collision grid for {@link #map}. */
	private BlockGrid block;
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
	/**
	 * The exit MoveGameMap held back for GMenu 0x74, the boss/regen warning, while it is open;
	 * null otherwise. The field stands still meanwhile, as CalcGMenu runs in CalcGamePlay's place.
	 */
	private AreaInfo.Portal bossGate;
	/** GMenu 0x74's EUC-KR strings: title, text, and the Enter / Cancel button labels. */
	private static final int GATE_TITLE = 0xd22e8;
	private static final int GATE_TEXT = 0xd22f8;
	private static final int GATE_ENTER = 0xd2b10;
	private static final int GATE_CANCEL = 0xd2bf0;
	/** The menu's ITEM tab (GOrder, opened with CLEAR or I): the field stands still while it is open. */
	private ItemMenu itemMenu;
	private boolean menuOpen;
	/** The open GOrder screen under menuOpen: a menu tab (gorder+4 = menuTab) or the quest board. */
	private OrderScreen menuScreen;
	private int menuTab;
	private QuestMenu questMenu;
	private QuestMenu questBoard;
	private EmptyTab emptyTab;
	/** GOrder 0xd/0xe (event lo 2): the field stands still while the shop is open. */
	private ShopMenu shop;
	private boolean shopOpen;
	/** gorder+0x48: the NPC CheckHeroOnNPC last talked to, the shop's keeper. */
	private Monster shopKeeper;
	/** A GMenu over the menu: a confirmation runs menuYes on "O.확인"; a message has none. */
	private boolean menuGMenu;
	private Runnable menuYes;
	/** DrawGMenuButtonAll's 0x180000 and 0x900 labels: "O.확인" and "X.취소". */
	private static final int OK_LABEL = 0xd2b20;
	private static final int CANCEL_LABEL = 0xd2c3c;
	/** The GOrder sheets, loaded the first time a GOrder screen opens. */
	private OrderScreen.Sheets orderSheets;
	private final OrderScreen.Host menuHost = new OrderScreen.Host() {
		@Override
		public void confirm(byte[] title, byte[] text, Runnable onYes) {
			talk.startWarning(title, text, so.cbytes(OK_LABEL), so.cbytes(CANCEL_LABEL));
			menuGMenu = true;
			menuYes = onYes;
		}

		@Override
		public void message(byte[] text) {
			talk.startNotice(text, 0, text.length);
			menuGMenu = true;
			menuYes = null;
		}

		@Override
		public void use(int id) {
			useItem(id);
		}

		@Override
		public void changed() {
			hero.recompute(rng);
		}

		@Override
		public void close() {
			menuOpen = false;
			shopOpen = false;
			// ExitGOrder: closing the menu ends 갈무리 early.
			if (heroAct == 2) {
				lootEnd();
			}
			// GOrderFuncFree with no phase running: the quests look again (an item sold or used).
			if (events.depth() == 0) {
				quests.analyze();
			}
		}

		@Override
		public void itemAdded(int id, int n) {
			quests.itemAdded(id, n);
		}

		@Override
		public void switchTab(int dir) {
			GameCore.this.switchTab(dir);
		}

		@Override
		public void notPorted(String what) {
			log = what + ": 아직 포팅되지 않았습니다";
		}
	};
	/** GMenu 0x67 / 0x3a5 / 900 over the quest list and the board, and a board quest taken. */
	private final QuestMenu.Popups questPopups = new QuestMenu.Popups() {
		@Override
		public void detail(Quests.Slot s) {
			talk.startQuest(quests.def(s.id1 - 1).title, null, quests.summary(s, true), TalkBox.CONFIRM_ONE);
			menuGMenu = true;
			menuYes = null;
		}

		@Override
		public void delete(int slot) {
			talk.startMessage(so.cbytes(so.u32(OrderScreen.MESSAGE + 4 * (0x3a5 - 900))), so.cbytes(GATE_CANCEL));
			menuGMenu = true;
			menuYes = () -> quests.delete(slot);
		}

		@Override
		public void full() {
			events.abortAll();
			talk.startMessage(so.cbytes(so.u32(OrderScreen.MESSAGE)), null);
			menuGMenu = true;
			menuYes = null;
		}

		@Override
		public void accepted(int id) {
			if (events.depth() > 0) {
				byte[] t = quests.titled(0xd4614, id);
				talk.headline(t, 0, t.length);
			}
		}
	};
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
	/**
	 * InitHeroAttack's pending attack (+0x47 = 5), and the swing: running (+0x1c), the step the
	 * next draw shows (+0x1e), frames step f0 and f1 still hold (+0x1f, +0x20), its
	 * GetHeroAttackFrame row and the weapon form (hero[4]).
	 */
	private boolean attackQueued;
	private boolean attackRunning;
	private int attackStep;
	private int holdF0;
	private int holdF1;
	private int[] attackFrames = new int[5];
	private int attackForm;
	/** +0x21 the last attack act, +0x91 swings in the chain, +0x93 its length (+0x94 = 3), +0x95 the link window. */
	private int lastAttackAct;
	private int chainHits;
	private int chainLen = CHAIN_BASE;
	private static final int CHAIN_BASE = 3;
	private int linkWindow;
	/** +0x96 / +0x97 the combo display frames and count; wawamain+0x5a68 / +0x5a6c what is shown and for how long. */
	private int comboShow;
	private int comboCount;
	private int comboShown;
	private int comboAge;
	/** AddDamage's queue (wawamain+0x55c4): {world x, y, kind (1 crit), frame, value (MAX_VALUE: MISS)}. */
	private final List<int[]> damages = new ArrayList<>();
	/** h[0x23]: the hero takes no hits (acts 3, 0x1d and 4), set at the start of each tick. */
	private boolean heroImmune;
	/** A direction pressed since the last flinch: only a fresh press cancels act 3 (h[0x47] apart from the held key). */
	private boolean flinchBreak;
	/** wawamain's target monster and its HP bar's frames (36 after each landed hit). */
	private Monster target;
	private int targetTimer;
	/**
	 * 갈무리 (act 2): the corpse (hero+0x9a), cycles left and in all (+0x59a0, +0x59a8), the
	 * charge (+0x45, 12 frames a cycle), the corpse's decay before the lock (+0x599c), the rune
	 * allowance (+0x59b0), the facing to give back (+6) and the gauge's start frame (+0x80, -1 off).
	 */
	private Monster loot;
	private int lootLeft;
	private int lootCycles;
	private int lootCharge;
	private int lootDecay;
	private int runeLeft;
	private int lootFacing;
	private int gaugeStart = -1;
	/** wawamain+0x5998: frames the attack key has been held with the living near, 0 off. */
	private int holdTimer;
	/** The attack key is down: its autorepeat is not a press. */
	private boolean attackDown;
	/** wawamain+0x5cf4: the corpse the hero stands on this tick; +0x5d00 the OK bubble's tracker and the step it shows (-1 none). */
	private Monster lootable;
	private int okTrack;
	private int okStage = -1;
	/** wawamain+0xfc: frames played with no phase running, which corpse decay counts. */
	private int playFrames;
	private Fight fight;
	/** The quest slots and state bytes (hero+0x398, quest+0x10) over dat/quesn. */
	private Quests quests;
	private Clix clix;
	/** DrawAndDestroyDamage's per-frame tables: MISS y and mode, number mode, crit and normal number y. */
	private static final int MISS_Y = 0xdb2a0;
	private static final int MISS_MODE = 0xdb2a8;
	private static final int NUMBER_MODE = 0xdb2b0;
	private static final int CRIT_Y = 0xdb2bc;
	private static final int NORMAL_Y = 0xdb2c8;
	/** map+0x2d &amp; 0x80: a boss came with the map, so fighting is allowed even where map+0x2d is 1. */
	private boolean bossLoaded;
	/**
	 * The way the current step moves when that is not the facing: NewCollPath's sidestep walks the
	 * hero sideways around terrain without turning it. -1 means it moves the way it faces.
	 */
	private int heroMoveDir = -1;
	/** KeyPressGamePlay's +0x47: the direction held (0 up, 1 down, 2 left, 3 right), -1 none. */
	private int heldDir = -1;
	/** A clicked cell the hero walks to while nothing is held (this port's touch pad), -1 none. */
	private int clickX = -1;
	private int clickY;
	/** CalcHeroRun is driving act 1, as opposed to a phase or an action. */
	private boolean runWalking;
	/** InitHeroState's +0x48 = (4 - 0) * 2 px a frame; mounts (12) and the cap (14) are not ported. */
	private static final int HERO_SPEED = 8;
	/** A scripted hero walk (83, e3): target cell and the arrival direction fixed at command time. */
	private int heroGoX = -1;
	private int heroGoY;
	private int heroGoDir;
	/** AddImoticon entries: target (null = hero), kind, frames drawn. */
	private final List<Emote> emotes = new ArrayList<>();
	/** InitUnit's draw list (wawamain+0x4e68): monsters and HERO_UNIT, in SortUnit's y order. */
	private final List<Object> unitOrder = new ArrayList<>();
	/** The hero's entry in unitOrder (the Hero object is replaced on a new game or a load). */
	private static final Object HERO_UNIT = new Object();
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
	/** 하급 포션..특급 포션: the HP potions the 2 key drinks, cheapest first. */
	private static final int POTION = 233;
	private static final int LAST_POTION = 238;
	/**
	 * The weapon a save from before saves recorded weapons holds: that port gave every hero item 297
	 * from the start. A new hero starts bare-handed (InitHero leaves +8..+0x10 at 0xff for every
	 * class); t02's script lends 297, takes it back, and t04's gives it for good.
	 */
	private static final int OLD_SAVE_WEAPON = 297;
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
		items = new Item(so);
		fight = new Fight(so);
		clix = new Clix(so);
		quests = new Quests(QuestBook.load(assets, false), so, new QuestLink());
		newHero(0, "");

		// The world behind the slot screen is not drawn until a slot is opened, but the rest of
		// the port expects a loaded map, so a01 stands loaded underneath.
		loadMap(MAP_CODES[0]);
		heroAnim = new HeroAnim(assets, so);
		talk = new TalkBox(assets, so);
		events = new EventRunner(new Stage(), talk);
		events.global = EventScript.global(assets);
		events.quest = quests.book.phases;
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
			if (bossGate == null && !menuOpen && !shopOpen) {
				gameTick();
			}
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

	/** InitHero for a class: bare-handed, whatever the class; the scripts hand out the first weapon. */
	private void newHero(int heroClass, String name) {
		// InitHero clears the whole record: no gold, an empty bag, InitHero's capacities.
		hero = new Hero(so, items, heroClass);
		hero.name = name;
		heroDir = 3;
		heroInts = new int[0x100];
		hero.recompute(rng);
		hero.hp = hero.maxHp;
		quests.reset();
		quests.heroClass = heroClass;
	}

	/**
	 * A save's items: "gold", "caps", "equip" and "stacks" (Inventory's texts). A save from before
	 * the inventory has "weapon" instead ("id,value", empty when bare-handed, missing in saves older
	 * still, which held OLD_SAVE_WEAPON) and a "potions" count: they come back as that weapon worn
	 * and as many 하급 포션.
	 */
	private void readInventory(Properties p, Inventory inv) {
		inv.gold = Integer.parseInt(p.getProperty("gold", "0"));
		String eq = p.getProperty("equip");
		if (eq != null) {
			inv.readCaps(p.getProperty("caps", ""));
			inv.readEquip(eq);
			inv.readStacks(p.getProperty("stacks", ""));
			return;
		}
		String w = p.getProperty("weapon");
		Item.Equip weapon = null;
		if (w == null) {
			weapon = items.create(OLD_SAVE_WEAPON, 0, new Random(0));
		} else {
			String[] f = w.split(",");
			if (f.length == 2) {
				int id = Integer.parseInt(f[0]);
				weapon = new Item.Equip(id, items.type(id), Integer.parseInt(f[1]), items.spread(id), items.level(id),
						items.durability(id) * 10, 0, 0);
			}
		}
		if (weapon != null) {
			weapon.slot = 1;
			inv.equip[0] = weapon;
		}
		int potions = Integer.parseInt(p.getProperty("potions", "0"));
		if (potions > 0) {
			inv.add(POTION, potions, 0, true, rng);
		}
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
				int heroClass = Integer.parseInt(p.getProperty("class", "0"));
				Inventory inv = new Inventory(items);
				readInventory(p, inv);
				out[i] = new SlotScreen.Summary(p.getProperty("name", ""), heroClass,
						Integer.parseInt(p.getProperty("level", "1")), Integer.parseInt(p.getProperty("mode", "0")),
						Integer.parseInt(p.getProperty("playTime", "0")),
						HeroAnim.wearing(items, inv.worn(), heroClass, 1, HeroAnim.ACT_STAND));
			} catch (NumberFormatException e) {
				out[i] = null;
			}
		}
		return out;
	}

	/** Loads one of the shipped maps by its 3-character code, as LoadGameMap does. */
	private void loadMap(String code) throws IOException {
		mapCode = code;
		target = null;
		if (heroAct == 2) {
			stopLoot();
		}
		lootable = null;
		okStage = -1;
		holdTimer = 0;
		String letter = code.substring(0, 1);
		map = MapData.load(assets, "assets/eng/map/" + letter + ".png", code);
		block = new BlockGrid(map, so);
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
		// A new map starts with no swing, chain, combo or damage numbers (they are in this map's px).
		attackQueued = false;
		attackRunning = false;
		chainHits = 0;
		linkWindow = 0;
		comboShow = 0;
		comboCount = 0;
		comboShown = 0;
		damages.clear();
		spawnMonsters();
		// RetrieveRegenMons: loading a boss (a size-3 monster) sets map+0x2d |= 0x80, so a boss map
		// stops counting as a town where the hero cannot fight.
		bossLoaded = false;
		for (Monster m : monsters) {
			if (!m.npc && m.variant < 0xa6 && fight.size(m.variant) == 3) {
				bossLoaded = true;
			}
		}
		camMode = -1;
		camUnit = null;
		camScrolling = false;
		// FuncMoveMap: hero +0x1d = 0.
		heroFade = 0;
		heroGoX = -1;
		changeHeroAct(HeroAnim.ACT_STAND);
		heroActRun = false;
		heroPx = 0;
		heroMoveDir = -1;
		runWalking = false;
		clickX = -1;
		emotes.clear();
		// LoadGameMap is followed by InitUnit: the draw list starts over from the new map's units.
		unitOrder.clear();
		log = area.name + " (" + map.width + "x" + map.height + ") 진입.";
	}

	/** InitSelectSlot: script variables start from zero for the slot being opened. */
	private void newEvents() {
		EventScript.Table global = events.global;
		events = new EventRunner(new Stage(), talk);
		events.global = global;
		events.quest = quests.book.phases;
	}

	/** LoadGameMap with a non-zero mode, then ExitShiftMap's SearchPhase; with none started, AnalysisQuest. */
	private void enterEvents() {
		events.loadMap(EventScript.map(assets, mapCode));
		if (!events.searchPhase()) {
			quests.analyze();
		}
	}

	/** GMenu 100 (a quest offer) or a 900+ message over the field, 0 when none; game and phase wait while one is up. */
	private int gmenu;
	/** GMenu 100's quest (gmenu+0x2c). */
	private int gmenuQuest;

	/**
	 * InitGMenu 100: the quest's title, the summary of a temporary InitQuest (which also says
	 * the time left on timed quests held), "퀘스트가 갱신되었습니다." with "닫기" for a main quest,
	 * "퀘스트를 수락합니다." with 예 / 아니오 otherwise.
	 */
	private void questOffer(int id) {
		QuestBook.Def def = quests.def(id);
		Quests.Slot tmp = new Quests.Slot();
		quests.init(tmp, id, true);
		boolean main = def.kind() == 0;
		talk.startQuest(def.title, so.cbytes(main ? 0xd21c8 : 0xd219c), quests.summary(tmp, true),
				main ? TalkBox.CLOSE : TalkBox.YES_NO);
		gmenu = 100;
		gmenuQuest = id;
	}

	/**
	 * ResultGMenu for the field GMenus. 100 (ResultGMenuQuestAccept): a main quest is taken
	 * whatever the key; a declined one, or one with no slot left (then GMenu 900), closes the
	 * running phases with result 3; an accepted one shows "퀘스트 수락" while a phase runs.
	 */
	private void gmenuResult(int r) {
		int g = gmenu;
		gmenu = 0;
		talk.clear();
		if (g != 100) {
			return;
		}
		int id = gmenuQuest;
		if (quests.def(id).kind() != 0 && r == 0) {
			events.abortAll();
			return;
		}
		if (quests.accept(id) < 0) {
			events.abortAll();
			openMessage(900);
			return;
		}
		if (events.depth() > 0) {
			talk.headlineNow(quests.titled(0xd2db0, id));
		}
	}

	/** GMenuFuncUp of a style-0 message from the table at 0xe2684 (900 first). */
	private void openMessage(int id) {
		talk.startMessage(so.cbytes(so.u32(OrderScreen.MESSAGE + 4 * (id - 900))), null);
		gmenu = id;
	}

	/** One CalcGamePlay tick in the original order; none while a field GMenu runs as the MainFunc instead. */
	private void gameTick() {
		if (gmenu != 0) {
			return;
		}
		frames++;
		lootable = null;
		drawnFrame();
		tickEffects();
		calcCamera();
		if (events.active()) {
			// SetPhaseHeroStop clears +0x47: a phase stops the player's own walk.
			stopRun();
			// CalcGamePlay keeps moving the hero and units while a phase runs; monsters stand still
			// (SetPhaseHeroStop sets their 0x2000 bit) and the player's input goes to the phase.
			scriptMoves();
			events.calcPhase();
			events.frame();
		} else {
			// No phase: the freeze lifts. Whatever act a phase left goes back to standing, unless
			// CalcHeroRun is walking the hero.
			frozen = false;
			heroGoX = -1;
			heroActRun = false;
			if (!runWalking && !attackRunning && heroAct != 3 && heroAct != 2) {
				heroPx = 0;
				if (heroAct != HeroAnim.ACT_STAND) {
					changeHeroAct(HeroAnim.ACT_STAND);
				}
			}
			events.frame();
			playFrames++;
			if (playFrames == quests.nextAlert) {
				quests.checkTime(false);
			}
			initHeroState();
			calcHeroAttack();
			monsterTick();
			calcHeroRun();
			postValues();
			attackHero();
			// The attack key held 9 frames with the living near: CheckHeroOnLoot from a stand,
			// a walk or a swing.
			if (holdTimer != 0 && ++holdTimer > 8 && (heroAct < 2 || heroAct > 8 && heroAct < 0x11)) {
				checkHeroOnLoot();
				holdTimer = 0;
			}
			okStage = lootable == null ? -1 : okFrame();
		}
		drawSetup();
	}

	/** InitHeroState: the original recomputes the hero's derived stats every tick. */
	private void initHeroState() {
		hero.recompute(rng);
	}

	/**
	 * CalcHeroRun: while a direction is held (+0x47) the hero walks, act 1, HERO_SPEED px a frame
	 * through MoveHeroPath, and with nothing held a walk goes back to standing. This port keeps the
	 * hero on cells, so a step that has started finishes at the next cell centre: letting go or
	 * turning mid-step takes at most one frame longer than the original's free pixel position.
	 */
	private void calcHeroRun() {
		if (attackRunning || heroAct == 2 || heroAct == 3 && !flinchBreak && heroPx == 0) {
			// Direction input waits while a swing plays (KeyPressGamePlay drops it); 갈무리 holds
			// the hero, and a flinch keeps it still until it ends, unless a direction is pressed
			// afresh during it.
			return;
		}
		if (heroPx == 0) {
			int dir = heldDir >= 0 ? heldDir : clickDir();
			if (dir < 0) {
				if (runWalking) {
					runWalking = false;
					changeHeroAct(HeroAnim.ACT_STAND);
				}
			} else {
				heroDir = dir;
				if (!runWalking || heroAct != HeroAnim.ACT_WALK) {
					runWalking = true;
					changeHeroAct(HeroAnim.ACT_WALK);
				}
				int nx = heroX + DIR_STEP[dir][0];
				int ny = heroY + DIR_STEP[dir][1];
				heroMoveDir = -1;
				if (map.inBounds(nx, ny) && !block.terrain(nx, ny) && monsterAt(nx, ny) == null) {
					heroPx = HERO_SPEED;
				} else {
					// CollHeroPath hands a terrain cell (not a monster or the map edge) to NewCollPath.
					int side = map.inBounds(nx, ny) && block.terrain(nx, ny) ? sidestep(dir) : -1;
					if (side >= 0) {
						heroMoveDir = side;
						heroPx = HERO_SPEED;
					} else {
						clickX = -1;
						// Blocked: the hero walks in place, and MoveHeroPath still checks the cell it
						// stands on with the new facing, which is how a portal on the map edge is taken.
						takeExit();
					}
				}
			}
		} else {
			heroPx += HERO_SPEED;
			if (heroPx >= TILE) {
				int moved = moveDir();
				heroPx = 0;
				heroMoveDir = -1;
				heroX += DIR_STEP[moved][0];
				heroY += DIR_STEP[moved][1];
				if (heroX == clickX && heroY == clickY) {
					clickX = -1;
				}
				takeExit();
			}
		}
	}

	/** The direction the hero's current step moves in. */
	private int moveDir() {
		return heroMoveDir >= 0 ? heroMoveDir : heroDir;
	}

	/** For each way of walking, the two ways NewCollPath can step aside, the one it takes on a tie first. */
	private static final int[][] ASIDE = { { 2, 3 }, { 3, 2 }, { 0, 1 }, { 1, 0 } };

	/**
	 * NewCollPath for a hero standing on a cell centre: walking into terrain, it steps aside toward
	 * the nearer side whose cells are free both beside the hero and beside the blocking cell, one
	 * cell (16 px) or two (32 px) away. On a tie walking up goes left, down right, left up and right
	 * down. Two cells away the hero steps one cell now and the next step finds the gap one away.
	 * Returns that side, or -1 when neither opens within two cells.
	 */
	private int sidestep(int dir) {
		int best = -1;
		int bestCells = 3;
		for (int side : ASIDE[dir]) {
			int cells = openAside(dir, side);
			if (cells < bestCells) {
				best = side;
				bestCells = cells;
			}
		}
		return best;
	}

	/** 1 or 2 when the way past the blocking cell opens that many cells toward {@code side}, else 3. */
	private int openAside(int dir, int side) {
		int sx = DIR_STEP[side][0];
		int sy = DIR_STEP[side][1];
		int fx = DIR_STEP[dir][0];
		int fy = DIR_STEP[dir][1];
		if (!free(heroX + sx, heroY + sy)) {
			return 3;
		}
		if (free(heroX + sx + fx, heroY + sy + fy)) {
			return 1;
		}
		return free(heroX + 2 * sx, heroY + 2 * sy) && free(heroX + 2 * sx + fx, heroY + 2 * sy + fy) ? 2 : 3;
	}

	/** NewCollPath's mask 0x23: no terrain and nothing standing there (this port's monsters stand for 0x20). */
	private boolean free(int x, int y) {
		return map.inBounds(x, y) && !block.terrain(x, y) && monsterAt(x, y) == null;
	}

	/** The way to the clicked cell, horizontal first; -1 once there or with no click. */
	private int clickDir() {
		if (clickX < 0) {
			return -1;
		}
		int dx = Integer.compare(clickX, heroX);
		int dy = Integer.compare(clickY, heroY);
		if (dx == 0 && dy == 0) {
			clickX = -1;
			return -1;
		}
		return dx != 0 ? (dx < 0 ? 2 : 3) : (dy < 0 ? 0 : 1);
	}

	/** A phase took the hero: the held direction and the click target are dropped. */
	private void stopRun() {
		heldDir = -1;
		clickX = -1;
		if (runWalking) {
			runWalking = false;
			heroPx = 0;
			heroMoveDir = -1;
			changeHeroAct(HeroAnim.ACT_STAND);
		}
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
			// InitMonsLimit: the patrol window is the tiles within 48 px of the spawn, kept on the map.
			int px = r.cellX * TILE + TILE / 2;
			int py = r.cellY * TILE + TILE / 2;
			Monster m = new Monster(r.cellX, r.cellY, name != null ? name : "몬스터#" + r.monsterId,
					ab != null ? ab.hp() : hpTier[tier], ab != null ? ab.attack() : atkTier[tier], r.monsterId,
					Math.max(0, px - 48) >> 4, Math.max(0, py - 48) >> 4,
					Math.min(map.width * TILE - 1, px + 48) >> 4, Math.min(map.height * TILE - 1, py + 48) >> 4);
			m.level = level;
			m.uid = i;
			// InitMonsNamed: a named unit (record flag 8) is not counted by kind kill goals.
			m.named = (r.flags & 8) != 0;
			int dir = r.direction == 8 ? rng.nextInt(8) : r.direction;
			if (npc) {
				m.npc = true;
				changeDir(m, 1, dir);
			} else {
				// InitMonsOne: hidden (state 0) when the id has that animation, else standing; the
				// animation starts at a step that differs between records.
				m.chaseLen = fight.chaseLen(r.monsterId);
				changeDir(m, fight.hides(r.monsterId) ? 0 : 1, dir);
				int[] e = monsterSprites == null || m.ani < 0 ? null : monsterSprites.animEntry(m.variant, m.ani);
				if (e != null && e[2] > 0) {
					m.step = i % e[2];
				}
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
	 * CalcMons: per monster, the pending push (+0x4b); then, while it is seen, the HP thirds that
	 * arm a flee (StateCond 0x40), CalcMonsAct and CalcMonsRun. A freed corpse leaves the list.
	 */
	private void monsterTick() {
		for (Monster m : monsters) {
			if (m.npc) {
				continue;
			}
			if (m.pushKind != 0) {
				moveMonster(m, m.pushDir, fight.pushed(fight.size(m.variant), attackForm, m.pushKind == 8), false);
				m.pushKind = 0;
			}
			if (m.fade >= 1) {
				continue;
			}
			int third = Math.max(m.hp, 0) * 3 / (m.maxHp + 1);
			if (third < m.hpThird) {
				if ((fight.cond(m.variant) & 0x40) != 0 && third == 0 && m.flee == 0 && m.hp > 0) {
					m.flee = -1;
				}
				m.hpThird = third;
			}
			calcMonsAct(m);
			calcMonsRun(m);
		}
		// FreeMonsOne: a corpse is gone once its fade-out has run.
		for (Monster m : monsters) {
			if (m.aniState == 9 && m.fade >= 4 && m == target) {
				target = null;
			}
		}
		monsters.removeIf(m -> m.aniState == 9 && m.fade >= 4);
	}

	/**
	 * CalcMonsAct for a normal monster: sight first (states 0, 1, 3, 0xb), then per state. "Cycle
	 * done" is step 0 with nothing held, which is where the stand, walk, alert, attack and flinch
	 * animations hand over.
	 */
	private void calcMonsAct(Monster m) {
		int st = m.aniState;
		boolean cyc = m.step == 0 && m.held == 0;
		if (m.variant <= 0xa5 && (st == 0 || st == 1 || st == 3 || st == 0xb) && hero.hp > 0 && heroAct <= 0x25
				&& look(m)) {
			return;
		}
		if (st == 5 && cyc) {
			chase(m);
		} else if (st == 4) {
			if (m.trig && !attackDecide(m)) {
				chase(m);
			}
		} else if (st == 2 || st == 0x11) {
			if (m.flee < 0 && m.hp > 0) {
				if (m.flee == -1 && rng.nextInt(100) > 49) {
					m.flee = 1;
				} else {
					m.flee = 1;
					changeDir(m, 10, detailDir(m, heroWorldX(), heroWorldY()) ^ 1);
				}
			} else if (st == 0x11 && fight.kind(m.variant) == 0) {
				// A boar waits for its post-attack cycle to end, with no roll.
				if (cyc && !attackDecide(m)) {
					chase(m);
				}
			} else if (rng.nextInt(100) < 50 && !attackDecide(m)) {
				chase(m);
			}
		} else if (st == 0xb) {
			if (m.trig) {
				actPoint(m);
			}
		} else if (st == 0xf) {
			fling(m);
		} else if (st == 9) {
			corpse(m);
		} else if (st != 0 && cyc) {
			if (st == 6 || st == 7) {
				changeDir(m, (fight.bullet(m.variant) == 2 || fight.bullet(m.variant) == 3) && ++m.cnt < 3 ? 6 : 0x11, m.dir);
			} else if (st == 8) {
				if (m.hp < 1) {
					initMonsDown(m);
				} else {
					changeDir(m, 2, m.dir);
				}
			} else if (st == 1 || st == 3) {
				actMove(m);
			}
		}
	}

	/**
	 * CalcMonsActLook: the hero inside the front or back sight rectangle (g_arcMonsLook_E, rotated
	 * for a diagonal facing) and reachable within the chase length turns the monster toward it;
	 * with StateCond 0x10 an ally of its kind already fighting does too.
	 */
	private boolean look(Monster m) {
		int mx = monsterPx(m);
		int my = monsterPy(m);
		int hx = heroWorldX();
		int hy = heroWorldY();
		int[] l = fight.look(m.variant);
		if (inSight(m.dir, l, hx - mx, hy - my)) {
			int r = routing(m, hx >> 4, hy >> 4, m.chaseLen, false);
			if (r >= 0) {
				lookDir(m, r);
				return true;
			}
		} else if ((fight.cond(m.variant) & 0x10) != 0) {
			for (Monster j : monsters) {
				if (j != m && !j.npc && fight.kind(j.variant) == fight.kind(m.variant) && j.aniState >= 4 && j.aniState <= 10
						&& inSight(m.dir, l, monsterPx(j) - mx, monsterPy(j) - my)) {
					lookDir(m, m.dir);
					return true;
				}
			}
		}
		return false;
	}

	/** CalcMonsActLook's rectangles for sight {a, b, c, d}: F in front, B behind; x0 &lt;= dx &lt; x1 and y0 &lt;= dy &lt; y1. */
	private static boolean inSight(int dir, int[] l, int dx, int dy) {
		if ((dir & 4) != 0) {
			// Diagonal facings rotate the offset; the second line uses the new dx, as decompiled.
			dx = (dx + dy) * 91 >> 7;
			dy = (dy - dx) * 91 >> 7;
		}
		int a = l[0];
		int b = l[1];
		int c = l[2];
		int d = l[3];
		return switch (dir & 3) {
			case 0 -> in(dx, dy, -c, c, -d, 0) || in(dx, dy, -a, a, 0, b);
			case 1 -> in(dx, dy, -c, c, 0, d) || in(dx, dy, -a, a, -b, 0);
			case 2 -> in(dx, dy, -d, 0, -c, c) || in(dx, dy, 0, b, -a, a);
			default -> in(dx, dy, 0, d, -c, c) || in(dx, dy, -b, 0, -a, a);
		};
	}

	private static boolean in(int x, int y, int x0, int x1, int y0, int y1) {
		return x0 <= x && x < x1 && y0 <= y && y < y1;
	}

	/**
	 * ChangeMonsLookDir: a standing, walking or homing monster notices the hero. One that hides
	 * (or stands without a hidden animation) goes alert (state 5, "!"); a risen ambusher goes
	 * straight to battle stance with the "!" bubble.
	 */
	private boolean lookDir(Monster m, int want) {
		int st = m.aniState;
		if (st != 0 && st != 1 && st != 3 && st != 0xb) {
			return false;
		}
		if (!fight.hides(m.variant) || st == 0) {
			adjustDir(m, 5, m.dir, want);
		} else {
			adjustDir(m, 2, m.dir, want);
			m.emote = 18;
		}
		return true;
	}

	/** CalcMonsActMove / CalcMonsActChase's flee: an armed flee (-1) runs half the time, a forced one always. */
	private boolean fleeNow(Monster m) {
		if (m.flee != -1 || rng.nextInt(100) < 50) {
			m.flee = 1;
			changeDir(m, 10, detailDir(m, heroWorldX(), heroWorldY()) ^ 1);
			return true;
		}
		m.flee = 1;
		return false;
	}

	/**
	 * CalcMonsActChase: route (GetMonsRouting, nearest fallback) to the hero's side at the attack
	 * rectangle's centre, the nearer side first; three failed routes walk straight at the hero,
	 * the fourth gives up with a "?".
	 */
	private void chase(Monster m) {
		if (m.flee < 0 && m.hp > 0 && fleeNow(m)) {
			return;
		}
		int tx = heroWorldX();
		int ty = heroWorldY();
		int mx = monsterPx(m);
		int[] r = fight.attackRect(m.variant, ty < monsterPy(m) ? 1 : 0);
		int cx = (r[0] + r[2]) >> 1;
		int g1 = tx + cx;
		int g2 = tx - cx;
		int gy = ty + ((r[1] + r[3]) >> 1);
		if (Math.abs(g2 - mx) < Math.abs(g1 - mx)) {
			int t = g1;
			g1 = g2;
			g2 = t;
		}
		if (hero.hp > 0) {
			m.trig = false;
			m.cross = 0;
			for (int g : new int[] { g1, g2 }) {
				int d = routing(m, g >> 4, gy >> 4, m.chaseLen, true);
				if (d >= 0) {
					adjustDir(m, 4, m.dir, d == 8 ? detailDir(m, tx, ty) : d);
					m.fails = 0;
					return;
				}
			}
			if (++m.fails < 4) {
				adjustDir(m, 4, m.dir, detailDir(m, tx, ty));
				return;
			}
			m.fails = 0;
		}
		m.emote = 18;
		changeDir(m, 1, m.dir);
	}

	/**
	 * CalcMonsActAttack: the hero inside the attack rectangle (g_arcMonsAttack_E; row 1 when the
	 * hero is above, mirrored when it is to the right) starts the attack (state 6), facing it
	 * sideways (boars: 8-way), armed (+0x3c) for one strike.
	 */
	private boolean attackDecide(Monster m) {
		if (hero.hp < 1 || heroAct > 0x25) {
			return false;
		}
		int mx = monsterPx(m);
		int my = monsterPy(m);
		int dx = heroWorldX() - mx;
		int dy = heroWorldY() - my;
		int row = heroWorldY() < my ? 1 : 0;
		int mir = row;
		int[] r = fight.attackRect(m.variant, row);
		if (mx <= heroWorldX()) {
			mir ^= 1;
		}
		int x0 = mir != 0 ? -r[2] : r[0];
		int x1 = mir != 0 ? -r[0] : r[2];
		if (!in(dx, dy, x0, x1, r[1], r[3])) {
			return false;
		}
		int d;
		int ax = Math.abs(dx);
		int ay = Math.abs(dy);
		if ((fight.cond(m.variant) & 6) == 0 || ay < ax >> 1) {
			d = row == mir ? 2 : 3;
		} else if (ax < ay >> 1) {
			d = row != 0 ? 0 : 1;
		} else {
			d = row == 0 ? (mir != 0 ? 7 : 5) : (mir != 0 ? 6 : 4);
		}
		// The row and mirror go in first: ChangeMonsDir keeps the row for sideways facings.
		m.row = row;
		m.mirror = mir != 0;
		changeDir(m, 6, d);
		m.trig = true;
		m.cnt = 0;
		m.fails = 0;
		return true;
	}

	/**
	 * CalcMonsActMove: out of the patrol window it heads home (state 0xb); otherwise it counts the
	 * cycles of its stand or walk and then switches, for RandRange(g_anMonsWaitCnt or MoveCnt)
	 * more cycles, in a ChangeMonsRandDir direction.
	 */
	private void actMove(Monster m) {
		if (m.flee < 0 && m.hp > 0 && fleeNow(m)) {
			return;
		}
		if (!inWindow(m)) {
			m.tgtX = (m.loX * 16 + (m.hiX + 1) * 16) >> 5;
			m.tgtY = (m.loY * 16 + (m.hiY + 1) * 16) >> 5;
			actPoint(m);
		} else if ((m.cnt & 0xf) < (m.cnt >> 4)) {
			m.cnt++;
		} else {
			int ns = m.aniState == 1 ? 3 : 1;
			int t = ns == 1 ? fight.waitCount(m.variant) : fight.moveCount(m.variant);
			m.cnt = randRange(t & 0xf, t >> 4) << 4;
			randDir(m, ns, m.dir);
		}
	}

	private boolean inWindow(Monster m) {
		int mx = monsterPx(m);
		int my = monsterPy(m);
		return m.loX * 16 <= mx && mx < (m.hiX + 1) * 16 && m.loY * 16 <= my && my < (m.hiY + 1) * 16;
	}

	/** CalcMonsActPoint: route to the target tile (no length limit), or stand if there is none. */
	private void actPoint(Monster m) {
		m.trig = false;
		m.cross = 0;
		int r = routing(m, m.tgtX, m.tgtY, 0xff, true);
		if (r < 0) {
			changeDir(m, 1, m.dir);
		} else {
			adjustDir(m, 0xb, m.dir, r == 8 ? detailDir(m, m.tgtX * 16 + 8, m.tgtY * 16 + 8) : r);
			m.cnt = 0;
		}
	}

	/** CalcMonsGetTrigger: crossing a tile centre on both axes (a still axis counts) re-arms the decision (+0x3c). */
	private void getTrigger(Monster m, int ox, int oy) {
		int x = monsterPx(m);
		int y = monsterPy(m);
		int yb = y == oy ? 1 : y > oy ? ((y + 8) >> 4 != (oy + 8) >> 4 ? 1 : 0) : ((y + 7) >> 4 != (oy + 7) >> 4 ? 1 : 0);
		int xb = x == ox ? 1 : x > ox ? ((x + 8) >> 4 != (ox + 8) >> 4 ? 1 : 0) : ((x + 7) >> 4 != (ox + 7) >> 4 ? 1 : 0);
		m.cross |= yb | xb << 1;
		if (m.cross == 3) {
			m.trig = true;
		}
	}

	/** ChangeMonsDetailDir: the 8-way direction from the monster toward a point. */
	private static int detailDir(Monster m, int tx, int ty) {
		return Fight.detailDir(tx - monsterPx(m), ty - monsterPy(m));
	}

	/** ChangeMonsRandDir: a random allowed column of g_anMonsTurnDir from the current direction. */
	private void randDir(Monster m, int st, int cur) {
		int mask = fight.mask(fight.kind(m.variant));
		int r = randRange(0, Integer.bitCount(mask & 0xff) - 1);
		int j = 0;
		for (int c = 0, k = 0; c < 8; c++) {
			if ((mask >> c & 1) != 0 && k++ == r) {
				j = c;
				break;
			}
		}
		changeDir(m, st, fight.turn(cur & 7, j));
	}

	/**
	 * ChangeMonsAdjustDir: the allowed turn from the current direction closest to the wanted one
	 * (with every direction allowed, the wanted one itself). A wanted 8 keeps the direction.
	 */
	private void adjustDir(Monster m, int st, int cur, int want) {
		int mask = fight.mask(fight.kind(m.variant));
		if (want > 7) {
			changeDir(m, st, cur);
			return;
		}
		if ((mask & 0xff) == 0xff) {
			changeDir(m, st, want);
			return;
		}
		int[] score = new int[8];
		for (int c = 0; c < 8; c++) {
			score[fight.turn(want, c) & 7] = 4 - ((c + 1) >> 1);
		}
		int best = 0;
		int pick = cur;
		for (int c = 0; c < 8; c++) {
			int d = fight.turn(cur & 7, c) & 7;
			if ((mask >> c & 1) != 0 && score[d] > best) {
				best = score[d];
				pick = d;
			}
		}
		changeDir(m, st, pick);
	}

	/** RandRange(a, b): a + Rand() % (b - a + 1), inclusive. */
	private int randRange(int a, int b) {
		return b < a ? a : a + rng.nextInt(b - a + 1);
	}

	/**
	 * GetMonsRouting: a breadth-first search over tiles from the monster to (tx, ty) through cells
	 * its body (g_aptMonsBlock_L) fits, straight steps before diagonals, at most 128 tiles queued.
	 * Returns the first step's direction, 8 when already there, -1 with no route or one longer
	 * than {@code limit} straight tiles (a diagonal counts 1.5). With {@code fallback} an
	 * unreachable target leads to the nearest reached tile, scanned outward from the target.
	 */
	private int routing(Monster m, int tx, int ty, int limit, boolean fallback) {
		int w = map.width;
		int h = map.height;
		int mx = monsterPx(m) >> 4;
		int my = monsterPy(m) >> 4;
		if (mx == tx && my == ty) {
			return 8;
		}
		int kind = fight.kind(m.variant);
		int bw = fight.bodyW(kind) - 1;
		int bh = fight.bodyH(kind) - 1;
		int mask = so.u8(MONS_BLOCK + m.variant) & 7;
		int[] g = new int[w * h];
		Arrays.fill(g, 0xffff);
		int start = my * w + mx;
		g[start] = 0;
		int[] q = new int[128];
		int head = 0;
		int tail = 0;
		int found = -1;
		boolean first = true;
		search:
		while (true) {
			int cur;
			int cost;
			if (first) {
				first = false;
				cur = start;
				cost = 8;
			} else {
				cur = q[head++ & 127];
				cost = (g[cur] & 0xfff8) + 8;
			}
			int x = cur % w;
			int y = cur / w;
			int left = x - ((bw + 1) >> 1);
			boolean up = row(x, y - 1 - bh, left, bw, mask);
			boolean down = row(x, y + 1, left, bw, mask);
			boolean lt = column(left - 1, y - bh, bh, mask);
			boolean rt = column(left + bw + 1, y - bh, bh, mask);
			boolean[] ok = { up, down, lt, rt,
					up && rt && free(x + 1 + (bw >> 1), y - 1 - bh, mask),
					down && lt && free(x - 1 - ((bw + 1) >> 1), y + 1, mask),
					up && lt && free(x - 1 - ((bw + 1) >> 1), y - 1 - bh, mask),
					down && rt && free(x + 1 + (bw >> 1), y + 1, mask) };
			for (int code = 0; code < 8; code++) {
				int nx = x + DIR_STEP[code][0];
				int ny = y + DIR_STEP[code][1];
				if (!ok[code] || nx < 0 || ny < 0 || nx >= w || ny >= h) {
					continue;
				}
				int n = ny * w + nx;
				if (cost < (g[n] & 0xfff8)) {
					q[tail++ & 127] = n;
					g[n] = cost | code;
					if (nx == tx && ny == ty) {
						found = n;
						break search;
					}
				}
			}
			if (tail <= head || head <= tail - 128) {
				break;
			}
		}
		int n = found;
		if (found < 0 || tail <= head || head <= tail - 128) {
			if (!fallback) {
				return -1;
			}
			n = nearest(g, w, h, mx, my, tx, ty);
			if (n < 0) {
				return -1;
			}
		}
		if (g[n] == 0) {
			return 8;
		}
		int c = 0;
		int code;
		do {
			code = g[n] & 7;
			c += code < 4 ? 2 : 3;
			n -= DIR_STEP[code][1] * w + DIR_STEP[code][0];
		} while (g[n] != 0);
		return c > 2 * limit ? -1 : code;
	}

	/** UnitBlockUp/Down: a row of the body's width is in the map and free of the monster's mask. */
	private boolean row(int x, int y, int left, int bw, int mask) {
		if (y < 0 || y >= map.height) {
			return false;
		}
		for (int i = left; i <= left + bw; i++) {
			if (!free(i, y, mask)) {
				return false;
			}
		}
		return true;
	}

	/** UnitBlockLeft/Right: a column of the body's height. */
	private boolean column(int x, int top, int bh, int mask) {
		if (x < 0 || x >= map.width) {
			return false;
		}
		for (int j = top; j <= top + bh; j++) {
			if (!free(x, j, mask)) {
				return false;
			}
		}
		return true;
	}

	private boolean free(int x, int y, int mask) {
		return map.inBounds(x, y) && !block.blocks(x, y, mask);
	}

	/** GetMonsRouting's fallback: the reached tile with the smallest mark, scanned out from the target toward the monster. */
	private static int nearest(int[] g, int w, int h, int mx, int my, int tx, int ty) {
		int cx = Math.max(0, Math.min(w - 1, tx));
		int cy = ty < 0 ? 0 : ty >= h ? h - 1 : ty;
		int best = 0xffff;
		int bestN = -1;
		if (Math.abs((my - cy) * w) < Math.abs(mx - cx)) {
			int step = Integer.compare(mx, cx);
			for (int col = cx, span = 1; col != mx; col += step, span++) {
				for (int k = 0; k <= span && cy + k < h; k++) {
					int n = (cy + k) * w + col;
					if (g[n] < best) {
						best = g[n];
						bestN = n;
					}
				}
				for (int k = 1; k <= span && cy - k >= 0; k++) {
					int n = (cy - k) * w + col;
					if (g[n] < best) {
						best = g[n];
						bestN = n;
					}
				}
				if (bestN >= 0) {
					break;
				}
			}
		} else {
			int step = Integer.compare(my, cy);
			for (int r = cy, span = 1; r != my; r += step, span++) {
				for (int k = 0; k <= span && cx + k < w; k++) {
					int n = r * w + cx + k;
					if (g[n] < best) {
						best = g[n];
						bestN = n;
					}
				}
				for (int k = 1; k <= span && cx - k >= 0; k++) {
					int n = r * w + cx - k;
					if (g[n] < best) {
						best = g[n];
						bestN = n;
					}
				}
				if (bestN >= 0) {
					break;
				}
			}
		}
		return bestN;
	}

	/**
	 * CalcMonsRun: walking (3), chasing (4), fleeing (0xa) and homing (0xb) move at g_anMonsSpeed
	 * plus frame &amp; 1 half px; the attack (6, 7) lunges and strikes inside its step window.
	 */
	private void calcMonsRun(Monster m) {
		int st = m.aniState;
		int k = switch (st) {
			case 3, 0xb -> 0;
			case 4, 0xa -> 1;
			case 6, 7 -> 3;
			default -> -1;
		};
		if (k < 0) {
			return;
		}
		int v = fight.speed(m.variant, k) + (frames & 1);
		int ox = monsterPx(m);
		int oy = monsterPy(m);
		switch (st) {
			case 3 -> {
				if (!moveMonster(m, m.dir, v, false) || !inWindow(m)) {
					m.cnt = 0;
				}
			}
			case 4 -> {
				if (!moveMonster(m, m.dir, v, false)) {
					changeDir(m, 2, m.dir);
				} else {
					getTrigger(m, ox, oy);
				}
			}
			case 0xa -> {
				if (!moveMonster(m, m.dir, v, false)) {
					if (m.cnt++ > 12) {
						changeDir(m, 2, m.dir);
					}
				} else if (m.fleeFrames++ > 36) {
					changeDir(m, 2, m.dir);
				}
			}
			case 0xb -> {
				if (!moveMonster(m, m.dir, v, false)) {
					if (m.cnt++ >= 37) {
						actMove(m);
					}
					return;
				}
				getTrigger(m, ox, oy);
				if (inWindow(m)) {
					m.cnt = 0;
					m.aniState = 1;
					actMove(m);
				}
			}
			default -> monsAttack(m, v);
		}
	}

	/**
	 * CalcMonsRun states 6 and 7: inside the attack's step window (g_anMonsAttFrm_E) the monster
	 * lunges every tick and AttackMonsHitHero tries the armed strike with RandRange(900, 1100)
	 * (2700..3300 for state 7), drawn each window tick. Projectiles are not ported: a ranged
	 * attacker strikes inside its rectangle like a melee one.
	 */
	private void monsAttack(Monster m, int v) {
		int[] w = fight.attackSteps(m.variant);
		if (m.step < w[0] || m.step > w[1]) {
			return;
		}
		int[] r = (fight.cond(m.variant) & 6) == 0 ? fight.attackRect(m.variant, m.row) : fight.boarRect(m.row);
		moveMonster(m, m.dir, v, false);
		int scale = m.aniState == 7 ? randRange(2700, 3300) : randRange(900, 1100);
		if (!m.trig) {
			return;
		}
		int x0 = m.mirror ? -r[2] : r[0];
		int x1 = m.mirror ? -r[0] : r[2];
		if (in(heroWorldX() - monsterPx(m), heroWorldY() - monsterPy(m), x0, x1, r[1], r[3]) && hero.hp > 0
				&& heroAct < 0x26) {
			monsterHitsHero(m, scale);
		}
	}

	/**
	 * CalcMonsAct state 0xf: four frames of g_aptKnock, flung the way +5 points (mode 2: terrain
	 * only) and lifted, the step held at 0; then InitMonsDown.
	 */
	private void fling(Monster m) {
		if (++m.cnt < 5) {
			int[] k = fight.knock(fight.size(m.variant), attackForm, m.cnt - 1);
			moveMonster(m, m.dir, k[0], true);
			m.lift = k[1];
			m.step = 0;
		} else {
			m.lift = 0;
			initMonsDown(m);
		}
	}

	/**
	 * InitMonsDown: CheckQuestMons, a corpse (state 9) facing the way it flew, decay 0, then
	 * SearchPhaseDead(uid).
	 */
	private void initMonsDown(Monster m) {
		quests.killed(m.variant, m.uid, m.named);
		changeDir(m, 9, m.dir);
		m.cnt = 0;
		events.searchUnit(5, m.uid);
	}

	/**
	 * CollMonsPath(m, amount, mode): {@code halfPx} half px along {@code dir} (diagonals
	 * (v * 7 + 4) / 20 on each axis), a px at a time from the cell centre. A cell the monster's
	 * terrain mask blocks stops it, and so do the hero and other solid monsters unless
	 * {@code fling} (mode 2, which tests terrain mask 0x23 alone).
	 */
	private boolean moveMonster(Monster m, int dir, int halfPx, boolean fling) {
		int d = (dir & 4) == 0 ? halfPx >> 1 : (halfPx * 7 + 4) / 20;
		if (d <= 0) {
			return false;
		}
		for (int axis = 0; axis < 2; axis++) {
			int sign = DIR_STEP[dir][axis];
			for (int i = 0; i < d && sign != 0; i++) {
				int off = (axis == 0 ? m.offX : m.offY) + sign;
				// The offset stays in -8..7 so that px >> 4 is always the cell.
				if (off > 7 || off < -8) {
					int nx = m.x + (axis == 0 ? sign : 0);
					int ny = m.y + (axis == 1 ? sign : 0);
					if (!map.inBounds(nx, ny) || block.blocks(nx, ny, fling ? 0x23 & 7 : so.u8(MONS_BLOCK + m.variant) & 7)
							|| !fling && (monsterAt(nx, ny) != null || nx == heroX && ny == heroY)) {
						return false;
					}
					m.x = nx;
					m.y = ny;
					off -= 16 * sign;
				}
				if (axis == 0) {
					m.offX = off;
				} else {
					m.offY = off;
				}
			}
		}
		return true;
	}

	/** A monster's position in world px: the cell centre, a script walk's progress and any push. */
	private static int monsterPx(Monster m) {
		return m.x * TILE + TILE / 2 + m.offX + unitOffX(m);
	}

	private static int monsterPy(Monster m) {
		return m.y * TILE + TILE / 2 + m.offY + unitOffY(m);
	}

	private int heroWorldX() {
		return heroX * TILE + TILE / 2 + heroOffX();
	}

	private int heroWorldY() {
		return heroY * TILE + TILE / 2 + heroOffY();
	}

	/**
	 * g_anMonsBlock: CollMonsPath's u8 collision mask per monster id, tested on the block grid. 175
	 * ids carry 0x2b and stop at the hero's terrain, 33 carry 0x02 and cross tiles without bit 1
	 * (deep water is 5), 4 carry 0 and cross everything. The spawn footprint bits 0x08 and 0x20 are
	 * left out: this port does not lay the footprints into the grid.
	 */
	private static final int MONS_BLOCK = 0xbea0c;

	/**
	 * AttackMonsHitHeroCore: the miss roll first (also a miss while the hero is immune or a phase
	 * runs), the shield block, the crit, the elemental and status rolls (statuses are not ported:
	 * a stun or knock result stands as a flinch), the flinch by the hero's act, armour wear, the
	 * damage (at least 1) with its number at the hero's chest, a shake and the hero's reaction.
	 */
	private void monsterHitsHero(Monster m, int scale) {
		int hx = heroWorldX();
		int hy = heroWorldY();
		int roll = rng.nextInt(1000);
		int dmg = Combat.monsterDamage(m.atk, scale, hero.armor);
		if (roll < Math.min(hero.dodge + 200 - m.accuracy, 750) || heroImmune || events.active()) {
			if (!heroImmune) {
				damages.add(new int[] { hx, hy - 20, 0x40, 0, Integer.MAX_VALUE });
			}
			m.trig = false;
			return;
		}
		if (rng.nextInt(100) < heroBlock() * 10) {
			damages.add(new int[] { hx, hy - 20, 0x40, 0, Integer.MAX_VALUE });
			m.trig = false;
			return;
		}
		boolean crit = rng.nextInt(1000) < (m.crit < 0xffff ? Math.min(m.crit, 750) : m.crit);
		if (!crit) {
			dmg >>= 1;
		}
		// mod: 150 on normal difficulty; the elemental status roll is drawn and its effect skipped.
		int mod = 150;
		rng.nextInt(1000);
		int spec = 0;
		int a = fight.spec(m.variant) >> 4;
		int b = fight.spec(m.variant) & 0xf;
		if ((a == 1 || b == 1) && rng.nextInt(1000) < mod) {
			spec = 2;
		}
		if (spec == 0 && (a == 2 || b == 2) && rng.nextInt(1000) < mod) {
			spec = 3;
		}
		if (spec == 0 && (a == 3 || b == 3) && rng.nextInt(1000) < mod) {
			spec = 4;
		}
		// The status resist roll; the hero's debuffs are not ported.
		rng.nextInt(100);
		if (spec == 0) {
			int chance = heroAct >= 9 && heroAct <= 0x1c ? 0 : heroAct < 2 ? 1000 : heroAct == 7 ? 300 : 150;
			spec = rng.nextInt(1000) < chance || crit ? 1 : 0;
		}
		armourWear(crit, spec);
		if (target == null && m.uid < 0xff00) {
			target = m;
			targetTimer = 36;
		}
		dmg = Math.max(dmg, 1);
		hero.hp -= dmg;
		boolean lethal = hero.hp < 1;
		int d = detailDir(m, hx, hy);
		if (!crit && !lethal) {
			damages.add(new int[] { hx, hy - 20, 0x40, 0, dmg });
			addSwing(spec == 0 ? 7 : 3, d & 3);
		} else {
			damages.add(new int[] { hx, hy - 20, 0x80, 0, dmg });
			addSwing(1, 0);
		}
		log = m.kind + "에게 " + dmg + " 피해를 입었다.";
		m.trig = false;
		if (lethal) {
			// The fall (act 4) and the death popup are not ported.
			knockedOutCheck();
		} else if (spec != 0) {
			heroFlinch();
		}
	}

	/** HeroState's +0x1c8: a class-1 hero's working shield in slot 7 blocks (grade) x 10 percent. */
	private int heroBlock() {
		Item.Equip s = hero.inv.worn(7);
		return hero.heroClass == 1 && s != null && s.dur != 0 ? items.shieldGrade(s.id) : 0;
	}

	/**
	 * ChangeHeroActDamage(3): the hero flinches (act 3), which drops a queued command and ends a
	 * swing; the next tick on it is immune until the flinch animation ends.
	 */
	private void heroFlinch() {
		// ChangeHeroActDamage: a flinch never interrupts 갈무리.
		if (heroAct == 2) {
			return;
		}
		attackQueued = false;
		attackRunning = false;
		flinchBreak = false;
		runWalking = false;
		changeHeroAct(3);
	}

	/**
	 * CalcMonsAct state 9: an unlocked corpse decays a step every 4 play frames up to 0xa0 and
	 * then fades; the first one the hero (standing, walking or sitting) is on gets the OK bubble.
	 * A locked one (0xfb, which 갈무리 renews each tick) turns 0xfc and fades once nobody renews
	 * it. Named, elite and boss corpses never decay in the original; this port marks none.
	 */
	private void corpse(Monster m) {
		if (m.cnt >= 0xfb) {
			if (m.cnt < 0xfc) {
				m.cnt++;
			} else if (m.fade < 1) {
				m.fade = 1;
			}
			return;
		}
		if (m.cnt < 0xa0) {
			if ((playFrames & 3) == 0) {
				m.cnt++;
			}
		} else if (m.fade < 1) {
			m.fade = 1;
		}
		if (lootable == null && (heroAct < 2 || heroAct == 7) && corpseOn(m, heroWorldX(), heroWorldY())) {
			lootable = m;
		}
	}

	/** Armour wear on a landed hit (not on 'n' maps): a worn helmet, armour, shoulder or class-1 shield loses durability. */
	private void armourWear(boolean crit, int spec) {
		if (mapCode.charAt(0) == 'n') {
			return;
		}
		int amt;
		int ch;
		if (!crit) {
			amt = spec == 0 ? 10 : randRange(10, 20);
			ch = spec == 0 ? 100 : 500;
		} else {
			amt = randRange(20, 30);
			ch = 1000;
		}
		ch += spec == 3 ? 100 : spec == 4 ? 200 : 0;
		List<Item.Equip> worn = new ArrayList<>();
		for (Item.Equip e : hero.inv.equip) {
			if (e != null && (e.slot == 3 || e.slot == 4 || e.slot == 10 || e.slot == 7 && hero.heroClass == 1)) {
				worn.add(e);
			}
		}
		if (worn.isEmpty()) {
			return;
		}
		Item.Equip it = worn.get(randRange(0, worn.size() - 1));
		if (rng.nextInt(1000) >= ch) {
			return;
		}
		it.dur -= amt;
		int msg = it.slot == 7 ? 0 : it.slot == 3 ? 1 : it.slot == 4 ? 2 : 3;
		if (it.dur < 0) {
			it.dur = 0;
			it.slot = 0;
			log = String.format(fmt(WEAR_BROKEN[msg]), items.name(it.id));
			hero.recompute(rng);
		} else if (it.dur <= it.durMax >> 2) {
			log = String.format(fmt(WEAR_LOW[msg]), items.name(it.id));
		}
	}

	/** The sysline texts of a broken / worn-down shield, helmet, armour and shoulder. */
	private static final int[] WEAR_BROKEN = { 0xd94d0, 0xd94ec, 0xd9508, 0xd9524 };
	private static final int[] WEAR_LOW = { 0xd9548, 0xd9578, 0xd95a8, 0xd95d8 };

	private String fmt(int va) {
		String s = so.cstring(va);
		return s == null ? "" : s;
	}

	private void knockedOutCheck() {
		if (hero.hp <= 0) {
			if (heroAct == 2) {
				lootCancel();
			}
			hero.hp = hero.maxHp;
			hero.inv.gold = Math.max(0, hero.inv.gold - 50);
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
		if (gmenu != 0) {
			int r = talk.gmenuKey(code);
			if (r >= 0) {
				gmenuResult(r);
			}
			return;
		}
		if (bossGate != null) {
			// ResultGMenu 0x74: Enter saves the slot (SaveSlot) and moves without asking again
			// (MoveGameMap(i, 0)); Cancel just closes the menu.
			int r = talk.warningKey(code);
			if (r >= 0) {
				AreaInfo.Portal p = bossGate;
				bossGate = null;
				talk.clear();
				if (r == 1) {
					save();
					moveGameMap(p, false);
				}
			}
			return;
		}
		if (menuOpen || shopOpen) {
			menuKey(code);
			return;
		}
		// KeyPressGamePlay hands every key to KeyPressPhase first; a running phase eats it.
		if (events.key(code)) {
			return;
		}
		switch (code) {
			case Keys.LEFT, Keys.A -> hold(2);
			case Keys.RIGHT, Keys.D -> hold(3);
			case Keys.UP, Keys.W -> hold(0);
			case Keys.DOWN, Keys.S -> hold(1);
			case Keys.ENTER, Keys.SPACE -> talkToNpc();
			case Keys.K1 -> {
				if (!attackDown) {
					attackDown = true;
					attackKey();
				}
			}
			case Keys.K2 -> drinkPotion();
			case Keys.F5 -> save();
			case Keys.F9 -> load();
			case Keys.I, Keys.BACK_SPACE -> openMenu();
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

	/**
	 * KeyPressGamePlay: a direction key sets +0x47. A key's autorepeat is not a press: only a
	 * fresh one breaks a flinch or ends 갈무리 early.
	 */
	private void hold(int dir) {
		boolean fresh = heldDir != dir;
		heldDir = dir;
		clickX = -1;
		if (fresh) {
			flinchBreak = true;
			if (heroAct == 2) {
				lootEnd();
			}
		}
	}

	/** The key's release: letting go of the held direction clears +0x47, letting go of attack {@link #releaseAttack}. */
	void keyUp(int code) {
		if (code == Keys.K1) {
			releaseAttack();
			return;
		}
		int dir = switch (code) {
			case Keys.UP, Keys.W -> 0;
			case Keys.DOWN, Keys.S -> 1;
			case Keys.LEFT, Keys.A -> 2;
			case Keys.RIGHT, Keys.D -> 3;
			default -> -1;
		};
		if (dir >= 0 && dir == heldDir) {
			heldDir = -1;
		}
	}

	/** The window lost focus: no release will arrive for a key still down. */
	void releaseKeys() {
		heldDir = -1;
		releaseAttack();
	}

	/**
	 * KeyReleaseGamePlay for the attack key: the hold timer stops; 갈무리 is cancelled in its
	 * first 4 frames (the corpse keeps its decay, nothing is gathered) and ended early after that.
	 */
	private void releaseAttack() {
		attackDown = false;
		holdTimer = 0;
		if (heroAct != 2) {
			return;
		}
		if (lootLeft == lootCycles && lootCharge < 4) {
			lootCancel();
		} else {
			lootEnd();
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
			// A click is a tap: pressed and let go.
			attackKey();
			holdTimer = 0;
		} else {
			// Walked to frame by frame by CalcHeroRun, like a held direction.
			clickX = gx;
			clickY = gy;
		}
	}

	/** One instant cell move; only the selftest's combat drive still uses it (players walk through CalcHeroRun). */
	private void step(int dx, int dy) {
		int nx = heroX + dx;
		int ny = heroY + dy;
		if (!map.inBounds(nx, ny)) {
			// MoveHeroPath turns first and checks the current cell even when the move is blocked, so
			// pushing against the map edge from inside a portal still takes it.
			heroDir = dy < 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3;
			takeExit();
			return;
		}
		if (monsterAt(nx, ny) != null) {
			log = ".mon스터가 길을 막고 있다.";
			return;
		}
		heroDir = dy < 0 ? 0 : dy > 0 ? 1 : dx < 0 ? 2 : 3;
		heroX = nx;
		heroY = ny;
		takeExit();
	}

	/**
	 * MoveHeroPath on an exit cell: walking into a portal while facing out of the map (the
	 * portal's facing ^ 1) runs the map's exit event for it, or else moves the map as
	 * MoveGameMap(i, 1) does.
	 */
	private void takeExit() {
		List<AreaInfo.Portal> exits = area.portals;
		for (int i = 0; i < exits.size(); i++) {
			AreaInfo.Portal p = exits.get(i);
			if (p.contains(heroX, heroY) && heroDir == (p.dir() ^ 1)) {
				if (!events.searchMapMove(i)) {
					moveGameMap(p, true);
				}
				return;
			}
		}
	}

	/**
	 * MoveGameMap(i, check): the destination's portal receives the hero at the same relative spot,
	 * scaled to its size, facing into the new map; then the new map's events start.
	 */
	private void moveGameMap(AreaInfo.Portal from, boolean check) {
		String mob = "assets/eng/mob/" + from.dest().charAt(0) + ".png";
		try {
			// A header high nibble of 3 or more marks a boss/regen map. MoveGameMap goes straight in
			// when GetRegen(1) has a live record for it; this port keeps none, so GMenu 0x74 asks.
			if (check && AreaInfo.load(assets, mob, from.dest()).ambience >> 4 >= 3) {
				stopRun();
				changeHeroAct(HeroAnim.ACT_STAND);
				talk.startWarning(so.cbytes(GATE_TITLE), so.cbytes(GATE_TEXT), so.cbytes(GATE_ENTER),
						so.cbytes(GATE_CANCEL));
				bossGate = from;
				return;
			}
		} catch (IOException e) {
			// No area file: not a gated map.
		}
		int rx = heroX - from.x0();
		int ry = heroY - from.y0();
		int w = from.x1() - from.x0();
		int h = from.y1() - from.y0();
		try {
			loadMap(from.dest());
		} catch (IOException e) {
			log = "맵 로드 실패: " + e;
			return;
		}
		if (from.destIndex() < area.portals.size()) {
			AreaInfo.Portal to = area.portals.get(from.destIndex());
			int tw = to.x1() - to.x0();
			int th = to.y1() - to.y0();
			heroX = to.x0() + (rx * tw + (tw >> 1)) / w;
			heroY = to.y0() + (ry * th + (th >> 1)) / h;
			heroDir = to.dir();
		}
		enterEvents();
	}

	private void notPorted(String what) {
		log = "[미포팅] " + what;
		if (notPortedSeen.add(what)) {
			System.out.println("[미포팅] " + what);
		}
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

	/** The unit standing on a cell; a dying monster or a corpse is no longer solid (+0x38 = 1). */
	private Monster monsterAt(int x, int y) {
		for (Monster m : monsters) {
			if (m.x == x && m.y == y && (m.npc || m.hp > 0)) {
				return m;
			}
		}
		return null;
	}

	/**
	 * KeyPressGamePlay's attack key (-5 / '5'; this port's 1): with no chain running,
	 * CheckHeroOnMons turns the hero toward monsters in reach. With none alive within 48 px a
	 * corpse in reach starts 갈무리 at once; otherwise the swing is queued (+0x47 = 5), and with
	 * the living near the hold timer starts.
	 */
	private void attackKey() {
		if (!attackRunning && chainHits == 0) {
			aim();
		}
		int hx = heroWorldX();
		int hy = heroWorldY();
		int[] box = { hx - 48, hy - 48, hx + 48, hy + 48 };
		boolean near = false;
		for (Monster m : monsters) {
			near |= targetable(m) && fight.hits(box, m.variant, monsterPx(m), monsterPy(m));
		}
		if (!near && checkHeroOnLoot()) {
			return;
		}
		attackQueued = true;
		if (near) {
			holdTimer = 1;
		}
	}

	/**
	 * CheckHeroOnLoot: in acts below 8, 9 and above 0x1c, the first unlocked corpse whose hurtbox
	 * meets the hero's ±24 px box starts 갈무리 (act 2, ending a swing and a queued command):
	 * size + 1 cycles of 12 frames, the hero turned to the corpse's centre, the corpse locked
	 * (decay 0xfb) and the gauge on. Elite (+2) and boss (5) cycle counts are not ported.
	 */
	private boolean checkHeroOnLoot() {
		if (heroAct >= 8 && heroAct <= 0x1c && heroAct != 9) {
			return false;
		}
		int hx = heroWorldX();
		int hy = heroWorldY();
		for (Monster m : monsters) {
			if (!corpseOn(m, hx, hy)) {
				continue;
			}
			int[] b = fight.blow(m.variant, monsterPx(m), monsterPy(m));
			int cx = b[0] + ((b[2] - b[0]) >> 1);
			int cy = b[1] + ((b[3] - b[1]) >> 1);
			lootFacing = heroDir;
			attackQueued = false;
			attackRunning = false;
			runWalking = false;
			changeHeroAct(2);
			lootCycles = fight.size(m.variant) + 1;
			lootLeft = lootCycles;
			runeLeft = 1;
			lootCharge = 0;
			loot = m;
			heroDir = cy < hy ? (cx < hx ? 2 : 0) : (hx < cx ? 3 : 1);
			gaugeStart = frames;
			lootDecay = m.cnt;
			m.cnt = 0xfb;
			return true;
		}
		return false;
	}

	/** A corpse (id &lt; 0xa5, state 9, not fading, not summoned, unlocked) whose hurtbox meets the ±24 px box around (hx, hy). */
	private boolean corpseOn(Monster m, int hx, int hy) {
		return m.variant < 0xa5 && m.aniState == 9 && m.fade < 1 && m.uid < 0xff00 && m.cnt < 0xfb
				&& fight.hits(new int[] { hx - 24, hy - 24, hx + 24, hy + 24 }, m.variant, monsterPx(m), monsterPy(m));
	}

	/** Ending 갈무리 early (a direction, an attack, the menu closing, the key let go): the last call gathers with the charge so far. */
	private void lootEnd() {
		lootLeft = lootLeft == 1 ? 0 : -1;
		gather();
	}

	/** KeyReleaseGamePlay / SetPhaseHeroStop: 갈무리 stops with nothing gathered, the corpse's decay given back plus the charge. */
	private void lootCancel() {
		if (loot != null && loot.aniState == 9) {
			loot.cnt = lootDecay + lootCharge;
		}
		stopLoot();
	}

	/** ChangeHeroAct(0), the facing from before 갈무리 back, the gauge off. */
	private void stopLoot() {
		changeHeroAct(HeroAnim.ACT_STAND);
		heroDir = lootFacing;
		gaugeStart = -1;
		loot = null;
	}

	/** g_anMonsDrop: u16 per monster id, the first g_anMonsItem entry (bits 0-11) and how many (12-15). */
	private static final int MONS_DROP = 0xcbb94;
	/** g_anMonsItem: u16 material ids of tier 0; the tier is added to the id. */
	private static final int MONS_ITEM = 0xcbe2c;
	/** g_anRuneDrop: 26 descending s16 bounds over 0..10000 that turn a weighted roll into a rune tier. */
	private static final int RUNE_DROP = 0xba5cc;
	/** g_anRuneProb: s8 tier per rune r (item 0x12d + r), 77 runes. */
	private static final int RUNE_PROB = 0xba4dc;
	/** g_aEquipGrade: the grade names a recipe's line starts with. */
	private static final int EQUIP_GRADE = 0xe5dbc;
	private static final int GATHER_FAIL = 0xdcc6c;
	private static final int GATHER_ITEM = 0xdcc8c;
	/** The full-bag texts: equipment, belt, material, etc., recipe book. */
	private static final int[] BAG_FULL = { 0xdccb0, 0xdcce8, 0xdcd10, 0xdcd48, 0xdcd7c };

	/**
	 * GetItemHeroGather(0) on the corpse, once a cycle and when 갈무리 ends early; the last call
	 * starts its fade. Under 5 frames of charge half the calls fail (bubble 3); otherwise 1-2,
	 * 2-3 or 3 items by the charge, of one tier weighted by the monster's level. The first slot
	 * may instead hold a rune (2 percent, one a corpse) or a recipe (20 percent), which end the
	 * list; the rest are the monster's materials. Equal ids merge and AddItem forces them in.
	 * Before the rune, each slot first rolls for a quest item (hero+0x398's drop lists), one a
	 * call. The luck stat (hero+0x1ec), pets and elite/boss chances are not ported. A last
	 * cycle of three items with no bag full shows bubble 0xb; then the hero stands again.
	 */
	private void gather() {
		Monster c = loot;
		int n = 0;
		boolean full = false;
		if (c != null && monsters.contains(c) && c.aniState == 9 && c.fade < 1) {
			if (lootLeft < 1) {
				c.fade = 1;
			}
			int fail = lootCharge < 5 ? 500 : 0;
			n = lootCharge < 9 ? randRange(1, 2) : lootCharge < 12 ? randRange(2, 3) : 3;
			List<String> lines = new ArrayList<>();
			if (rng.nextInt(1000) < fail) {
				new Stage().emote(EventRunner.HERO, 3);
				lines.add(fmt(GATHER_FAIL));
			} else {
				int lv = c.level;
				int[] w = new int[4];
				for (int k = 0; k < 4; k++) {
					w[k] = Math.max(0, 16 - Math.abs(lv - 22 * k - 13));
				}
				if (w[0] + w[1] + w[2] == 0) {
					w[3] = 16;
				}
				int r = randRange(0, w[0] + w[1] + w[2] + w[3] - 1);
				int tier = 0;
				while (tier < 3 && (r -= w[tier]) >= 0) {
					tier++;
				}
				int grade = tier;
				int[] ids = new int[n];
				int[] counts = new int[n];
				Arrays.fill(ids, -1);
				Arrays.fill(counts, 1);
				boolean quest = false;
				for (int j = 0; j < n; j++) {
					if (!quest) {
						int q = quests.drop(c.variant, c.uid, rng);
						if (q >= 0) {
							ids[j] = q;
							quest = true;
						}
					}
					if (j == 0 && ids[0] < 0) {
						boolean rune = rng.nextInt(1000) < 20;
						if (runeLeft != 0 && rune) {
							int x = randWeight10000(lv * 25 + 3500);
							int i = 0;
							while (i < 0x19 && (so.s16(RUNE_DROP + i * 2) <= x || x < so.s16(RUNE_DROP + i * 2 + 2))) {
								i++;
							}
							ids[0] = itemRune(i);
							runeLeft--;
							break;
						}
						if (rng.nextInt(1000) < 200) {
							List<Integer> recipes = new ArrayList<>();
							for (int id = 0x8c; id < 0xe8; id++) {
								int l = items.level(id);
								if (l - 4 <= lv && (lv <= l + 4 || id > 0xb3 && id < 0xb8)) {
									recipes.add(id);
								}
							}
							if (!recipes.isEmpty()) {
								ids[0] = recipes.get(randRange(0, recipes.size() - 1)) + 0xee;
								int base = 80;
								int x = randRange(0, base);
								int t4 = 0x61;
								int t3 = 0x5a;
								if (base > 100) {
									t4 = (base * 19 - 0x5ca) / 22 + 0x4e;
									t3 = (base * 12 - 0x3a8) / 22 + 0x4e;
								}
								grade = x > t4 ? 4 : x > t3 ? 3 : x >= 0x4f ? 2 : x >= 0x33 ? 1 : 0;
								break;
							}
						}
					}
					int d = so.u16(MONS_DROP + c.variant * 2);
					// Ids with no materials (0xa3, 0xa4) would divide by zero in the original.
					if (ids[j] < 0 && d >> 12 > 0) {
						ids[j] = so.u16(MONS_ITEM + ((d & 0xfff) + randRange(0, (d >> 12) - 1)) * 2) + tier;
					}
				}
				for (int j = 1; j < n; j++) {
					for (int k = j - 1; k >= 0 && ids[j] >= 0; k--) {
						if (ids[j] == ids[k]) {
							ids[j] = -1;
							counts[k]++;
						}
					}
				}
				int bits = 0;
				for (int j = 0; j < n; j++) {
					if (ids[j] < 0) {
						continue;
					}
					int left = hero.inv.add(ids[j], counts[j], grade, true, rng);
					int t = items.type(ids[j]);
					if (left != 0) {
						full = true;
						bits |= t >= 1 && t <= 0xc ? 1 : t == 0xe || t == 0xf ? 2 : t == 0xd ? 4 : t == 0x13 ? 0x10 : 8;
					}
					if (left >= 0) {
						quests.itemAdded(ids[j], counts[j] - left);
						String name = items.name(ids[j]);
						if (t == 0x13) {
							name = so.cstring(so.u32(EQUIP_GRADE + grade * 4)) + " " + name;
						}
						// The sysline's colour codes (\a>, \a0) and indent are dropped for the log line.
						lines.add(String.format(fmt(GATHER_ITEM).replaceAll("\u0007.", "").trim(), name, counts[j]));
					}
				}
				events.searchPhase();
				for (int b = 0; b < BAG_FULL.length; b++) {
					if ((bits >> b & 1) != 0) {
						lines.add(fmt(BAG_FULL[b]));
					}
				}
			}
			log = String.join("  ", lines);
		}
		if (lootLeft == 0 && n == 3 && !full) {
			new Stage().emote(EventRunner.HERO, 0xb);
		}
		if (lootLeft < 1) {
			stopLoot();
		}
	}

	/**
	 * RandWeight10000(p): x in 0..9999 kept with probability falling linearly in x, steeper as p
	 * drops below 10000; above it the low end thins instead.
	 */
	private int randWeight10000(int p) {
		int lo = p < 10001 ? (p - 10000) * 2 + 10000 : 10000;
		int hi = p < 10001 ? 10000 : (10000 - p) * 2 + 10000;
		while (true) {
			int x = rng.nextInt(10000);
			int t = hi * x / 10000;
			if (rng.nextInt(10000) < lo - t + hi) {
				return x;
			}
		}
	}

	/** GetItemRune(tier): a random rune (item 0x12d + r) of that g_anRuneProb tier, or -1. */
	private int itemRune(int tier) {
		List<Integer> runes = new ArrayList<>();
		for (int r = 0; r < 0x4d; r++) {
			if (so.s8(RUNE_PROB + r) == tier) {
				runes.add(r);
			}
		}
		return runes.isEmpty() ? -1 : runes.get(randRange(0, runes.size() - 1)) + 0x12d;
	}

	/** DrawImoticonOK's tracker (+0x5d00) on the frame count: shown on consecutive frames the OK bubble pops in over three (0, 1, then 2 = full). */
	private int okFrame() {
		int fc = frames;
		if (okTrack + 1 == fc) {
			okTrack = fc;
			return 2;
		}
		if (okTrack + 2 == fc) {
			okTrack = fc;
			return 1;
		}
		if (okTrack + 3 == fc) {
			okTrack = fc - 1;
			return 0;
		}
		okTrack = fc - 2;
		return -1;
	}

	/**
	 * CheckHeroOnMons: the first facing, in the aim order for the current one, whose first swing
	 * would reach a monster turns the hero toward their average position (g_aAdjDir).
	 */
	private void aim() {
		Item.Equip w = hero.inv.worn(1);
		int form = w == null ? 0 : w.weaponForm();
		int hx = heroWorldX();
		int hy = heroWorldY();
		for (int facing : fight.aimOrder(heroDir & 3)) {
			int[] r = fight.rect(form, 9, facing, hx, hy);
			int n = 0;
			int sx = 0;
			int sy = 0;
			for (Monster m : monsters) {
				if (targetable(m) && fight.hits(r, m.variant, monsterPx(m), monsterPy(m))) {
					n++;
					sx += monsterPx(m);
					sy += monsterPy(m);
				}
			}
			if (n > 0) {
				heroDir = Fight.facingToward(sx / n - hx, sy / n - hy);
				return;
			}
		}
	}

	/** AttackHero's targets: monsters (id &lt; 0xa5) alive, seen and not a pet. */
	private static boolean targetable(Monster m) {
		return !m.npc && m.hp > 0 && m.fade <= 0 && m.variant < 0xa5;
	}

	/**
	 * CalcHeroAttack / InitHeroAttack, then CalcHeroCombo. A queued attack waits for the hero to
	 * reach a cell centre. No weapon (look &lt; 0) or a town map (map+0x2d == 1) cancels it
	 * (InitHeroWeaponSpeed). During a swing a press before step f1 is dropped; from f1 on it
	 * starts the next swing while the chain is open, and once the chain is complete it waits for
	 * the swing to end.
	 */
	private void calcHeroAttack() {
		heroImmune = heroAct == 3 || heroAct == 0x1d || heroAct == 4;
		// CalcHeroAct: a flinch ends with its cycle; a held direction walks on from the stand.
		if (heroAct == 3 && heroStep == 0 && heroHeld == 0) {
			changeHeroAct(HeroAnim.ACT_STAND);
		}
		// CalcHeroAct's 갈무리 tick: the corpse re-locked, a gather every 12 frames.
		if (heroAct == 2) {
			if (loot != null && loot.aniState == 9) {
				loot.cnt = 0xfb;
			}
			if (++lootCharge > 11) {
				lootLeft--;
				gather();
				lootCharge = 0;
			}
		}
		// InitHeroAttack: an attack during 갈무리 ends it early, then swings.
		if (heroAct == 2 && attackQueued) {
			lootEnd();
		}
		if (attackQueued && heroPx == 0 && heroAct != 3) {
			Item.Equip w = hero.inv.worn(1);
			if (w == null || area.mapKind() == 1 && !bossLoaded) {
				attackQueued = false;
				if (w == null) {
					log = "맨손으로는 공격할 수 없다.";
				}
			} else if (!attackRunning) {
				startSwing(w);
			} else if (attackStep < attackFrames[1]) {
				attackQueued = false;
			} else if (chainHits < chainLen) {
				startSwing(w);
			}
		}
		calcHeroCombo();
	}

	/**
	 * HeroAttack.c's normal attack: act 9, then 0xa and 0xf while the link window (+0x95) stays
	 * open, back to 9 once the chain of 3 is done. The weapon look's delays set how long steps
	 * f0 and f1 hold, and the window is f4 + both delays + 4 frames.
	 */
	private void startSwing(Item.Equip w) {
		int act = chainHits >= chainLen ? 9
				: linkWindow > 0 && lastAttackAct == 9 ? 0xa
				: linkWindow > 0 && lastAttackAct == 0xa ? 0xf
				: linkWindow > 0 && lastAttackAct == 0xf ? 0x10 : 9;
		attackForm = w.weaponForm();
		attackFrames = fight.frames(attackForm, act);
		int[] d = fight.delays(attackForm, items.look(w.id));
		holdF0 = d[0];
		holdF1 = d[1];
		lastAttackAct = act;
		attackQueued = false;
		attackStep = 0;
		attackRunning = true;
		linkWindow = attackFrames[4] + d[0] + d[1] + 4;
		if (chainHits < chainLen) {
			chainHits++;
		} else {
			chainHits = 1;
			chainLen = CHAIN_BASE;
		}
		runWalking = false;
		changeHeroAct(act);
	}

	/** CalcHeroCombo: the combo count shows while its frames last; the chain resets once the link window closes. */
	private void calcHeroCombo() {
		if (comboShow == 0) {
			comboCount = 0;
			comboShown = 0;
		} else {
			if (comboCount == comboShown) {
				comboAge++;
			} else {
				comboShown = comboCount;
				comboAge = 0;
			}
			comboShow--;
		}
		if (linkWindow == 0) {
			chainHits = 0;
			chainLen = CHAIN_BASE;
		} else {
			linkWindow--;
		}
	}

	/**
	 * CalcHeroAnim for a swing: step f0 shows for holdF0 frames and f1 for holdF1 (0 skips it),
	 * every other step one frame; at f4 the swing is over (+0x1c = 0). Returns the step drawn;
	 * attackStep becomes the next one, which AttackHero reads.
	 */
	private int attackShown() {
		int[] f = attackFrames;
		int s = attackStep;
		if (s == f[0]) {
			if (holdF0-- <= 0) {
				s++;
			}
		} else if (s == f[1]) {
			if (holdF1-- <= 0) {
				s++;
			}
		}
		int shown = s;
		if (s != f[0] && s != f[1]) {
			s++;
		}
		if (s == f[0] && holdF0 <= 0 || s == f[1] && holdF1 <= 0) {
			s++;
		}
		attackStep = s;
		if (s >= f[4]) {
			attackRunning = false;
			// CheckQuestSkill at the end of a basic attack's animation (id 0).
			quests.skillUsed(0);
		}
		return shown;
	}

	/**
	 * AttackHero: on the swing's hit step (f2) every live monster whose hurtbox (g_arcMonsBlow_LE)
	 * meets the swing's rectangle is struck, nearest first, at most 50.
	 */
	private void attackHero() {
		if (!attackRunning || attackStep != attackFrames[2]) {
			return;
		}
		int hx = heroWorldX();
		int hy = heroWorldY();
		int[] r = fight.rect(attackForm, heroAct, heroDir & 3, hx, hy);
		List<Monster> hit = new ArrayList<>();
		for (Monster m : monsters) {
			if (targetable(m) && fight.hits(r, m.variant, monsterPx(m), monsterPy(m))) {
				hit.add(m);
			}
		}
		hit.sort((a, b) -> Integer.compare(dist2(a, hx, hy), dist2(b, hx, hy)));
		// The swing's first landed hit takes the target bar (Hero.c:285-345).
		boolean first = true;
		for (int i = 0; i < hit.size() && i < 50; i++) {
			Monster m = hit.get(i);
			if (hitMonster(m) && first && m.uid < 0xff00) {
				first = false;
				target = m;
				targetTimer = 36;
			}
		}
	}

	private static int dist2(Monster m, int x, int y) {
		int dx = monsterPx(m) - x;
		int dy = monsterPy(m) - y;
		return dx * dx + dy * dy;
	}

	/**
	 * AttackHeroHitMons for a plain swing (element, race, debuffs and the boss cap aside): the
	 * damage and its number at the monster's half height, a shake (crit pattern 2, else 7 + the
	 * hero's facing) and the combo count. A struck monster notices the hero (ChangeMonsLookDir);
	 * at 0 HP it goes down, otherwise it flinches (state 8) when the roll beats its size's
	 * threshold (0 while it attacks) or on a crit, and is pushed away from the hero (+0x4b: 8 on
	 * a crit, else 1). Returns whether the swing landed.
	 */
	private boolean hitMonster(Monster m) {
		Combat.Result r = Combat.heroHits(hero.power(), hero.accuracy, hero.crit, m.defence, m.dodge, rng);
		int mx = monsterPx(m);
		int my = monsterPy(m);
		int toHero = Fight.facingToward(heroWorldX() - mx, heroWorldY() - my);
		lookDir(m, detailDir(m, heroWorldX(), heroWorldY()));
		if (r.missed()) {
			damages.add(new int[] { mx, my - fight.halfY(m.variant), 0, 0, Integer.MAX_VALUE });
			log = m.kind + "에게 빗나감.";
			return false;
		}
		int dealt = r.damage();
		m.hp -= dealt;
		damages.add(new int[] { mx, my - fight.halfY(m.variant), r.crit() ? 1 : 0, 0, dealt });
		addSwing(r.crit() ? 2 : 7, heroDir);
		comboCount++;
		comboShow = fight.comboFrames(attackForm);
		log = m.kind + "에게 " + dealt + " 피해." + (r.crit() ? " 크리티!" : "");
		if (m.hp <= 0) {
			killMonster(m, toHero);
			return true;
		}
		int t = m.aniState == 6 ? 0 : fight.flinch(fight.size(m.variant));
		if (r.crit() || rng.nextInt(1000) - hero.crit - t < 0) {
			changeDir(m, 8, toHero);
		}
		m.pushKind = r.crit() ? 8 : 1;
		m.pushDir = toHero ^ 1;
		return true;
	}

	/**
	 * ChangeMonsDown(m, facing the hero): the experience now, then state 0xf, the fling away
	 * from the hero. A kill gives nothing else; 갈무리 on the corpse gives the loot.
	 */
	private void killMonster(Monster m, int toHero) {
		int levels = hero.gainExp(hero.expFor(m.level, false), rng);
		log += " 처치." + (levels > 0 ? " 레벨 " + hero.level + " 달성!" : "");
		// InitHeroLevelUp: with no phase running and none started, AnalysisQuest.
		if (levels > 0 && events.depth() == 0 && !events.searchPhase()) {
			quests.analyze();
		}
		changeDir(m, 0xf, toHero);
		m.cnt = 0;
	}

	/** AddSwing(n, dir): a new pattern takes over unless one just started lower (raw n compared); 3 and 7 add the direction. */
	private void addSwing(int n, int dir) {
		if (swingCnt < 2 || n < swingPat) {
			int p = n == 3 || n == 7 ? n + (dir & 3) : n;
			swingPat = p <= 10 ? p : 0;
			swingCnt = 3;
		}
	}

	/**
	 * DrawAndDestroyDamage on the field: MISS (gameui 0x47) rises and fades over frames 0..4. A
	 * number's digits each run a frame behind the one before, big (0x31, crits 0x3b) for their
	 * first two frames and then small (0x27, crits 0x31), centred on the hit point by
	 * GetDamageNoWidth; a crit adds its burst (0x45, then 0x46). Crits use palette row 0, others 1.
	 */
	private void drawDamages(Gfx g, int ox, int oy) {
		if (damages.isEmpty()) {
			return;
		}
		Gsac ui;
		try {
			ui = orderSheets().gameui;
		} catch (IOException e) {
			return;
		}
		for (int[] d : damages) {
			int f = d[3];
			// The digit set follows kind & 0x81 (hero crits 0x80 too), the burst kind & 1 alone.
			boolean crit = (d[2] & 0x81) != 0;
			int row = (d[2] & 0xc0) != 0 ? 2 : crit ? 0 : 1;
			if (d[4] == Integer.MAX_VALUE) {
				uiSprite(g, ui, 0x47, ox, oy, d[0], d[1] + so.s8(MISS_Y + f), row, so.s8(MISS_MODE + f));
				continue;
			}
			if ((d[2] & 1) != 0 && f < 8) {
				uiSprite(g, ui, f < 1 ? 0x45 : 0x46, ox, oy, d[0], d[1] + f, row, so.s8(NUMBER_MODE + 1 + f));
			}
			String s = String.valueOf(d[4]);
			int big = crit ? 0x3b : 0x31;
			int small = big - 10;
			int[] xb = digitOffsets(ui, s, big);
			int[] xs = digitOffsets(ui, s, small);
			int fi = f;
			for (int i = 0; i < s.length(); i++) {
				if (fi < 9) {
					int y = so.s8((crit ? CRIT_Y : NORMAL_Y) + fi);
					uiSprite(g, ui, s.charAt(i) - '0' + (fi < 2 ? big : small), ox, oy, d[0] + (fi < 2 ? xb[i] : xs[i]),
							d[1] + y, row, so.s8(NUMBER_MODE + fi));
					if (--fi < 0) {
						break;
					}
				} else {
					fi--;
				}
			}
		}
	}

	/** GetDamageNoWidth: each digit's x, the widths of one glyph set summed and centred. */
	private int[] digitOffsets(Gsac ui, String s, int set) {
		int[] out = new int[s.length()];
		int x = 0;
		for (int i = 0; i < s.length(); i++) {
			out[i] = x;
			x += ui.width(clix.frame(Clix.GAMEUI, (s.charAt(i) - '0' + set) * 8));
		}
		for (int i = 0; i < out.length; i++) {
			out[i] -= x >> 1;
		}
		return out;
	}

	/** DrawSpriteClixPal on the field: a gameui clix entry at world px (wx, wy), scaled to the view; modes 1..3 fade it. */
	private void uiSprite(Gfx g, Gsac ui, int entry, int ox, int oy, int wx, int wy, int row, int mode) {
		int off = entry * 8;
		int f = clix.frame(Clix.GAMEUI, off);
		if (f < 0 || f >= ui.frameCount()) {
			return;
		}
		Img img = ui.frame(f, row, 0);
		int x = ox + (wx + clix.x(Clix.GAMEUI, off)) * CELL / TILE;
		int y = oy + (wy + clix.y(Clix.GAMEUI, off)) * CELL / TILE;
		g.drawImage(img, x, y, img.w * CELL / TILE, img.h * CELL / TILE, 255 * (4 - Math.max(0, Math.min(3, mode))) / 4);
	}

	/**
	 * DrawGameUI's combo counter (wawamain+0x5a68 of 2 or more): "COMBO" (gameui 0x49) moved right
	 * by the extra digits, then the digits (0x27 + d, palette 3, 11 px apart) below clix_gameui
	 * 0x5f's box, nudged -5 px and +1 px on the first two frames after the count changes.
	 */
	private void drawCombo(Gfx g) {
		Gsac ui;
		try {
			ui = orderSheets().gameui;
		} catch (IOException e) {
			return;
		}
		String s = String.valueOf(comboShown);
		int box = 0x5f * 8;
		clix.sprite(g, ui, Clix.GAMEUI, 0x49 * 8, (s.length() - 1) * clix.w(Clix.GAMEUI, box) + (comboAge == 0 ? 1 : 0), 0);
		int x = clix.x(Clix.GAMEUI, box) + (comboAge == 0 ? -5 : comboAge == 1 ? 1 : 0);
		int y = clix.y(Clix.GAMEUI, box) + clix.h(Clix.GAMEUI, box);
		for (int i = 0; i < s.length(); i++) {
			clix.sprite(g, ui, Clix.GAMEUI, (s.charAt(i) - 9) * 8, x, y, 3);
			x += 0xb;
		}
	}

	/** g_anMonsStateElAtk_E: u8 per monster id, its elemental attack (0 none). */
	private static final int MONS_EL = 0xc0f94;

	/**
	 * DrawGameUI's target bar: "name Lv" centred in clix_gameui 0x5c's box over a black alpha-3
	 * fill (an elemental monster's box is 12 px wider for its gorder 0x175.. icon), the HP bar at
	 * 0x5d's height, red over #432a1a. The original's small font is drawn with the 16 px one.
	 */
	private void drawTarget(Gfx g) {
		Monster m = target;
		String s = m.kind + " Lv" + m.level;
		int tw = Gfx.stringWidth(s);
		int box = 0x5c * 8;
		int el = so.u8(MONS_EL + m.variant);
		int bx = (480 - (tw + 2)) / 2;
		int bw = tw + (el == 0 ? 2 : 14);
		int by = clix.y(Clix.GAMEUI, box);
		int bh = clix.h(Clix.GAMEUI, box);
		g.fillRect(bx, by, bw, bh, alpha8(3) << 24);
		if (el != 0) {
			int off = (0x175 + (el < 0x50 ? 0 : el < 0xa0 ? 1 : 2)) * 8;
			try {
				// The icon's clix offset is replaced by (4, 2).
				clix.sprite(g, orderSheets().gorder, Clix.GORDER, off, bx + bw - 12 + 4 - clix.x(Clix.GORDER, off),
						by + 2 + 2 - clix.y(Clix.GORDER, off));
			} catch (IOException e) {
				log = "그림 실패: " + e;
			}
		}
		g.drawString(s, bx + 2 + (bw - 4 - tw) / 2, by + 2 + (bh - 4 - 16) / 2, 0xffe8e8e8);
		int w = bw - 4;
		int fill = Math.min(w, w * Math.max(m.hp, 0) / m.maxHp);
		int yb = clix.y(Clix.GAMEUI, 0x5d * 8);
		int hb = clix.h(Clix.GAMEUI, 0x5d * 8);
		if (fill < w) {
			g.fillRect(bx + fill, yb, w - fill, hb, 0xff432a1a);
		}
		if (fill > 0) {
			g.fillRect(bx, yb, fill, hb, 0xffff0000);
		}
	}

	/**
	 * The 2 key: FuncUseItem on the cheapest HP potion held. Its global phase (record +0) applies
	 * the effect, then DelItem takes one. The cooldown (hero+0x388) is not ported.
	 */
	private void drinkPotion() {
		for (int id = POTION; id <= LAST_POTION; id++) {
			if (hero.inv.count(id, -1) > 0) {
				useItem(id);
				return;
			}
		}
		log = "회복 물약이 없다.";
	}

	/** FuncUseItem: a consumable's global phase (record +0) runs, then DelItem takes one. */
	private void useItem(int id) {
		int t = items.type(id);
		if (t != 0xe && t != 0xf) {
			log = items.name(id) + " 사용: 아직 포팅되지 않았습니다";
			return;
		}
		int before = hero.hp;
		events.execPhase(2, items.script(id), true);
		hero.inv.remove(id, 1, -1);
		log = items.name(id) + " 사용. HP +" + (hero.hp - before);
	}

	/** CLEAR in play opens GOrder; only its ITEM tab is ported, so the menu opens there. */
	private void openMenu() {
		try {
			if (itemMenu == null) {
				itemMenu = new ItemMenu(orderSheets(), so, items, heroAnim);
			}
		} catch (IOException e) {
			log = "메뉴 로드 실패: " + e;
			return;
		}
		changeHeroAct(HeroAnim.ACT_STAND);
		itemMenu.open(hero, menuHost);
		menuScreen = itemMenu;
		menuTab = 1;
		menuOpen = true;
	}

	/**
	 * The tab column's up / down: GOrderFuncBack and InitGOrder for the next of STATUS, ITEM,
	 * SKILL, PET, LOG, QUEST and SYSTEM, with the focus left on the tab column. ITEM and QUEST
	 * are ported; the others show their tab and an empty panel.
	 */
	private void switchTab(int dir) {
		menuTab = (menuTab + dir + 7) % 7;
		try {
			if (menuTab == 1) {
				itemMenu.openOnTabs(hero, menuHost);
				menuScreen = itemMenu;
			} else if (menuTab == 5) {
				if (questMenu == null) {
					questMenu = new QuestMenu(orderSheets(), so, items, assets, quests, questPopups);
				}
				questMenu.open(hero, menuHost, false, null);
				menuScreen = questMenu;
			} else {
				if (emptyTab == null) {
					emptyTab = new EmptyTab(orderSheets(), so, items);
				}
				emptyTab.open(hero, menuHost, menuTab);
				menuScreen = emptyTab;
			}
		} catch (IOException e) {
			log = "메뉴 로드 실패: " + e;
		}
	}

	/** Event 42: GOrderFuncUp(8), the town's quest board over today's list; play waits while it is open. */
	private void openBoard(int[] ids) {
		try {
			if (questBoard == null) {
				questBoard = new QuestMenu(orderSheets(), so, items, assets, quests, questPopups);
			}
		} catch (IOException e) {
			log = "게시판 로드 실패: " + e;
			return;
		}
		questBoard.open(hero, menuHost, true, quests.boardList(ids, mapCode));
		menuScreen = questBoard;
		menuOpen = true;
	}

	private OrderScreen.Sheets orderSheets() throws IOException {
		if (orderSheets == null) {
			orderSheets = new OrderScreen.Sheets(assets, so);
		}
		return orderSheets;
	}

	/** Event lo 2: GOrderFuncUp(0xd) with the NPC last talked to behind the counter. */
	private void openShop(int mode, int first, int last) {
		try {
			if (shop == null) {
				shop = new ShopMenu(orderSheets(), so, items, talk, rng);
			}
		} catch (IOException e) {
			log = "상점 로드 실패: " + e;
			return;
		}
		shop.open(hero, menuHost, shopKeeperView, mode, first, last);
		shopOpen = true;
	}

	/** ChangeMonsDir and DrawGOrderMons for the shop's keeper, animated by the shop's own frames. */
	private final ShopMenu.Keeper shopKeeperView = new ShopMenu.Keeper() {
		@Override
		public void face(int dir) {
			if (shopKeeper != null) {
				changeDir(shopKeeper, 1, dir);
			}
		}

		@Override
		public void draw(Gfx g, int x, int y, int w, int h, int frame) {
			Monster m = shopKeeper;
			if (m == null || monsterSprites == null) {
				return;
			}
			int[] e = m.ani >= 0 ? monsterSprites.animEntry(m.variant, m.ani) : null;
			int steps = Math.max(1, e == null ? 1 : e[2]);
			PartSprites.Pose pose = m.ani >= 0 ? monsterSprites.pose(m.variant, m.ani, frame / 2 % steps, m.mirror)
					: monsterSprites.standing(m.variant);
			if (pose == null) {
				return;
			}
			// Clipped to the box's inside, centred across, feet at the centre + 0x1e.
			int[] old = g.getClip();
			g.clipRect(x + 1, y + 1, w - 2, h - 2);
			g.drawImage(pose.image(), x + w / 2 - pose.anchorX(), y + h / 2 + 0x1e - pose.anchorY());
			g.setClip(old);
		}
	};

	/** KeyPressGMenu over the menu first, else KeyPressGOrder. */
	private void menuKey(int code) {
		if (menuGMenu) {
			if (talk.kind == TalkBox.Kind.QUEST || talk.kind == TalkBox.Kind.MESSAGE) {
				// GMenu 0x67, 0x3a5 and 900: ResultGMenu(1) runs menuYes (a delete), any result closes.
				int r = talk.gmenuKey(code);
				if (r >= 0) {
					Runnable yes = menuYes;
					menuGMenu = false;
					menuYes = null;
					talk.clear();
					if (r == 1 && yes != null) {
						yes.run();
					}
				}
				return;
			}
			if (menuYes != null) {
				int r = talk.warningKey(code);
				if (r >= 0) {
					Runnable yes = menuYes;
					menuGMenu = false;
					menuYes = null;
					talk.clear();
					if (r == 1) {
						yes.run();
					}
				}
			} else if (talk.key(code, 0, null, null, null) || code == Keys.ESCAPE || code == Keys.BACK_SPACE) {
				menuGMenu = false;
				talk.clear();
			}
			return;
		}
		if (shopOpen) {
			shop.key(code);
		} else {
			menuScreen.key(code);
		}
	}

	private int potionCount() {
		int n = 0;
		for (int id = POTION; id <= LAST_POTION; id++) {
			n += hero.inv.count(id, -1);
		}
		return n;
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

		boolean tbar = target != null && !events.active() && !menuOpen && !shopOpen && bossGate == null;
		// The original's target bar sits in this top band, so this port's status line gives way to it.
		if (!tbar) {
			int from = hero.levelExp(hero.level);
			String expText = hero.level >= Hero.MAX_LEVEL ? "MAX"
					: (hero.exp - from) + "/" + (hero.levelExp(hero.level + 1) - from);
			// The original bitmap font is wider than the old 12 pt one: double spaces keep this on one line.
			g.drawString("Lv" + hero.level + " (" + expText + "EXP)  HP " + hero.hp + "/" + hero.maxHp
					+ "  ATK " + hero.power() + "  골드 " + hero.inv.gold + "  물약 " + potionCount(), MAP_X, 5, 0xffe6dcc8);
		}
		int foot = MAP_Y + VIEW_H * CELL;
		g.drawString(area.name + "  밝기 " + area.brightness + (area.rainy() ? "  비" : ""),
				MAP_X, foot - 17, 0xffb4d2f0);
		// Below the 480x320 overlay's scaled bottom edge (OVERLAY_H = 277), so phases do not cover them.
		g.drawString(log, MAP_X, OVERLAY_H, 0xfffae696);
		// One line of the 16 px font is all that fits under the log; the map keys (3..[) stay unlisted.
		g.drawString("Enter 대화 1 공격 2 물약 I 가방 F5 저장 F9 불러오기 ESC 종료", MAP_X, OVERLAY_H + 18, 0xffaaa596);
		boolean combo = comboShown > 1 && !menuOpen && !shopOpen && bossGate == null;
		if (events.overlay() || bossGate != null || gmenu != 0 || menuOpen || shopOpen || combo || tbar) {
			// DrawPhase and DrawGMenu work on the original 480x320 screen; fit it like the slot screen.
			Arrays.fill(overlay.px, 0);
			Gfx og = new Gfx(overlay);
			if (tbar) {
				drawTarget(og);
			}
			if (combo) {
				drawCombo(og);
			}
			if (menuOpen || shopOpen) {
				if (shopOpen) {
					shop.draw(og);
				} else {
					menuScreen.draw(og);
				}
				if (menuGMenu) {
					talk.draw(og, 0, true);
				}
			} else if (bossGate != null || gmenu != 0) {
				// A GMenu is not a phase: no fast/slow button.
				talk.draw(og, 0, true);
			} else if (events.overlay()) {
				events.draw(og);
			}
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
		return new int[] { heroX * TILE + TILE / 2 + heroOffX(), heroY * TILE + TILE / 2 - 20 + heroOffY() };
	}

	/** The px the hero has walked toward its next cell, which DrawHeroAnim's position includes. */
	private int heroOffX() {
		return heroPx == 0 ? 0 : DIR_STEP[moveDir()][0] * heroPx;
	}

	private int heroOffY() {
		return heroPx == 0 ? 0 : DIR_STEP[moveDir()][1] * heroPx;
	}

	/** The px a unit has walked toward its script target. */
	private static int unitOffX(Monster m) {
		return m.goX < 0 ? 0 : Integer.compare(m.goX, m.x) * m.goPx;
	}

	private static int unitOffY(Monster m) {
		return m.goX < 0 ? 0 : Integer.compare(m.goY, m.y) * m.goPx;
	}

	/** A unit's target: its position lifted by its kind's height (DAT_01419794, taken as DrawImoticon's 0xc08d4). */
	private int[] unitTarget(Monster m) {
		return new int[] { m.x * TILE + TILE / 2 + unitOffX(m), m.y * TILE + TILE / 2 - unitHeight(m) + unitOffY(m) };
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

	/** The hero as drawn: acts 0xf and 0x10 draw as 9 and 0xa (hero+0x2f). */
	private HeroAnim.Look heroLook() {
		int act = heroAct == 0xf ? 9 : heroAct == 0x10 ? 0xa : heroAct;
		return HeroAnim.wearing(items, hero.inv.worn(), hero.heroClass, heroDir, act);
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
		int hx = ox + heroX * CELL + CELL / 2 + heroOffX() * CELL / TILE;
		int hy = oy + heroY * CELL + CELL / 2 + heroOffY() * CELL / TILE;
		sortUnits();
		for (Object u : unitOrder) {
			if (u == HERO_UNIT) {
				drawHero(g, hx, hy);
			} else {
				drawMonster(g, (Monster) u, ox, oy);
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
		// AddImoticon(hero, 0xc) / DrawImoticonOK: the OK bubble over the hero on a corpse.
		if (okStage >= 0) {
			int ey = hy - 0x28 * CELL / TILE;
			if (okStage < 2) {
				clip(g, okStage, hx, ey);
			} else {
				clip(g, 2, hx, ey);
				clip(g, 0x2a, hx, ey);
				clip(g, (frames & 2) == 0 ? 0x2c : 0x2b, hx, ey);
			}
		}
		drawDamages(g, ox, oy);
		drawGauge(g, ox, oy);
		if (fadeV != 0) {
			int a = alpha8(Math.abs(fadeV)) << 24;
			g.fillRect(MAP_X, MAP_Y, VIEW_W * CELL, VIEW_H * CELL, fadeWhite ? a | 0xffffff : a);
		}
		g.setClip(clip);
	}

	/**
	 * InitUnit and SortUnit: the draw list is the map's units and then the hero, with units that
	 * appear later appended and freed ones dropped; every frame it is insertion-sorted by the
	 * unit's y pixel (+2), so a unit lower on the map draws over one above it and units on the
	 * same line keep the last frame's order.
	 */
	private void sortUnits() {
		if (unitOrder.isEmpty()) {
			unitOrder.addAll(monsters);
			unitOrder.add(HERO_UNIT);
		} else {
			unitOrder.removeIf(u -> u != HERO_UNIT && !monsters.contains(u));
			for (Monster m : monsters) {
				if (!unitOrder.contains(m)) {
					unitOrder.add(m);
				}
			}
		}
		for (int i = 1; i < unitOrder.size(); i++) {
			Object u = unitOrder.get(i);
			int y = unitY(u);
			int j = i;
			while (j > 0 && y < unitY(unitOrder.get(j - 1))) {
				unitOrder.set(j, unitOrder.get(j - 1));
				j--;
			}
			unitOrder.set(j, u);
		}
	}

	/** A unit's y pixel (+2): the hero's or the monster's cell centre plus its offsets, without lift. */
	private int unitY(Object u) {
		if (u == HERO_UNIT) {
			return heroWorldY();
		}
		Monster m = (Monster) u;
		return m.y * TILE + TILE / 2 + unitOffY(m) + m.offY;
	}

	/** DrawUnit for a monster: DrawMonsAnim with its emote and quest mark. */
	private void drawMonster(Gfx g, Monster m, int ox, int oy) {
		// DrawUnit draws a unit only while +0x42 is below 4.
		if (m.fade >= 4) {
			return;
		}
		int cx = ox + m.x * CELL + CELL / 2 + (unitOffX(m) + m.offX) * CELL / TILE;
		// DrawMonsAnim draws a lifted monster (+0x36) higher.
		int cy = oy + m.y * CELL + CELL / 2 + (unitOffY(m) + m.offY - m.lift) * CELL / TILE;
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
		// DrawMonsAnim's emote (+0x2e): the bubble, then sweat, "?" (standing, walking, homing) or "!".
		if (m.emote != 0 && !m.npc) {
			int ey = cy - unitHeight(m) * CELL / TILE;
			clip(g, 0x30, cx, ey);
			clip(g, m.emote < 0 ? 0x33 : m.aniState == 1 || m.aniState == 3 || m.aniState == 0xb ? 0x32 : 0x31, cx, ey);
		}
		// DrawMonsAnim's quest mark (+0x39) over the head while no phase runs, bobbing by
		// {0, 1, 4, 3, 2, 1, 0}[frames % 7] (0xdd4d8).
		if (m.questMark != 0 && !events.active()) {
			int bob = so.u8(0xdd4d8 + frames % 7);
			clip(g, 0x2c + m.questMark, cx, cy - (unitHeight(m) + bob) * CELL / TILE);
		}
	}

	/** DrawUnit for the hero: DrawHeroAnim and this port's HP bar under the feet. */
	private void drawHero(Gfx g, int hx, int hy) {
		if (heroFade >= 4) {
			return;
		}
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

	/**
	 * DrawGameUI's 갈무리 gauge (gameui 0x4b at half strength, 0x4a, then 0x4c.. one per tenth)
	 * 40 px over the hero, kept 7 px inside the view's top; from its 5th frame on.
	 */
	private void drawGauge(Gfx g, int ox, int oy) {
		if (gaugeStart < 0 || gaugeStart >= frames || heroAct != 2 || lootLeft == lootCycles && lootCharge <= 3) {
			return;
		}
		Gsac ui;
		try {
			ui = orderSheets().gameui;
		} catch (IOException e) {
			return;
		}
		int t = lootCycles * 12;
		int k = Math.max(0, Math.min(10, (10 * (frames - gaugeStart + 1) + t / 2) / t));
		int wx = heroWorldX();
		int wy = Math.max((MAP_Y - oy) * TILE / CELL + 7, heroWorldY() - 40);
		uiSprite(g, ui, 0x4b, ox, oy, wx, wy, 0, 2);
		uiSprite(g, ui, 0x4a, ox, oy, wx, wy, 0, 0);
		for (int i = 0; i < k; i++) {
			uiSprite(g, ui, 0x4c + i, ox, oy, wx, wy, 0, 0);
		}
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
			// ChangeMonsDir's side effects: the alert bubble, the flee's counters, the fling counter.
			if (state == 5) {
				m.emote = 18;
			} else if (state == 10) {
				m.fleeFrames = 0;
				m.cnt = 0;
				m.emote = -18;
			} else if (state == 0xf || state == 0x10) {
				m.cnt = 0;
			}
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
		heroMoveDir = -1;
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
		for (Monster m : monsters) {
			m.emote -= Integer.signum(m.emote);
		}
		// DrawGameUI: the target bar shows while frames are drawn with no phase.
		if (target != null && !events.active() && --targetTimer <= 0) {
			target = null;
		}
		if (fadeV != 0 && fadeV < 8) {
			fadeV++;
		}
		if (frozen) {
			return;
		}
		if (heroFade < 4 && heroAnim != null) {
			try {
				HeroAnim.Look look = heroLook();
				if (attackRunning) {
					heroStep = attackShown();
				} else if (heroHeld < heroAnim.delay(look, heroStep)) {
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
		// DrawAndDestroyDamage: MISS goes once frame 4 is drawn, a number once its last digit passes frame 7.
		damages.removeIf(d -> d[4] == Integer.MAX_VALUE ? d[3] > 3 : d[3] - String.valueOf(d[4]).length() > 7);
		for (int[] d : damages) {
			d[3]++;
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
			int nx = heroX + DIR_STEP[heroDir][0];
			int ny = heroY + DIR_STEP[heroDir][1];
			// Against the map edge or terrain CollHeroPath makes no progress, so nothing accumulates
			// either. The script walk's NewCollPath sidestep is left out: scripts walk open ground.
			if (map.inBounds(nx, ny) && !block.terrain(nx, ny)) {
				heroPx += heroDir >= 4 ? 6 : 8;
				if (heroPx >= TILE) {
					heroPx -= TILE;
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
				shopKeeper = m;
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
			target = null;
			// SetPhaseHeroStop: 갈무리 stops with nothing gathered.
			if (heroAct == 2) {
				lootCancel();
			}
			// SetPhaseHeroStop: acts 1, 0x1e and 7 go back to standing; gameTick already keeps the
			// monsters still under a phase. A scripted walk keeps its act 1 [inferred: CalcHeroActEMove owns it].
			if (heroGoX < 0 && (heroAct == HeroAnim.ACT_WALK || heroAct == 0x1e || heroAct == 7)) {
				changeHeroAct(HeroAnim.ACT_STAND);
			}
			// [inferred] a phase also ends a swing and drops a queued attack.
			attackQueued = false;
			if (attackRunning) {
				attackRunning = false;
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
				heroMoveDir = -1;
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
			Monster m = new Monster(x, y, name != null ? name : "#" + id, 1, 0, id, x, y, x, y);
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

		@Override
		public void mapExit(int exit) {
			if (exit < area.portals.size()) {
				// ExitPhase: MoveGameMap(exit, 1), so a boss/regen map asks first here too.
				moveGameMap(area.portals.get(exit), true);
			}
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

		/**
		 * AnalysisPhaseItem hi 1: stacks are forced in, and only a range with no room at all refuses
		 * (result 3). Equipment makes one instance (flags bit 0: grade 0 with sockets, else none) and
		 * with bit 1 is put on at once in its type's slot when the level and the slot allow.
		 */
		@Override
		public boolean giveItem(int id, int count, int flags) {
			Inventory inv = hero.inv;
			int type = items.type(id);
			if (!Item.equipment(type)) {
				int left = inv.add(id, count, 0, true, rng);
				inv.sort();
				if (left < 0) {
					return false;
				}
				log = items.name(id) + " " + (count - left) + "개 획득";
				quests.itemAdded(id, count - left);
				return true;
			}
			int i = inv.add(id, count, (flags & 1) != 0 ? 0 : -1, true, rng);
			if (i < 0) {
				return false;
			}
			Item.Equip e = inv.equip[i];
			inv.sort();
			if ((flags & 2) != 0 && inv.wear(e, Inventory.slotFor(type), hero.level)) {
				hero.recompute(rng);
				log = items.name(id) + " 장착";
			} else {
				log = items.name(id) + " 획득";
			}
			quests.itemAdded(id, count);
			return true;
		}

		@Override
		public void takeItem(int id, int count) {
			hero.inv.remove(id, count, -1);
			hero.recompute(rng);
		}

		@Override
		public void save() {
			GameCore.this.save();
		}

		/**
		 * Kind-4 integers from hero+0xa4: 0 gold (+0xa4), 2 level (+0xac), 3 HP (+0xb0), 4 max HP (+0xb4), 6 max MP
		 * (+0xbc) — InitHeroState writes +0xb4 and +0xbc (max MP = pow112(INT) * 6 >> 4, as
		 * {@link Hero#maxMp}), and a01 sets HP to hero[4] / 5. The maxima are derived values, so
		 * writes to them do not stick; MP (+0xb8) and the rest have no field here and are kept as is.
		 */
		@Override
		public int heroInt(int i) {
			return switch (i) {
				case 0 -> hero.inv.gold;
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
				case 0 -> hero.inv.gold = v;
				case 2 -> hero.level = v;
				case 3 -> hero.hp = Math.min(hero.maxHp, v);
				case 4, 6 -> {
				}
				default -> heroInts[i & 0xff] = v;
			}
		}

		@Override
		public int itemCount(int id) {
			return hero.inv.held(id);
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
			GameCore.this.notPorted(what);
		}

		@Override
		public void openShop(int mode, int first, int last) {
			GameCore.this.openShop(mode, first, last);
		}

		@Override
		public void openBoard(int[] ids) {
			GameCore.this.openBoard(ids);
		}

		@Override
		public void questOffer(int id) {
			GameCore.this.questOffer(id);
		}

		@Override
		public int questFlags(int id) {
			return quests.flagsOf(id);
		}

		@Override
		public int questSlot(int id) {
			Quests.Slot s = quests.find(id);
			return s == null ? -1 : java.util.Arrays.asList(quests.slots).indexOf(s);
		}

		@Override
		public void questTurnIn(int id) {
			quests.turnIn(id);
		}

		@Override
		public void questBagFull() {
			openMessage(0x386);
		}

		@Override
		public int questCondition(int op, int x) {
			return quests.condition(op, x);
		}

		@Override
		public void questPhaseEnd() {
			quests.phaseEnd();
		}
	}

	/**
	 * AnalysisQuest's NPC marks: for each open local type-4 event, the first unit of its NPC (the
	 * byte after its trigger) gets 1 when AnalysisPhaseQuest reaches an offer it could take, 2 a
	 * turn-in of a complete quest, 3 a turn-in of one still open; when the live flags reach
	 * nothing, the walk runs again with every open goal taken as met (offers there count only
	 * for failed quests, turn-ins by the live flags). Variables and hero integers come back after
	 * each event. While a phase runs the marks stay clear.
	 */
	private void questMarks() {
		for (Monster m : monsters) {
			m.questMark = 0;
		}
		if (events.depth() != 0) {
			return;
		}
		EventScript.Vars v = events.vars;
		byte[] u8 = v.u8.clone();
		byte[] two = v.two.clone();
		byte[] one = v.one.clone();
		int[] sys = v.sys.clone();
		int[] ints = heroInts.clone();
		int gold = hero.inv.gold;
		int level = hero.level;
		int hp = hero.hp;
		EventScript.Table t = events.local;
		for (int i = 0; i < t.count(); i++) {
			if (t.status[i] != 0 || t.type(i) != 4) {
				continue;
			}
			Monster m = unit(t.d[t.offs[i] + 1] & 0xff);
			if (m == null) {
				continue;
			}
			int r = events.questProbe(i);
			if (r == Integer.MAX_VALUE) {
				m.questMark |= 1;
			} else if (r < 0) {
				m.questMark |= (quests.slots[~r].flags & 0x40) != 0 ? 2 : 3;
			} else if (r > 0) {
				if ((quests.hypo[r - 1] & 0x80) != 0) {
					m.questMark |= 1;
				}
			} else {
				int[] real = new int[Quests.SLOTS];
				for (int s = 0; s < Quests.SLOTS; s++) {
					real[s] = quests.slots[s].flags;
					if (quests.slots[s].id1 != 0) {
						quests.slots[s].flags = quests.hypo[s];
					}
				}
				r = events.questProbe(i);
				for (int s = 0; s < Quests.SLOTS; s++) {
					quests.slots[s].flags = real[s];
				}
				if (r == Integer.MAX_VALUE) {
					m.questMark |= 1;
				} else if (r < 0) {
					m.questMark |= (real[~r] & 0x40) != 0 ? 2 : 3;
				} else if (r > 0 && (real[r - 1] & 0x80) != 0) {
					m.questMark |= 1;
				}
			}
			System.arraycopy(u8, 0, v.u8, 0, u8.length);
			System.arraycopy(two, 0, v.two, 0, two.length);
			System.arraycopy(one, 0, v.one, 0, one.length);
			System.arraycopy(sys, 0, v.sys, 0, sys.length);
			System.arraycopy(ints, 0, heroInts, 0, ints.length);
			hero.inv.gold = gold;
			hero.level = level;
			hero.hp = hp;
		}
	}

	/** What the quest code reads from the game. */
	private final class QuestLink implements Quests.Host {
		@Override
		public int tick() {
			return playFrames;
		}

		@Override
		public int depth() {
			return events.depth();
		}

		@Override
		public int condition(byte[] d, int p) {
			return events.condition(d, p);
		}

		@Override
		public int param(byte[] d, int p) {
			return events.param(d, p);
		}

		@Override
		public String mapCode() {
			return mapCode;
		}

		@Override
		public String monsterName(int kind) {
			String s = monsterSprites == null ? null : monsterSprites.name(kind);
			return s == null ? "" : s;
		}

		@Override
		public String itemName(int id) {
			return items.name(id);
		}

		@Override
		public int itemType(int id) {
			return items.type(id);
		}

		@Override
		public int[] itemGroup(int id) {
			return items.group(id);
		}

		@Override
		public void headline(byte[] text, boolean wait) {
			if (wait) {
				talk.headline(text, 0, text.length);
			} else {
				talk.headlineNow(text);
			}
		}

		@Override
		public void sysline(byte[] text) {
			// One log line stands in for the sysline; its colour codes are dropped.
			log = Ksc.decode(text, 0, text.length).replaceAll("\u0007.", "").replace('\n', ' ').trim();
		}

		@Override
		public boolean searchPhase() {
			return events.searchPhase();
		}

		@Override
		public void marks() {
			questMarks();
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
			p.setProperty("gold", String.valueOf(hero.inv.gold));
			p.setProperty("caps", hero.inv.capText());
			p.setProperty("equip", hero.inv.equipText());
			p.setProperty("stacks", hero.inv.stackText());
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
			// SaveSlot keeps the frame count the quest deadlines run on, the slots and the state bytes.
			p.setProperty("tick", String.valueOf(playFrames));
			p.setProperty("questSlots", quests.saveSlots());
			p.setProperty("questState", quests.saveState());
			// Units a phase spawned (uid 0x100 + n) live only as long as the scene; map units stay.
			// A corpse is not kept: the dead are gone when the slot loads.
			// The aggressive, idle and state columns of older saves stay as placeholders; facing is the direction.
			p.setProperty("monsters", monsters.stream().filter(m -> m.uid < 0x100 && (m.npc || m.hp > 0))
					.map(m -> String.join(",", String.valueOf(m.x), String.valueOf(m.y), m.kind,
							String.valueOf(m.hp), String.valueOf(m.atk), String.valueOf(m.variant),
							"false", String.valueOf(m.loX), String.valueOf(m.loY),
							String.valueOf(m.hiX), String.valueOf(m.hiY), "false",
							"0", String.valueOf(m.dir), String.valueOf(m.level),
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
			playFrames = Integer.parseInt(p.getProperty("tick", "0"));
			quests.loadSlots(p.getProperty("questSlots", ""));
			quests.loadState(p.getProperty("questState", ""));
			loadMap(p.getProperty("map", MAP_CODES[0]));
			heroX = Integer.parseInt(p.getProperty("x", "10"));
			heroY = Integer.parseInt(p.getProperty("y", "12"));
			readInventory(p, hero.inv);
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
							Integer.parseInt(f[7]), Integer.parseInt(f[8]), Integer.parseInt(f[9]),
							Integer.parseInt(f[10]));
					int dir = Integer.parseInt(f[13]) & 7;
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
						changeDir(m, 1, 1);
					} else {
						m.chaseLen = fight.chaseLen(m.variant);
						changeDir(m, fight.hides(m.variant) ? 0 : 1, dir);
					}
					monsters.add(m);
				}
			}
			log = "불러오기 완료.";
			// LoadSlot loads the map with mode 3: its events reload and SearchPhase runs, with no AnalysisQuest.
			events.loadMap(EventScript.map(assets, mapCode));
			events.searchPhase();
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
		/** +0x2e: the emote bubble's frames, positive "!" or "?", negative sweat. */
		int emote;
		/** +0x3c: armed to decide (chase) or strike (attack); +0x3d the tile centres crossed. */
		boolean trig;
		int cross;
		/** +0x35 failed routes, +0x3e flee (-1 armed), +0x30 flee frames, +0x25 HP thirds. */
		int fails;
		int flee;
		int fleeFrames;
		int hpThird = 2;
		/** +0x46 / +0x48: the tile CalcMonsActPoint heads for; +0x2b the chase length. */
		int tgtX;
		int tgtY;
		int chaseLen;
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
		/** +6 &amp; 8: a named unit (InitMonsNamed). */
		boolean named;
		/** +0x39: AnalysisQuest's mark, 1 a quest to offer, 2 one to report, 3 one in progress. */
		int questMark;
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
		/** Px off the cell centre left by pushes and the death fling (CollMonsPath), and the lift (+0x36). */
		int offX;
		int offY;
		int lift;
		/** +0x4b / +0x4c: the push CalcMons applies next tick (1 normal, 8 crit) and its direction. */
		int pushKind;
		int pushDir;
		/** +0x2d: state 0xf's fling frame, state 9's corpse decay (0..0xa0). */
		int cnt;

		Monster(int x, int y, String kind, int hp, int atk, int variant, int loX, int loY, int hiX, int hiY) {
			this.x = x;
			this.y = y;
			this.kind = kind;
			this.hp = hp;
			this.maxHp = hp;
			this.atk = atk;
			this.variant = variant;
			this.loX = loX;
			this.loY = loY;
			this.hiX = hiX;
			this.hiY = hiY;
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
		// Hold left into t01's exit (column 0, rows 3..22). CalcHeroRun walks 8 px a frame, so the
		// 11 cells from x=11 take 22 frames; frame 1 is caught mid-step. The exit event runs, then
		// MoveGameMap puts the hero on t02's portal 0 at the east edge, same row, facing west.
		key(Keys.LEFT);
		int walkTicks = 0;
		while ("t01".equals(mapCode) && !events.active() && walkTicks < 200) {
			gameTick();
			if (++walkTicks == 1) {
				writeFrame(sink, "walk.png");
			}
		}
		keyUp(Keys.LEFT);
		System.out.println("walk frames=" + walkTicks + " hero=" + heroX + "," + heroY + " dir=" + heroDir);
		int moveTicks = 0;
		String offered = "none";
		while ((events.active() || gmenu != 0) && moveTicks < 8000) {
			if (gmenu == 100 && "none".equals(offered)) {
				// t02's entry event offers main quest 1 (GMenu 100); the next OK takes it.
				offered = String.valueOf(gmenuQuest);
				writeFrame(sink, "quest-offer.png");
			}
			gameTick();
			if (++moveTicks % 12 == 0) {
				key(Keys.ENTER);
			}
		}
		writeFrame(sink, "mapmove.png");
		System.out.println("mapmove map=" + mapCode + " hero=" + heroX + "," + heroY + " dir=" + heroDir
				+ " frames=" + moveTicks + " running=" + events.active() + " offered=" + offered + " slot0="
				+ (quests.slots[0].id1 - 1));
		String t02Vars = vars(events.vars);
		// The original refuses to fight in a town (map+0x2d == 1), so the combat drive goes to a02,
		// the forest north of Ata'mal. Walking a cell at a time does not tick; each swing does.
		loadMap("a02");
		int[][] moves = { { 1, 0 }, { 1, 0 }, { 0, 1 }, { 0, 1 }, { 1, 0 }, { 1, 0 }, { 1, 0 } };
		for (int[] mv : moves) {
			step(mv[0], mv[1]);
		}
		Monster target = nearestMonster();
		int guard = 0;
		while (target != null && guard++ < 400) {
			int dist = Math.abs(target.x - heroX) + Math.abs(target.y - heroY);
			if (dist <= 1) {
				strike(target);
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
				+ " hp=" + hero.hp + "/" + hero.maxHp + " atk=" + hero.power() + " gold=" + hero.inv.gold
				+ " monsters=" + monsters.size() + " log=" + log);
		// LoadGameBlock's terrain on t01 (no monsters): holding right at (27,10) the deep sea stops the
		// hero, with no gap within two cells up or down; holding down at (15,17) the rock at (15,18)
		// is open on both sides, so NewCollPath steps right (down's tie) still facing down, and 4
		// frames end at (16,18).
		loadMap("t01");
		monsters.clear();
		System.out.println("terrain " + holdFrom(27, 10, 3, 6) + " " + holdFrom(15, 17, 1, 4));
		// CollMonsPath on the same grid: a monster in t01's shallow water at (27,9) chases the hero
		// over the deep sea at (28,9) (tile 5). g_anMonsBlock id 1 (0x2b) stops there; id 66 (0x02,
		// no bit 0) and id 124 (0) go on, so the x values read 27 28 28.
		System.out.println("monster-terrain " + chaseFrom(1) + " " + chaseFrom(66) + " " + chaseFrom(124));
		// GMenu 0x74: z01's east exit (portal 4, x 54, rows 6..10) leads to the regen map a11. Facing
		// out of it the warning opens instead of the move; right then OK cancels, and OK on Enter
		// saves and goes in.
		loadMap("z01");
		monsters.clear();
		heroX = 54;
		heroY = 8;
		heroDir = 3;
		takeExit();
		boolean asked = bossGate != null;
		writeFrame(sink, "bossgate.png");
		key(Keys.RIGHT);
		key(Keys.ENTER);
		String stayed = mapCode + (bossGate == null ? "" : "(open)");
		takeExit();
		key(Keys.ENTER);
		System.out.println("bossgate asked=" + asked + " cancel=" + stayed + " enter=" + mapCode);
		// Inventory rules: 150 하급 포션 go whole into an empty slot (AddItem does not cap it at 99);
		// ten more trim that slot to 99 and the 61 left open the next, so slots read 99/61. DelItem
		// takes 100 from the last slot back and SortItem closes the gap: 60/0. A sword goes on in
		// slot 1 but not in slot 3, and the save texts read back the same.
		Inventory inv = new Inventory(items);
		int a = inv.add(POTION, 150, 0, false, rng);
		int b = inv.add(POTION, 10, 0, false, rng);
		String added = inv.stackCount[Inventory.CONSUMABLES] + "/" + inv.stackCount[Inventory.CONSUMABLES + 1];
		int notTaken = inv.remove(POTION, 100, -1);
		String taken = inv.stackCount[Inventory.CONSUMABLES] + "/" + inv.stackCount[Inventory.CONSUMABLES + 1];
		Item.Equip sword = inv.equip[inv.add(OLD_SAVE_WEAPON, 1, 0, true, rng)];
		boolean wrongSlot = inv.wear(sword, 3, Hero.MAX_LEVEL);
		boolean rightSlot = inv.wear(sword, 1, Hero.MAX_LEVEL);
		Inventory back = new Inventory(items);
		back.readCaps(inv.capText());
		back.readEquip(inv.equipText());
		back.readStacks(inv.stackText());
		boolean same = back.equipText().equals(inv.equipText()) && back.stackText().equals(inv.stackText());
		System.out.println("inventory add=" + a + "," + b + " slots=" + added + " left=" + notTaken + " slots=" + taken
				+ " count=" + inv.count(POTION, -1) + " slot3=" + wrongSlot + " slot1=" + rightSlot + " worn="
				+ (inv.worn(1) == sword) + " roundtrip=" + same);
		// FuncUseItem: a 하급 포션 runs global phase 3 (its record +0), whose assignment heals, and
		// then DelItem takes it.
		hero.hp = 1;
		hero.inv.add(POTION, 1, 0, true, rng);
		drinkPotion();
		System.out.println("potion hp=1->" + hero.hp + " left=" + potionCount() + " running=" + events.active());
		// ITEM tab: I opens the menu on the category bar, DOWN enters the slot grid at slot 1 (the
		// worn dagger); its popup's second row, 해제, takes it off. SELECT on the now empty slot lists
		// the bag gear that fits it, and that item's 장착 puts it back. CLEAR three times closes.
		Item.Equip dagger = hero.inv.worn(1);
		// Whatever a11's entry started plays out first: a running phase takes the keys.
		for (int i = 0; i < 600 && events.active(); i++) {
			gameTick();
			if (i % 5 == 0) {
				key(Keys.ENTER);
			}
		}
		key(Keys.I);
		boolean opened = menuOpen;
		key(Keys.DOWN);
		writeFrame(sink, "menu-item.png");
		key(Keys.ENTER);
		key(Keys.DOWN);
		key(Keys.ENTER);
		String off = (hero.inv.worn(1) == null) + "/" + hero.power();
		key(Keys.ENTER);
		key(Keys.ENTER);
		writeFrame(sink, "menu-popup.png");
		key(Keys.ENTER);
		String on = (hero.inv.worn(1) == dagger) + "/" + hero.power();
		key(Keys.BACK_SPACE);
		key(Keys.BACK_SPACE);
		key(Keys.BACK_SPACE);
		// Consumables: RIGHT on the bar picks tab 1, the first stack's popup starts on 사용, GMenu
		// 0xcc's O.확인 runs FuncUseItem, and one potion is gone.
		hero.inv.add(POTION, 1, 0, false, rng);
		int potions = potionCount();
		key(Keys.I);
		key(Keys.RIGHT);
		key(Keys.DOWN);
		key(Keys.ENTER);
		key(Keys.ENTER);
		key(Keys.ENTER);
		String used = potions + "->" + potionCount();
		key(Keys.BACK_SPACE);
		key(Keys.BACK_SPACE);
		key(Keys.BACK_SPACE);
		System.out.println("menu open=" + opened + " unequip=" + off + " wear=" + on + " use=" + used + " closed="
				+ !menuOpen);
		// Shop: in a01 수잔 (unit 4) ends her talk with event lo 2 hi 6, GOrderFuncUp(0xd) in mode 6
		// (the misc goods over a grade range). With 5,000 gold: OK leaves the BUY/SELL bar, a second
		// OK lets go of mode 6's unseen tabs, RIGHT twice reaches the third cell (하급 포션), OK opens
		// 0xca at 1, RIGHT makes 2, OK buys at twice the base each. Back returns to the bar, DOWN
		// switches to SELL, and the first cell sells whole at its base. Back twice closes; the talk
		// then ends.
		loadMap("a01");
		enterEvents();
		for (int i = 0; i < 600 && events.active(); i++) {
			gameTick();
		}
		Monster susan = null;
		for (Monster m : monsters) {
			if (m.npc && m.uid == 4) {
				susan = m;
			}
		}
		heroX = susan.x;
		heroY = susan.y + 1;
		hero.inv.gold = 5000;
		talkToNpc();
		for (int i = 0; i < 600 && events.active() && !shopOpen; i++) {
			gameTick();
			if (i % 5 == 0) {
				key(Keys.ENTER);
			}
		}
		boolean shopped = shopOpen;
		writeFrame(sink, "shop-buy.png");
		int id = so.u16(0x10ba30 + 4);
		int had = hero.inv.count(id, -1);
		key(Keys.ENTER);
		key(Keys.ENTER);
		key(Keys.RIGHT);
		key(Keys.RIGHT);
		key(Keys.ENTER);
		key(Keys.RIGHT);
		writeFrame(sink, "shop-popup.png");
		key(Keys.ENTER);
		String buy = items.name(id) + " " + had + "->" + hero.inv.count(id, -1) + " gold=5000->" + hero.inv.gold
				+ " cost=" + items.cost(id);
		// The last misc cell (23rd: row 3, column 4) is 낡은 단검 at grade 4: 0xcf buys one, bought (+0x1d = 1).
		int gearId = so.u16(0x10ba30 + 2 * 22);
		int goldBefore = hero.inv.gold;
		key(Keys.DOWN);
		key(Keys.DOWN);
		key(Keys.DOWN);
		key(Keys.RIGHT);
		key(Keys.RIGHT);
		key(Keys.ENTER);
		writeFrame(sink, "shop-gear.png");
		key(Keys.ENTER);
		Item.Equip bought = hero.inv.equip[0];
		for (Item.Equip e : hero.inv.equip) {
			if (e != null && e.slot == 0) {
				bought = e;
			}
		}
		String gear = items.name(bought.id) + " grade=" + bought.grade + " origin=" + bought.origin + " paid="
				+ (goldBefore - hero.inv.gold) + " cost=" + items.cost(gearId);
		// SELL: the unworn dagger comes first (0xd0, its base), then the potions (0xcb, the whole stack).
		goldBefore = hero.inv.gold;
		key(Keys.BACK_SPACE);
		key(Keys.DOWN);
		key(Keys.ENTER);
		writeFrame(sink, "shop-sell.png");
		key(Keys.ENTER);
		key(Keys.ENTER);
		int gearSold = hero.inv.gold - goldBefore;
		key(Keys.ENTER);
		key(Keys.ENTER);
		String sell = gearSold + "+" + (hero.inv.gold - goldBefore - gearSold);
		key(Keys.BACK_SPACE);
		key(Keys.BACK_SPACE);
		boolean closed = !shopOpen;
		for (int i = 0; i < 600 && events.active(); i++) {
			gameTick();
			if (i % 5 == 0) {
				key(Keys.ENTER);
			}
		}
		System.out.println("shop open=" + shopped + " buy=" + buy + " gear=" + gear + " sell=" + sell + " closed="
				+ closed + " talkDone=" + !events.active());
		// Melee (InitHeroAttack, CalcHeroAnim, AttackHero): on a02 the hero stands above a monster,
		// facing down with 낡은 단검 (form 1, delays 0 and 3: frames 0,5,3,-1,6). One press: the
		// swing starts the next tick, the hit lands two ticks later (step f2) and the swing ends
		// after D0 + 4 + D1 = 7 drawn frames. Pressing again in each recovery chains acts 9, 0xa,
		// 0xf for a combo of 3. The foe is held standing in its cell until it dies, so it neither
		// strikes back nor drifts out of reach on the pushes, and its dodge is pinned so that no
		// swing misses whatever the monsters drew from the RNG. At 1 HP it flies four frames (state 0xf) the way it faces, lies as a
		// corpse (state 9) and is freed 640 play frames later. A town (a01) and bare hands start no swing.
		loadMap("a02");
		for (int i = 0; i < 600 && events.active(); i++) {
			gameTick();
		}
		Monster foe = nearestMonster();
		heroX = foe.x;
		heroY = foe.y - 1;
		heroPx = 0;
		heroDir = 1;
		foe.hp = 100000;
		foe.dodge = -1000;
		int ticks = 0;
		int hitAt = -1;
		attackKey();
		releaseAttack();
		while (ticks < 40 && (attackQueued || attackRunning)) {
			tickFoeHeld(foe);
			ticks++;
			if (hitAt < 0 && (foe.hp < 100000 || !damages.isEmpty())) {
				hitAt = ticks;
				writeFrame(sink, "melee.png");
			}
		}
		StringBuilder acts = new StringBuilder(Integer.toHexString(lastAttackAct));
		for (int k = 0; k < 2; k++) {
			while (attackStep < attackFrames[1]) {
				tickFoeHeld(foe);
			}
			attackKey();
			releaseAttack();
			tickFoeHeld(foe);
			acts.append(',').append(Integer.toHexString(lastAttackAct));
			while (attackRunning) {
				tickFoeHeld(foe);
			}
		}
		int combo = comboCount;
		foe.hp = 1;
		int startX = monsterPx(foe);
		int startY = monsterPy(foe);
		for (int t = 0; t < 120 && foe.hp > 0; t++) {
			if (!attackQueued && !attackRunning) {
				attackKey();
				releaseAttack();
			}
			tickFoeHeld(foe);
			startX = monsterPx(foe);
			startY = monsterPy(foe);
		}
		int flingTicks = 0;
		while (foe.aniState == 0xf && flingTicks < 20) {
			gameTick();
			flingTicks++;
		}
		int flung = Math.abs(monsterPx(foe) - startX) + Math.abs(monsterPy(foe) - startY);
		int expect = 0;
		for (int n = 0; n < 4; n++) {
			expect += fight.knock(fight.size(foe.variant), attackForm, n)[0] >> 1;
		}
		writeFrame(sink, "corpse.png");
		int gone = 0;
		while (monsters.contains(foe) && gone < 2000) {
			gameTick();
			gone++;
		}
		loadMap("a01");
		attackKey();
		releaseAttack();
		gameTick();
		boolean town = attackRunning;
		loadMap("a02");
		Item.Equip held = hero.inv.worn(1);
		held.slot = 0;
		attackKey();
		releaseAttack();
		gameTick();
		boolean bare = attackRunning;
		held.slot = 1;
		System.out.println("melee swing=" + ticks + " hit=" + hitAt + " acts=" + acts + " combo=" + combo + " fling="
				+ flingTicks + "/" + flung + "of" + expect + "px corpse=" + (foe.aniState == 9) + " freed=" + gone + " town=" + town
				+ " bare=" + bare);
		aiTest(sink);
		lootTest(sink);
		questTest(sink, t02Vars);
		zorderTest(sink);
		new GameCore(assets, storage).writeFrame(sink, "slots-after.png");
	}

	/**
	 * Selftest of SortUnit on a01: an NPC one cell below the hero draws after (over) the hero,
	 * one cell above draws before; stepping onto the NPC's line keeps the last frame's order.
	 */
	private void zorderTest(FrameSink sink) throws IOException {
		Monster npc = null;
		for (Monster m : monsters) {
			if (m.npc && npc == null) {
				npc = m;
			}
		}
		heroPx = 0;
		heroX = npc.x;
		heroY = npc.y - 1;
		sortUnits();
		boolean below = unitOrder.indexOf(npc) > unitOrder.indexOf(HERO_UNIT);
		writeFrame(sink, "zorder.png");
		heroY = npc.y;
		sortUnits();
		boolean tieAfter = unitOrder.indexOf(npc) > unitOrder.indexOf(HERO_UNIT);
		heroY = npc.y + 1;
		sortUnits();
		boolean above = unitOrder.indexOf(npc) < unitOrder.indexOf(HERO_UNIT);
		heroY = npc.y;
		sortUnits();
		boolean tieBefore = unitOrder.indexOf(npc) < unitOrder.indexOf(HERO_UNIT);
		System.out.println("zorder below=" + below + " above=" + above + " tie=" + (tieAfter && tieBefore));
	}

	/**
	 * Selftest of the quest line in t02, from the variables t01 left and quest 1 taken (the
	 * mapmove step plays its offer): a kill turns quest 1 in (death event, opcode 31) and offers
	 * quest 2, whose 100 % quest drop gives 퍽퍽한 고기 from the corpse's 갈무리; that opens ev3's
	 * trigger, and talking to 로만 (ev4) turns quest 2 in: its state byte becomes 1.
	 */
	private void questTest(FrameSink sink, String t02Vars) throws IOException {
		newEvents();
		restoreVars(events.vars, t02Vars);
		quests.reset();
		loadMap("t02");
		enterEvents();
		runPhases(sink, null);
		quests.analyze();
		String marks0 = marks();
		if (hero.inv.worn(1) == null) {
			new Stage().giveItem(297, 1, 2);
		}
		quests.accept(1);
		quests.analyze();
		String offer1 = quests.slots[0].id1 - 1 + "/" + Integer.toHexString(quests.slots[0].flags);
		String marksOpen = marks();
		Monster foe = nearestMonster();
		monsters.removeIf(m -> m != foe && !m.npc);
		heroX = foe.x;
		heroY = foe.y - 1;
		heroPx = 0;
		foe.offX = 0;
		foe.offY = 0;
		foe.hp = 1;
		foe.dodge = -1000;
		strike(foe);
		runPhases(sink, null);
		String after1 = "state1=" + quests.state[1] + " slot0=" + (quests.slots[0].id1 - 1);
		heroX = foe.x;
		heroY = foe.y - 1;
		for (int i = 0; i < 4; i++) {
			gameTick();
		}
		int meatBefore = hero.inv.count(136, -1);
		key(Keys.K1);
		for (int t = 0; t < 200 && heroAct == 2; t++) {
			gameTick();
		}
		keyUp(Keys.K1);
		int meat = hero.inv.count(136, -1);
		boolean ev3 = events.active();
		runPhases(sink, null);
		Monster roman = null;
		for (Monster m : monsters) {
			if (m.npc && m.variant == 202) {
				roman = m;
			}
		}
		heroX = roman.x - 1;
		heroY = roman.y;
		heroPx = 0;
		heroDir = 3;
		String marks1 = marks();
		writeFrame(sink, "quest-mark.png");
		key(Keys.ENTER);
		runPhases(sink, "quest-done.png");
		// Sub slots 1-4 full: offering sub quest 13 shows 예 / 아니오, and 예 finds no slot (GMenu 900).
		for (int id : new int[] { 8, 9, 10, 12 }) {
			quests.accept(id);
		}
		// SaveSlot / LoadSlot keep the slots (kill counts, deadlines) and the state bytes.
		String kept = quests.saveSlots() + "|" + quests.saveState();
		save();
		load();
		boolean roundtrip = kept.equals(quests.saveSlots() + "|" + quests.saveState());
		questOffer(13);
		writeFrame(sink, "quest-sub.png");
		key(Keys.ENTER);
		boolean full = gmenu == 900;
		writeFrame(sink, "quest-full.png");
		key(Keys.ENTER);
		// a01's board (local event 10: IF var 0x368, op 42 over quests 158..174): today's list,
		// 확인 takes the first into FREE slot 5 and closes the board.
		loadMap("a01");
		enterEvents();
		runPhases(sink, null);
		events.vars.set(0x368, 1);
		events.execPhase(EventRunner.LOCAL, 10, true);
		for (int t = 0; t < 400 && !menuOpen && events.active(); t++) {
			gameTick();
			if (t % 12 == 11 && !menuOpen) {
				key(Keys.ENTER);
			}
		}
		boolean board = menuOpen && menuScreen == questBoard;
		writeFrame(sink, "quest-board.png");
		key(Keys.ENTER);
		int free = quests.slots[5].id1 - 1;
		boolean closed = !menuOpen;
		runPhases(sink, null);
		// The menu: ITEM's bar, back to the tab column, down four tabs to QUEST; the rows, the SUB
		// slot's popup, 상세보기, 삭제.
		openMenu();
		key(Keys.ESCAPE);
		for (int i = 0; i < 4; i++) {
			key(Keys.DOWN);
		}
		int tab = menuTab;
		key(Keys.RIGHT);
		key(Keys.DOWN);
		writeFrame(sink, "quest-list.png");
		int sub = quests.slots[1].id1 - 1;
		key(Keys.ENTER);
		key(Keys.ENTER);
		boolean detail = menuGMenu && talk.kind == TalkBox.Kind.QUEST;
		writeFrame(sink, "quest-detail.png");
		key(Keys.ENTER);
		key(Keys.DOWN);
		key(Keys.ENTER);
		boolean ask = menuGMenu && talk.kind == TalkBox.Kind.MESSAGE;
		key(Keys.ENTER);
		int deleted = quests.slots[1].id1 - 1;
		key(Keys.ESCAPE);
		key(Keys.ESCAPE);
		System.out.println("quest slot0=" + offer1 + " " + after1 + " meat=" + meatBefore + "->" + meat + " ev3=" + ev3
				+ " state2=" + quests.state[2] + " slot0=" + (quests.slots[0].id1 - 1) + " full=" + full + "/" + gmenu
				+ " roundtrip=" + roundtrip);
		System.out.println("quest marks=" + marks0 + "/" + marksOpen + " report=" + marks1 + " board=" + board + "/" + free + " closed="
				+ closed + " tab=" + tab + " detail=" + detail + " delete=" + ask + " " + sub + "->" + deleted + " menu="
				+ menuOpen + " a01=" + marks());
	}

	/** Selftest: the marked units as variant:mark. */
	private String marks() {
		StringBuilder b = new StringBuilder();
		for (Monster m : monsters) {
			if (m.questMark != 0) {
				b.append(b.length() == 0 ? "" : ",").append(m.variant).append(':').append(m.questMark);
			}
		}
		return b.length() == 0 ? "-" : b.toString();
	}

	/** Selftest: plays running phases and field GMenus out, OK every 12 frames; true if a GMenu came up. */
	private boolean runPhases(FrameSink sink, String shot) throws IOException {
		boolean menu = false;
		for (int t = 1; t < 4000 && (events.active() || gmenu != 0); t++) {
			if (gmenu != 0 && !menu) {
				menu = true;
				if (shot != null) {
					writeFrame(sink, shot);
				}
			}
			gameTick();
			if (t % 12 == 0) {
				key(Keys.ENTER);
			}
		}
		return menu;
	}

	/** Selftest combat: face an adjacent monster, press attack and play the swing (and any fling) out. */
	private void strike(Monster m) {
		heroDir = Fight.facingToward(monsterPx(m) - heroWorldX(), monsterPy(m) - heroWorldY());
		attackKey();
		releaseAttack();
		for (int i = 0; i < 60 && (attackQueued || attackRunning || m.aniState == 0xf); i++) {
			gameTick();
		}
	}

	/**
	 * Selftest of 갈무리: on a02 the hero kills a lone monster and stands over its corpse, where
	 * the OK bubble pops in (step 2 after three frames). A tap is cancelled within 4 frames: the
	 * decay comes back plus the 2 frames held and nothing is gathered. Held, the attack key
	 * gathers every 12 frames for size + 1 cycles, the corpse fades and is freed, and the bag
	 * holds more of the monster's materials (unless a rune or recipe took the first slot).
	 */
	private void lootTest(FrameSink sink) throws IOException {
		loadMap("a02");
		for (int i = 0; i < 600 && events.active(); i++) {
			gameTick();
		}
		Monster foe = nearestMonster();
		monsters.removeIf(m -> m != foe && !m.npc);
		heroX = foe.x;
		heroY = foe.y - 1;
		heroPx = 0;
		foe.offX = 0;
		foe.offY = 0;
		foe.hp = 1;
		foe.dodge = -1000;
		strike(foe);
		// The corpse flew off: step over to the cell above it.
		heroX = foe.x;
		heroY = foe.y - 1;
		for (int i = 0; i < 4; i++) {
			gameTick();
		}
		int ok = okStage;
		writeFrame(sink, "loot-ok.png");
		int decay = foe.cnt;
		key(Keys.K1);
		boolean started = heroAct == 2;
		gameTick();
		gameTick();
		keyUp(Keys.K1);
		boolean cancelled = started && heroAct != 2 && foe.cnt == decay + 2 && foe.fade == 0;
		int before = materials(foe.variant);
		key(Keys.K1);
		int ticks = 0;
		while (heroAct == 2 && ticks < 200) {
			gameTick();
			if (++ticks == 8) {
				writeFrame(sink, "loot.png");
			}
		}
		String got = log;
		int after = materials(foe.variant);
		keyUp(Keys.K1);
		int freed = 0;
		while (monsters.contains(foe) && freed < 20) {
			gameTick();
			freed++;
		}
		System.out.println("loot ok=" + ok + " cancel=" + cancelled + " cycles=" + lootCycles + " ticks=" + ticks
				+ " items=" + before + "->" + after + " freed=" + freed + " log=" + got);
	}

	/** Selftest: how many of a monster id's materials, of any tier, the bag holds. */
	private int materials(int id) {
		int d = so.u16(MONS_DROP + id * 2);
		int n = 0;
		for (int j = 0; j < d >> 12; j++) {
			for (int t = 0; t < 4; t++) {
				n += hero.inv.count(so.u16(MONS_ITEM + ((d & 0xfff) + j) * 2) + t, -1);
			}
		}
		return n;
	}

	/** Selftest: a tick with {@code foe}, while alive, put back standing at its cell centre so it neither strikes nor drifts. */
	private void tickFoeHeld(Monster foe) {
		int x = foe.x;
		int y = foe.y;
		gameTick();
		if (foe.hp > 0) {
			foe.x = x;
			foe.y = y;
			foe.offX = 0;
			foe.offY = 0;
			changeDir(foe, 1, foe.dir);
		}
	}

	/**
	 * Selftest of CalcMonsAct: on a02 the hero stands three cells in front of a monster. It sees
	 * the hero (alert "!" or straight to battle), chases to its attack side, attacks, and its
	 * strike lands or misses; a landed strike takes the target bar. Then, with the hero gone far
	 * away, every monster ends inside its patrol window or heads home (state 0xb).
	 */
	private void aiTest(FrameSink sink) throws IOException {
		loadMap("a02");
		for (int i = 0; i < 600 && events.active(); i++) {
			gameTick();
		}
		hero.hp = hero.maxHp;
		Monster foe = nearestMonster();
		heroX = foe.x + DIR_STEP[foe.dir & 3][0] * 3;
		heroY = foe.y + DIR_STEP[foe.dir & 3][1] * 3;
		heroPx = 0;
		int seen = -1;
		int chase = -1;
		int attack = -1;
		int struck = -1;
		int flinch = -1;
		int hp0 = hero.hp;
		for (int t = 1; t <= 300 && (struck < 0 || flinch < 0); t++) {
			gameTick();
			int st = foe.aniState;
			if (seen < 0 && (st == 5 || st == 2)) {
				seen = t;
				writeFrame(sink, "ai-alert.png");
			}
			if (chase < 0 && st == 4) {
				chase = t;
			}
			if (attack < 0 && st == 6) {
				attack = t;
			}
			if (struck < 0 && (hero.hp < hp0 || !damages.isEmpty() && damages.get(damages.size() - 1)[2] == 0x40)) {
				struck = t;
				writeFrame(sink, "ai-hit.png");
			}
			if (flinch < 0 && heroAct == 3) {
				flinch = t;
				writeFrame(sink, "ai-flinch.png");
			}
			hero.hp = Math.max(hero.hp, 1);
		}
		boolean targeted = target == foe;
		int hp1 = hero.hp;
		hero.hp = hero.maxHp;
		heroX = 0;
		heroY = 0;
		int home = 0;
		for (int t = 0; t < 1500; t++) {
			gameTick();
		}
		for (Monster m : monsters) {
			if (!m.npc && (inWindow(m) || m.aniState == 0xb)) {
				home++;
			}
		}
		System.out.println("ai seen=" + seen + " chase=" + chase + " attack=" + attack + " struck=" + struck + " hp="
				+ hp0 + "->" + hp1 + " flinch=" + flinch + " target=" + targeted + " home=" + home + "/"
				+ monsters.stream().filter(m -> !m.npc).count());
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
			if (targetable(m) && d < bestDist) {
				best = m;
				bestDist = d;
			}
		}
		return best;
	}

	/**
	 * Selftest: puts the hero on a cell facing {@code dir} and holds that direction through CalcHeroRun
	 * alone (no events, monsters or random draws) for some frames; reports where the hero ends.
	 */
	private String holdFrom(int x, int y, int dir, int frames) {
		heroX = x;
		heroY = y;
		heroDir = dir;
		heroPx = 0;
		heroMoveDir = -1;
		hold(dir);
		for (int i = 0; i < frames; i++) {
			calcHeroRun();
		}
		heldDir = -1;
		return x + "," + y + "->" + heroX + "," + heroY + " dir=" + heroDir;
	}

	/** Selftest: a monster of {@code id} at (27,9) takes a 16 px step right toward the hero at (29,9); its x. */
	private int chaseFrom(int id) {
		monsters.clear();
		Monster m = new Monster(27, 9, "#" + id, 1, 0, id, 25, 7, 29, 11);
		monsters.add(m);
		heroX = 29;
		heroY = 9;
		moveMonster(m, 3, 32, false);
		return m.x;
	}
}

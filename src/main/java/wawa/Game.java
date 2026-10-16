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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * Vertical slice of wild.apk: the ARM world map (assets/img/map.png, GSAC raw image)
 * with grid movement, monsters, turn-based combat and a local save file.
 * Combat mirrors libwawa.so: charge() grants the item then writes the slot.
 */
public final class Game extends JFrame {

	private static final int SCALE = 2;
	private static final int MAP_W = 400;
	private static final int MAP_H = 320;
	private static final int CELL = 20;
	private static final int COLS = MAP_W / CELL;
	private static final int ROWS = MAP_H / CELL;
	private static final int MAP_X = 8;
	private static final int MAP_Y = 26;

	private final BufferedImage worldMap;
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
	private HeroSprite heroSprite;
	private MonsterStats monsterStats;
	private int heroX = 10;
	private int heroY = 12;
	private int heroMaxHp = 42;
	private int heroHp = 42;
	/** Hero level; max HP and XP requirement come from the original level curves. */
	private int heroLevel = 1;
	private int heroExp;
	/** Combat fields the native build keeps in the hero record: scale, base attack, evasion. */
	private int heroAtkScale = 1000;
	private int heroBaseAttack = 40;
	private int heroEvade = 200;
	/** Phase state mirrored from InitPhase/CalcGamePlay: 8-slot queue, pause flag, cooldown. */
	private final PhaseQueue phases = new PhaseQueue();
	private boolean phasePaused;
	private int pendingSteps;
	private int menuCooldown;
	private int heroAtk = 14;
	private int gold = 300;
	private int potions = 3;
	private String log = "월드맵 진입. 방향키/클릭으로 이동.";

	public Game() throws IOException {
		super("Wawa - wild.apk Java port");
		Path assets = assetsDir();
		Gsac mapGsac = Gsac.load(assets.resolve("img/map.png"));
		worldMap = mapGsac.rawImage(MAP_W);
		Gsac face = Gsac.load(assets.resolve("img/face00.png"));
		heroPortrait = face.frame(0);

		// Map code a01 = assets/eng/map/a.png, language 01, decoded by ReadMapFile/LoadGameMap.
		loadMap(MAP_CODES[0]);

		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);


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
		});
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				onClick(e);
			}
		});

		// CalcGamePlay runs on the native 62 ms timer with this order:
		// InitHeroState -> CalcHeroAct -> CalcMons -> CalcHeroRun -> Post*Value -> phase pop.
		javax.swing.Timer loop = new javax.swing.Timer(62, e -> {
			gameTick();
			repaint();
		});
		loop.start();
	}

	/** Loads one of the shipped maps by its 3-character code, as LoadGameMap does. */
	private void loadMap(String code) throws IOException {
		assetsRoot = assetsDir();
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
			try {
				heroSprite = HeroSprite.load(assetsRoot);
			} catch (IOException e) {
				heroSprite = null;
			}
		}
		heroX = map.width / 2;
		heroY = map.height / 2;
		monsters.clear();
		spawnMonsters();
		log = area.name + " (" + map.width + "x" + map.height + ") 진입.";
	}

	/** One CalcGamePlay tick in the original order. */
	private void gameTick() {
		if (phasePaused) {
			return;
		}
		initHeroState();
		calcHeroAct();
		monsterTick();
		calcHeroRun();
		postValues();
		for (Monster m : monsters) {
			if (m.atkOnce && (m.x != heroX || m.y != heroY)) {
				m.atkOnce = false;
			}
		}
		if (pendingSteps > 0) {
			pendingSteps--;
			if (pendingSteps == 0) {
				PhaseQueue.Step s = phases.pop();
				if (PhaseQueue.contains(s, heroX, heroY)) {
					log = "현재 구역이 끝났다.";
				}
				menuCooldown = 720;
			}
		}
	}

	/** InitHeroState: the original recomputes the hero's derived stats every tick. */
	private void initHeroState() {
		heroMaxHp = 40 + HeroCurves.pow112(heroLevel) / 8;
		heroBaseAttack = 8 + HeroCurves.pow112(heroLevel) / 16;
		heroEvade = 120 + heroLevel * 10;
		if (heroHp > heroMaxHp) {
			heroHp = heroMaxHp;
		}
	}

	/** CalcHeroAct: resolve a queued step that reaches the hero. */
	private void calcHeroAct() {
		PhaseQueue.Step s = phases.peek();
		if (s == null) {
			return;
		}
		if (PhaseQueue.contains(s, heroX, heroY) && pendingSteps == 0) {
			phases.pop();
			log = "퀘스트 단계를 완료했다.";
		}
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

	private static Path assetsDir() {
		String arg = System.getProperty("wawa.assets", "assets");
		return Path.of(arg);
	}

	/**
	 * LoadMonsToMap: one monster per record of the area's .mbk. Position, facing, name and
	 * sprite always come from the shipped data; HP, attack and critical chance come from the
	 * stat tables too wherever the id has a row, and fall back to this port's tiers otherwise.
	 */
	private void spawnMonsters() {
		int[] hpTier = { 30, 45, 60, 80, 140 };
		int[] atkTier = { 6, 9, 12, 15, 24 };
		for (MonsterTable.Record r : monsterTable.records) {
			boolean known = monsterStats != null && monsterStats.has(r.monsterId);
			int tier = Math.min(hpTier.length - 1, (r.monsterId % 210) / 42);
			int hp = known ? monsterStats.hp(r.monsterId) : hpTier[tier];
			int atk = known ? monsterStats.attack(r.monsterId) : atkTier[tier];
			boolean aggressive = tier >= 2;
			String name = monsterSprites == null ? null : monsterSprites.name(r.monsterId);
			Monster m = new Monster(r.cellX, r.cellY, name != null ? name : "몬스터#" + r.monsterId,
					hp, atk, r.monsterId, aggressive, Math.max(0, r.cellX - 2),
					Math.max(0, r.cellY - 2), r.cellX + 2, r.cellY + 2);
			m.facing = r.direction;
			m.critResist = known ? monsterStats.critical(r.monsterId) : tier * 60;
			m.guarded = tier >= 3;
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
			if (m.hp <= 0) {
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
		Combat.Result r = Combat.strike(1000, 0, m.atk, 0, 0, heroEvade, false, true,
				rng.nextInt(1000), rng.nextInt(1000));
		heroHp -= r.damage();
		log = m.kind + "에게 " + r.damage() + " 피해를 입었다." + (r.missed() ? " 빗나감." : "");
		if (heroHp <= 0) {
			heroHp = heroMaxHp;
			gold = Math.max(0, gold - 50);
			heroX = 10;
			heroY = 12;
			log = "쓰러졌다. 골드 50을 잃고 마을로 복귀.";
		}
	}

	private void onKey(int code) {
		switch (code) {
			case KeyEvent.VK_LEFT, KeyEvent.VK_A -> step(-1, 0);
			case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> step(1, 0);
			case KeyEvent.VK_UP, KeyEvent.VK_W -> step(0, -1);
			case KeyEvent.VK_DOWN, KeyEvent.VK_S -> step(0, 1);
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
		if (target != null && Math.abs(gx - heroX) <= 1 && Math.abs(gy - heroY) <= 1) {
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
			if (d <= 1 && d < bestDist) {
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
		Combat.Result r = Combat.strike(heroAtkScale, 0, heroBaseAttack, 0, m.critResist, heroEvade,
				false, m.guarded, rng.nextInt(1000), rng.nextInt(1000));
		int dealt = r.damage();
		m.hp -= dealt;
		log = m.kind + "에게 " + dealt + " 피해." + (r.crit() ? " 크리티!" : "") + (r.missed() ? " 빗나감." : "");
		if (m.hp <= 0) {
			monsters.remove(m);
			int reward = 20 + rng.nextInt(40);
			gold += reward;
			gainExp(reward);
			if (rng.nextInt(3) == 0) {
				potions++;
			}
			log += " 처치. 골드 +" + reward;
		} else {
			Combat.Result back = Combat.strike(1000, 0, m.atk, 0, 0, 0, false, true,
					rng.nextInt(1000), 1000);
			heroHp -= back.damage();
			log += " 반격 " + back.damage() + (back.missed() ? " (회피)" : "") + ".";
			if (heroHp <= 0) {
				heroHp = heroMaxHp;
				gold = Math.max(0, gold - 50);
				heroX = 10;
				heroY = 12;
				log = "쓰러졌다. 골드 50을 잃고 마을로 복귀.";
			}
		}
	}

	/**
	 * Awards XP against the original requirement curve and recomputes max HP / ATK from
	 * CalcHeroPow112 exactly as the native build does, so levels grow on the same numbers.
	 */
	private void gainExp(int amount) {
		heroExp += amount;
		while (heroExp >= HeroCurves.pow088(heroLevel)) {
			heroExp -= HeroCurves.pow088(heroLevel);
			heroLevel++;
			heroMaxHp = 40 + HeroCurves.pow112(heroLevel) / 8;
			heroAtk = 6 + heroLevel * 2 + HeroCurves.pow112(heroLevel) / 64;
			heroHp = heroMaxHp;
			log += " 레벨 " + heroLevel + " 달성!";
		}
	}

	private void drinkPotion() {
		if (potions <= 0) {
			log = "회복 물약이 없다.";
			return;
		}
		potions--;
		int healed = Math.min(40, heroMaxHp - heroHp);
		heroHp += healed;
		log = "물약 사용. HP +" + healed;
	}

	private void draw(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setColor(new Color(10, 10, 14));
		g.fillRect(0, 0, getWidth(), getHeight());

		drawTiles(g);
		drawMinimap(g);

		g.drawImage(heroPortrait, 2, 2, 22, 22, null);
		g.setFont(new Font("SansSerif", Font.PLAIN, 12));
		g.setColor(new Color(230, 220, 200));
		g.drawString("Lv" + heroLevel + " (" + heroExp + "/" + HeroCurves.pow088(heroLevel) + "EXP)   HP "
				+ heroHp + "/" + heroMaxHp + "   ATK " + heroAtk + "   골드 " + gold + "   물약 " + potions,
				28, 16);
		int foot = MAP_Y + VIEW_H * CELL;
		g.setColor(new Color(180, 210, 240));
		g.drawString(area.name + "  밝기 " + area.brightness + (area.rainy() ? "  비" : ""),
				MAP_X, foot - 4);
		g.setColor(new Color(250, 230, 150));
		g.drawString(log, MAP_X, foot + 14);
		g.setColor(new Color(170, 165, 150));
		g.drawString("[방향키] 이동  [클릭] 이동/전투  [1] 공격  [2] 물약  [F5] 저장  [F9] 불러오기"
				+ "  [3~` ] 12개 지형 전환  [ESC] 종료",
				MAP_X, foot + 30);
	}

	/** Draws the tile grid with a camera centred on the hero, using the DrawTile cell rule. */
	private void drawTiles(Graphics2D g) {
		int camX = Math.max(0, Math.min(map.width - VIEW_W, heroX - VIEW_W / 2));
		int camY = Math.max(0, Math.min(map.height - VIEW_H, heroY - VIEW_H / 2));
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
			int px = MAP_X + (m.x - camX) * CELL;
			int py = MAP_Y + (m.y - camY) * CELL;
			if (m.x < camX || m.y < camY || m.x >= camX + VIEW_W || m.y >= camY + VIEW_H) {
				continue;
			}
			// InitMonsOne anchors the sprite at the cell centre, and the engine draws it at its
			// own size on a 16px grid, so the view scale carries over.
			BufferedImage spr = monsterSprites == null ? null : monsterSprites.standing(m.variant);
			if (spr != null) {
				int w = Math.max(1, spr.getWidth() * CELL / TILE);
				int h = Math.max(1, spr.getHeight() * CELL / TILE);
				g.drawImage(spr, px + CELL / 2 - w / 2, py + CELL / 2 - h / 2, w, h, null);
			} else {
				g.setColor(m.color());
				g.fillOval(px + 4, py + 4, CELL - 8, CELL - 8);
				g.setColor(Color.BLACK);
				g.drawOval(px + 4, py + 4, CELL - 8, CELL - 8);
			}
			bar(g, px + 2, py + CELL - 5, (float) m.hp / m.maxHp, new Color(220, 60, 60));
		}
		int hx = MAP_X + (heroX - camX) * CELL;
		int hy = MAP_Y + (heroY - camY) * CELL;
		BufferedImage hero = heroSprite == null ? null : heroSprite.standing();
		if (hero != null) {
			// Same anchoring and view scale as the monsters: the engine draws the part at its own
			// size on the 16px grid.
			int w = Math.max(1, hero.getWidth() * CELL / TILE);
			int h = Math.max(1, hero.getHeight() * CELL / TILE);
			g.drawImage(hero, hx + CELL / 2 - w / 2, hy + CELL / 2 - h / 2, w, h, null);
		} else {
			g.setColor(new Color(250, 210, 90));
			g.fillOval(hx + 3, hy + 3, CELL - 6, CELL - 6);
			g.setColor(Color.BLACK);
			g.drawOval(hx + 3, hy + 3, CELL - 6, CELL - 6);
		}
		bar(g, hx, hy + CELL - 5, (float) heroHp / heroMaxHp, new Color(80, 220, 120));
	}

	/** Deterministic stand-in shading derived from the real cell byte. */
	private static Color groundColour(int cell) {
		if (cell == 0xff) {
			return new Color(18, 14, 10);
		}
		int v = (cell & 0x3f) * 4;
		return new Color(46 + v / 2, 40 + v / 3, 30 + v / 4);
	}

	/** The ARMOKAR world map stays on screen as a locator, with the hero and monsters marked. */
	private void drawMinimap(Graphics2D g) {
		int w = 120;
		int h = w * MAP_H / MAP_W;
		int x = getWidth() - w - 6;
		int y = MAP_Y;
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRect(x - 2, y - 2, w + 4, h + 4);
		g.drawImage(worldMap, x, y, w, h, null);
		g.setColor(new Color(0, 0, 0, 120));
		for (Monster m : monsters) {
			g.fillRect(x + m.x * w / map.width - 1, y + m.y * h / map.height - 1, 2, 2);
		}
		g.setColor(Color.RED);
		g.fillRect(x + heroX * w / map.width - 2, y + heroY * h / map.height - 2, 4, 4);
		g.setColor(Color.WHITE);
		g.drawRect(x, y, w, h);
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
			p.setProperty("x", String.valueOf(heroX));
			p.setProperty("y", String.valueOf(heroY));
			p.setProperty("hp", String.valueOf(heroHp));
			p.setProperty("gold", String.valueOf(gold));
			p.setProperty("potions", String.valueOf(potions));
			p.setProperty("level", String.valueOf(heroLevel));
			p.setProperty("exp", String.valueOf(heroExp));
			p.setProperty("monsters", monsters.stream()
					.map(m -> String.join(",", String.valueOf(m.x), String.valueOf(m.y), m.kind,
							String.valueOf(m.hp), String.valueOf(m.atk), String.valueOf(m.variant),
							String.valueOf(m.aggressive), String.valueOf(m.loX), String.valueOf(m.loY),
							String.valueOf(m.hiX), String.valueOf(m.hiY), String.valueOf(m.idle),
							String.valueOf(m.state), String.valueOf(m.facing)))
					.reduce((a, b) -> a + ";" + b).orElse(""));
			try (OutputStream os = Files.newOutputStream(saveFile())) {
				p.store(os, "wawa save");
			}
			log = "저장 완료: " + saveFile();
		} catch (IOException e) {
			log = "저장 실패: " + e;
		}
	}

	private void load() {
		if (!Files.exists(saveFile())) {
			log = "저장 파일이 없다.";
			return;
		}
		try {
			Properties p = new Properties();
			try (InputStream is = Files.newInputStream(saveFile())) {
				p.load(is);
			}
			heroX = Integer.parseInt(p.getProperty("x", "10"));
			heroY = Integer.parseInt(p.getProperty("y", "12"));
			heroHp = Integer.parseInt(p.getProperty("hp", "120"));
			gold = Integer.parseInt(p.getProperty("gold", "300"));
			potions = Integer.parseInt(p.getProperty("potions", "3"));
			heroLevel = Integer.parseInt(p.getProperty("level", "1"));
			heroExp = Integer.parseInt(p.getProperty("exp", "0"));
			heroMaxHp = 40 + HeroCurves.pow112(heroLevel) / 8;
			heroAtk = 6 + heroLevel * 2 + HeroCurves.pow112(heroLevel) / 64;
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
					monsters.add(m);
				}
			}
			log = "불러오기 완료.";
		} catch (IOException | NumberFormatException e) {
			log = "불러오기 실패: " + e;
		}
	}

	private static Path saveFile() {
		return Path.of(System.getProperty("wawa.save", "wawa-save.properties"));
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
		/** Per-mille crit resistance read from the monster record at +0x3e. */
		int critResist;
		/** Mirrors the +0x1d flag: unset means the target takes half damage. */
		boolean guarded;

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
			Game g = new Game();
			g.selfTest(new java.io.File("out/selftest.png"));
			System.exit(0);
		}
		Game g = new Game();
		g.setLocationRelativeTo(null);
		g.setVisible(true);
	}

	/** Drives scripted input and writes the resulting frame, so behaviour is verifiable without GUI automation. */
	void selfTest(java.io.File shot) throws IOException {
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
			if (heroLevel > 3) {
				break;
			}
			target = nearestMonster();
		}
		save();
		load();
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
				VIEW_W * CELL + 16, MAP_Y + VIEW_H * CELL + 46, java.awt.image.BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		draw(g2);
		g2.dispose();
		Files.write(shot.toPath(), toPng(img));
		System.out.println("hero=" + heroX + "," + heroY + " lv=" + heroLevel + " exp=" + heroExp
				+ " hp=" + heroHp + "/" + heroMaxHp + " atk=" + heroAtk + " gold=" + gold
				+ " monsters=" + monsters.size() + " log=" + log);
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

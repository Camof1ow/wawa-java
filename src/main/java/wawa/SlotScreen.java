package wawa;

import java.io.IOException;

/**
 * The scene a game starts on: the three save slots around the camp (InitSelectSlot,
 * DrawSelectSlot, DrawPopupSlot, KeyPressSelectSlot) and the new-game panel an empty slot opens
 * (GOrder 7: KeyPressGOrderNewGame and its draw routine).
 *
 * <p>Everything is laid out on the original 480x320 screen. Rectangles and sprite placements come
 * from the static tables clix_gorder (0xe3da4) and clix_gmenu (0xe3b3c); text from g_aJobName,
 * g_aJobSummary, g_aDifficulty and g_astrProp1st, read out of libwawa.so.
 *
 * <pre>
 * background   map a13, camera centred on (0x1d0, 0xa8)                     KeyPressTitle / DrawSelectSlot
 * slot heroes  map (0x1a8 + 0x30 * i, 0xb0), the middle one at 0xa8          InitSelectSlot
 * cursor       gorder frame 88 at clix_gorder[0x16c + slot]                  DrawSelectSlot
 * popup        name (147,108), MODE (199,138), PLAY TIME (199,150),
 *              LEVEL (199,162), class icon clix_gorder[0x16f + class],
 *              all shifted by ((slot - 1) * 16, slot == 1 ? 100 : 108)       DrawPopupSlot
 * new game     |NAME row (150,20,220,20), |SPECIALTY row (150,50,190,20),
 *              class name (239,50,90,20), arrows gorder 62/63, class icon
 *              gorder 23+c at (350,50), stats (300,80+18i,70,16),
 *              summary (145,180,230,80)                                     GOrder 7 draw
 * </pre>
 */
final class SlotScreen {

	static final int W = 480;
	static final int H = 320;
	static final int SLOTS = 3;
	/** The name buffer the hero record keeps is 0xb bytes including the terminator. */
	private static final int NAME_BYTES = 10;
	private static final int WHITE = 0xffffffff;

	private static final int JOB_NAME = 0xe5a88;
	private static final int JOB_SUMMARY = 0xe5b18;
	private static final int DIFFICULTY = 0xe5a80;
	private static final int PROP_NAME = 0xe5c98;
	private static final int CAMERA_X = 0x1d0 - W / 2;
	private static final int CAMERA_Y = 0xa8 - H / 2;

	/**
	 * What a slot file says about its hero, as InitSelectSlot copies it into the 0x28-byte record;
	 * {@code look} is that record's drawing part (kind, looks, colours).
	 */
	record Summary(String name, int heroClass, int level, int mode, int playTime, HeroAnim.Look look) {
	}

	/** What the caller has to do after a key. */
	sealed interface Action {
		record Continue(int slot) implements Action {
		}

		record NewGame(int slot, String name, int heroClass) implements Action {
		}

		record Delete(int slot) implements Action {
		}

		record Quit() implements Action {
		}
	}

	private enum Phase { SELECT, MENU, NEW_GAME, TYPING }

	private final ElfImage elf;
	private final MapData map;
	private final Tiles tiles;
	private final Gsac gorder;
	private final Gsac imenu;
	private final Item items;
	private final HeroAnim heroes;
	/** Frames drawn so far; hero animations advance on it. */
	private int ticks;
	/** DrawGOrderHero's preview act (+7): standing or walking, re-rolled every 16 frames. */
	private int previewAct = HeroAnim.ACT_STAND;
	/** Seeded so the selftest's frames stay the same from run to run. */
	private final java.util.Random previewRand = new java.util.Random(0);
	private final Summary[] slots;
	private int slot;
	private Phase phase = Phase.SELECT;
	/** DrawSlotSelect's list: 0 continue, 1 delete. */
	private int menuIndex;
	/** New-game panel: row 0 name, row 1 class. */
	private int row;
	private int heroClass;
	private String name = "";
	private String notice = "";

	SlotScreen(Assets assets, ElfImage elf, Item items, Summary[] slots) throws IOException {
		this.elf = elf;
		this.items = items;
		this.heroes = new HeroAnim(assets, elf);
		this.slots = slots;
		this.map = MapData.load(assets, "assets/eng/map/a.png", "a13");
		this.tiles = Tiles.load(assets, map);
		this.gorder = Gsac.loadCompanion(assets, "assets/img/gorder.png");
		this.imenu = Gsac.loadCompanion(assets, "assets/img/imenu.png");
	}

	int slot() {
		return slot;
	}

	void slotDeleted(int index) {
		slots[index] = null;
		phase = Phase.SELECT;
	}

	/** keyPressed; returns null when the screen handled the key itself. */
	Action key(int code) {
		notice = "";
		switch (phase) {
			case SELECT -> {
				switch (code) {
					case Keys.LEFT -> slot = (slot + SLOTS - 1) % SLOTS;
					case Keys.RIGHT -> slot = (slot + 1) % SLOTS;
					case Keys.UP, Keys.DOWN -> {
						if (slots[slot] != null) {
							phase = Phase.MENU;
							menuIndex = 0;
						}
					}
					case Keys.ENTER, Keys.SPACE -> {
						return open();
					}
					case Keys.DELETE -> {
						if (slots[slot] != null) {
							phase = Phase.MENU;
							menuIndex = 1;
						}
					}
					case Keys.ESCAPE -> {
						return new Action.Quit();
					}
					default -> {
					}
				}
			}
			case MENU -> {
				switch (code) {
					case Keys.UP, Keys.DOWN -> menuIndex ^= 1;
					case Keys.ESCAPE -> phase = Phase.SELECT;
					case Keys.ENTER, Keys.SPACE -> {
						phase = Phase.SELECT;
						return menuIndex == 0 ? open() : new Action.Delete(slot);
					}
					default -> {
					}
				}
			}
			case NEW_GAME -> {
				switch (code) {
					case Keys.UP -> row = (row + 1) % 2;
					case Keys.DOWN -> row = (row + 1) % 2;
					case Keys.LEFT -> {
						if (row == 1) {
							heroClass = (heroClass + 2) % 3;
						}
					}
					case Keys.RIGHT -> {
						if (row == 1) {
							heroClass = (heroClass + 1) % 3;
						}
					}
					case Keys.ENTER, Keys.SPACE -> {
						if (row == 0) {
							phase = Phase.TYPING;
						} else if (name.isBlank()) {
							// KeyPressGOrderNewGame refuses an all-space name and opens popup 0x389.
							notice = "이름을 입력하세요.";
							row = 0;
						} else {
							phase = Phase.SELECT;
							return new Action.NewGame(slot, name.strip(), heroClass);
						}
					}
					case Keys.ESCAPE -> phase = Phase.SELECT;
					default -> {
					}
				}
			}
			case TYPING -> {
				switch (code) {
					case Keys.ENTER, Keys.ESCAPE -> {
						phase = Phase.NEW_GAME;
						row = 1;
					}
					case Keys.BACK_SPACE -> {
						if (!name.isEmpty()) {
							name = name.substring(0, name.length() - 1);
						}
					}
					default -> {
					}
				}
			}
		}
		return null;
	}

	/** keyTyped, only used while the name is being typed. */
	void typed(char c) {
		if (phase != Phase.TYPING || Character.isISOControl(c)) {
			return;
		}
		String next = name + c;
		if (Ksc.encode(next).length <= NAME_BYTES) {
			name = next;
		}
	}

	/**
	 * InitGOrder case 7 fills the name edit box with "크리스노아" (0xd3cfc; "Chris Noah" at
	 * 0xd3d08 when m_langID is not Korean).
	 */
	private static final int DEFAULT_NAME = 0xd3cfc;

	/** LoadSlot on a filled slot continues it; on an empty one GOrderFuncUp(7) opens the new-game panel. */
	private Action open() {
		if (slots[slot] != null) {
			return new Action.Continue(slot);
		}
		phase = Phase.NEW_GAME;
		row = 0;
		heroClass = 0;
		name = elf.cstring(DEFAULT_NAME);
		return null;
	}

	Img render() {
		Img img = new Img(W, H);
		Gfx g = new Gfx(img);
		ticks++;
		drawScene(g);
		if (phase == Phase.NEW_GAME || phase == Phase.TYPING) {
			// GOrder 7 opens over the faded camp scene in place of the slot labels.
			g.fillRect(0, 0, W, H, 110 << 24);
			drawNewGame(g);
		} else {
			drawSlotHeroes(g);
			bar(g, 0, 14, W, 19);
			text(g, "슬롯선택", 0, 14, W, 19, WHITE, true);
			sprite(g, 88, CURSOR[slot][0], CURSOR[slot][1]);
			drawPopup(g);
			if (phase == Phase.MENU) {
				drawMenu(g);
			}
		}
		if (!notice.isEmpty()) {
			bar(g, 130, 286, 220, 17);
			text(g, notice, 130, 286, 220, 17, 0xffffdc78, true);
		}
		return img;
	}

	/** clix_gorder[0x16c + slot]: where the marker above the chosen hero goes. */
	private static final int[][] CURSOR = { { 192, 98 }, { 240, 90 }, { 288, 98 } };

	private void drawScene(Gfx g) {
		g.fillRect(0, 0, W, H, 0xff000000);
		int x0 = Math.floorDiv(CAMERA_X, 16);
		int y0 = Math.floorDiv(CAMERA_Y, 16);
		for (int ty = y0; ty <= y0 + H / 16 + 1; ty++) {
			for (int tx = x0; tx <= x0 + W / 16 + 1; tx++) {
				int cell = map.cell(tx, ty);
				Img f = cell == 0xff ? null : tiles.patch(map.layer(tx, ty), cell);
				if (f != null) {
					g.drawImage(f, tx * 16 - CAMERA_X, ty * 16 - CAMERA_Y);
				}
			}
		}
	}

	/**
	 * The three slot heroes at the camp. InitSelectSlotHero seats every slot hero (act 0x26) and
	 * stands the chosen one (act 0); an empty slot shows "SLOT" instead.
	 */
	private void drawSlotHeroes(Gfx g) {
		for (int i = 0; i < SLOTS; i++) {
			int x = 0x1a8 + 0x30 * i - CAMERA_X;
			int y = (i == 1 ? 0xa8 : 0xb0) - CAMERA_Y;
			if (slots[i] == null) {
				text(g, "SLOT", x - 40, y - 10, 80, 10, WHITE, true);
			} else {
				int act = i == slot ? HeroAnim.ACT_STAND : HeroAnim.ACT_SIT;
				hero(g, slots[i].look().withAct(act), x, y);
			}
		}
	}

	/** DrawHeroAnim with the hero's position at (x, y); the animation steps every other frame. */
	private void hero(Gfx g, HeroAnim.Look look, int x, int y) {
		// ponytail: one step per two frames stands in for the per-record delay CalcHeroAnim counts.
		PartSprites.Pose p;
		try {
			p = heroes.pose(look, ticks / 2);
		} catch (IOException e) {
			p = null;
		}
		if (p != null) {
			g.drawImage(p.image(), x - p.anchorX(), y - p.anchorY());
		}
	}

	private void drawPopup(Gfx g) {
		int dx = (slot - 1) * 16;
		int dy = slot == 1 ? 100 : 108;
		// ponytail: DrawGMenuBack's style-0 frame is a jump table not traced; imenu frame 3
		// (240x80) is the panel that fits the rectangles DrawPopupSlot writes into.
		g.drawImage(imenu.frame(3), 140 + dx, 100 + dy);
		Summary s = slots[slot];
		int ink = 0xff462814;
		text(g, s == null ? "빈슬롯" : s.name(), 147 + dx, 108 + dy, 228, 15, ink, true);
		g.fillRect(147 + dx, 125 + dy, 228, 1, ink);
		line(g, "MODE", s == null ? "" : jobText(DIFFICULTY, s.mode()), 199 + dx, 138 + dy, ink);
		line(g, "PLAY TIME", s == null || s.playTime() == 0 ? "--:--:--" : playTime(s.playTime()),
				199 + dx, 150 + dy, ink);
		line(g, "LEVEL", s == null ? "" : String.format("%02d", s.level()), 199 + dx, 162 + dy, ink);
		if (s != null) {
			// clix_gorder puts the icon at x 307; "PLAY TIME  hh:mm:ss" in DrawString's font runs to
			// 342, so it moves to the panel's right edge.
			sprite(g, 23 + s.heroClass(), 352 + dx, 138 + dy);
		}
	}

	/** DrawSlotSelect: the continue / delete list at (0xb2, 0xd2). */
	private void drawMenu(Gfx g) {
		String[] items = { "이어하기", "삭제하기" };
		for (int i = 0; i < 2; i++) {
			int y = 0xd2 + i * 20;
			g.fillRect(0xb2, y, 124, 18, i == menuIndex ? 0xdcc87828 : 170 << 24);
			text(g, items[i], 0xb2, y, 124, 18, WHITE, true);
		}
	}

	private void drawNewGame(Gfx g) {
		int sel = 0xc8c87828;
		int off = 170 << 24;
		g.fillRect(150, 20, 220, 20, row == 0 ? sel : off);
		text(g, "NAME", 154, 20, 60, 20, WHITE, false);
		text(g, phase == Phase.TYPING ? name + "_" : name, 214, 20, 150, 20, WHITE, false);
		g.fillRect(150, 50, 190, 20, row == 1 ? sel : off);
		text(g, "SPECIALTY", 154, 50, 80, 20, WHITE, false);
		text(g, jobText(JOB_NAME, heroClass), 239, 50, 90, 20, WHITE, true);
		sprite(g, 62, 232, 56);
		sprite(g, 63, 330, 56);
		sprite(g, 23 + heroClass, 350, 50);
		// The preview box shows SetGOrderNewGameHero's outfit for the class on prehero[1].
		g.fillRect(150, 80, 140, 90, 120 << 24);
		// DrawGOrderHero: on every 16th frame, when Rand1000() <= 499 (DAT_0004db28), RandRange(0,1)
		// sets the act to standing or walking; a kind 0 weapon is drawn 4 px right, kind 2 8 px.
		if ((ticks & 0xf) == 0 && previewRand.nextInt(1000) <= 499) {
			previewAct = previewRand.nextInt(2) == 0 ? HeroAnim.ACT_STAND : HeroAnim.ACT_WALK;
		}
		HeroAnim.Look preview = HeroAnim.preview(items, heroClass, previewAct);
		int nudge = preview.kind() == 0 ? 4 : preview.kind() == 2 ? 8 : 0;
		hero(g, preview, 220 + nudge, 150);
		for (int i = 0; i < 4; i++) {
			int y = 80 + i * 18;
			g.fillRect(300, y, 70, 16, off);
			text(g, jobText(PROP_NAME, i), 305, y, 30, 16, WHITE, false);
			text(g, String.valueOf(Hero.startStat(elf, heroClass, i)), 335, y, 30, 16, WHITE, false);
		}
		g.fillRect(145, 180, 230, 80, off);
		wrap(g, jobText(JOB_SUMMARY, heroClass), 151, 186, 218);
	}

	private String jobText(int table, int index) {
		String s = elf.cstring(elf.u32(table + index * 4));
		return s == null ? "" : s;
	}

	/** "%s%02d:%02d:%02d" over ticks of 1/12 s, as DrawPopupSlot divides them. */
	static String playTime(int t) {
		return String.format("%02d:%02d:%02d", t / 43200, t / 720 % 60, t / 12 % 60);
	}

	/** DrawPopupSlot writes "%s%s" (label, value) left-aligned from the line's x. */
	private void line(Gfx g, String label, String value, int x, int y, int ink) {
		g.drawString(label + "  " + value, x, y, ink);
	}

	private void sprite(Gfx g, int frame, int x, int y) {
		g.drawImage(gorder.frame(frame), x, y);
	}

	private static void bar(Gfx g, int x, int y, int w, int h) {
		g.fillRect(x, y, w, h, 150 << 24);
	}

	/** DrawString's glyphs are 16 rows tall; y centres that cell in the box. */
	private static void text(Gfx g, String s, int x, int y, int w, int h, int argb, boolean centre) {
		int tx = centre ? x + (w - Gfx.stringWidth(s)) / 2 : x;
		g.drawString(s, tx, y + (h - 16) / 2, argb);
	}

	/** Breaks at '\n' and wherever the next character would pass {@code w}; lines are 16 px apart. */
	private static void wrap(Gfx g, String s, int x, int y, int w) {
		StringBuilder lineText = new StringBuilder();
		int ty = y;
		for (char c : s.toCharArray()) {
			if (c == '\n' || Gfx.stringWidth(lineText.toString() + c) > w) {
				g.drawString(lineText.toString(), x, ty, WHITE);
				ty += 16;
				lineText.setLength(0);
				if (c == '\n') {
					continue;
				}
			}
			lineText.append(c);
		}
		g.drawString(lineText.toString(), x, ty, WHITE);
	}
}

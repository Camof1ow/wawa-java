package wawa;

import java.io.IOException;

/**
 * What GOrder's screens (the menu's ITEM tab, the shops) share on the original 480x320 screen:
 * the gorder, imenu, gameui and icon sheets, the static clix tables, GOrderFuncBack /
 * DrawGOrderBack, DrawGOrderIcon, DrawMiniString, DrawGOrderMoney and DrawGOrderCursor.
 */
abstract class OrderScreen {

	static final int W = 480;
	static final int H = 320;

	/** What a GOrder screen asks of the game. */
	interface Host {
		/** A titled two-button GMenu (0xcc, 0xce) whose "O.확인" runs {@code onYes}. */
		void confirm(byte[] title, byte[] text, Runnable onYes);

		/** A GMenu message such as 0x38e, dismissed with OK. */
		void message(byte[] text);

		/** FuncUseItem(id, 1). */
		void use(int id);

		/** InitHeroState(1) after the worn gear changed. */
		void changed();

		void close();

		/** CheckQuestItem(id, n) after AddItem took n of id. */
		void itemAdded(int id, int n);

		/** The tab column's up / down: the menu moves to the previous or next of its seven tabs. */
		void switchTab(int dir);

		void notPorted(String what);
	}

	/** The sheets the GOrder screens draw from, loaded once (LoadGOrderIcon for icon0..7). */
	static final class Sheets {
		final Gsac gorder;
		final Gsac imenu;
		final Gsac gameui;
		final Gsac[] icons = new Gsac[8];
		/** The first global icon frame of each sheet; clix_icon counts frames across icon0..icon7. */
		final int[] iconFirst = new int[9];

		Sheets(Assets assets, ElfImage so) throws IOException {
			gorder = Gsac.loadCompanion(assets, "assets/img/gorder.png");
			imenu = Gsac.loadCompanion(assets, "assets/img/imenu.png");
			gameui = Gsac.loadCompanion(assets, "assets/img/gameui.png");
			// LoadGOrderIcon: icon1..6 take the external palette g_anRecipeEquip numbers.
			for (int k = 0; k < 8; k++) {
				int pal = so.u8(RECIPE_EQUIP + k);
				String file = "assets/img/icon" + k + ".png";
				icons[k] = pal != 0 ? Gsac.loadWithPalette(assets, file, String.format("assets/pal/%03d.png", pal))
						: Gsac.loadCompanion(assets, file);
				iconFirst[k + 1] = iconFirst[k] + icons[k].frameCount();
			}
		}
	}

	/** g_anCursor: the s8 bob of arrows and the cursor, by frame. */
	static final int CURSOR_BOB = 0xb9868;
	static final int ITEM_ICON = 0xc99d0;
	static final int RECIPE_EQUIP = 0xb9870;
	static final int TEXT_COLOR = 0xe58f4;
	static final int EQUIP_GRADE = 0xe5dbc;
	static final int MESSAGE = 0xe2684;
	static final int MONEY_LABEL = 0xd3c6c;
	static final int WEAPON_HAND = 0xe2924;
	static final int ARMOUR_KIND = 0xe2934;
	/** The weapon-type words after the hand: 검, 도끼, 둔기 by element. */
	static final int[] WEAPON_KIND = { 0xd7d08, 0xd7d0c, 0xd7d14 };
	/** DrawGOrderIcon's background and tint per grade 1..4, then for any other grade (BBGGRR00). */
	static final int[] GRADE_BG = { 0x32574500, 0x502c0800, 0x4c1d3300, 0x0a0c8700, 0x435e7100 };
	static final int[] GRADE_TINT = { 0x009b8a00, 0xe67d1700, 0xff60ab00, 0x1e9aee00, 0x4493cb00 };
	static final int[] MATERIAL_BG = { 0x96969600, 0x51726200, 0x467a9f00, 0x8e5fa300 };
	static final int EDGE = 0x1a2a4300;
	static final int ATTACK_FMT = 0xd7d50;
	static final int DEFENCE_FMT = 0xd7d90;
	static final int LIMIT_FMT = 0xd7ee0;
	static final int LIMIT_LEVEL_SHORT_FMT = 0xd7e50;
	static final int LIMIT_WORN_FMT = 0xd7e08;
	static final int MATERIAL_KIND = 0xd7ff8;
	static final int MATERIAL_USE = 0xe2984;

	final ElfImage so;
	final Item items;
	final Clix clix;
	final Gsac gorder;
	final Gsac imenu;
	final Gsac gameui;
	private final Gsac[] icons;
	private final int[] iconFirst;

	Hero hero;
	Host host;
	/** wawamain+0xf4: frames drawn, for the bobbing arrows. */
	int frames;
	/** gorder+0x54/+0x58: the arrow, easing toward the cursor rectangle's corner. */
	int arrowX = -1;
	int arrowY;

	OrderScreen(Sheets s, ElfImage so, Item items) {
		this.so = so;
		this.items = items;
		this.clix = new Clix(so);
		gorder = s.gorder;
		imenu = s.imenu;
		gameui = s.gameui;
		icons = s.icons;
		iconFirst = s.iconFirst;
	}

	String label(int table, int i) {
		String s = so.cstring(so.u32(table + 4 * i));
		return s == null ? "" : s;
	}

	String fmt(int va) {
		String s = so.cstring(va);
		return s == null ? "" : s;
	}

	/** A GMenu message from the table at 0xe2684 (id 900 first). */
	void message(int id) {
		host.message(so.cbytes(so.u32(MESSAGE + 4 * (id - 900))));
	}

	/** KeyPressGOrder for this screen; false once it has closed. */
	abstract boolean key(int code);

	/** DrawGOrder for this screen, once per frame on the 480x320 layer. */
	abstract void draw(Gfx g);

	/**
	 * GOrderFuncBack and DrawGOrderBack: the dimmed field, the frame, a tab base and inactive label
	 * (clix_iorder, imenu) per entry of {@code inactive}, the panel, and the active label.
	 */
	void back(Gfx g, int[] inactive, int active) {
		// DrawFillBlur blurs the field behind; a darker fill stands in for it.
		g.fillRect(0, 0, W, H, 0x80000000);
		for (int off : new int[] { 0x10, 0x18, 0x20, 0x28, 0x30 }) {
			clix.sprite(g, gorder, Clix.GORDER, off, 0, 0);
		}
		for (int i = 0; i < inactive.length; i++) {
			clix.sprite(g, gorder, Clix.GORDER, 0x40, i * 0x12, 0);
			clix.sprite(g, imenu, Clix.IORDER, inactive[i], 0, 0, 0);
		}
		clix.sprite(g, gorder, Clix.GORDER, 0x00, 0, 0);
		clix.sprite(g, imenu, Clix.IORDER, active, 0, 0);
	}

	/** DrawGOrderCursor: the rectangle framed in four edges, and the hand easing to its corner. */
	void cursor(Gfx g, int x, int y, int w, int h) {
		edge(g, x, y, w, h, EDGE);
		edge(g, x - 1, y - 1, w + 2, h + 2, rgb(0x88fffe00));
		edge(g, x - 2, y - 1, w + 4, h + 2, rgb(0x88fffe00));
		edge(g, x - 3, y - 2, w + 6, h + 4, EDGE);
		// The arrow eases toward the cell's lower right corner over two frames.
		int tx = x + w;
		int ty = y + h;
		if (arrowX < 0) {
			arrowX = tx;
			arrowY = ty;
		} else {
			arrowX += (tx - arrowX) / 2;
			arrowY += (ty - arrowY) / 2;
		}
		clix.sprite(g, gorder, Clix.GORDER, 0xd8, arrowX, arrowY + so.s8(CURSOR_BOB + frames % 6));
	}

	/** DrawGOrderMoney: white 3/8 on {@code fill}, then "1,234  " and the 'L' after it, right-aligned in {@code text}. */
	void money(Gfx g, int fill, int text) {
		int[] mb = rect(Clix.GORDER, fill);
		g.fillRect(mb[0], mb[1], mb[2], mb[3], 3 * 32 << 24 | 0xffffff);
		mini(g, commas(hero.inv.gold) + "  ", rect(Clix.GORDER, text), 0x12, 6);
		mini(g, fmt(MONEY_LABEL), rect(Clix.GORDER, text), 0x12, 0);
	}

	/** The kind GetGOrderItemSummary names: hand and weapon word for types 1..4, the armour word for 5..0xc. */
	String kind(int id) {
		int t = items.type(id);
		if (t <= 4) {
			int element = items.element(id);
			String k = element < WEAPON_KIND.length ? so.cstring(WEAPON_KIND[element]) : null;
			return label(WEAPON_HAND, t - 1) + (k == null ? "" : k);
		}
		return label(ARMOUR_KIND, Math.min(7, t - 5));
	}

	/**
	 * GetGOrderItemSummary for an equipment instance, its main lines only: "grade name", its kind
	 * with attack or defence, and "레벨제한 n, 내구도 a/b" (in red when the level or the durability
	 * falls short, durability shown as (v + 9) / 10). Set bonuses, options, sockets and runes are
	 * left out.
	 */
	String gearSummary(Item.Equip e) {
		StringBuilder b = new StringBuilder();
		b.append(label(EQUIP_GRADE, e.origin == 1 ? 5 : e.grade)).append(' ').append(items.name(e.id)).append('\n');
		b.append(kind(e.id)).append(", ");
		b.append(String.format(fmt(e.type <= 4 ? ATTACK_FMT : DEFENCE_FMT), e.value));
		int limit = hero.level < e.level ? LIMIT_LEVEL_SHORT_FMT : e.dur <= e.durMax >> 2 ? LIMIT_WORN_FMT : LIMIT_FMT;
		b.append('\n').append(String.format(fmt(limit), e.level, (e.dur + 9) / 10, (e.durMax + 9) / 10));
		return b.toString();
	}

	/**
	 * GetGOrderItemSummary for a stack: the name with " xN" when a count is given, then the
	 * material's kind and use, or the record's description.
	 */
	String stackSummary(int id, int count) {
		String name = count > 0 ? items.name(id) + " x" + count : items.name(id);
		if (items.type(id) == 0xd) {
			int k = id >> 2;
			int kind = k == 0x1a || k == 0x1b ? 2 : k == 8 || k == 0xc || k > 0x16 && k < 0x1a || k == 0x1d || k == 0x20 || k == 0x21 ? 1 : 0;
			return name + "\n" + so.cstring(MATERIAL_KIND + kind * 12) + "\n" + label(MATERIAL_USE, Math.min(4, items.element(id)));
		}
		String text = items.description(id);
		return text.isEmpty() ? name : name + "\n" + text;
	}

	/**
	 * DrawGOrderIcon(x, y, id, edge style, grade): the 19 x 19 background by grade (gear and
	 * recipes) or by id &amp; 3 (materials), then the icon frame g_anItemIcon[id] picks from
	 * clix_icon, counted across icon0..icon7; gear of types 1..8 takes palette row record +8 +
	 * grade with entry 1 tinted by grade, types 9..0xc add the grade to the frame.
	 */
	void icon(Gfx g, int x, int y, int id, int style, int grade) {
		int type = items.type(id);
		int bg;
		int tint = 0;
		int gi = grade >= 1 && grade <= 4 ? grade - 1 : 4;
		if (Item.equipment(type) || type == 0x13) {
			bg = GRADE_BG[gi];
			tint = rgb(GRADE_TINT[gi]);
		} else if (type == 0xd) {
			bg = MATERIAL_BG[id & 3];
		} else {
			bg = GRADE_BG[4];
		}
		if (style != 0) {
			edge(g, x - 1, y - 1, 0x15, 0x15, style == 1 ? EDGE : style == 2 ? rgb(0xffb21900) : 0xff000000);
		}
		g.fillRect(x, y, 0x13, 0x13, rgb(bg));
		int f = so.s16(Clix.ICON + so.u8(ITEM_ICON + id) * 8 + 4);
		int row = 0;
		if (type >= 1 && type <= 8) {
			row = items.colour(id) + grade;
		} else if (type >= 9 && type <= 0xc) {
			f += grade;
			tint = 0;
		} else {
			tint = 0;
		}
		for (int k = 0; k < 8; k++) {
			if (f >= iconFirst[k] && f < iconFirst[k + 1]) {
				g.drawImage(icons[k].frame(f - iconFirst[k], row, tint), x, y);
				return;
			}
		}
	}

	/** 1234567 as "1,234,567" (String.format's ',' flag is avoided for the web build). */
	static String commas(int v) {
		String s = Integer.toString(v);
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			if (i > 0 && s.charAt(i - 1) != '-' && (s.length() - i) % 3 == 0) {
				b.append(',');
			}
			b.append(s.charAt(i));
		}
		return b.toString();
	}

	/**
	 * DrawMiniString: letters are clix_gameui entry (c &amp; ~0x20) + 0x71, digits c + 0x67, a few
	 * signs their own entries, anything else a 6 px gap; a glyph advances its width + 1. Flags:
	 * &amp; 3 aligns across (1 centre, 2 right), 0x10 centres down, 0x20 sits 7 px above the bottom.
	 */
	void mini(Gfx g, String s, int[] r, int flags, int row) {
		int w = 0;
		for (char c : s.toCharArray()) {
			w += miniAdvance(c);
		}
		int x = r[0] + ((flags & 3) == 0 ? 0 : (flags & 1) == 0 ? r[2] - w : r[2] - w >> 1);
		int y = r[1] + ((flags & 0x30) == 0 ? 0 : (flags & 0x10) == 0 ? r[3] - 7 : r[3] - 6 >> 1);
		for (char c : s.toCharArray()) {
			int e = miniEntry(c);
			if (e >= 0) {
				clix.sprite(g, gameui, Clix.GAMEUI, e * 8, x, y, row);
			}
			x += miniAdvance(c);
		}
	}

	private int miniAdvance(char c) {
		int e = miniEntry(c);
		return e < 0 ? 6 : gameui.width(clix.frame(Clix.GAMEUI, e * 8)) + 1;
	}

	private static int miniEntry(char c) {
		if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
			return (c & ~0x20) + 0x71;
		}
		if (c >= '0' && c <= '9') {
			return c + 0x67;
		}
		return switch (c) {
			case '*' -> 0xa1;
			case '#' -> 0xa2;
			case '%' -> 0xa3;
			case '+' -> 0xa4;
			case '-' -> 0xa5;
			case ':' -> 0xa6;
			case '.', ',' -> 0xa7;
			case '|' -> 0xa8;
			case '<' -> 0xad;
			case '>' -> 0xae;
			case '/' -> 0xaf;
			default -> -1;
		};
	}

	/** DrawGOrderHero's level digits: clix_gameui + (ch - 0x13) * 8. */
	void glyph(Gfx g, char c, int x, int y, int row) {
		clix.sprite(g, gameui, Clix.GAMEUI, (c - 0x13) * 8, x, y, row);
	}

	/** Lines break at '\n' and at the width; "\x07 d" switches colour as DrawString does; 16 px a line. */
	void text(Gfx g, String s, int x, int y, int w) {
		text(g, s, x, y, w, 0xffffffff);
	}

	void text(Gfx g, String s, int x, int y, int w, int ink) {
		int argb = ink;
		int tx = x;
		int ty = y;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 7 && i + 1 < s.length()) {
				char d = s.charAt(++i);
				argb = d == '0' ? ink : rgb(so.u32(TEXT_COLOR + (d - '1') * 4));
				continue;
			}
			int cw = Gfx.stringWidth(String.valueOf(c));
			if (c == '\n' || tx + cw > x + w) {
				tx = x;
				ty += 16;
				if (c == '\n') {
					continue;
				}
			}
			tx += g.drawString(String.valueOf(c), tx, ty, argb);
		}
	}

	int[] rect(int table, int off) {
		return new int[] { clix.x(table, off), clix.y(table, off), clix.w(table, off), clix.h(table, off) };
	}

	static void edge(Gfx g, int x, int y, int w, int h, int argb) {
		g.fillRect(x, y, w, 1, argb);
		g.fillRect(x, y + h - 1, w, 1, argb);
		g.fillRect(x, y, 1, h, argb);
		g.fillRect(x + w - 1, y, 1, h, argb);
	}

	/** SetColorDraw's BBGGRR00 as opaque ARGB. */
	static int rgb(int bbggrr00) {
		return 0xff000000 | ((bbggrr00 >>> 8) & 0xff) << 16 | ((bbggrr00 >>> 16) & 0xff) << 8 | bbggrr00 >>> 24;
	}
}

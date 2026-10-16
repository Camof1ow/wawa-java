package wawa;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The shops: GOrder 0xd (buy) and 0xe (sell) as DrawGOrder lays them out, KeyPressGOrder's
 * BUY/SELL bar, KeyPressGOrderBuySell, and the GMenu popups 0xca / 0xcb (a stack, with a
 * quantity) and 0xcf / 0xd0 (equipment) with ResultGMenuItemBuy and the sale.
 *
 * <p>Modes 0 and 1 (four category tabs each), 3 (misc) and 6 (the misc list over a grade range)
 * are ported; the gamble (2) and the cash shop (4) are not. Bought gear gets no option or socket
 * from the option argument, since options and sockets are not ported.
 */
final class ShopMenu extends OrderScreen {

	/** The shopkeeper GameCore turns (ChangeMonsDir) and draws in the shop's box (DrawGOrderMons). */
	interface Keeper {
		void face(int dir);

		void draw(Gfx g, int x, int y, int w, int h, int frame);
	}

	static final int BUY = 0xd;
	static final int SELL = 0xe;
	/** Modes 0 and 1: u16 ids per category tab, 12 each except mode 0's last (4). */
	private static final int[][] GOODS = { { 0x10b968, 0x10b980, 0x10b998, 0x10b9b0 },
			{ 0x10b9b8, 0x10b9d0, 0x10b9e8, 0x10ba00 } };
	private static final int MISC = 0x10ba30;
	private static final int MISC_COUNT = 23;
	private static final int ASK = 0xd4294;
	private static final int PRICE = 0xd4268;
	private static final int SELL_ASK = 0xd42c0;
	private static final int BUY_ITEM = 0xd2524;
	private static final int SELL_ITEM = 0xd2550;
	private static final int BUY_GEAR = 0xd2630;
	private static final int SELL_GEAR = 0xd265c;
	private static final int O_BUY = 0xd2a7c;
	private static final int O_SELL = 0xd2a8c;
	private static final int X_CANCEL = 0xd2c3c;
	private static final int CANCEL = 0xd2bf0;
	private static final int ATTACK_RANGE_FMT = 0xd7d6c;
	private static final int DEFENCE_RANGE_FMT = 0xd7da8;
	private static final int LIMIT_RANGE_FMT = 0xd7f20;
	private static final int LIMIT_RANGE_SHORT_FMT = 0xd7e98;
	private static final int LEVEL_FMT = 0xd7dec;
	private static final int LEVEL_SHORT_FMT = 0xd7dc8;

	/** A cell: an id at a grade; selling also keeps the instance, or the stack slot and its count. */
	private record Entry(int id, int grade, Item.Equip equip, int slot, int count) {
	}

	private final TalkBox talk;
	private final Random rng;
	private Keeper keeper;
	/** gorder+0: BUY or SELL. */
	private int screen;
	/** gorder+4. */
	private int mode;
	/** gorder+8: 0 the BUY/SELL bar, 1 inside. */
	private int focus;
	/** gorder+0xc: the first cell shown, a multiple of 6. */
	private int scroll;
	/** gorder+0x14: the cursor is on the category tabs. */
	private boolean tabFocus;
	/** gorder+0x18: the category tab, -1 for none. */
	private int tab;
	private int col;
	private int row;
	/** gorder+0x124 / +0x128: the grade range (+0x12c, the option flag, has nothing to add here). */
	private int first;
	private int last;
	/** The GMenu over the shop (0xca, 0xcb, 0xcf, 0xd0), or 0, with its cell and gmenu+0x30. */
	private int popup;
	private Entry popupEntry;
	private int qty;
	private int qtyMax;

	ShopMenu(Sheets sheets, ElfImage so, Item items, TalkBox talk, Random rng) {
		super(sheets, so, items);
		this.talk = talk;
		this.rng = rng;
	}

	/** Event lo 2: gorder+4 = mode, the grade range from the argument bytes, GOrderFuncUp(0xd). */
	void open(Hero h, Host host, Keeper keeper, int mode, int first, int last) {
		this.hero = h;
		this.host = host;
		this.keeper = keeper;
		this.mode = mode;
		this.first = first;
		this.last = last;
		focus = 0;
		tab = -1;
		popup = 0;
		arrowX = -1;
		initBuy();
	}

	/** InitGOrder 0xd: the keeper faces 5; tabs for modes 0, 1 and 6 with the cursor on them. */
	private void initBuy() {
		screen = BUY;
		keeper.face(5);
		tabFocus = true;
		scroll = 0;
		if (mode == 2 || mode == 3 || mode == 4) {
			tabFocus = false;
		} else {
			tab = 0;
		}
		col = 0;
		row = 0;
	}

	/** InitGOrder 0xe: the keeper faces 7; the tab focus stays as it was (the original's quirk). */
	private void initSell() {
		screen = SELL;
		keeper.face(7);
		scroll = 0;
		col = 0;
		row = 0;
	}

	// ---- lists and prices ---------------------------------------------------------------------

	/**
	 * Buying: every id of the tab's table (the misc list for modes 3 and 6) at each grade from
	 * first to last. Selling: the unworn equipment, then the stacks except types 0xf..0x11 and
	 * 0x14 and id 0x1d6, recipes at their level.
	 */
	private List<Entry> list() {
		List<Entry> out = new ArrayList<>();
		if (screen == BUY) {
			boolean tabs = mode == 0 || mode == 1;
			int table = tabs ? GOODS[mode][tab] : MISC;
			int n = tabs ? mode == 0 && tab == 3 ? 4 : 12 : MISC_COUNT;
			for (int g = first; g <= last; g++) {
				for (int i = 0; i < n; i++) {
					out.add(new Entry(so.u16(table + 2 * i), g, null, -1, 0));
				}
			}
			return out;
		}
		Inventory inv = hero.inv;
		for (Item.Equip e : inv.equip) {
			if (e != null && e.slot == 0) {
				out.add(new Entry(e.id, e.grade, e, -1, 1));
			}
		}
		for (int i = 0; i < Inventory.STACKS; i++) {
			int id = inv.stackId[i];
			if (id == -1) {
				continue;
			}
			int t = items.type(id);
			if (t >= 0xf && t <= 0x11 || t == 0x14 || id == 0x1d6) {
				continue;
			}
			out.add(new Entry(id, t == 0x13 ? inv.stackGrade[i] : 0, null, i, inv.stackCount[i]));
		}
		return out;
	}

	/** DrawGOrder's selection: the cell under (col, row), whatever has the focus; null on an empty cell. */
	private Entry selected(List<Entry> l) {
		int n = scroll + row * 6 + col;
		return n < l.size() ? l.get(n) : null;
	}

	/** GetGOrderItemCost(0): twice the base, the cash items' own price aside. */
	private int buyPrice(int id) {
		int c = items.cost(id);
		return items.type(id) == 0x14 ? c : c * 2;
	}

	/** GetGOrderItemCost(1): the base; gambled gear 60 (the option count, not ported, would add 200 / 1000 each). */
	private int sellPrice(Entry e) {
		return e.equip != null && e.equip.origin == 2 ? 60 : items.cost(e.id);
	}

	private int price(Entry e) {
		return screen == BUY ? buyPrice(e.id) : sellPrice(e);
	}

	// ---- keys ---------------------------------------------------------------------------------

	/** KeyPressGOrder (the bar) and KeyPressGOrderBuySell; returns false once the shop has closed. */
	boolean key(int code) {
		boolean back = code == Keys.ESCAPE || code == Keys.BACK_SPACE;
		boolean ok = code == Keys.ENTER || code == Keys.SPACE || code == Keys.K5;
		boolean left = code == Keys.LEFT || code == Keys.K4;
		boolean right = code == Keys.RIGHT || code == Keys.K6;
		boolean up = code == Keys.UP || code == Keys.K2;
		boolean down = code == Keys.DOWN || code == Keys.K8;
		if (popup != 0) {
			popupKey(ok, back, left, right, up, down);
			return true;
		}
		if (focus == 0) {
			if (back) {
				host.close();
				return false;
			}
			if (ok || right) {
				focus = 1;
				row = 0;
				col = 0;
			} else if (down && screen == BUY) {
				initSell();
			} else if (up && screen == SELL) {
				initBuy();
			}
			return true;
		}
		List<Entry> l = list();
		if (back) {
			if (!tabFocus && mode < 2) {
				tabFocus = true;
			} else {
				focus = 0;
			}
		} else if (ok) {
			select(l);
		} else if (left || right) {
			int d = left ? -1 : 1;
			if (tabFocus) {
				focus = 1;
				tab = Math.floorMod(tab + d, 4);
				row = 0;
				col = 0;
			} else {
				col = Math.floorMod(col + d, 6);
			}
		} else if (down) {
			if (tabFocus) {
				tabFocus = false;
			} else if (++row >= 5) {
				if (scroll + 30 < l.size()) {
					scroll += 6;
					row = 4;
				} else {
					row = 0;
					scroll = 0;
				}
			}
		} else if (up && !tabFocus && --row < 0) {
			if (scroll > 0) {
				scroll -= 6;
				row = 0;
			} else {
				row = 4;
				if (l.size() > 30) {
					scroll = (l.size() - 25) / 6 * 6;
				}
			}
		}
		return true;
	}

	/** OK inside: off the tabs, or the popup for the cell (0x385 first when the gold falls short). */
	private void select(List<Entry> l) {
		if (screen == BUY && tab >= 0 && tabFocus) {
			tabFocus = false;
			return;
		}
		Entry e = selected(l);
		if (e == null) {
			return;
		}
		int t = items.type(e.id);
		boolean stack = t == 0 || t > 12;
		if (screen == BUY && hero.inv.gold < buyPrice(e.id)) {
			message(0x385);
			return;
		}
		popup = screen == BUY ? stack ? 0xca : 0xcf : stack ? 0xcb : 0xd0;
		popupEntry = e;
		// gmenu+0x30 starts at max(gorder+0x28, 1): 1 to buy, the whole stack to sell.
		qtyMax = popup == 0xca ? 99 : popup == 0xcb ? e.count : 1;
		qty = Math.max(screen == BUY ? 0 : e.count, 1);
	}

	/** KeyPressGMenu for the shop's popups: the quantity by 1 across and 10 up and down. */
	private void popupKey(boolean ok, boolean back, boolean left, boolean right, boolean up, boolean down) {
		if (popup == 0xca || popup == 0xcb) {
			int d = left ? -1 : right ? 1 : up ? 10 : down ? -10 : 0;
			qty = Math.max(1, Math.min(qtyMax, qty + d));
		}
		if (ok) {
			result();
		} else if (back) {
			popup = 0;
		}
	}

	/** ResultGMenuItemBuy, or the sale: gold, the bag, then SortItem. */
	private void result() {
		Entry e = popupEntry;
		popup = 0;
		Inventory inv = hero.inv;
		if (screen == SELL) {
			inv.gold += sellPrice(e) * qty;
			if (e.equip != null) {
				for (int i = 0; i < Inventory.EQUIP; i++) {
					if (inv.equip[i] == e.equip) {
						inv.equip[i] = null;
					}
				}
			} else {
				inv.stackCount[e.slot] -= qty;
			}
			inv.sort();
			return;
		}
		int cost = buyPrice(e.id) * qty;
		if (inv.gold < cost) {
			message(0x385);
			return;
		}
		if (Item.equipment(items.type(e.id))) {
			// AddItem(id, 1, ~grade, 0): the grade as given, no random sockets; bought (+0x1d = 1).
			int i = inv.add(e.id, 1, ~e.grade, false, rng);
			if (i < 0) {
				message(0x386);
				return;
			}
			inv.equip[i].origin = 1;
		} else if (inv.add(e.id, qty, -1, false, rng) < 0) {
			message(0x386);
			return;
		}
		inv.sort();
		inv.gold -= cost;
	}

	// ---- summary ------------------------------------------------------------------------------

	/** The cell's summary; the popups' (mode 1) drop the name line and leave room for the icon. */
	private String summary(Entry e, boolean popup) {
		String s = e.equip != null ? gearSummary(e.equip)
				: Item.equipment(items.type(e.id)) ? shopGearSummary(e.id, e.grade)
				: stackSummary(e.id, screen == SELL ? e.count : 0);
		if (!popup) {
			return s;
		}
		int nl = s.indexOf('\n');
		return nl < 0 ? "" : "     " + s.substring(nl + 1);
	}

	/**
	 * GetGOrderItemSummary for a shop's gear, a virtual instance: the base times the grade
	 * multiplier, ± record +2 percent, and record +4's durability ± record +6 percent; the level
	 * in red (\x07 9) when the hero is below it.
	 */
	private String shopGearSummary(int id, int grade) {
		int t = items.type(id);
		boolean low = hero.level < items.level(id);
		StringBuilder b = new StringBuilder();
		b.append(label(EQUIP_GRADE, grade)).append(' ').append(items.name(id)).append('\n').append(kind(id));
		if (t < 9) {
			int v = items.graded(id, grade);
			int dev = v * items.spread(id) / 100;
			b.append(", ").append(String.format(fmt(t <= 4 ? ATTACK_RANGE_FMT : DEFENCE_RANGE_FMT), v - dev, v + dev));
			int d = items.durability(id);
			int dd = d * items.durabilitySpread(id) / 100;
			b.append('\n').append(String.format(fmt(low ? LIMIT_RANGE_SHORT_FMT : LIMIT_RANGE_FMT), items.level(id), d - dd, d + dd));
		} else {
			b.append('\n').append(String.format(fmt(low ? LEVEL_SHORT_FMT : LEVEL_FMT), items.level(id)));
		}
		return b.toString();
	}

	// ---- drawing ------------------------------------------------------------------------------

	/** DrawGOrder for 0xd / 0xe, then the popup; once per frame on the 480x320 layer. */
	void draw(Gfx g) {
		frames++;
		List<Entry> l = list();
		boolean buy = screen == BUY;
		back(g, new int[] { 0xc0, 0xc8 }, buy ? 0x78 : 0x80);
		if (buy && (mode == 0 || mode == 1)) {
			// The category bar: its background, the tab's highlight and the four labels.
			clix.sprite(g, gorder, Clix.GORDER, 0xb8, 0, 0);
			clix.sprite(g, gorder, Clix.GORDER, 0xd0, 25 * tab, 0, focus != 0 ? 1 : 0);
			for (int i = 0; i < 4; i++) {
				clix.sprite(g, gorder, Clix.GORDER, 0x658 + (3 * i + mode) * 8, 0, 0, i == tab && tabFocus && focus != 0 ? 1 : 0);
			}
		}
		if (tab >= 0) {
			int bob = so.s8(CURSOR_BOB + (frames >> 1) % 6);
			clix.sprite(g, gorder, Clix.GORDER, 0xc0, bob, 0);
			clix.sprite(g, gorder, Clix.GORDER, 0xc8, -bob, 0);
		}
		money(g, buy ? 0x6e8 : 0x748, buy ? 0x6f0 : 0x750);
		for (int off : buy ? new int[] { 0x6b8, 0x6c0, 0x6c8 } : new int[] { 0x718, 0x720, 0x728 }) {
			clix.sprite(g, gorder, Clix.GORDER, off, 0, 0);
		}
		int[] grid = rect(Clix.GORDER, buy ? 0x6d0 : 0x730);
		drawGrid(g, grid, l);
		if (l.size() > 30) {
			int[] sb = rect(Clix.GORDER, buy ? 0x710 : 0x770);
			int steps = (l.size() - 25) / 6;
			g.fillRect(sb[0], sb[1], sb[2], sb[3], rgb(0x6b99c700));
			int len = steps >= 11 ? sb[3] / 10 : sb[3] / (steps + 1);
			g.fillRect(sb[0], sb[1] + Math.min(sb[3] - len, scroll / 6 * len), sb[2], len, rgb(0x1c548c00));
		}
		Entry e = selected(l);
		int[] panel = rect(Clix.GORDER, buy ? 0x700 : 0x760);
		g.fillRect(panel[0], panel[1], panel[2], panel[3], 3 * 32 << 24);
		if (e != null) {
			int[] tb = rect(Clix.GORDER, buy ? 0x708 : 0x768);
			text(g, summary(e, false), tb[0], tb[1], tb[2]);
		}
		// The keeper's line: what to buy or sell, or the selected cell's price.
		int[] sp = rect(Clix.GORDER, buy ? 0x6d8 : 0x738);
		String line = e == null ? fmt(buy ? ASK : SELL_ASK) : fmt(PRICE).replace("%s", commas(price(e)));
		int lines = line.split("\n", -1).length;
		text(g, line, sp[0], sp[1] + (sp[3] - 16 * lines) / 2, sp[2], rgb(EDGE));
		int[] kb = rect(Clix.GORDER, buy ? 0x6e0 : 0x740);
		keeper.draw(g, kb[0], kb[1], kb[2], kb[3], frames);
		drawCursor(g, grid);
		if (popup != 0) {
			drawPopup(g);
		}
	}

	/** The 6 x 5 grid: edge, inner rules every 20 px, and the visible cells' icons. */
	private void drawGrid(Gfx g, int[] r, List<Entry> l) {
		edge(g, r[0], r[1], r[2], r[3], EDGE);
		int ix = r[0] + 1;
		int iy = r[1] + 1;
		for (int j = 0; j < 5; j++) {
			g.fillRect(ix + 0x13 + 0x14 * j, iy, 1, r[3] - 2, rgb(0x2463a300));
			if (0x13 + 0x14 * j < r[3] - 2) {
				g.fillRect(ix, iy + 0x13 + 0x14 * j, r[2] - 2, 1, rgb(0x2463a300));
			}
		}
		for (int n = scroll; n < l.size() && n - scroll < 30; n++) {
			int k = n - scroll;
			Entry e = l.get(n);
			icon(g, ix + k % 6 * 0x14, iy + k / 6 * 0x14, e.id, 0, e.grade);
		}
	}

	/**
	 * GetGOrderSelRects: the grid cell once the tabs let go, the category label (only modes 0 and 1
	 * set it), else the rectangle left from before, the BUY/SELL label on the bar.
	 */
	private void drawCursor(Gfx g, int[] grid) {
		if (focus != 0 && !tabFocus) {
			cursor(g, grid[0] + col * 0x14 + 1, grid[1] + row * 0x14 + 1, 0x13, 0x13);
		} else if (focus != 0 && screen == BUY && mode < 2) {
			int off = 0x658 + (3 * tab + mode) * 8;
			cursor(g, clix.x(Clix.GORDER, off), clix.y(Clix.GORDER, off) - 1, 0x12, 0x0f);
		} else {
			int off = screen == BUY ? 0x78 : 0x80;
			cursor(g, clix.x(Clix.IORDER, off), clix.y(Clix.IORDER, off), 60, 20);
		}
	}

	/**
	 * DrawGMenu style 3 for the shop. 0xca / 0xcb: the line at clix_gmenu+0x108, the icon at +0x120
	 * and the quantity box +0x128 with gorder's arrows, both buttons lit. 0xcf / 0xd0: black 3/8 on
	 * +0x48, the summary (mode 1) in +0x50 from y + 8 with the icon, the line at +0x58, OK lit.
	 */
	private void drawPopup(Gfx g) {
		Entry e = popupEntry;
		boolean buy = screen == BUY;
		talk.menuBack3(g, Ksc.encode(items.name(e.id)));
		if (popup == 0xca || popup == 0xcb) {
			int[] body = rect(Clix.GMENU, 0x108);
			String s = fmt(buy ? BUY_ITEM : SELL_ITEM);
			text(g, s, body[0] + (body[2] - Gfx.stringWidth(s)) / 2, body[1] + (body[3] - 16) / 2, W, rgb(EDGE));
			int[] ic = rect(Clix.GMENU, 0x120);
			icon(g, ic[0], ic[1], e.id, 1, e.grade);
			int[] q = rect(Clix.GMENU, 0x128);
			// [inferred] the box's edge takes the icon's fill colour; EDGE stands in for it.
			edge(g, q[0], q[1], q[2], q[3], EDGE);
			g.fillRect(q[0] + 1, q[1] + 1, q[2] - 2, q[3] - 2, 3 * 32 << 24 | 0xffffff);
			for (int off : new int[] { 0x8b8, 0x8c0, 0x8c8, 0x8d0 }) {
				clix.sprite(g, gorder, Clix.GORDER, off, 0, 0);
			}
			g.fillRect(q[0] + q[2] / 4, q[1] + q[3] - 1, q[2] / 2, 1, rgb(EDGE));
			mini(g, String.valueOf(qty), q, 0x11, 0);
			talk.button(g, so.cbytes(buy ? O_BUY : O_SELL), 160, 220, true);
			talk.button(g, so.cbytes(X_CANCEL), 290, 220, true);
		} else {
			int[] b = rect(Clix.GMENU, 0x48);
			g.fillRect(b[0], b[1], b[2], b[3], 3 * 32 << 24);
			int[] sb = rect(Clix.GMENU, 0x50);
			icon(g, sb[0] + 1, sb[1] + 9, e.id, 1, e.grade);
			text(g, summary(e, true), sb[0], sb[1] + 8, sb[2]);
			int[] body = rect(Clix.GMENU, 0x58);
			String s = fmt(buy ? BUY_GEAR : SELL_GEAR);
			text(g, s, body[0] + (body[2] - Gfx.stringWidth(s)) / 2, body[1] + (body[3] - 16) / 2, W);
			talk.button(g, so.cbytes(buy ? O_BUY : O_SELL), 160, 220, true);
			talk.button(g, so.cbytes(CANCEL), 290, 220, false);
		}
	}
}

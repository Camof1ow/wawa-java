package wawa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The menu's ITEM tab (GOrder type 1) on the original 480x320 screen: DrawGOrder's ITEM layers,
 * KeyPressGOrderItem and the item popup (DrawItemSelect). Its categories are 0 equipment, 1
 * consumables, 2 materials, 3 misc and 4 recipes.
 *
 * <p>The other GOrder tabs (status, skill, pet, log, quest, system) are not ported: the menu
 * opens on ITEM's category bar, and its tab column only shows their icons. Of the popup's
 * actions, crafting, quick slots, recipe tracking and the material menu's compress, decompress
 * and mutate report themselves as not ported.
 */
final class ItemMenu extends OrderScreen {

	private static final int ACTION_LABEL = 0xe29ec;
	private static final int MATERIAL_LABEL = 0xe2a2c;
	private static final int ATK_LABEL = 0xd3fd8;
	private static final int DEF_LABEL = 0xd3fe0;
	private static final int SET_1 = 0xd3fe8;
	private static final int SET_2 = 0xd3fec;
	private static final int USE_TEXT = 0xd2578;
	private static final int DISCARD_ITEM_TITLE = 0xd24e4;
	private static final int DISCARD_EQUIP_TITLE = 0xd2504;
	private static final int DISCARD_ITEM_TEXT = 0xd25d8;
	private static final int DISCARD_EQUIP_TEXT = 0xd2600;

	private static final int EQUIP = 1;
	private static final int MAKE = 2;
	private static final int USE = 4;
	private static final int QUICK = 8;
	private static final int UNEQUIP = 0x10;
	private static final int TRACK = 0x20;
	private static final int INFO = 0x40;
	private static final int DISCARD = 0x80;

	private final HeroAnim heroes;
	private final Random previewRand = new Random(0);

	/** gorder+0x08: 0 the tab column, 1 the grid, 2 picking a bag item for a slot, 3 a slot for an item. */
	private int focus;
	/** gorder+0x14: the cursor is on the category bar. */
	private boolean onBar;
	/** gorder+0x18. */
	private int tab;
	private int scroll;
	private int row;
	private int col;
	/** gorder+0x1c: focus 2's slot; focus 3 keeps the item being put on in {@link #moving}. */
	private int targetSlot;
	private Item.Equip moving;
	/** DrawItemSelect: the popup's action bits in order (null when closed) and the one selected. */
	private int[] options;
	private boolean materialMenu;
	private int selected;
	private int previewAct = HeroAnim.ACT_STAND;

	ItemMenu(Sheets sheets, ElfImage so, Item items, HeroAnim heroes) {
		super(sheets, so, items);
		this.heroes = heroes;
	}

	/** GOrderFuncUp to ITEM: InitGOrder's ITEM state, the cursor on the category bar of tab 0. */
	void open(Hero h, Host host) {
		this.hero = h;
		this.host = host;
		focus = 1;
		onBar = true;
		tab = 0;
		scroll = 0;
		row = 0;
		col = 0;
		options = null;
		moving = null;
		arrowX = -1;
	}

	// ---- lists ---------------------------------------------------------------------------

	/** The tab's bag entries: equipment instances (tab 0) or stack slot numbers, up to the first gap. */
	private List<Object> list() {
		List<Object> out = new ArrayList<>();
		Inventory inv = hero.inv;
		if (tab == 0) {
			for (int i = 0; i < inv.equipCap && inv.equip[i] != null; i++) {
				Item.Equip e = inv.equip[i];
				// Picking an item for a slot lists only what fits it.
				if (focus != 2 || Inventory.fits(e.type, targetSlot)) {
					out.add(e);
				}
			}
			return out;
		}
		int first = switch (tab) {
			case 1 -> Inventory.CONSUMABLES;
			case 2 -> 0;
			case 3 -> 0xaf;
			default -> 0xf5;
		};
		for (int i = 0; i < capacity() + (tab == 3 ? 10 : 0) && inv.stackId[first + i] != -1; i++) {
			out.add(first + i);
		}
		return out;
	}

	/** hero[0x877 + tab]. */
	private int capacity() {
		Inventory inv = hero.inv;
		return switch (tab) {
			case 0 -> inv.equipCap;
			case 1 -> inv.consumableCap;
			case 2 -> inv.materialCap;
			case 3 -> inv.miscCap;
			default -> inv.recipeCap;
		};
	}

	private int visible() {
		return tab == 0 ? 18 : 30;
	}

	/** The bag entry under the cursor, or null (a slot cell, the bar, or an empty cell). */
	private Object underCursor(List<Object> l) {
		if (onBar || tab == 0 && row < 2) {
			return null;
		}
		int v = (tab == 0 ? row - 2 : row) * 6 + col + scroll;
		return v < l.size() ? l.get(v) : null;
	}

	private int idOf(Object o) {
		return o instanceof Item.Equip e ? e.id : hero.inv.stackId[(Integer) o];
	}

	// ---- keys ----------------------------------------------------------------------------

	/** KeyPressGOrder / KeyPressGOrderItem; returns false once the menu has closed. */
	boolean key(int code) {
		if (options != null) {
			popupKey(code);
			return true;
		}
		boolean clear = code == Keys.ESCAPE || code == Keys.BACK_SPACE;
		boolean select = code == Keys.ENTER || code == Keys.SPACE || code == Keys.K5;
		if (focus == 0) {
			if (clear) {
				host.close();
				return false;
			}
			if (select || code == Keys.RIGHT || code == Keys.K6) {
				focus = 1;
				row = 0;
				col = 0;
			}
			return true;
		}
		List<Object> l = list();
		if (clear) {
			if (onBar) {
				focus = 0;
			} else if (focus == 2) {
				focus = 1;
				row = (targetSlot - 1) / 6;
				col = (targetSlot - 1) % 6;
				scroll = 0;
			} else if (focus == 3) {
				focus = 1;
				int idx = indexOf(moving) - scroll;
				row = idx / 6 + 2;
				col = idx % 6;
				moving = null;
			} else {
				onBar = true;
			}
		} else if (code == Keys.LEFT || code == Keys.K4 || code == Keys.RIGHT || code == Keys.K6) {
			int d = code == Keys.LEFT || code == Keys.K4 ? -1 : 1;
			if (onBar) {
				tab = Math.floorMod(tab + d, 5);
				col = 0;
				row = 0;
				scroll = 0;
			} else if (focus != 3) {
				col = Math.floorMod(col + d, 6);
			}
		} else if (code == Keys.DOWN || code == Keys.K8) {
			if (onBar) {
				onBar = false;
			} else if (focus != 3) {
				down(l.size());
			}
		} else if (code == Keys.UP || code == Keys.K2) {
			if (!onBar && focus != 3) {
				up(l.size());
			}
		} else if (select) {
			select(l);
		}
		return true;
	}

	private void down(int n) {
		if (++row < 5) {
			return;
		}
		if (scroll + visible() < n) {
			scroll += 6;
			row = 4;
		} else {
			scroll = 0;
			row = focus == 2 ? 2 : 0;
		}
	}

	private void up(int n) {
		int r = row - 1;
		if (focus == 1 && tab == 0 && r < 2 && scroll != 0) {
			scroll -= 6;
			return;
		}
		if (focus == 2 && r < 2 || focus != 2 && r < 0) {
			if (scroll == 0) {
				scroll = n - visible() < 1 ? 0 : (n + 5 - visible()) / 6 * 6;
				row = 4;
			} else {
				scroll -= 6;
			}
			return;
		}
		row = r;
	}

	/** SELECT: the bar, a slot cell, a bag item, or (focus 3) the slot to put the item in. */
	private void select(List<Object> l) {
		if (onBar) {
			onBar = false;
			return;
		}
		if (focus == 3) {
			int slot = row * 6 + col + 1;
			if (row < 2 && Inventory.fits(moving.type, slot)) {
				wear(moving, slot);
			}
			return;
		}
		if (focus == 1 && tab == 0 && row < 2) {
			int slot = row * 6 + col + 1;
			// Without the skills that unlock them, slots 2, 7 and 8 stay shut (slot 7 also for class 0).
			if (col == 1 || row == 1 && col < 2) {
				return;
			}
			if (hero.inv.worn(slot) != null) {
				openPopup(new int[] { EQUIP, UNEQUIP, INFO });
			} else {
				focus = 2;
				targetSlot = slot;
				row = 2;
				col = 0;
				scroll = 0;
			}
			return;
		}
		Object o = underCursor(l);
		if (o == null) {
			return;
		}
		if (focus == 2) {
			openPopup(new int[] { EQUIP, INFO });
			return;
		}
		int[] opts = actions(o);
		if (opts.length > 1) {
			openPopup(opts);
		} else if (tab == 2) {
			materialMenu = true;
			options = new int[] { 0, 1, 2, 3 };
			selected = 0;
		} else if (opts.length == 1) {
			act(opts[0], o);
		}
	}

	/** DrawItemSelect's mask for a bag entry, as the bits in ascending order. */
	private int[] actions(Object o) {
		int id = idOf(o);
		int type = items.type(id);
		int d = type == 0x11 || id == 0x1d6 ? 0 : o instanceof Item.Equip e && e.slot != 0 ? UNEQUIP : DISCARD;
		int mask;
		if (o instanceof Item.Equip e) {
			mask = d | INFO | (levelOk(e) ? EQUIP : 0);
		} else if (tab == 1 || id == 0x1d7 || id >= 0xf9 && id <= 0x100 || id == 0x1fc || id == 0x1fd) {
			mask = d | QUICK | (id < 0xf9 || id > 0xfe ? USE : 0);
		} else if (id >= 0x12d && id <= 0x1d6) {
			mask = d | (id == 0x1d6 ? USE : MAKE) | (id >= 0x17a && id <= 0x1d5 ? TRACK : 0);
		} else if (id == 0x1d8 || id >= 0x1e2 && id <= 0x1fb) {
			mask = d | USE;
		} else {
			mask = d;
		}
		int[] bits = new int[Integer.bitCount(mask)];
		for (int b = 1, n = 0; b <= 0x80; b <<= 1) {
			if ((mask & b) != 0) {
				bits[n++] = b;
			}
		}
		return bits;
	}

	private boolean levelOk(Item.Equip e) {
		return hero.level >= e.level && e.dur >= 1;
	}

	private void openPopup(int[] bits) {
		materialMenu = false;
		options = bits;
		selected = 0;
	}

	private void popupKey(int code) {
		if (code == Keys.ESCAPE || code == Keys.BACK_SPACE) {
			options = null;
		} else if (code == Keys.UP || code == Keys.K2) {
			selected = Math.floorMod(selected - 1, options.length);
		} else if (code == Keys.DOWN || code == Keys.K8) {
			selected = Math.floorMod(selected + 1, options.length);
		} else if (code == Keys.ENTER || code == Keys.SPACE || code == Keys.K5) {
			int pick = options[selected];
			boolean material = materialMenu;
			options = null;
			List<Object> l = list();
			Object o = underCursor(l);
			if (material) {
				if (pick == 3 && o != null) {
					act(DISCARD, o);
				} else {
					host.notPorted("재료 " + label(MATERIAL_LABEL, pick));
				}
			} else {
				act(pick, o);
			}
		}
	}

	/** One popup action on the bag entry {@code o}, or on the slot cell under the cursor when o is null. */
	private void act(int bit, Object o) {
		Inventory inv = hero.inv;
		boolean slotCell = focus == 1 && tab == 0 && row < 2;
		int slot = row * 6 + col + 1;
		switch (bit) {
			case EQUIP -> {
				if (slotCell) {
					focus = 2;
					targetSlot = slot;
					row = 2;
					col = 0;
					scroll = 0;
				} else if (o instanceof Item.Equip e && focus == 2) {
					if (levelOk(e)) {
						wear(e, targetSlot);
					} else {
						message(0x38e);
					}
				} else if (o instanceof Item.Equip e) {
					int s = firstSlot(e.type);
					if (!levelOk(e) || s == 0) {
						message(0x38e);
					} else {
						focus = 3;
						moving = e;
						row = (s - 1) / 6;
						col = (s - 1) % 6;
					}
				}
			}
			case UNEQUIP -> {
				Item.Equip e = slotCell ? inv.worn(slot) : o instanceof Item.Equip x ? x : null;
				if (e != null) {
					e.slot = 0;
					host.changed();
				}
			}
			case USE -> {
				int id = idOf(o);
				host.confirm(Ksc.encode(items.name(id)), so.cbytes(USE_TEXT), () -> host.use(id));
			}
			case INFO -> {
				Object target = slotCell ? inv.worn(slot) : o;
				if (target != null) {
					host.message(Ksc.encode(summary(target)));
				}
			}
			case DISCARD -> {
				boolean gear = o instanceof Item.Equip;
				host.confirm(so.cbytes(gear ? DISCARD_EQUIP_TITLE : DISCARD_ITEM_TITLE),
						so.cbytes(gear ? DISCARD_EQUIP_TEXT : DISCARD_ITEM_TEXT), () -> discard(o));
			}
			default -> host.notPorted("아이템 " + label(ACTION_LABEL, Integer.numberOfTrailingZeros(bit)));
		}
	}

	/** GMenu 0xce's yes: an instance goes, or the whole stack (gmenu+0x30 = the count). */
	private void discard(Object o) {
		Inventory inv = hero.inv;
		if (o instanceof Item.Equip e) {
			for (int i = 0; i < Inventory.EQUIP; i++) {
				if (inv.equip[i] == e) {
					inv.equip[i] = null;
				}
			}
			if (e.slot != 0) {
				host.changed();
			}
		} else {
			inv.stackCount[(Integer) o] = 0;
		}
		inv.sort();
		int n = list().size();
		if (scroll > 0 && scroll >= n) {
			scroll -= 6;
		}
	}

	private void wear(Item.Equip e, int slot) {
		if (hero.inv.wear(e, slot, hero.level)) {
			host.changed();
		} else {
			message(0x38e);
		}
		focus = 1;
		moving = null;
		row = (slot - 1) / 6;
		col = (slot - 1) % 6;
		scroll = 0;
	}

	/** GetGOrderEquipWear's first fitting slot for a type, or 0. */
	private static int firstSlot(int type) {
		for (int s = 1; s <= 12; s++) {
			if (Inventory.fits(type, s)) {
				return s;
			}
		}
		return 0;
	}

	private int indexOf(Item.Equip e) {
		List<Object> l = list();
		for (int i = 0; i < l.size(); i++) {
			if (l.get(i) == e) {
				return i;
			}
		}
		return 0;
	}

	// ---- summary (GetGOrderItemSummary) ---------------------------------------------------

	/** GetGOrderItemSummary for the info box: an equipment instance or a bag stack slot. */
	String summary(Object o) {
		if (o instanceof Item.Equip e) {
			return gearSummary(e);
		}
		int slot = (Integer) o;
		return stackSummary(hero.inv.stackId[slot], hero.inv.stackCount[slot]);
	}

	// ---- drawing ---------------------------------------------------------------------------

	/** DrawGOrder for ITEM, then DrawItemSelect; called once per frame on the 480x320 layer. */
	void draw(Gfx g) {
		frames++;
		List<Object> l = list();
		drawBack(g);
		drawItemStatic(g, l);
		drawHero(g);
		drawCursor(g, l);
		if (options != null) {
			drawSelect(g);
		}
	}

	/** GOrderFuncBack and DrawGOrderBack: the seven tab icons, ITEM (the second) active. */
	private void drawBack(Gfx g) {
		back(g, new int[] { 0x88, 0x90, 0x98, 0xa0, 0xa8, 0xb0, 0xb8 }, 0x40 + 8);
	}

	private void drawItemStatic(Gfx g, List<Object> l) {
		// The category bar: its background, the active tab's highlight and the five icons.
		clix.sprite(g, gorder, Clix.GORDER, 0xb8, 0, 0);
		clix.sprite(g, gorder, Clix.GORDER, 0xd0, 0x19 * tab, 0, focus != 0 ? 1 : 0);
		for (int i = 0; i < 5; i++) {
			clix.sprite(g, gorder, Clix.GORDER, 0x210 + 8 * i, 0, 0, i == tab && focus != 0 ? 1 : 0);
		}
		// The hero box, HP and SP bars.
		int[] hb = rect(Clix.GORDER, 0x2b8);
		edge(g, hb[0], hb[1], hb[2], hb[3], EDGE);
		g.fillRect(hb[0] + 1, hb[1] + 1, hb[2] - 2, hb[3] - 2, rgb(0xbbe9ff00));
		clix.sprite(g, gorder, Clix.GORDER, 0x238, 0, 0);
		clix.sprite(g, gorder, Clix.GORDER, 0x240, 0, 0);
		bar(g, 0x3a0, 0x3b0, 0x3c0, hero.hp, hero.maxHp);
		bar(g, 0x3a8, 0x3b8, 0x3c8, hero.maxMp, hero.maxMp);
		money(g, 0x2e8, 0x2f0);
		valueBox(g, 0x2f8, String.valueOf(hero.statPower + weaponValue()), fmt(ATK_LABEL));
		valueBox(g, 0x300, String.valueOf(hero.armor), fmt(DEF_LABEL));
		if (tab == 0) {
			mini(g, fmt(SET_1), rect(Clix.GORDER, 0x2c8), 0, 0);
			mini(g, fmt(SET_2), rect(Clix.GORDER, 0x2d0), 0, 0);
			drawSlots(g);
		}
		drawBag(g, l);
		// The info box with the summary of the item under the cursor.
		int[] ib = rect(Clix.GORDER, 0x308);
		g.fillRect(ib[0], ib[1], ib[2], ib[3], 3 * 32 << 24);
		Object o = focus == 0 ? null : tab == 0 && row < 2 && !onBar ? hero.inv.worn(row * 6 + col + 1) : underCursor(l);
		if (o != null) {
			int[] tb = rect(Clix.GORDER, 0x310);
			text(g, summary(o), tb[0], tb[1], tb[2]);
		}
	}

	private int weaponValue() {
		Item.Equip w = hero.inv.worn(1);
		return w == null || w.dur == 0 ? 0 : w.value;
	}

	/** The slot grid: twelve cells in two rows, worn gear or an empty slot's silhouette. */
	private void drawSlots(Gfx g) {
		int[] s = rect(Clix.GORDER, 0x2c0);
		edge(g, s[0], s[1], s[2], s[3], EDGE);
		edge(g, s[0] - 1, s[1] - 1, s[2] + 2, s[3] + 2, EDGE);
		int ix = s[0] + 1;
		int iy = s[1] + 1;
		for (int j = 0; j < 5; j++) {
			g.fillRect(ix + 0x13 + 0x14 * j, iy, 1, s[3] - 2, rgb(0x2463a300));
		}
		g.fillRect(ix, iy + 0x13, s[2] - 2, 1, rgb(0x2463a300));
		for (int idx = 0; idx < 12; idx++) {
			int cx = ix + idx % 6 * 0x14;
			int cy = iy + idx / 6 * 0x14;
			Item.Equip e = hero.inv.worn(idx + 1);
			if (e != null) {
				icon(g, cx, cy, e.id, 0, e.grade);
				if (e.dur == 0) {
					g.fillRect(cx, cy, 0x13, 0x13, 4 * 32 << 24 | rgb(0x4000) & 0xffffff);
				}
			} else if (idx != 1 && idx != 6 && idx != 7) {
				// Slots 2, 7 and 8 need skills: their silhouettes stay off as the original leaves them.
				clix.sprite(g, gorder, Clix.GORDER, 0x248 + 8 * idx, 0, 0);
			}
		}
	}

	/** The bag grid, the scroll bar, the count and the shading of cells past the capacity. */
	private void drawBag(Gfx g, List<Object> l) {
		int[] b = rect(Clix.GORDER, tab == 0 ? 0x2d8 : 0x2e0);
		edge(g, b[0], b[1], b[2], b[3], EDGE);
		int ix = b[0] + 1;
		int iy = b[1] + 1;
		int limit = tab == 0 ? 0x28 : 0x50;
		for (int j = 0; j < 5; j++) {
			g.fillRect(ix + 0x13 + 0x14 * j, iy, 1, b[3] - 2, rgb(0x2463a300));
		}
		for (int r = 0; 0x13 + 0x14 * r <= limit; r++) {
			g.fillRect(ix, iy + 0x13 + 0x14 * r, b[2] - 2, 1, rgb(0x2463a300));
		}
		for (int n = scroll; n < l.size() && n - scroll < visible(); n++) {
			int v = n - scroll;
			int cx = ix + v % 6 * 0x14;
			int cy = iy + v / 6 * 0x14;
			Object o = l.get(n);
			if (o instanceof Item.Equip e) {
				icon(g, cx, cy, e.id, 0, e.grade);
				if (e.slot != 0) {
					clix.sprite(g, gameui, Clix.GAMEUI, 0x240, cx, cy);
				}
				if (!levelOk(e)) {
					g.fillRect(cx, cy, 0x13, 0x13, 5 * 32 << 24 | rgb(0x4000) & 0xffffff);
				}
			} else {
				int slot = (Integer) o;
				icon(g, cx, cy, hero.inv.stackId[slot], 0, tab == 4 ? hero.inv.stackGrade[slot] : 0);
			}
		}
		if (focus < 2) {
			for (int v = 0; v < visible(); v++) {
				if (scroll + v >= capacity() + (tab == 3 ? 10 : 0)) {
					g.fillRect(ix + v % 6 * 0x14, iy + v / 6 * 0x14, 0x13, 0x13, 4 * 32 << 24 | rgb(EDGE) & 0xffffff);
				}
			}
		}
		int steps = (l.size() - (tab == 0 ? 13 : 25)) / 6;
		if (steps > 0) {
			int[] sb = rect(Clix.GORDER, 0x318);
			g.fillRect(sb[0], sb[1], sb[2], sb[3], rgb(0x6b99c700));
			int len = steps >= 11 ? sb[3] / 10 : sb[3] / (steps + 1);
			g.fillRect(sb[0], sb[1] + Math.min(sb[3] - len, scroll / 6 * len), sb[2], len, rgb(0x1c548c00));
		}
		int listed = l.size();
		mini(g, listed + "/" + capacity(), rect(Clix.GORDER, 0x320), 0x12, listed >= capacity() ? 3 : 0);
	}

	/** DrawGOrderHero in the hero box: the hero as worn, standing or walking at random. */
	private void drawHero(Gfx g) {
		int[] hb = rect(Clix.GORDER, 0x2b8);
		if ((frames & 0xf) == 0 && previewRand.nextInt(1000) <= 499) {
			previewAct = previewRand.nextInt(2) == 0 ? HeroAnim.ACT_STAND : HeroAnim.ACT_WALK;
		}
		HeroAnim.Look look = HeroAnim.wearing(items, hero.inv.worn(), hero.heroClass, 1, previewAct);
		int nudge = look.kind() == 0 ? 4 : look.kind() == 2 ? 8 : 0;
		PartSprites.Pose p;
		try {
			p = heroes.pose(look, frames / 2);
		} catch (IOException e) {
			p = null;
		}
		if (p != null) {
			int[] old = g.getClip();
			g.setClip(new int[] { hb[0] + 1, hb[1] + 1, hb[2] - 2, hb[3] - 2 });
			int x = hb[0] + 1 + (hb[2] - 2) / 2 + nudge;
			int y = hb[1] + hb[3] - 0x10;
			g.drawImage(p.image(), x - p.anchorX(), y - p.anchorY());
			g.setClip(old);
		}
		// The level after gameui+0x398's label, and the category bar's bobbing side arrows.
		clix.sprite(g, gameui, Clix.GAMEUI, 0x398, 0, 0);
		String lv = String.format("%02d", hero.level);
		int lx = clix.x(Clix.GAMEUI, 0x398);
		int ly = clix.y(Clix.GAMEUI, 0x398);
		glyph(g, lv.charAt(0), lx + 0x11, ly, 0);
		glyph(g, lv.charAt(1), lx + 0x17, ly, 0);
		int bob = so.s8(CURSOR_BOB + (frames >> 1) % 6);
		clix.sprite(g, gorder, Clix.GORDER, 0xc0, bob, 0);
		clix.sprite(g, gorder, Clix.GORDER, 0xc8, -bob, 0);
	}

	/** GetGOrderSelRects and DrawGOrderCursor: the cell under the cursor framed, and the arrow. */
	private void drawCursor(Gfx g, List<Object> l) {
		int x;
		int y;
		int w = 0x13;
		int h = 0x13;
		if (focus == 0) {
			x = clix.x(Clix.IORDER, 0x48);
			y = clix.y(Clix.IORDER, 0x48);
			w = 0x3c;
			h = 0x14;
		} else if (onBar) {
			int off = 0x210 + 8 * tab;
			x = clix.x(Clix.GORDER, off);
			y = clix.y(Clix.GORDER, off) - 1;
			w = 0x12;
			h = 0x0f;
		} else {
			int bx;
			int by;
			if (tab == 0 && row < 2) {
				bx = clix.x(Clix.GORDER, 0x2c0);
				by = clix.y(Clix.GORDER, 0x2c0);
			} else if (tab == 0) {
				bx = clix.x(Clix.GORDER, 0x2d8);
				by = clix.y(Clix.GORDER, 0x2d8) - 0x28;
			} else {
				bx = clix.x(Clix.GORDER, 0x2e0);
				by = clix.y(Clix.GORDER, 0x2e0);
			}
			x = bx + col * 0x14 + 1;
			y = by + row * 0x14 + 1;
		}
		if (focus == 2) {
			slotEdge(g, targetSlot);
		} else if (focus == 3 && moving != null) {
			for (int s = 1; s <= 12; s++) {
				if (Inventory.fits(moving.type, s)) {
					slotEdge(g, s);
				}
			}
		}
		cursor(g, x, y, w, h);
	}

	private void slotEdge(Gfx g, int slot) {
		int sx = clix.x(Clix.GORDER, 0x2c0) + (slot - 1) % 6 * 0x14;
		int sy = clix.y(Clix.GORDER, 0x2c0) + (slot - 1) / 6 * 0x14;
		edge(g, sx, sy, 0x15, 0x15, rgb(0x40ff4000));
	}

	/** DrawSelect: the popup by the arrow, one 100 x 22 row per option. */
	private void drawSelect(Gfx g) {
		int n = options.length;
		int x = arrowX > 0xf0 ? arrowX - 0x91 : arrowX;
		int y = Math.min(arrowY, 0xaa);
		int bg = n == 5 ? 0x28 : n == 4 ? 0x30 : 0x38;
		clix.sprite(g, imenu, Clix.IORDER, bg, x, y);
		int pad = n == 4 || n == 5 ? 0xc : (0x5a - 0x16 * n) / 2;
		for (int i = 0; i < n; i++) {
			int ry = y + pad + 0x17 * i;
			boolean sel = i == selected;
			g.fillRect(x + 0xc, ry, 0x64, 0x16, 3 * 32 << 24 | (sel ? 0xffffff : 0));
			String s = materialMenu ? label(MATERIAL_LABEL, options[i]) : label(ACTION_LABEL, Integer.numberOfTrailingZeros(options[i]));
			g.drawString(s, x + 0xc + (0x64 - Gfx.stringWidth(s)) / 2, ry + (0x16 - 16) / 2, rgb(sel ? EDGE : 0xd9f3ff00));
			if (sel) {
				edge(g, x + 0xb, ry - 1, 0x66, 0x18, EDGE);
				edge(g, x + 0xa, ry - 2, 0x68, 0x1a, rgb(0x88fffe00));
			}
		}
	}

	/** An HP or SP bar: the empty frame, the full one cut to value / max, and the 1 px border. */
	private void bar(Gfx g, int full, int empty, int frameRect, int value, int max) {
		clix.sprite(g, gameui, Clix.GAMEUI, empty, 0, 0);
		int fx = clix.x(Clix.GAMEUI, full);
		int fy = clix.y(Clix.GAMEUI, full);
		int fw = gameui.width(clix.frame(Clix.GAMEUI, full));
		int[] old = g.getClip();
		g.clipRect(fx, fy, max <= 0 ? 0 : fw * Math.max(0, Math.min(value, max)) / max, H);
		clix.sprite(g, gameui, Clix.GAMEUI, full, 0, 0);
		g.setClip(old);
		int[] r = rect(Clix.GAMEUI, frameRect);
		edge(g, r[0], r[1], r[2], r[3], EDGE);
	}

	/** A white 3/8 box with a right-aligned value in mini digits and its label on the left. */
	private void valueBox(Gfx g, int off, String value, String label) {
		int[] r = rect(Clix.GORDER, off);
		g.fillRect(r[0], r[1], r[2], r[3], 3 * 32 << 24 | 0xffffff);
		mini(g, value + " ", r, 0x12, 6);
		mini(g, label, r, 0x10, 5);
	}

}

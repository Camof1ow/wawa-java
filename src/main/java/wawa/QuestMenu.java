package wawa;

import java.io.IOException;

/**
 * GOrder 5 (the menu's QUEST tab: the seven quest slots, a summary, 상세보기 / 삭제) and GOrder 8
 * (a town's quest board: up to five free quests and their description), drawn from clix_gorder
 * 0x478.. as DrawGOrder does (GOrder.c:1322-1430, 1685-1747; PressGOrderQuest.c, PressGOrderBoard.c).
 */
final class QuestMenu extends OrderScreen {

	/** The GMenus these screens open, and what accepting a board quest does. */
	interface Popups {
		/** GMenu 0x67: the slot's summary with its description, "확인". */
		void detail(Quests.Slot s);

		/** GMenu 0x3a5: "선택 퀘스트를 삭제합니다." with 확인 / 취소; 확인 empties the slot. */
		void delete(int slot);

		/** No free slot for a board quest: the running phases close with 3 and GMenu 900 shows. */
		void full();

		/** A board quest taken: "퀘스트 수락" when a phase runs. */
		void accepted(int id);
	}

	/** Row text colours: a list row, a board row, and either row under the cursor. */
	private static final int LIST_INK = 0xd9f3ff00;
	private static final int BOARD_INK = 0x73acef00;
	private static final int SELECTED_INK = 0x1a2a4300;
	/** The status prefixes: none, "완료)", "실패)", "진행중)". */
	private static final int[] PREFIX = { 0xd4068, 0xd406c, 0xd4078, 0xd4084 };
	private static final int SUB_LABEL = 0xd40d0;
	private static final int DETAIL = 0xd3db8;
	private static final int DELETE = 0xd3df4;

	private final Quests quests;
	private final Popups popups;
	/** The quest list icons (img/town, frame QT &gt;&gt; 5). */
	private final Gsac town;
	private boolean board;
	/** The board's quests (gorder+0x118). */
	private int[] list = new int[0];
	/** gorder+8: 0 the tab column (the list only), 1 the rows. */
	private int focus;
	/** gorder+0x38: the row under the cursor. */
	private int cur;
	/** gbselect / gnselect: the 상세보기 / 삭제 popup and its row. */
	private boolean popup;
	private int popupRow;
	/** TextScroll: how far the selected title has scrolled, and the frames it waits at the start. */
	private int scroll;
	private int scrollWait = 8;

	QuestMenu(Sheets s, ElfImage so, Item items, Assets assets, Quests quests, Popups popups) throws IOException {
		super(s, so, items);
		this.quests = quests;
		this.popups = popups;
		town = Gsac.loadCompanion(assets, "assets/img/town.png");
	}

	/** InitGOrder 5 (the list, focus on the tab column) or 8 (the board over {@code ids}, focus on the rows). */
	void open(Hero hero, Host host, boolean board, int[] ids) {
		this.hero = hero;
		this.host = host;
		this.board = board;
		list = ids == null ? new int[0] : ids;
		focus = board ? 1 : 0;
		cur = 0;
		popup = false;
		arrowX = -1;
		resetScroll();
	}

	private void resetScroll() {
		scroll = 0;
		scrollWait = 8;
	}

	// ---- keys -----------------------------------------------------------------------------

	@Override
	boolean key(int code) {
		boolean back = code == Keys.ESCAPE || code == Keys.BACK_SPACE;
		boolean ok = code == Keys.ENTER || code == Keys.SPACE || code == Keys.K5;
		boolean up = code == Keys.UP || code == Keys.K2;
		boolean down = code == Keys.DOWN || code == Keys.K8;
		if (board) {
			return boardKey(back, ok, up, down);
		}
		if (focus == 0) {
			if (back) {
				host.close();
				return false;
			}
			if (up || down) {
				host.switchTab(up ? -1 : 1);
			} else if (ok || code == Keys.RIGHT || code == Keys.K6) {
				focus = 1;
				cur = 0;
				resetScroll();
			}
			return true;
		}
		if (popup) {
			if (up || down) {
				popupRow ^= 1;
			} else if (back) {
				popup = false;
			} else if (ok) {
				Quests.Slot s = quests.slots[cur];
				if (popupRow == 0) {
					// The popup stays open behind 0x67.
					popups.detail(s);
				} else if (cur >= 1 && s.id1 != 0) {
					popup = false;
					popups.delete(cur);
				}
			}
			return true;
		}
		if (up || down) {
			cur = (cur + (up ? 6 : 1)) % 7;
			resetScroll();
		} else if (back) {
			focus = 0;
		} else if (ok && quests.slots[cur].id1 != 0) {
			if (cur == 0) {
				popups.detail(quests.slots[0]);
			} else {
				popup = true;
				popupRow = 0;
			}
		}
		return true;
	}

	private boolean boardKey(boolean back, boolean ok, boolean up, boolean down) {
		int n = list.length;
		if (back) {
			host.close();
			return false;
		}
		if ((up || down) && n > 0) {
			cur = (cur + (up ? n - 1 : 1)) % n;
			resetScroll();
		} else if (ok && n > 0) {
			int id = list[cur];
			for (int s = 5; s <= 6; s++) {
				Quests.Slot slot = quests.slots[s];
				if (slot.id1 == 0 || slot.id1 == id + 1) {
					quests.init(slot, id, true);
					popups.accepted(id);
					host.close();
					return false;
				}
			}
			popups.full();
		}
		return true;
	}

	// ---- drawing --------------------------------------------------------------------------

	@Override
	void draw(Gfx g) {
		frames++;
		if (board) {
			drawBoard(g);
		} else {
			drawList(g);
		}
	}

	/**
	 * GOrder 5: the tabs with QUEST active, then per slot its row (clix_gorder 0x478 for the
	 * MAIN / SUB / FREE head rows, 0x480 otherwise) at a 22 px pitch: the status prefix and title
	 * in 0x488, the list icon at 0x498 and the head label at 0x490; the summary panel 0x4a0 with
	 * the summary in 0x4a8; the cursor; the 상세보기 / 삭제 popup.
	 */
	private void drawList(Gfx g) {
		back(g, new int[] { 0x88, 0x90, 0x98, 0xa0, 0xa8, 0xb0, 0xb8 }, 0x68);
		for (int i = 0; i < 7; i++) {
			int dy = i * 0x16;
			boolean sel = focus != 0 && cur == i;
			int[] r = rect(Clix.GORDER, i == 0 || i == 1 || i == 5 ? 0x478 : 0x480);
			g.fillRect(r[0], r[1] + dy, r[2], r[3], 3 * 32 << 24 | (sel ? 0xffffff : 0));
			Quests.Slot s = quests.slots[i];
			if (s.id1 != 0) {
				QuestBook.Def def = quests.def(s.id1 - 1);
				String title = fmt(PREFIX[prefix(s.flags)]) + Ksc.decode(def.title, 0, def.title.length);
				row(g, title, rect(Clix.GORDER, 0x488), dy, 0, sel, LIST_INK);
				int[] ic = rect(Clix.GORDER, 0x498);
				Img f = town.frame(def.qt >> 5);
				if (f != null) {
					g.drawImage(f, ic[0], ic[1] + dy);
				}
			}
			String label = i == 0 ? "|MAIN" : i == 1 ? fmt(SUB_LABEL) : i == 5 ? "|FREE" : null;
			if (label != null) {
				int[] lr = rect(Clix.GORDER, 0x490);
				lr[1] += dy;
				mini(g, label, lr, 0x10, 4 + (sel ? 1 : 0));
			}
		}
		int[] p = rect(Clix.GORDER, 0x4a0);
		g.fillRect(p[0], p[1], p[2], p[3], 3 * 32 << 24);
		if (focus != 0 && quests.slots[cur].id1 != 0) {
			byte[] sum = quests.summary(quests.slots[cur], false);
			int[] tb = rect(Clix.GORDER, 0x4a8);
			int[] old = g.getClip();
			g.clipRect(tb[0], tb[1], tb[2], tb[3]);
			text(g, Ksc.decode(sum, 0, sum.length), tb[0], tb[1], tb[2]);
			g.setClip(old);
		}
		if (focus == 0) {
			cursor(g, clix.x(Clix.IORDER, 0x68), clix.y(Clix.IORDER, 0x68), 0x3c, 0x14);
		} else {
			int[] r = rect(Clix.GORDER, cur == 0 || cur == 1 || cur == 5 ? 0x478 : 0x480);
			cursor(g, r[0], r[1] + cur * 0x16, r[2], r[3]);
		}
		if (popup) {
			drawPopup(g);
		}
	}

	/** The prefix DrawGOrder picks: failed 2; complete 1, or 3 when the quest has no goal lists; open 0. */
	private static int prefix(int flags) {
		if ((flags & 0x80) != 0) {
			return 2;
		}
		if ((flags & 0x40) != 0) {
			return (flags & 0xf) == 0 ? 3 : 1;
		}
		return 0;
	}

	/**
	 * GOrder 8: the frame and panel with no tabs, black over clix_gorder 0x4b0; for each quest a
	 * row (0x478 for the first, 0x480 after) with its title in 0x488 widened by 10 and "|FREE" on
	 * the first; the selected quest's description in 0x4b8. An empty board draws nothing more.
	 */
	private void drawBoard(Gfx g) {
		g.fillRect(0, 0, W, H, 0x80000000);
		for (int off : new int[] { 0x10, 0x18, 0x20, 0x28, 0x30 }) {
			clix.sprite(g, gorder, Clix.GORDER, off, 0, 0);
		}
		clix.sprite(g, gorder, Clix.GORDER, 0x00, 0, 0);
		int[] p = rect(Clix.GORDER, 0x4b0);
		g.fillRect(p[0], p[1], p[2], p[3], 3 * 32 << 24);
		if (list.length == 0) {
			return;
		}
		for (int i = 0; i < list.length; i++) {
			int dy = i * 0x16;
			boolean sel = cur == i;
			int[] r = rect(Clix.GORDER, i == 0 ? 0x478 : 0x480);
			g.fillRect(r[0], r[1] + dy, r[2], r[3], 3 * 32 << 24 | (sel ? 0xffffff : 0));
			byte[] title = quests.def(list[i]).title;
			row(g, Ksc.decode(title, 0, title.length), rect(Clix.GORDER, 0x488), dy, 10, sel, BOARD_INK);
			if (i == 0) {
				int[] lr = rect(Clix.GORDER, 0x490);
				mini(g, "|FREE", lr, 0x10, 4 + (sel ? 1 : 0));
			}
		}
		byte[] desc = quests.def(list[cur]).desc;
		int[] tb = rect(Clix.GORDER, 0x4b8);
		int[] old = g.getClip();
		g.clipRect(tb[0], tb[1], tb[2], tb[3]);
		text(g, Ksc.decode(desc, 0, desc.length), tb[0], tb[1], tb[2]);
		g.setClip(old);
		int[] r = rect(Clix.GORDER, cur == 0 ? 0x478 : 0x480);
		cursor(g, r[0], r[1] + cur * 0x16, r[2], r[3]);
	}

	/**
	 * A row's title in cell {@code c} (moved down by dy, widened by {@code extra}), centred down:
	 * DrawTextColor clipped to the cell, or under the cursor TextScroll, which slides a title too
	 * wide for the cell 5 px a frame after 8 frames' wait and draws it again 30 px behind.
	 */
	private void row(Gfx g, String s, int[] c, int dy, int extra, boolean sel, int ink) {
		int x = c[0];
		int y = c[1] + dy + ((c[3] - 15 + 1) >> 1);
		int w = c[2] + extra;
		int argb = rgb(sel ? SELECTED_INK : ink);
		int width = Gfx.stringWidth(s.replaceAll("\u0007.", ""));
		int[] old = g.getClip();
		g.clipRect(x, c[1] + dy, w, c[3]);
		if (!sel || width <= w) {
			text(g, s, x, y, 0x7fff, argb);
		} else {
			text(g, s, x - scroll, y, 0x7fff, argb);
			text(g, s, x - scroll + width + 30, y, 0x7fff, argb);
			if (scrollWait > 0) {
				scrollWait--;
			} else if ((scroll += 5) > width + 30) {
				resetScroll();
			}
		}
		g.setClip(old);
	}

	/** DrawQuestSelect: 상세보기 / 삭제 by the arrow, as DrawSelect lays out two rows. */
	private void drawPopup(Gfx g) {
		int x = arrowX > 0xf0 ? arrowX - 0x91 : arrowX;
		int y = Math.min(arrowY, 0xaa);
		clix.sprite(g, imenu, Clix.IORDER, 0x38, x, y);
		String[] labels = { fmt(DETAIL), fmt(DELETE) };
		for (int i = 0; i < 2; i++) {
			int ry = y + 23 + 0x17 * i;
			boolean sel = i == popupRow;
			g.fillRect(x + 12, ry, 100, 22, 3 * 32 << 24 | (sel ? 0xffffff : 0));
			g.drawString(labels[i], x + 12 + (100 - Gfx.stringWidth(labels[i])) / 2, ry + (22 - 16) / 2,
					rgb(sel ? SELECTED_INK : 0xd9f3ff00));
			if (sel) {
				edge(g, x + 11, ry - 1, 102, 24, EDGE);
				edge(g, x + 10, ry - 2, 104, 26, rgb(0x88fffe00));
			}
		}
	}
}

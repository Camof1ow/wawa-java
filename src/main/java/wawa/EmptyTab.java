package wawa;

/**
 * A menu tab this port has not ported yet (STATUS, SKILL, PET, LOG, SYSTEM): GOrderFuncBack and
 * DrawGOrderBack with its icon active, the cursor on the tab column, and the tab column's keys
 * (up / down to the next tab, back to close).
 */
final class EmptyTab extends OrderScreen {

	private int tab;

	EmptyTab(Sheets s, ElfImage so, Item items) {
		super(s, so, items);
	}

	void open(Hero hero, Host host, int tab) {
		this.hero = hero;
		this.host = host;
		this.tab = tab;
		arrowX = -1;
	}

	@Override
	boolean key(int code) {
		if (code == Keys.ESCAPE || code == Keys.BACK_SPACE) {
			host.close();
			return false;
		}
		if (code == Keys.UP || code == Keys.K2 || code == Keys.DOWN || code == Keys.K8) {
			host.switchTab(code == Keys.UP || code == Keys.K2 ? -1 : 1);
		}
		return true;
	}

	@Override
	void draw(Gfx g) {
		frames++;
		back(g, new int[] { 0x88, 0x90, 0x98, 0xa0, 0xa8, 0xb0, 0xb8 }, 0x40 + 8 * tab);
		cursor(g, clix.x(Clix.IORDER, 0x40 + 8 * tab), clix.y(Clix.IORDER, 0x40 + 8 * tab), 0x3c, 0x14);
	}
}

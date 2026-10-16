package wawa;

/**
 * The static clix tables in libwawa.so: 8-byte entries that are either sprite placements
 * {s16 dx, s16 dy, s16 frame, u16 flags} or rectangles {s16 x, y, w, h}. A sprite's flags hold
 * its palette row (&gt;&gt; 8 &amp; 0x7f) and flip (&gt;&gt; 4 &amp; 3: bit 0 across, bit 1 down).
 */
final class Clix {

	static final int IMENU = 0xe324c;
	static final int IORDER = 0xe331c;
	static final int GAMEUI = 0xe34cc;
	static final int GMENU = 0xe3b3c;
	static final int GORDER = 0xe3da4;
	static final int ICON = 0xe4994;

	private final ElfImage so;

	Clix(ElfImage so) {
		this.so = so;
	}

	int x(int table, int off) {
		return so.s16(table + off);
	}

	int y(int table, int off) {
		return so.s16(table + off + 2);
	}

	/** A rectangle entry's width, or a sprite entry's frame. */
	int w(int table, int off) {
		return so.s16(table + off + 4);
	}

	int h(int table, int off) {
		return so.s16(table + off + 6);
	}

	int frame(int table, int off) {
		return so.s16(table + off + 4);
	}

	/** DrawSpriteClix: the entry's frame at (x + dx, y + dy), in the entry's palette row and flip. */
	void sprite(Gfx g, Gsac sheet, int table, int off, int x, int y) {
		sprite(g, sheet, table, off, x, y, so.u16(table + off + 6) >> 8 & 0x7f);
	}

	/** DrawSpriteClixPal: the same with the palette row given. */
	void sprite(Gfx g, Gsac sheet, int table, int off, int x, int y, int row) {
		int f = frame(table, off);
		if (f < 0 || f >= sheet.frameCount()) {
			return;
		}
		Img img = sheet.frame(f, row, 0);
		int flip = so.u16(table + off + 6) >> 4 & 3;
		g.drawImage(img, x + x(table, off), y + y(table, off), (flip & 1) != 0 ? -img.w : img.w,
				(flip & 2) != 0 ? -img.h : img.h);
	}
}

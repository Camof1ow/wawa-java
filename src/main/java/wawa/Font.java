package wawa;

/**
 * DrawString's (libwawa.so 0x365f4) metrics: which characters its bitmap font draws and how far the
 * pen moves. The glyphs themselves are drawn by the screen with a vector font (Galmuri), in the cells
 * the original lays out. Its GOT entries point at the bold arrays, eng_12_b and han_11_b:
 *
 * <pre>
 * eng_12_b  0xe701c   8x16, one byte per row, MSB left; char c (0x21..0x7f) at (c - 0x20) * 16
 * han_11_b  0xf947c   16x15, two bytes per row; 30 bytes per glyph:
 *                     0..2349      KS X 1001 Hangul 0xb0a1..0xc8fe, ((b1 - 0xb0) * 94 + b2 - 0xa1)
 *                     2350..2400   jamo 0xa4a1..0xa4d3
 *                     2401..2407   0xa4fd/0xa1a4, 0xa1a5, 0xa2b8, 0xa2ba, 0xa1e3, 0xa1e5, 0xa1a6
 * </pre>
 *
 * The offsets below are DrawString's literals (DAT_0003694c..74) as they stand in the image.
 */
final class Font {

	/** The han_11_b offset DrawString picks for a two-byte character, or -1 when it draws none. */
	static int han(int b1, int b2) {
		if (b1 >= 0xb0 && b1 <= 0xc8 && b2 >= 0xa1 && b2 != 0xff) {
			return b1 * 0xb04 + 0xfff86d40 + b2 * 0x1e + 0xffffed22;
		}
		if (b1 == 0xa4) {
			return b2 > 0xa0 && b2 < 0xd4 ? b2 * 0x1e + 0x10086 : b2 == 0xfd ? 0x1195e : -1;
		}
		if (b1 == 0xa1) {
			return switch (b2) {
				case 0xe3 -> 0x119d6;
				case 0xe5 -> 0x119f4;
				case 0xa4 -> 0x1195e;
				case 0xa5 -> 0x1197c;
				case 0xa6 -> 0x11a12;
				default -> -1;
			};
		}
		if (b1 == 0xa2) {
			return b2 == 0xb8 ? 0x1199a : b2 == 0xba ? 0x119b8 : -1;
		}
		return -1;
	}

	/** DrawString's pen advance for a two-byte character: 16, but 30 for an undrawn 0xa1 row symbol. */
	static int advance(int b1, int b2) {
		return b1 == 0xa1 && han(b1, b2) < 0 ? 30 : 16;
	}

	private Font() {
	}
}

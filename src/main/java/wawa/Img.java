package wawa;

import java.util.ArrayList;
import java.util.List;

/**
 * An ARGB pixel buffer: {@code px[y * w + x]} holds 0xAARRGGBB, not premultiplied.
 * Text is not rasterised into the pixels: {@link Gfx} keeps it in {@link #text} as glyphs, so the
 * screen can draw it with a vector font at its own resolution.
 */
final class Img {

	final int w;
	final int h;
	final int[] px;
	/** Glyphs drawn on this image, in drawing order, in its pixel coordinates. */
	final List<Glyph> text = new ArrayList<>();

	Img(int w, int h) {
		this(w, h, new int[w * h]);
	}

	Img(int w, int h, int[] px) {
		this.w = w;
		this.h = h;
		this.px = px;
	}

	/**
	 * One character in a cell of the original font, {@code scale} times its size: the cell's top-left
	 * is (x, y), Hangul cells are 16 x 15 and ASCII ones 8 x 16. The clip is in the same pixels, end exclusive.
	 */
	static final class Glyph {
		/** The vector font, GalmuriMono11 (OFL): its Hangul is twice its ASCII width, like the original's 16 and 8 px cells. */
		static final String FONT = "GalmuriMono11";
		/** Font size for scale 1: GalmuriMono11's 12-unit Hangul advance becomes the original 16 px. */
		static final float EM = 16;
		/** The baseline below the cell top for scale 1: Hangul ink fills rows 0..14 like the original's. */
		static final float BASELINE = 15;

		final String ch;
		final float x;
		final float y;
		final float scale;
		/** Changed when something is drawn over the glyph after it (see {@link Gfx}). */
		int argb;
		final float cx0;
		final float cy0;
		final float cx1;
		final float cy1;

		Glyph(String ch, float x, float y, float scale, int argb, float cx0, float cy0, float cx1, float cy1) {
			this.ch = ch;
			this.x = x;
			this.y = y;
			this.scale = scale;
			this.argb = argb;
			this.cx0 = cx0;
			this.cy0 = cy0;
			this.cx1 = cx1;
			this.cy1 = cy1;
		}

		/** The cell's width in this image's pixels. */
		float width() {
			return (ch.charAt(0) < 0x80 ? 8 : 16) * scale;
		}

		float height() {
			return (ch.charAt(0) < 0x80 ? 16 : 15) * scale;
		}
	}
}

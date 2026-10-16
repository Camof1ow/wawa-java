package wawa;

/**
 * Software renderer into an {@link Img}, the way the original draws into its frame buffer.
 * Translation and clip behave like AWT's: both are given in the current (translated) coordinates.
 * Colours blend source-over. Text goes to the target's glyph list instead of its pixels; drawing
 * an image carries that image's glyphs along, scaled, and drawing over glyphs tints them.
 */
final class Gfx {

	private final Img t;
	private int tx;
	private int ty;
	private boolean clipped;
	/** The clip in target pixels, end exclusive, always inside the target. */
	private int cx0;
	private int cy0;
	private int cx1;
	private int cy1;

	/** Starts a fresh frame on {@code target}: its earlier glyphs are dropped. */
	Gfx(Img target) {
		this.t = target;
		target.text.clear();
		setClip(null);
	}

	Img target() {
		return t;
	}

	void translate(int dx, int dy) {
		tx += dx;
		ty += dy;
	}

	/** {x, y, w, h} in current coordinates, or null when nothing was clipped. */
	int[] getClip() {
		return clipped ? new int[] { cx0 - tx, cy0 - ty, cx1 - cx0, cy1 - cy0 } : null;
	}

	void setClip(int[] c) {
		clipped = c != null;
		cx0 = 0;
		cy0 = 0;
		cx1 = t.w;
		cy1 = t.h;
		if (c != null) {
			clip(c[0], c[1], c[2], c[3]);
		}
	}

	/** Narrows the clip to its intersection with this rectangle. */
	void clipRect(int x, int y, int w, int h) {
		clipped = true;
		clip(x, y, w, h);
	}

	private void clip(int x, int y, int w, int h) {
		cx0 = Math.max(cx0, x + tx);
		cy0 = Math.max(cy0, y + ty);
		cx1 = Math.max(cx0, Math.min(cx1, x + tx + w));
		cy1 = Math.max(cy0, Math.min(cy1, y + ty + h));
	}

	void fillRect(int x, int y, int w, int h, int argb) {
		int x0 = Math.max(cx0, x + tx);
		int x1 = Math.min(cx1, x + tx + w);
		int y0 = Math.max(cy0, y + ty);
		int y1 = Math.min(cy1, y + ty + h);
		for (int py = y0; py < y1; py++) {
			for (int i = py * t.w + x0, end = py * t.w + x1; i < end; i++) {
				t.px[i] = over(argb, t.px[i]);
			}
		}
		// ponytail: a glyph is tinted by what lands on its centre only; a half-covered glyph takes all or nothing.
		boolean opaque = argb >>> 24 == 255;
		t.text.removeIf(gl -> {
			int mx = (int) (gl.x + gl.width() / 2);
			int my = (int) (gl.y + gl.height() / 2);
			if (mx < x0 || mx >= x1 || my < y0 || my >= y1) {
				return false;
			}
			// Opaque paint hides the glyph; tinting it would leave its outline over a textured background.
			if (opaque) {
				return true;
			}
			gl.argb = over(argb, gl.argb);
			return false;
		});
	}

	void drawImage(Img img, int x, int y) {
		drawImage(img, x, y, img.w, img.h, 255);
	}

	/** Nearest-neighbour scaling; a negative width (height) mirrors, x (y) staying the left (top) edge. */
	void drawImage(Img img, int x, int y, int w, int h) {
		drawImage(img, x, y, w, h, 255);
	}

	/** The same, with every source alpha multiplied by {@code alpha} (0..255). */
	void drawImage(Img img, int x, int y, int w, int h, int alpha) {
		int aw = Math.abs(w);
		int ah = Math.abs(h);
		if (aw == 0 || ah == 0 || img.w == 0 || img.h == 0 || alpha <= 0) {
			return;
		}
		int dx = x + tx;
		int dy = y + ty;
		int x0 = Math.max(cx0, dx);
		int x1 = Math.min(cx1, dx + aw);
		int y0 = Math.max(cy0, dy);
		int y1 = Math.min(cy1, dy + ah);
		for (int py = y0; py < y1; py++) {
			int sy = ah == img.h ? py - dy : (int) ((2L * (py - dy) + 1) * img.h / (2L * ah));
			int row = (h < 0 ? img.h - 1 - sy : sy) * img.w;
			for (int px = x0; px < x1; px++) {
				int sx = aw == img.w ? px - dx : (int) ((2L * (px - dx) + 1) * img.w / (2L * aw));
				int s = img.px[row + (w < 0 ? img.w - 1 - sx : sx)];
				if (alpha < 255) {
					s = ((s >>> 24) * alpha / 255) << 24 | s & 0xffffff;
				}
				int i = py * t.w + px;
				t.px[i] = over(s, t.px[i]);
			}
		}
		// Glyphs already on the target under this image's pixels (see fillRect).
		t.text.removeIf(gl -> {
			int mx = (int) (gl.x + gl.width() / 2);
			int my = (int) (gl.y + gl.height() / 2);
			if (mx < x0 || mx >= x1 || my < y0 || my >= y1) {
				return false;
			}
			int s = source(img, mx - dx, my - dy, w, h, alpha);
			if (s >>> 24 == 255) {
				return true;
			}
			gl.argb = over(s, gl.argb);
			return false;
		});
		// This image's own glyphs land on top; mirrored images carry none, so they are never mirrored.
		float sx = aw / (float) img.w;
		float sy = ah / (float) img.h;
		for (Img.Glyph gl : img.text) {
			int a = (gl.argb >>> 24) * alpha / 255;
			t.text.add(new Img.Glyph(gl.ch, dx + gl.x * sx, dy + gl.y * sy, gl.scale * sy, a << 24 | gl.argb & 0xffffff,
					Math.max(cx0, dx + gl.cx0 * sx), Math.max(cy0, dy + gl.cy0 * sy),
					Math.min(cx1, dx + gl.cx1 * sx), Math.min(cy1, dy + gl.cy1 * sy)));
		}
	}

	/** The pixel of {@code img} drawn at (u, v) of its w x h destination box, with {@code alpha} applied. */
	private static int source(Img img, int u, int v, int w, int h, int alpha) {
		int aw = Math.abs(w);
		int ah = Math.abs(h);
		int sy = ah == img.h ? v : (int) ((2L * v + 1) * img.h / (2L * ah));
		int sx = aw == img.w ? u : (int) ((2L * u + 1) * img.w / (2L * aw));
		int s = img.px[(h < 0 ? img.h - 1 - sy : sy) * img.w + (w < 0 ? img.w - 1 - sx : sx)];
		return alpha < 255 ? ((s >>> 24) * alpha / 255) << 24 | s & 0xffffff : s;
	}

	/** DrawString: puts EUC-KR text with its top-left at (x, y) on the target's glyph list; returns the pen advance. */
	int drawText(byte[] euckr, int off, int len, int x, int y, int argb) {
		return text(this, euckr, off, len, x, y, argb);
	}

	int drawString(String s, int x, int y, int argb) {
		byte[] b = Ksc.encode(s);
		return drawText(b, 0, b.length, x, y, argb);
	}

	/** DrawString's pen advance: space 5, other ASCII 8, two-byte characters 16 (30 for an undrawn 0xa1 row symbol). */
	static int textWidth(byte[] euckr, int off, int len) {
		return text(null, euckr, off, len, 0, 0, 0);
	}

	static int stringWidth(String s) {
		byte[] b = Ksc.encode(s);
		return textWidth(b, 0, b.length);
	}

	/**
	 * DrawString's loop: stops at a NUL or after {@code len} bytes; draws nothing when {@code g} is null.
	 * A byte below 0x80 is one ASCII cell, anything else starts a two-byte character.
	 */
	private static int text(Gfx g, byte[] s, int off, int len, int x, int y, int argb) {
		int pen = x;
		int end = Math.min(s.length, off + len);
		for (int i = off; i < end;) {
			int b1 = s[i] & 0xff;
			if (b1 == 0) {
				break;
			}
			if (b1 < 0x80) {
				i++;
				if (b1 > 0x20 && g != null) {
					g.glyph(String.valueOf((char) b1), pen, y, 8, 16, argb);
				}
				pen += b1 == 0x20 ? 5 : 8;
				continue;
			}
			int b2 = i + 1 < end ? s[i + 1] & 0xff : 0;
			if (Font.han(b1, b2) >= 0 && g != null) {
				g.glyph(Ksc.decode(s, i, 2), pen, y, 16, 15, argb);
			}
			i += 2;
			pen += Font.advance(b1, b2);
		}
		return pen - x;
	}

	/** One glyph cell of w x h at (x, y), unless the clip hides all of it. */
	private void glyph(String ch, int x, int y, int w, int h, int argb) {
		int dx = x + tx;
		int dy = y + ty;
		if (dx < cx1 && dy < cy1 && dx + w > cx0 && dy + h > cy0) {
			t.text.add(new Img.Glyph(ch, dx, dy, 1, argb, cx0, cy0, cx1, cy1));
		}
	}

	/** Source-over for non-premultiplied ARGB. */
	private static int over(int s, int d) {
		int sa = s >>> 24;
		if (sa == 255) {
			return s;
		}
		if (sa == 0) {
			return d;
		}
		int k = (d >>> 24) * (255 - sa) / 255;
		int oa = sa + k;
		int r = (((s >> 16) & 255) * sa + ((d >> 16) & 255) * k) / oa;
		int g = (((s >> 8) & 255) * sa + ((d >> 8) & 255) * k) / oa;
		int b = ((s & 255) * sa + (d & 255) * k) / oa;
		return oa << 24 | r << 16 | g << 8 | b;
	}
}

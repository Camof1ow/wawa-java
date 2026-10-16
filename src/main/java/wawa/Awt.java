package wawa;

import java.awt.Color;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/** Desktop-only bridge between {@link Img} and AWT images, its glyphs' vector font, and a PNG writer for the tools. */
final class Awt {

	private static java.awt.Font font;

	/** {@link Img.Glyph#FONT}, from the classpath (javac builds copy src/main/resources next to the classes). */
	private static synchronized java.awt.Font font() {
		if (font == null) {
			try (InputStream in = Awt.class.getResourceAsStream(Img.Glyph.FONT + ".ttf")) {
				if (in == null) {
					throw new IllegalStateException(Img.Glyph.FONT + ".ttf is not on the classpath: copy src/main/resources into it");
				}
				font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, in);
			} catch (IOException | FontFormatException e) {
				throw new IllegalStateException(e);
			}
		}
		return font;
	}

	/** Draws {@code img}'s glyphs on {@code g}, where img itself was drawn at (ox, oy), {@code scale} times its size. */
	static void drawText(Graphics2D g, Img img, double ox, double oy, double scale) {
		Graphics2D t = (Graphics2D) g.create();
		t.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		t.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		Shape clip = t.getClip();
		float size = -1;
		for (Img.Glyph gl : img.text) {
			if (gl.argb >>> 24 == 0) {
				continue;
			}
			float s = (float) (Img.Glyph.EM * gl.scale * scale);
			if (s != size) {
				size = s;
				t.setFont(font().deriveFont(s));
			}
			t.setClip(clip);
			t.clip(new Rectangle2D.Double(ox + gl.cx0 * scale, oy + gl.cy0 * scale,
					(gl.cx1 - gl.cx0) * scale, (gl.cy1 - gl.cy0) * scale));
			t.setColor(new Color(gl.argb, true));
			t.drawString(gl.ch, (float) (ox + gl.x * scale), (float) (oy + (gl.y + Img.Glyph.BASELINE * gl.scale) * scale));
		}
		t.dispose();
	}

	/** {@code img} with its glyphs, {@code scale} times its size (pixels nearest-neighbour). */
	static BufferedImage frame(Img img, int scale) {
		BufferedImage out = new BufferedImage(img.w * scale, img.h * scale, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(toBuffered(img), 0, 0, img.w * scale, img.h * scale, null);
		drawText(g, img, 0, 0, scale);
		g.dispose();
		return out;
	}

	static BufferedImage toBuffered(Img img) {
		BufferedImage out = new BufferedImage(Math.max(1, img.w), Math.max(1, img.h), BufferedImage.TYPE_INT_ARGB);
		out.setRGB(0, 0, img.w, img.h, img.px, 0, img.w);
		return out;
	}

	static Img toImg(BufferedImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		return new Img(w, h, img.getRGB(0, 0, w, h, null, 0, w));
	}

	static byte[] png(Img img) throws IOException {
		return png(toBuffered(img));
	}

	static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "png", bos);
		return bos.toByteArray();
	}

	private Awt() {
	}
}

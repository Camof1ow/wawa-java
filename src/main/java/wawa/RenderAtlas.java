package wawa;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Composites an 8-bit tile atlas with the RGB palette stored beside it.
 *
 * Ground atlases (img/tileNN.png) carry no palette: 20-byte header, then one index byte per
 * pixel, and the colours live in pal/tileNN.png as 3-byte RGB triples whose first entry is the
 * magenta colour key. DrawTile copies 16x16 cells out of this 256x64 sheet.
 *
 * Usage: RenderAtlas &lt;img/tileNN.png&gt; &lt;pal/tileNN.png&gt; &lt;out.png&gt; [scale]
 */
public final class RenderAtlas {

	/** Index byte to colour, mapping anything past the palette (and the key) to transparent. */
	public static BufferedImage composite(byte[] raw, byte[] pal) {
		int w = u16(raw, 0x04);
		int h = u16(raw, 0x06);
		int colours = (pal.length - 2) / 3;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = raw[20 + y * w + x] & 0xFF;
				int argb = 0;
				if (i < colours && !(i == 0)) {
					int p = i * 3;
					argb = 0xFF000000 | ((pal[p] & 0xFF) << 16) | ((pal[p + 1] & 0xFF) << 8)
							| (pal[p + 2] & 0xFF);
				}
				img.setRGB(x, y, argb);
			}
		}
		return img;
	}

	public static void main(String[] args) throws Exception {
		byte[] raw = Files.readAllBytes(Path.of(args[0]));
		byte[] pal = Files.readAllBytes(Path.of(args[1]));
		Path out = Path.of(args[2]);
		int scale = args.length > 3 ? Integer.parseInt(args[3]) : 4;

		BufferedImage img = composite(raw, pal);
		System.out.println("atlas " + img.getWidth() + "x" + img.getHeight() + " paletteEntries="
				+ ((pal.length - 2) / 3));
		BufferedImage big = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale,
				BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = big.createGraphics();
		g.setColor(new java.awt.Color(40, 40, 48));
		g.fillRect(0, 0, big.getWidth(), big.getHeight());
		g.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
		g.setColor(new java.awt.Color(90, 90, 100));
		for (int i = 0; i <= 16; i++) {
			g.drawLine(i * 16 * scale, 0, i * 16 * scale, big.getHeight());
		}
		for (int i = 0; i <= 4; i++) {
			g.drawLine(0, i * 16 * scale, big.getWidth(), i * 16 * scale);
		}
		g.dispose();
		Files.write(out, Png.of(big));
		System.out.println("wrote " + out);
	}

	static int u16(byte[] d, int off) {
		return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
	}

	private RenderAtlas() {
	}
}

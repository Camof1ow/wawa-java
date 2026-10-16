package wawa;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders an 8-bit ground tile atlas through the RGB palette stored beside it
 * ({@link TileAtlas#composite}), enlarged and with the 16x16 cell grid drawn over it.
 *
 * Usage: RenderAtlas &lt;img/tileNN.png&gt; &lt;pal/tileNN.png&gt; &lt;out.png&gt; [scale]
 */
public final class RenderAtlas {

	public static void main(String[] args) throws Exception {
		byte[] raw = Files.readAllBytes(Path.of(args[0]));
		byte[] pal = Files.readAllBytes(Path.of(args[1]));
		Path out = Path.of(args[2]);
		int scale = args.length > 3 ? Integer.parseInt(args[3]) : 4;

		BufferedImage img = Awt.toBuffered(TileAtlas.composite(raw, pal));
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
		Files.write(out, Awt.png(big));
		System.out.println("wrote " + out);
	}

	private RenderAtlas() {
	}
}

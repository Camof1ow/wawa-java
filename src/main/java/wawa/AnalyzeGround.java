package wawa;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Compares a screenshot of the original against the cell grid.
 *
 * If the ground really is a 256x64 atlas of 16x16 patterns, then any 16x16 aligned window of the
 * map area contains only a small number of distinct blocks, and those blocks are the atlas. This
 * counts them and writes the distinct blocks out so the count can be judged rather than assumed.
 *
 * Usage: AnalyzeGround &lt;screenshot&gt; &lt;x&gt; &lt;y&gt; &lt;w&gt; &lt;h&gt; &lt;outDir&gt;
 */
public final class AnalyzeGround {

	public static void main(String[] args) throws Exception {
		Path shot = Path.of(args[0]);
		int x0 = Integer.parseInt(args[1]);
		int y0 = Integer.parseInt(args[2]);
		int w = Integer.parseInt(args[3]);
		int h = Integer.parseInt(args[4]);
		Path out = Path.of(args[5]);
		Files.createDirectories(out);

		BufferedImage img = ImageIO.read(shot.toFile());
		if (img == null) {
			throw new IllegalStateException("cannot read " + shot);
		}
		System.out.println("screenshot " + img.getWidth() + "x" + img.getHeight());

		Map<String, BufferedImage> unique = new LinkedHashMap<>();
		int cols = w / 16;
		int rows = h / 16;
		System.out.println("-- distinct 16x16 blocks per grid offset (a 256x64 atlas gives ~64)");
		int best = Integer.MAX_VALUE;
		int bestOx = 0;
		int bestOy = 0;
		for (int oy = 0; oy < 16; oy++) {
			for (int ox = 0; ox < 16; ox++) {
				Map<String, Integer> seen = new LinkedHashMap<>();
				for (int cy = 0; cy < rows; cy++) {
					for (int cx = 0; cx < cols; cx++) {
						int bx = x0 + ox + cx * 16;
						int by = y0 + oy + cy * 16;
						if (bx + 16 > img.getWidth() || by + 16 > img.getHeight()) {
							continue;
						}
						seen.merge(key(img.getSubimage(bx, by, 16, 16)), 1, Integer::sum);
					}
				}
				if (seen.size() < best) {
					best = seen.size();
					bestOx = ox;
					bestOy = oy;
				}
				if (ox < 4 && oy < 4) {
					System.out.printf("   offset %2d,%2d -> %d distinct%n", ox, oy, seen.size());
				}
			}
		}
		System.out.printf("best offset %d,%d -> %d distinct blocks%n", bestOx, bestOy, best);
		{
			int ox = bestOx;
			int oy = bestOy;
			for (int cy = 0; cy < rows; cy++) {
				for (int cx = 0; cx < cols; cx++) {
					int bx = x0 + ox + cx * 16;
					int by = y0 + oy + cy * 16;
					if (bx + 16 > img.getWidth() || by + 16 > img.getHeight()) {
						continue;
					}
					unique.putIfAbsent(key(img.getSubimage(bx, by, 16, 16)), img.getSubimage(bx, by, 16, 16));
				}
			}
		}
		System.out.println("cells=" + (cols * rows) + " distinct at best offset=" + unique.size());

		// Also count distinct average colours, which is what a per-cell colour index would give.
		Map<Integer, Integer> colours = new LinkedHashMap<>();
		for (int i = 0; i < cols * rows; i++) {
			int cx = i % cols;
			int cy = i / cols;
			BufferedImage block = img.getSubimage(x0 + cx * 16, y0 + cy * 16, 16, 16);
			long r = 0;
			long g = 0;
			long b = 0;
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					int p = block.getRGB(x, y);
					r += (p >> 16) & 0xFF;
					g += (p >> 8) & 0xFF;
					b += p & 0xFF;
				}
			}
			int key = (int) ((r / 256) << 16 | (g / 256) << 8 | (b / 256));
			colours.merge(key, 1, Integer::sum);
		}
		System.out.println("distinct average cell colours=" + colours.size());
		colours.entrySet().stream()
				.sorted((a, b2) -> b2.getValue() - a.getValue())
				.limit(12)
				.forEach(e -> System.out.printf("   #%06x x%d%n", e.getKey(), e.getValue()));

		int colsPerSheet = 8;
		int rowsSheet = (unique.size() + colsPerSheet - 1) / colsPerSheet;
		BufferedImage sheet = new BufferedImage(colsPerSheet * 17, rowsSheet * 17, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(30, 30, 36));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		int i = 0;
		for (BufferedImage b : unique.values()) {
			g.drawImage(b, (i % colsPerSheet) * 17, (i / colsPerSheet) * 17, null);
			i++;
		}
		g.dispose();
		Files.write(out.resolve("blocks.png"), Png.of(sheet));
		System.out.println("wrote " + out.resolve("blocks.png"));
	}

	private static String key(BufferedImage b) {
		StringBuilder sb = new StringBuilder(1024);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				sb.append(Integer.toHexString(b.getRGB(x, y))).append(',');
			}
		}
		return sb.toString();
	}

	private AnalyzeGround() {
	}
}

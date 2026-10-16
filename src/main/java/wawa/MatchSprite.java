package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * Identifies which extracted GSAC frame a sprite in a screenshot came from.
 *
 * The VM renders the original, so a crop of any sprite on its screen is ground truth for our own
 * extraction: if some archive/frame reproduces it pixel for pixel, the palette, colour key and
 * frame indexing are all confirmed at once. The device screen is wider than the 480 px design, so
 * several scale factors are tried.
 *
 * Usage: MatchSprite &lt;screenshot&gt; &lt;x&gt; &lt;y&gt; &lt;w&gt; &lt;h&gt; &lt;assetsDir&gt; [topN]
 */
public final class MatchSprite {

	private static final double[] SCALES = { 1.0, 1.25, 1.5, 5.0 / 3.0, 1.75, 2.0 };

	public static void main(String[] args) throws Exception {
		Path shot = Path.of(args[0]);
		int x = Integer.parseInt(args[1]);
		int y = Integer.parseInt(args[2]);
		int w = Integer.parseInt(args[3]);
		int h = Integer.parseInt(args[4]);
		Path assets = Path.of(args[5]);
		DirAssets apk = new DirAssets(assets);
		int topN = args.length > 6 ? Integer.parseInt(args[6]) : 8;

		BufferedImage img = ImageIO.read(shot.toFile());
		BufferedImage probe = img.getSubimage(x, y, w, h);
		int pArgs = probe.getRGB(0, 0) & 0xFFFFFF;
		System.out.printf("probe %dx%d at %d,%d  corner=#%06x%n", w, h, x, y, pArgs);

		List<Hit> hits = new ArrayList<>();
		try (Stream<Path> s = Files.list(assets.resolve("img"))) {
			for (Path p : s.sorted().toList()) {
				Gsac g;
				try {
					g = Gsac.loadCompanion(apk, apk.path(p));
				} catch (IOException e) {
					continue;
				}
				if (!g.fullyDecodable()) {
					continue;
				}
				for (int f = 0; f < g.frameCount(); f++) {
					BufferedImage frame = Awt.toBuffered(g.frame(f));
					for (double scale : SCALES) {
						double score = compare(probe, frame, scale);
						if (score >= 0) {
							hits.add(new Hit(p.getFileName().toString(), f, scale, score,
									g.width(f), g.height(f)));
						}
					}
				}
			}
		}
		hits.sort((a, b) -> Double.compare(b.score, a.score));
		System.out.println("scanned " + hits.size() + " candidates");
		for (int i = 0; i < Math.min(topN, hits.size()); i++) {
			Hit h2 = hits.get(i);
			System.out.printf("   %-14s frame %-3d scale %.3f  size %dx%d  score %.4f%n",
					h2.file(), h2.frame(), h2.scale(), h2.fw(), h2.fh(), h2.score());
		}
	}

	/** Fraction of probe pixels that the scaled frame reproduces, or -1 if it cannot cover the probe. */
	private static double compare(BufferedImage probe, BufferedImage frame, double scale) {
		int fw = (int) Math.round(frame.getWidth() * scale);
		int fh = (int) Math.round(frame.getHeight() * scale);
		if (fw < probe.getWidth() || fh < probe.getHeight()) {
			return -1;
		}
		int total = 0;
		int match = 0;
		for (int py = 0; py < probe.getHeight(); py++) {
			for (int px = 0; px < probe.getWidth(); px++) {
				int sx = (int) (px / scale);
				int sy = (int) (py / scale);
				if (sx >= frame.getWidth() || sy >= frame.getHeight()) {
					continue;
				}
				int a = probe.getRGB(px, py) & 0xFFFFFF;
				int b = frame.getRGB(sx, sy);
				if ((b >>> 24) == 0) {
					continue;
				}
				total++;
				int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
				int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
				int db = Math.abs((a & 0xFF) - (b & 0xFF));
				if (dr + dg + db <= 24) {
					match++;
				}
			}
		}
		return total < probe.getWidth() * probe.getHeight() / 4 ? -1 : (double) match / total;
	}

	private record Hit(String file, int frame, double scale, double score, int fw, int fh) {
	}

	private MatchSprite() {
	}
}

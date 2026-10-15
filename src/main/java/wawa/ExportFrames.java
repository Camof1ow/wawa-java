package wawa;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes every non-empty frame of the given GSAC archives as individual PNGs.
 *
 * Monster and hero sprites live in the numbered archives (img/000.gsa .. img/051.gsa), so the map
 * composite needs single frames rather than the per-archive contact sheets ExtractAll produces.
 *
 * Usage: ExportFrames &lt;assetsDir&gt; &lt;outDir&gt; &lt;archiveName&gt;...
 */
public final class ExportFrames {

	public static void main(String[] args) throws Exception {
		Path assets = Path.of(args[0]).resolve("img");
		Path out = Path.of(args[1]);
		Files.createDirectories(out);
		int written = 0;
		for (int a = 2; a < args.length; a++) {
			String name = args[a];
			Path src = assets.resolve(name + ".png");
			if (!Files.exists(src)) {
				System.out.println("missing " + src);
				continue;
			}
			Gsac g = Gsac.loadCompanion(src);
			if (!g.fullyDecodable() || g.frameCount() == 0) {
				System.out.println("skipping " + name + " mode=" + g.mode());
				continue;
			}
			List<Integer> keep = new ArrayList<>();
			for (int f = 0; f < g.frameCount(); f++) {
				if (hasPixels(g.frame(f))) {
					keep.add(f);
				}
			}
			for (int f : keep) {
				Files.write(out.resolve(name + "_" + f + ".png"), Png.of(g.frame(f)));
				written++;
			}
			System.out.printf("%s: %d/%d frames kept%n", name, keep.size(), g.frameCount());
		}
		System.out.println("exported " + written + " frames -> " + out);
	}

	/** False when every pixel of the frame is transparent. */
	private static boolean hasPixels(BufferedImage img) {
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if ((img.getRGB(x, y) >>> 24) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	private ExportFrames() {
	}
}

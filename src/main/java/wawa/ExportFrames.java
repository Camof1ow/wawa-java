package wawa;

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
		DirAssets apk = new DirAssets(Path.of(args[0]));
		Path out = Path.of(args[1]);
		Files.createDirectories(out);
		int written = 0;
		for (int a = 2; a < args.length; a++) {
			String name = args[a];
			String src = "assets/img/" + name + ".png";
			if (!apk.exists(src)) {
				System.out.println("missing " + src);
				continue;
			}
			Gsac g = Gsac.loadCompanion(apk, src);
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
				Files.write(out.resolve(name + "_" + f + ".png"), Awt.png(g.frame(f)));
				written++;
			}
			System.out.printf("%s: %d/%d frames kept%n", name, keep.size(), g.frameCount());
		}
		System.out.println("exported " + written + " frames -> " + out);
	}

	/** False when every pixel of the frame is transparent. */
	private static boolean hasPixels(Img img) {
		for (int p : img.px) {
			if ((p >>> 24) != 0) {
				return true;
			}
		}
		return false;
	}

	private ExportFrames() {
	}
}

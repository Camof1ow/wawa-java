package wawa;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists GSAC frames whose size is near a target, so a sprite seen on the VM screen can be found
 * by shape before worrying about its palette (the engine remaps palettes per use, so colour
 * comparison fails).
 *
 * Usage: FindBySize &lt;assetsDir&gt; &lt;w&gt; &lt;h&gt; &lt;tolerance&gt; &lt;out.png&gt;
 */
public final class FindBySize {

	public static void main(String[] args) throws Exception {
		Path assets = Path.of(args[0]);
		DirAssets apk = new DirAssets(assets);
		int tw = Integer.parseInt(args[1]);
		int th = Integer.parseInt(args[2]);
		int tol = Integer.parseInt(args[3]);
		Path out = Path.of(args[4]);

		List<Gsac> hits = new ArrayList<>();
		List<Integer> frames = new ArrayList<>();
		List<String> names = new ArrayList<>();
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
					if (Math.abs(g.width(f) - tw) <= tol && Math.abs(g.height(f) - th) <= tol) {
						hits.add(g);
						frames.add(f);
						names.add(p.getFileName().toString().replace(".png", ""));
					}
				}
			}
		}
		System.out.println("frames near " + tw + "x" + th + " (+-" + tol + "): " + hits.size());

		int cell = 80;
		int cols = 10;
		int rows = (hits.size() + cols - 1) / cols;
		BufferedImage sheet = new BufferedImage(cols * cell, Math.max(1, rows) * (cell + 14),
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = sheet.createGraphics();
		g2.setColor(new Color(24, 24, 30));
		g2.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
		for (int i = 0; i < hits.size(); i++) {
			int cx = (i % cols) * cell;
			int cy = (i / cols) * (cell + 14);
			BufferedImage f = Awt.toBuffered(hits.get(i).frame(frames.get(i)));
			g2.drawImage(f, cx + (cell - f.getWidth()) / 2, cy + (cell - f.getHeight()) / 2, null);
			g2.setColor(new Color(180, 180, 180));
			g2.drawString(names.get(i) + " f" + frames.get(i), cx + 2, cy + cell + 11);
			g2.setColor(new Color(90, 90, 100));
			g2.drawRect(cx, cy, cell - 1, cell - 1);
		}
		g2.dispose();
		Files.write(out, Awt.png(sheet));
		System.out.println("wrote " + out);
	}

	private FindBySize() {
	}
}

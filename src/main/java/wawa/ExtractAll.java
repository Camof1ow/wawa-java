package wawa;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Extracts every asset family in the APK into one tree so each can be compared against the
 * running original. Output under out/extract:
 *
 *   img/&lt;name&gt;.png      contact sheet of all frames of every GSAC archive
 *   map/&lt;code&gt;.json      decoded grid (width, height, tile sets, cells)
 *   map/&lt;code&gt;.png       the grid rendered through its tile sets, for pixel comparison
 *   dat/&lt;nn&gt;.json        every record table
 *   area/&lt;code&gt;.json      area metadata
 *   font/&lt;name&gt;.bin       the C-array byte payload of each bitmap font
 *   font/&lt;name&gt;.png       guessed 1bpp glyph atlas
 *   index.txt              what was produced
 */
public final class ExtractAll {

	private static final List<String> index = new ArrayList<>();
	private static DirAssets apk;

	public static void main(String[] args) throws Exception {
		Path assets = Path.of(args.length > 0 ? args[0] : "assets");
		apk = new DirAssets(assets);
		Path out = Path.of(args.length > 1 ? args[1] : "out/extract");
		Files.createDirectories(out);

		sprites(assets.resolve("img"), out.resolve("img"));
		maps(assets, out.resolve("map"));
		dat(assets.resolve("eng/dat"), out.resolve("dat"));
		areas(assets.resolve("eng/mob"), out.resolve("area"));
		fonts(assets.resolve("font"), out.resolve("font"));

		Files.write(out.resolve("index.txt"), index);
		System.out.println("extracted " + index.size() + " artifacts to " + out);
	}

	private static void sprites(Path dir, Path out) throws IOException {
		Files.createDirectories(out);
		int sheets = 0;
		int frames = 0;
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.sorted().toList()) {
				Gsac g;
				try {
					g = Gsac.loadCompanion(apk, apk.path(p));
				} catch (IOException e) {
					index.add("img SKIP " + p.getFileName() + " (" + e.getMessage() + ")");
					continue;
				}
				if (!g.fullyDecodable() || g.frameCount() == 0) {
					if (g.isRawImage() && g.rawWidth() > 0 && g.rawHeight() > 0) {
						String name = p.getFileName().toString().replace(".png", "");
						Files.write(out.resolve(name + ".png"), Awt.png(g.rawImage(g.rawWidth())));
						index.add("img " + name + " RAW " + g.rawWidth() + "x" + g.rawHeight());
						sheets++;
					} else {
						index.add("img SKIP " + p.getFileName() + " mode=" + g.mode());
					}
					continue;
				}
				String name = p.getFileName().toString().replace(".png", "");
				Files.write(out.resolve(name + ".png"), Awt.png(sheet(g)));
				index.add("img " + name + " frames=" + g.frameCount() + " mode=" + g.mode());
				sheets++;
				frames += g.frameCount();
			}
		}
		System.out.println("img: " + sheets + " sheets, " + frames + " frames");
	}

	private static BufferedImage sheet(Gsac g) {
		int cols = (int) Math.ceil(Math.sqrt(g.frameCount()));
		int rows = (g.frameCount() + cols - 1) / cols;
		int cw = 1;
		int ch = 1;
		for (int i = 0; i < g.frameCount(); i++) {
			cw = Math.max(cw, g.width(i));
			ch = Math.max(ch, g.height(i));
		}
		BufferedImage out = new BufferedImage(cols * cw, rows * ch, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = out.createGraphics();
		g2.setColor(new Color(36, 36, 44));
		g2.fillRect(0, 0, out.getWidth(), out.getHeight());
		for (int i = 0; i < g.frameCount(); i++) {
			BufferedImage f = Awt.toBuffered(g.frame(i));
			g2.drawImage(f, (i % cols) * cw, (i / cols) * ch, null);
		}
		g2.dispose();
		return out;
	}

	private static void maps(Path assets, Path out) throws IOException {
		Files.createDirectories(out);
		try (Stream<Path> s = Files.list(assets.resolve("eng/map"))) {
			for (Path p : s.sorted().toList()) {
				String letter = p.getFileName().toString().replace(".png", "");
				String code = letter + "01";
				try {
					MapData m = MapData.load(apk, apk.path(p), code);
					StringBuilder sb = new StringBuilder("{\n  \"code\": \"" + code + "\",\n");
					sb.append("  \"width\": ").append(m.width).append(",\n");
					sb.append("  \"height\": ").append(m.height).append(",\n");
					sb.append("  \"tileSets\": [");
					for (int i = 0; i < 4; i++) {
						sb.append(i > 0 ? ", " : "").append(m.tileSet(i));
					}
					sb.append("],\n  \"cells\": [\n");
					for (int y = 0; y < m.height; y++) {
						sb.append("   ");
						for (int x = 0; x < m.width; x++) {
							sb.append(String.format("%d", m.cell(x, y))).append(x + 1 < m.width ? "," : "");
						}
						sb.append(y + 1 < m.height ? ",\n" : "\n");
					}
					sb.append("  ]\n}\n");
					Files.writeString(out.resolve(code + ".json"), sb.toString());
					Tiles t = Tiles.load(apk, m);
					Files.write(out.resolve(code + ".png"), Awt.png(renderMap(m, t)));
					index.add("map " + code + " " + m.width + "x" + m.height + " tileSets=["
							+ m.tileSet(0) + "," + m.tileSet(1) + "," + m.tileSet(2) + "," + m.tileSet(3) + "]");
				} catch (IOException e) {
					index.add("map " + code + " FAIL " + e.getMessage());
				}
			}
		}
	}

	/** Draws the grid through each layer's tile set, cropping 16x16 at the cell's low bits. */
	private static BufferedImage renderMap(MapData m, Tiles t) {
		BufferedImage img = new BufferedImage(m.width * 16, m.height * 16, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		for (int y = 0; y < m.height; y++) {
			for (int x = 0; x < m.width; x++) {
				Img f = t.patch(m.layer(x, y), m.cell(x, y));
				if (f != null) {
					g.drawImage(Awt.toBuffered(f), x * 16, y * 16, 16, 16, null);
				}
			}
		}
		g.dispose();
		return img;
	}

	private static void dat(Path dir, Path out) throws IOException {
		Files.createDirectories(out);
		int n = 0;
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.sorted().toList()) {
				try {
					DataTables t = DataTables.load(apk, apk.path(p));
					StringBuilder sb = new StringBuilder("{\n  \"declared\": " + t.declared + ",\n  \"records\": [\n");
					for (int i = 0; i < t.size(); i++) {
						int[] row = t.records.get(i);
						sb.append("    [");
						for (int j = 0; j < row.length; j++) {
							sb.append(row[j]).append(j + 1 < row.length ? "," : "");
						}
						sb.append(i + 1 < t.size() ? "],\n" : "]\n");
					}
					sb.append("  ]\n}\n");
					Files.writeString(out.resolve(p.getFileName().toString().replace(".png", ".json")), sb.toString());
					index.add("dat " + p.getFileName() + " records=" + t.size());
					n++;
				} catch (IOException e) {
					index.add("dat " + p.getFileName() + " FAIL " + e.getMessage());
				}
			}
		}
		System.out.println("dat: " + n);
	}

	private static void areas(Path dir, Path out) throws IOException {
		Files.createDirectories(out);
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.sorted().toList()) {
				String letter = p.getFileName().toString().replace(".png", "");
				AreaInfo a;
				try {
					a = AreaInfo.load(apk, apk.path(p), letter + "01");
				} catch (IOException e) {
					a = AreaInfo.absent(letter + "01");
				}
				Files.writeString(out.resolve(letter + "01.json"),
						"{\n  \"name\": \"" + a.name + "\",\n  \"ambience\": " + a.ambience
								+ ",\n  \"brightness\": " + a.brightness + ",\n  \"rainy\": " + a.rainy() + "\n}\n");
				index.add("area " + letter + "01 name=\"" + a.name + "\"");
			}
		}
	}

	private static void fonts(Path dir, Path out) throws IOException {
		Files.createDirectories(out);
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.sorted().toList()) {
				String text = Files.readString(p, StandardCharsets.UTF_8);
				List<Byte> bytes = new ArrayList<>();
				for (String tok : text.split(",")) {
					String t = tok.trim();
					if (t.startsWith("//")) {
						t = t.substring(2).trim();
					}
					int hex = t.indexOf("0X");
					if (hex < 0) {
						hex = t.indexOf("0x");
					}
					if (hex < 0) {
						continue;
					}
					String digits = t.substring(hex + 2).trim().split("[^0-9A-Fa-f]")[0];
					if (digits.isEmpty()) {
						continue;
					}
					bytes.add((byte) Integer.parseInt(digits, 16));
				}
				byte[] data = new byte[bytes.size()];
				for (int i = 0; i < data.length; i++) {
					data[i] = bytes.get(i);
				}
				String name = p.getFileName().toString().replace(".h", "");
				Files.write(out.resolve(name + ".bin"), data);
				Files.write(out.resolve(name + ".png"), Awt.png(fontGuess(data, name)));
				index.add("font " + name + " bytes=" + data.length);
			}
		}
	}

	/** The C arrays hold one glyph per 16 bytes here; drawn as 8px-wide 1bpp rows for inspection. */
	private static BufferedImage fontGuess(byte[] data, String name) {
		int per = 16;
		int glyphs = Math.max(1, data.length / per);
		int cols = 32;
		int rows = (glyphs + cols - 1) / cols;
		BufferedImage img = new BufferedImage(cols * 8 + cols, rows * 16 + rows, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(20, 20, 24));
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.setFont(new Font("Monospaced", Font.PLAIN, 8));
		for (int i = 0; i < glyphs; i++) {
			int gx = (i % cols) * 9;
			int gy = (i / cols) * 17;
			for (int row = 0; row < per; row++) {
				int b = data[i * per + row] & 0xFF;
				for (int bit = 0; bit < 8; bit++) {
					if ((b & (0x80 >> bit)) != 0) {
						g.setColor(Color.WHITE);
						g.fillRect(gx + bit, gy + row, 1, 1);
					}
				}
			}
		}
		g.setColor(new Color(90, 120, 90));
		g.drawString(name, 2, img.getHeight() - 2);
		g.dispose();
		return img;
	}

	private ExtractAll() {
	}
}

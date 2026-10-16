package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bitmap font from the C headers in {@code assets/font}.
 *
 * <p>Each header is {@code unsigned char <name>[] = { ... }} with a {@code // <n>} comment before
 * every glyph, so the glyphs are the byte runs between comments. A glyph is 1bpp, one bit per
 * pixel, left to right and most significant bit first, and rows are packed to whole bytes:
 *
 * <pre>
 *   eng_12  8 x 16   16 bytes      eng_8  8 x 11   11 bytes
 *   han_11 16 x 15   30 bytes      han_14 24 x 19   57 bytes
 * </pre>
 *
 * <p>The English headers hold the 95 printable ASCII glyphs. The Korean ones hold 2444: the same
 * ASCII run followed by the 2350 syllables of the KS X 1001 completion set, which is EUC-KR
 * 0xB0A1 through 0xC8FE in code order, so glyph 94 + k is the syllable at 0xB0A1 + k.
 */
public final class BitmapFont {

	private static final Charset KOREAN = Charset.forName("MS949");
	private static final Pattern GLYPH =
			Pattern.compile("//\\s*(\\d+)\\s*([^/]*?)(?=//|\\z)", Pattern.DOTALL);
	private static final Pattern HEX = Pattern.compile("0[xX]([0-9a-fA-F]{1,2})");
	/** KS X 1001 lays its Hangul out in rows of 94, starting at row 0xB0, column 0xA1. */
	private static final int HANGUL_BASE = 0xB0A1;
	private static final int HANGUL_COLS = 94;
	private static final int HANGUL_COUNT = 2350;
	/** The English glyphs start at glyph 0 = space. */
	private static final int ASCII_FIRST = 0x20;

	private final int cellWidth;
	private final int cellHeight;
	private final byte[][] glyphs;

	private BitmapFont(int cellWidth, byte[][] glyphs) {
		this.cellWidth = cellWidth;
		this.glyphs = glyphs;
		int perRow = Math.max(1, cellWidth / 8);
		// One glyph in each header carries a stray trailing byte, so take the most common length
		// rather than the longest.
		Map<Integer, Integer> counts = new HashMap<>();
		for (byte[] g : glyphs) {
			counts.merge(g.length, 1, Integer::sum);
		}
		int bytes = 0;
		int best = -1;
		for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
			if (e.getKey() > 0 && e.getValue() > best) {
				best = e.getValue();
				bytes = e.getKey();
			}
		}
		this.cellHeight = bytes / perRow;
	}

	/**
	 * Loads a header. The cell width follows the name: the {@code eng_} headers are one byte wide,
	 * and the Korean ones two up to 12pt and three above, which is what makes their byte counts
	 * divide into whole rows.
	 */
	public static BitmapFont load(Path header) throws IOException {
		String name = header.getFileName().toString();
		int size = Integer.parseInt(name.replaceAll("\\D+", ""));
		int width = name.startsWith("han") ? (size <= 12 ? 16 : 24) : 8;
		return new BitmapFont(width, parse(Files.readString(header, Charset.forName("ISO-8859-1"))));
	}

	private static byte[][] parse(String text) {
		Map<Integer, byte[]> byIndex = new HashMap<>();
		Matcher m = GLYPH.matcher(text);
		while (m.find()) {
			Matcher h = HEX.matcher(m.group(2));
			List<Byte> values = new ArrayList<>();
			while (h.find()) {
				values.add((byte) Integer.parseInt(h.group(1), 16));
			}
			byte[] glyph = new byte[values.size()];
			for (int i = 0; i < glyph.length; i++) {
				glyph[i] = values.get(i);
			}
			byIndex.put(Integer.parseInt(m.group(1)), glyph);
		}
		int count = byIndex.isEmpty() ? 0 : Collections.max(byIndex.keySet()) + 1;
		byte[][] out = new byte[count][];
		for (int i = 0; i < count; i++) {
			out[i] = byIndex.getOrDefault(i, new byte[0]);
		}
		return out;
	}

	public int cellWidth() {
		return cellWidth;
	}

	public int cellHeight() {
		return cellHeight;
	}

	public int glyphCount() {
		return glyphs.length;
	}

	/**
	 * The glyph index for a character, or -1 when this font has none.
	 *
	 * <p>The Korean headers hold the 2350 completion syllables first, in the order KS X 1001
	 * assigns them (which is also Unicode order), and then the 94 compatibility jamo, so a
	 * syllable is at its rank in that set and U+3131 + k is at 2350 + k. They carry no ASCII:
	 * the {@code eng_} headers are what draws Latin text.
	 */
	public int indexOf(char c) {
		if (c >= 0x3131 && c <= 0x318e) {
			int index = HANGUL_COUNT + (c - 0x3131);
			return index < glyphs.length ? index : -1;
		}
		if (c < 0x80) {
			int i = c - ASCII_FIRST;
			return i >= 0 && i < glyphs.length && glyphs.length <= 0x80 ? i : -1;
		}
		byte[] euc = String.valueOf(c).getBytes(KOREAN);
		if (euc.length != 2) {
			return -1;
		}
		// KS X 1001 addresses its Hangul by row and column, not by a flat code offset: rows run
		// 0xB0.. and columns 0xA1.., 94 to a row.
		int row = (euc[0] & 0xFF) - (HANGUL_BASE >> 8);
		int col = (euc[1] & 0xFF) - (HANGUL_BASE & 0xFF);
		int k = row * HANGUL_COLS + col;
		if (row < 0 || col < 0 || k >= HANGUL_COUNT) {
			return -1;
		}
		return k < glyphs.length ? k : -1;
	}

	/** One glyph as an image, or null when the font has no such glyph. */
	public BufferedImage glyph(int index) {
		if (index < 0 || index >= glyphs.length || glyphs[index].length == 0) {
			return null;
		}
		byte[] g = glyphs[index];
		int perRow = Math.max(1, cellWidth / 8);
		int rows = g.length / perRow;
		BufferedImage img = new BufferedImage(cellWidth, rows, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < rows; y++) {
			int bits = 0;
			for (int b = 0; b < perRow; b++) {
				bits = (bits << 8) | (g[y * perRow + b] & 0xFF);
			}
			for (int x = 0; x < cellWidth; x++) {
				if ((bits >> (cellWidth - 1 - x) & 1) != 0) {
					img.setRGB(x, y, 0xFF000000);
				}
			}
		}
		return img;
	}

	/** Renders a string, advancing one cell per character. */
	public BufferedImage render(String text) {
		BufferedImage img = new BufferedImage(Math.max(1, text.length() * cellWidth),
				Math.max(1, cellHeight), BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < text.length(); i++) {
			// A space advances without inking, which is what the blank cell in the headers means.
			if (text.charAt(i) == ' ') {
				continue;
			}
			BufferedImage g = glyph(indexOf(text.charAt(i)));
			if (g == null) {
				continue;
			}
			for (int y = 0; y < g.getHeight(); y++) {
				for (int x = 0; x < g.getWidth(); x++) {
					if ((g.getRGB(x, y) >>> 24) != 0) {
						img.setRGB(i * cellWidth + x, y, 0xFF000000);
					}
				}
			}
		}
		return img;
	}

	/** Renders a string white on grey, so a decode can be checked by eye. */
	public BufferedImage renderVisible(String text) {
		BufferedImage src = render(text);
		BufferedImage out =
				new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				out.setRGB(x, y, (src.getRGB(x, y) >>> 24) == 0 ? 0xFF101010 : 0xFFFFFFFF);
			}
		}
		return out;
	}

	/** Writes sample renders, which is how the decode is verified. */
	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]);
		Path out = Path.of(args.length > 1 ? args[1] : "out");
		Files.createDirectories(out);
		String korean = "아타말 마을 가나다 한글";
		String english = "Ata'mal Village 0123";
		for (String header : new String[] {"eng_8.h", "eng_12.h", "han_11.h", "han_14.h"}) {
			BitmapFont f = BitmapFont.load(dir.resolve(header));
			String text = header.startsWith("han") ? korean : english;
			BufferedImage img = f.renderVisible(text);
			Path file = out.resolve(header.replace(".h", ".png"));
			javax.imageio.ImageIO.write(img, "png", file.toFile());
			System.out.printf("%-10s cell=%dx%d glyphs=%d -> %s (%dx%d)%n", header, f.cellWidth,
					f.cellHeight, f.glyphCount(), file.getFileName(), img.getWidth(), img.getHeight());
		}
	}
}

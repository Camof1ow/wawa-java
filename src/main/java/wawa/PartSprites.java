package wawa;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What monsters and the hero share: the dat/&lt;NN&gt;.dat part tables GetGameDat splits, the
 * sprite slots DrawSpriteFull draws from, and the composition of a part list into one image.
 *
 * <pre>
 * u16 countA, u16 countB, u16 countC
 * A  countA x {s16 next, u16 firstB, u8 countB, u8 delay}    frame chain
 * B  countB x u16                                           part references
 * C  countC x {u32 w0, u32 w1}                              parts
 * </pre>
 *
 * A part draws frame {@code w0 & 0xfff} of slot {@code w1 & 0xff} (img/&lt;slot&gt;.png; a frame
 * past the archive's end continues into the next slot, as DrawSpriteFull walks the slot array)
 * with its top-left corner at the owner's position plus {@code dx = w1 >> 20} and
 * {@code dy = (w1 << 12) >> 20}. Flip bits are {@code (w0 >>> 24) & 3}: bit 0 horizontal, bit 1
 * vertical (the Norm/Hori/Vert/HoVe order of the DrawSprite* routines). The draw routines walk
 * the list from its last entry to its first, so the first entry ends up on top.
 */
final class PartSprites {

	/** One drawn part: archive slot, frame, offset, flip bits and palette row. */
	record Part(int slot, int frame, int dx, int dy, int flip, int row) {
	}

	/** The image of one pose and where the owner's own position falls inside it. */
	record Pose(BufferedImage image, int anchorX, int anchorY) {
	}

	record Dat(int[][] chain, int[] refs, int[][] parts) {
	}

	private final Path assets;
	private final Map<Integer, Dat> dats = new HashMap<>();
	private final Map<Integer, Gsac> archives = new HashMap<>();

	PartSprites(Path assets) {
		this.assets = assets;
	}

	Dat dat(int number) throws IOException {
		Dat d = dats.get(number);
		if (d == null) {
			d = parse(Files.readAllBytes(assets.resolve("dat").resolve(String.format("%02d.png", number))));
			dats.put(number, d);
		}
		return d;
	}

	/**
	 * Composes parts, given in drawing order (back to front), into an image. {@code mirror} is the
	 * hero's +0x1a flag: DrawHeroAnim then places a part at {@code x - dx - width} and flips it.
	 */
	Pose compose(List<Part> parts, boolean mirror) throws IOException {
		List<BufferedImage> images = new ArrayList<>();
		List<int[]> at = new ArrayList<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Part p : parts) {
			BufferedImage img = frame(p.slot(), p.frame(), p.row());
			if (img == null) {
				continue;
			}
			int flip = mirror ? p.flip() ^ 1 : p.flip();
			int x = mirror ? -p.dx() - img.getWidth() : p.dx();
			images.add(flip(img, flip));
			at.add(new int[] {x, p.dy()});
			minX = Math.min(minX, x);
			minY = Math.min(minY, p.dy());
			maxX = Math.max(maxX, x + img.getWidth());
			maxY = Math.max(maxY, p.dy() + img.getHeight());
		}
		if (images.isEmpty()) {
			return null;
		}
		BufferedImage out = new BufferedImage(maxX - minX, maxY - minY, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		for (int i = 0; i < images.size(); i++) {
			g.drawImage(images.get(i), at.get(i)[0] - minX, at.get(i)[1] - minY, null);
		}
		g.dispose();
		return new Pose(out, -minX, -minY);
	}

	/** A frame of a slot, continuing into the following slots when the index runs past the end. */
	BufferedImage frame(int slot, int frame, int row) throws IOException {
		while (true) {
			Gsac g = archive(slot);
			if (g == null || !g.fullyDecodable()) {
				return null;
			}
			if (frame < g.frameCount()) {
				return g.frame(frame, row);
			}
			if (g.frameCount() == 0) {
				return null;
			}
			frame -= g.frameCount();
			slot++;
		}
	}

	private Gsac archive(int slot) throws IOException {
		if (!archives.containsKey(slot)) {
			Path file = assets.resolve("img").resolve(String.format("%03d.png", slot));
			archives.put(slot, Files.exists(file) ? Gsac.loadCompanion(file) : null);
		}
		return archives.get(slot);
	}

	private static Dat parse(byte[] b) {
		int countA = u16(b, 0);
		int countB = u16(b, 2);
		int countC = u16(b, 4);
		int at = 6;
		int[][] chain = new int[countA][];
		for (int i = 0; i < countA; i++, at += 6) {
			chain[i] = new int[] {(short) u16(b, at), u16(b, at + 2), b[at + 4] & 0xff, b[at + 5] & 0xff};
		}
		int[] refs = new int[countB];
		for (int i = 0; i < countB; i++, at += 2) {
			refs[i] = u16(b, at);
		}
		int[][] parts = new int[countC][];
		for (int i = 0; i < countC; i++, at += 8) {
			parts[i] = new int[] {u32(b, at), u32(b, at + 4)};
		}
		return new Dat(chain, refs, parts);
	}

	private static BufferedImage flip(BufferedImage img, int flip) {
		if (flip == 0) {
			return img;
		}
		int w = img.getWidth();
		int h = img.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int sx = (flip & 1) != 0 ? w - 1 - x : x;
				int sy = (flip & 2) != 0 ? h - 1 - y : y;
				out.setRGB(x, y, img.getRGB(sx, sy));
			}
		}
		return out;
	}

	private static int u16(byte[] b, int at) {
		return (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8);
	}

	private static int u32(byte[] b, int at) {
		return u16(b, at) | (u16(b, at + 2) << 16);
	}
}

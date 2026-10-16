package wawa;

import java.io.IOException;
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
	record Pose(Img image, int anchorX, int anchorY) {
	}

	record Dat(int[][] chain, int[] refs, int[][] parts) {
	}

	private final Assets assets;
	private final Map<Integer, Dat> dats = new HashMap<>();
	private final Map<Integer, Gsac> archives = new HashMap<>();

	PartSprites(Assets assets) {
		this.assets = assets;
	}

	Dat dat(int number) throws IOException {
		Dat d = dats.get(number);
		if (d == null) {
			d = parse(assets.read(String.format("assets/dat/%02d.png", number)));
			dats.put(number, d);
		}
		return d;
	}

	/**
	 * Composes parts, given in drawing order (back to front), into an image. {@code mirror} is the
	 * hero's +0x1a flag: DrawHeroAnim then places a part at {@code x - dx - width} and flips it.
	 */
	Pose compose(List<Part> parts, boolean mirror) throws IOException {
		List<Img> images = new ArrayList<>();
		List<int[]> at = new ArrayList<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Part p : parts) {
			Img img = frame(p.slot(), p.frame(), p.row());
			if (img == null) {
				continue;
			}
			int flip = mirror ? p.flip() ^ 1 : p.flip();
			int x = mirror ? -p.dx() - img.w : p.dx();
			images.add(img);
			at.add(new int[] {x, p.dy(), flip});
			minX = Math.min(minX, x);
			minY = Math.min(minY, p.dy());
			maxX = Math.max(maxX, x + img.w);
			maxY = Math.max(maxY, p.dy() + img.h);
		}
		if (images.isEmpty()) {
			return null;
		}
		Img out = new Img(maxX - minX, maxY - minY);
		Gfx g = new Gfx(out);
		for (int i = 0; i < images.size(); i++) {
			Img img = images.get(i);
			int[] a = at.get(i);
			// Flip bit 0 mirrors left-right, bit 1 top-bottom.
			g.drawImage(img, a[0] - minX, a[1] - minY, (a[2] & 1) != 0 ? -img.w : img.w,
					(a[2] & 2) != 0 ? -img.h : img.h);
		}
		return new Pose(out, -minX, -minY);
	}

	/** A frame of a slot, continuing into the following slots when the index runs past the end. */
	Img frame(int slot, int frame, int row) throws IOException {
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
			String file = String.format("assets/img/%03d.png", slot);
			archives.put(slot, assets.exists(file) ? Gsac.loadCompanion(assets, file) : null);
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

	private static int u16(byte[] b, int at) {
		return (b[at] & 0xff) | ((b[at + 1] & 0xff) << 8);
	}

	private static int u32(byte[] b, int at) {
		return u16(b, at) | (u16(b, at + 2) << 16);
	}
}

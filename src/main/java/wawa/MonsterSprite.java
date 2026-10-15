package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and caches the standing-pose sprite of a monster.
 *
 * <p>The archive comes from MonsterData, and the pose inside it is the one spot where the
 * engine's rule cannot be reproduced statically: CalcMonsAnim picks it with
 * {@code frame = baseFrame + step} where baseFrame comes from a per-kind direction table held in
 * a pointer array the loader fills at run time, so its values are not in the file. What is in the
 * file is the animation chain table (section A of dat/&lt;kind&gt;.dat), whose records are
 * (next, spriteSlot, drawnFrame).
 *
 * <p>As a stand-in, the archive's dominant frame size is taken as the body — a monster's own
 * frames all share one size — and among those the frame with the most opaque pixels and at least
 * four distinct colours is used. That avoids the single-colour silhouettes and effect overlays
 * that also live in these archives.
 */
public final class MonsterSprite {

	private static final int MIN_COLOURS = 4;

	private final Path assets;
	private final MonsterData kinds;
	private final Map<Integer, BufferedImage> bySlot = new HashMap<>();

	private MonsterSprite(Path assets, MonsterData kinds) {
		this.assets = assets;
		this.kinds = kinds;
	}

	/** The libraries sit beside the assets directory in the APK, not inside it. */
	public static Path nativeLib(Path assets) {
		return assets.getParent().resolve("lib/armeabi/libwawa.so");
	}

	public static MonsterSprite load(Path assets) throws IOException {
		return load(assets, nativeLib(assets));
	}

	public static MonsterSprite load(Path assets, Path nativeLib) throws IOException {
		return new MonsterSprite(assets, MonsterData.load(nativeLib));
	}

	/** The standing pose for a monster id, or null when its archive cannot be decoded. */
	public BufferedImage standing(int monsterId) {
		int slot = kinds.sprite(monsterId);
		if (bySlot.containsKey(slot)) {
			return bySlot.get(slot);
		}
		BufferedImage img = decode(slot);
		bySlot.put(slot, img);
		return img;
	}

	public int palette(int monsterId) {
		return kinds.palette(monsterId);
	}

	/** The shipped Korean name, or null when that id has none. */
	public String name(int monsterId) {
		return kinds.name(monsterId);
	}

	private BufferedImage decode(int slot) {
		String name = String.format("%03d", slot);
		Gsac g;
		try {
			g = Gsac.load(assets.resolve("img").resolve(name + ".png"),
					assets.resolve("pal").resolve(name + ".png"));
		} catch (IOException e) {
			return null;
		}
		if (!g.fullyDecodable() || g.frameCount() == 0) {
			return null;
		}
		int[] sizes = new int[g.frameCount()];
		Map<Long, Integer> histogram = new HashMap<>();
		for (int f = 0; f < g.frameCount(); f++) {
			long key = ((long) g.width(f) << 32) | (g.height(f) & 0xffffffffL);
			histogram.merge(key, 1, Integer::sum);
		}
		long body = 0;
		int best = -1;
		for (Map.Entry<Long, Integer> e : histogram.entrySet()) {
			if (e.getValue() > best) {
				best = e.getValue();
				body = e.getKey();
			}
		}
		int bodyW = (int) (body >>> 32);
		int bodyH = (int) (body & 0xffffffffL);

		BufferedImage fallback = null;
		int fallbackScore = -1;
		BufferedImage rich = null;
		int richScore = -1;
		for (int f = 0; f < g.frameCount(); f++) {
			if (g.width(f) != bodyW || g.height(f) != bodyH) {
				continue;
			}
			BufferedImage img = g.frame(f);
			int score = opaque(img);
			if (score > fallbackScore) {
				fallbackScore = score;
				fallback = img;
			}
			if (colours(img) >= MIN_COLOURS && score > richScore) {
				richScore = score;
				rich = img;
			}
		}
		return rich != null ? rich : fallback;
	}

	private static int opaque(BufferedImage img) {
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if ((img.getRGB(x, y) >>> 24) != 0) {
					n++;
				}
			}
		}
		return n;
	}

	private static int colours(BufferedImage img) {
		java.util.HashSet<Integer> set = new java.util.HashSet<>();
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int p = img.getRGB(x, y);
				if ((p >>> 24) != 0) {
					set.add(p);
				}
			}
		}
		return set.size();
	}
}

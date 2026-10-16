package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads the hero's standing sprite.
 *
 * <p>The engine composes the hero from 14-byte part records: each one names its own archive slot,
 * a frame index, a width and a height, and CalcHeroAnim derives the frame as {@code A + tick}
 * from a chain record and the archive's frame table. The parts themselves live in archive 021,
 * which the loader tags with ani_sprite == 2 and which is the only one of that set holding a
 * character: 001, 011, 017, 022 and 025 are shadows, smoke, slashes and explosions.
 *
 * <p>Inside 021, frames 0..18, 20 and 22 are complete heads and 19, 21 and 23..115 are body and
 * armour parts. Which part sits at which offset comes from the per-part tables the loader fills
 * at run time, and those read as zero during play, so this draws the head alone rather than
 * guessing an alignment.
 */
public final class HeroSprite {

	/** ani_sprite[21] == 2, and no other archive in that set holds character art. */
	private static final int ARCHIVE = 21;
	/** Frames 0..22 are heads; the body parts that follow are drawn at offsets we cannot recover. */
	private static final int LAST_HEAD = 22;

	private final Gsac g;

	private HeroSprite(Gsac g) {
		this.g = g;
	}

	public static HeroSprite load(Path assets) throws IOException {
		String name = String.format("%03d", ARCHIVE);
		Path dir = assets.resolve("img");
		Gsac g = Gsac.load(dir.resolve(name + ".png"), assets.resolve("pal").resolve(name + ".png"));
		if (!g.fullyDecodable() || g.frameCount() == 0) {
			throw new IOException("archive " + name + " could not be decoded");
		}
		return new HeroSprite(g);
	}

	/** The head frame with the most opaque pixels, which is the front-facing one. */
	public BufferedImage standing() {
		BufferedImage best = null;
		int bestScore = -1;
		for (int f = 0; f <= LAST_HEAD && f < g.frameCount(); f++) {
			BufferedImage img = g.frame(f);
			int score = opaque(img);
			if (score > bestScore) {
				bestScore = score;
				best = img;
			}
		}
		return best;
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
}

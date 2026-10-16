package wawa;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The standing pose of a monster: animation state 0, step 0, composed by {@link MonsterAnim}
 * exactly as CalcMonsAnim/DrawMonsAnim assemble it from dat/&lt;NN&gt;.dat parts.
 */
public final class MonsterSprite {

	private final MonsterData kinds;
	private final MonsterAnim anim;
	private final Map<Integer, PartSprites.Pose> byKind = new HashMap<>();
	private final Map<Long, PartSprites.Pose> poses = new HashMap<>();

	private MonsterSprite(Path assets, MonsterData kinds) {
		this.kinds = kinds;
		this.anim = new MonsterAnim(assets, kinds);
	}

	/** The libraries sit beside the assets directory in the APK, not inside it. */
	public static Path nativeLib(Path assets) {
		return assets.toAbsolutePath().getParent().resolve("lib/armeabi/libwawa.so");
	}

	public static MonsterSprite load(Path assets) throws IOException {
		return load(assets, nativeLib(assets));
	}

	public static MonsterSprite load(Path assets, Path nativeLib) throws IOException {
		return new MonsterSprite(assets, MonsterData.load(nativeLib));
	}

	/** The standing pose for a monster id, or null when none of its parts can be decoded. */
	PartSprites.Pose standing(int monsterId) {
		int kind = kinds.kind(monsterId);
		if (!byKind.containsKey(kind)) {
			PartSprites.Pose pose;
			try {
				pose = anim.pose(monsterId, 0, 0);
			} catch (IOException e) {
				pose = null;
			}
			byKind.put(kind, pose);
		}
		return byKind.get(kind);
	}

	/**
	 * One step of ani_* entry {@code ani} of the monster's kind, mirrored as ChangeMonsDir's +0x28
	 * says; null when nothing in it decodes.
	 */
	PartSprites.Pose pose(int monsterId, int ani, int step, boolean mirror) {
		int kind = kinds.kind(monsterId);
		int[] entry = kinds.animEntry(kind, ani);
		int s = entry == null ? 0 : Math.floorMod(step, Math.max(1, entry[2]));
		long key = (long) kind << 32 | (long) ani << 16 | (long) s << 1 | (mirror ? 1 : 0);
		return poses.computeIfAbsent(key, k -> {
			try {
				return anim.pose(monsterId, ani, s, mirror);
			} catch (IOException e) {
				return null;
			}
		});
	}

	int kind(int monsterId) {
		return kinds.kind(monsterId);
	}

	/** {u16, baseFrame, steps} of ani entry {@code ani} of the monster's kind, or null. */
	int[] animEntry(int monsterId, int ani) {
		return kinds.animEntry(kinds.kind(monsterId), ani);
	}

	/** Frames step {@code step} of entry {@code ani} holds beyond its first. */
	int delay(int monsterId, int ani, int step) {
		try {
			return anim.delay(monsterId, ani, step);
		} catch (IOException e) {
			return 0;
		}
	}

	public int palette(int monsterId) {
		return kinds.palette(monsterId);
	}

	/** The shipped Korean name, or null when that id has none. */
	public String name(int monsterId) {
		return kinds.name(monsterId);
	}
}

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

	public int palette(int monsterId) {
		return kinds.palette(monsterId);
	}

	/** The shipped Korean name, or null when that id has none. */
	public String name(int monsterId) {
		return kinds.name(monsterId);
	}
}

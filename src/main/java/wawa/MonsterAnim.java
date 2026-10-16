package wawa;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a monster's drawn pose the way CalcMonsAnim and DrawMonsAnim do in libwawa.so.
 *
 * <p>The animation data is dat/&lt;NN&gt;.dat ({@link PartSprites}), where NN comes from the kind's
 * row in the table LoadGameMonsOne reads (0xbac4c), not from the kind itself. A frame is a chain
 * of up to eight A records starting at {@code baseFrame + step}. CalcMonsAnim walks that chain
 * from its last record back to the first and, for every B reference without bit 15, inserts part
 * {@code ref & 0x3fff} at list position {@code w0 >>> 26} (or appends it when the list is
 * shorter). Bit 14 hides the part. B references with bit 15 set are meant to change the visibility
 * of a part already in the list, but both the Android and the WIPI build mask the reference with
 * 0xffff3fff, which keeps bit 15, so the lookup never matches a stored id and the reference
 * changes nothing; this port skips them for the same result.
 *
 * <p>DrawMonsAnim draws every part with the monster's palette variant rather than the part's own
 * palette field; variants are not applied yet, so the first palette row is used.
 */
public final class MonsterAnim {

	static final int CHAIN_LIMIT = 8;
	static final int PART_INDEX = 0x3fff;
	static final int TOGGLE = 0x8000;
	static final int HIDDEN = 0x4000;

	private final MonsterData data;
	private final PartSprites sprites;

	public MonsterAnim(Path assets, MonsterData data) {
		this.data = data;
		this.sprites = new PartSprites(assets);
	}

	/** The parts of one animation step, in drawing order (back to front). */
	List<PartSprites.Part> parts(int monsterId, int state, int step) throws IOException {
		int kind = data.kind(monsterId);
		int[] entry = data.animEntry(kind, state);
		if (entry == null) {
			return List.of();
		}
		PartSprites.Dat dat = sprites.dat(data.dat(kind));
		int frame = entry[1] + Math.floorMod(step, Math.max(1, entry[2]));
		List<int[]> live = chainParts(dat, frame, false);
		List<PartSprites.Part> out = new ArrayList<>();
		for (int i = live.size() - 1; i >= 0; i--) {
			if (live.get(i)[1] != 0) {
				out.add(part(dat.parts()[live.get(i)[0]], 0));
			}
		}
		return out;
	}

	/**
	 * The delay byte of the step's first A record: the step shows delay + 1 frames. CalcHeroAnim
	 * reads it so; CalcMonsAnim is assumed to do the same [inferred].
	 */
	int delay(int monsterId, int state, int step) throws IOException {
		int kind = data.kind(monsterId);
		int[] entry = data.animEntry(kind, state);
		if (entry == null) {
			return 0;
		}
		PartSprites.Dat dat = sprites.dat(data.dat(kind));
		int frame = entry[1] + Math.floorMod(step, Math.max(1, entry[2]));
		return frame < 0 || frame >= dat.chain().length ? 0 : dat.chain()[frame][3];
	}

	/** Composes one animation step into an image, or null when nothing in it can be decoded. */
	public PartSprites.Pose pose(int monsterId, int state, int step) throws IOException {
		return pose(monsterId, state, step, false);
	}

	/** The same, mirrored left-right when {@code mirror}. */
	PartSprites.Pose pose(int monsterId, int state, int step, boolean mirror) throws IOException {
		return sprites.compose(parts(monsterId, state, step), mirror);
	}

	/**
	 * The part list CalcMonsAnim and CalcHeroAnim build from the chain starting at {@code frame}:
	 * entries are {part index, visible}, in list order (first entry on top). CalcHeroAnim also hides
	 * parts whose w0 bit 23 is set ({@code hideBit23}); CalcMonsAnim does not look at that bit.
	 */
	static List<int[]> chainParts(PartSprites.Dat dat, int frame, boolean hideBit23) {
		List<int[]> live = new ArrayList<>();
		if (frame < 0 || frame >= dat.chain().length) {
			return live;
		}
		int[] chain = new int[CHAIN_LIMIT];
		int n = 0;
		for (int f = frame; n < CHAIN_LIMIT && f >= 0; f = dat.chain()[f][0]) {
			chain[n++] = f;
		}
		for (int i = n - 1; i >= 0; i--) {
			int[] rec = dat.chain()[chain[i]];
			for (int k = 0; k < rec[2]; k++) {
				int ref = dat.refs()[rec[1] + k];
				if ((ref & TOGGLE) != 0) {
					continue;
				}
				int index = ref & PART_INDEX;
				int w0 = dat.parts()[index][0];
				boolean hidden = (ref & HIDDEN) != 0 || hideBit23 && (w0 & 0x800000) != 0;
				int[] part = {index, hidden ? 0 : 1};
				int layer = w0 >>> 26;
				if (layer < live.size()) {
					live.add(layer, part);
				} else {
					live.add(part);
				}
			}
		}
		return live;
	}

	/** A C entry as a drawn part with the given palette row. */
	static PartSprites.Part part(int[] c, int row) {
		int w0 = c[0];
		int w1 = c[1];
		return new PartSprites.Part(w1 & 0xff, w0 & 0xfff, w1 >> 20, (w1 << 12) >> 20, (w0 >>> 24) & 3, row);
	}
}

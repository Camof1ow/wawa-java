package wawa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The hero's drawn pose, as CalcHeroAnim / DrawHeroAnim build it from actPart (acts below 0x26),
 * the direct ani indices of acts 0x26..0x2b, and act 0x1d, which plays act 4 backwards (getting up).
 * Attack timing (GetHeroAttackFrame), skills and riding are not ported. Ported from the WIPI build,
 * whose CalcHeroAnim decompiles without the jump tables that hide the Android one.
 *
 * <p>A step shows its first A record's delay byte + 1 frames (CalcHeroAnim, fp 2d64a 284-299).
 * <pre>
 * body       dat/20.dat (3135 frames), the chain of ani_hero[a].base + step   like a monster
 * ani_hero   0xc2144   493 x {s16 layerBase, s16 base, s16 steps}
 * actPart    0xb9f2c   g_anHeroActPart[act][dir][kind][7]: [0] ani index, [1..5] equipment
 *                      layer offsets from layerBase (-1 none), [6] effect layer
 * conKind    0xb9884   per equipment type: first con_hero entry
 * conCount   0xb9890   per equipment type: frames it replaces
 * con_hero   0xc2cd4   36 x {u8 slot, u8 firstFrame, s16 lnkBase}
 * lnk_hero   0xc2d64   356 x {u8 slot, u8 frame, s8 dx0, s8 dy0, s8 dx1, s8 dy1}
 * </pre>
 *
 * Five equipment layers sit on the body: layer 0 the weapon (type 5, 6 or 7 by weapon kind, look
 * +0xd/+0xe/+0xf), layer 1 the off hand (type 1, look +9; type 8, look +0x10, for kind 3), layers
 * 2..4 armour (types 2..4, looks +0xa..+0xc); each colour is at its look + 9. A layer with look
 * &lt; 0 is skipped. Each layer adds the parts of one A record (ani[layerBase + offset].base + step);
 * a part whose slot and frame fall in its type's con_hero range takes palette row = colour, and
 * with look &gt; 0 is swapped for lnk_hero[lnkBase + look] (slot, frame and an offset by flip).
 * Layer parts go in front of the body part that held their list position (w0 &gt;&gt;&gt; 26), or
 * at the end. Direction 3 draws direction 2 mirrored.
 */
final class HeroAnim {

	static final int ACT_STAND = 0;
	static final int ACT_WALK = 1;
	static final int ACT_FALL = 4;
	/** Getting up: act 4's rows played from the last step back to the first. */
	static final int ACT_RISE = 0x1d;
	static final int ACT_SIT = 0x26;
	/** CalcHeroAnim's ani index for acts 0x26..0x2b; they draw no equipment but 0x26's armour. */
	private static final int[] DIRECT_ANI = { 0x16, 0x1a, 0x18, 0x19, 0x17, 0x1b };

	private static final int BODY_DAT = 20;
	private static final int ANI = 0xc2144;
	private static final int ACT_PART = 0xb9f2c;
	private static final int CON_KIND = 0xb9884;
	private static final int CON_COUNT = 0xb9890;
	private static final int CON = 0xc2cd4;
	private static final int LNK = 0xc2d64;

	/** The first 0x28 bytes of a hero record that drawing reads: kind +4, dir +5, act +7, looks and colours. */
	record Look(int kind, int dir, int act, int[] looks, int[] colours) {

		/** Looks and colours are indexed by record offset - 8, i.e. +8 .. +0x10. */
		static Look bare(int kind, int dir, int act) {
			int[] none = new int[9];
			Arrays.fill(none, -1);
			return new Look(kind, dir, act, none, new int[9]);
		}

		Look withAct(int a) {
			return new Look(kind, dir, a, looks, colours);
		}
	}

	private final ElfImage elf;
	private final PartSprites sprites;

	/**
	 * SetGOrderNewGameHero: the outfit the new-game preview dresses each class in, as
	 * {record offset, item id, colour added} into prehero[1]; the weapon kind follows.
	 */
	private static final int[][][] PREVIEW = {
		{ { 0xe, 150, 4 }, { 0xa, 190, 3 }, { 0xb, 214, 3 }, { 0xc, 202, 3 } },
		{ { 0xd, 169, 4 }, { 0x9, 229, 3 }, { 0xa, 193, 3 }, { 0xb, 217, 3 }, { 0xc, 205, 3 } },
		{ { 0xf, 155, 2 }, { 0x10, 155, 2 }, { 0xa, 185, 2 }, { 0xb, 209, 2 }, { 0xc, 197, 2 } },
	};
	private static final int[] PREVIEW_KIND = { 2, 0, 3 };

	static Look preview(Item items, int heroClass, int act) {
		// Facing down (1), toward the viewer, as the original's new-game screen shows the class; the
		// command that sets prehero[1] +5 sits in KeyPressGOrderNewGame's undecompiled jump table.
		Look l = Look.bare(PREVIEW_KIND[heroClass], 1, act);
		for (int[] e : PREVIEW[heroClass]) {
			l.looks()[e[0] - 8] = items.look(e[1]);
			l.colours()[e[0] - 8] = items.colour(e[1]) + e[2];
		}
		return l;
	}

	/**
	 * The port's hero: only the weapon is worn, under the look offset its kind uses
	 * (+0xd, +0xe or +0xf), with the colour AddItem gave the instance (record +8, grade 0).
	 */
	static Look wearing(Item items, Item.Instance weapon, int dir, int act) {
		int kind = weapon == null ? 0 : weapon.kind();
		Look l = Look.bare(kind, dir, act);
		if (weapon != null) {
			int o = kind == 0 ? 0xd : kind == 2 ? 0xe : 0xf;
			l.looks()[o - 8] = items.look(weapon.id());
			l.colours()[o - 8] = items.colour(weapon.id());
		}
		return l;
	}

	HeroAnim(Assets assets, ElfImage elf) {
		this.elf = elf;
		this.sprites = new PartSprites(assets);
	}

	PartSprites.Pose pose(Look look, int step) throws IOException {
		return sprites.compose(parts(look, step), mirrored(look));
	}

	/** Steps in one cycle of the look's act. */
	int steps(Look look) {
		return Math.max(1, elf.s16(ANI + ani(look) * 6 + 4));
	}

	/** Frames step {@code step} holds beyond its first. */
	int delay(Look look, int step) throws IOException {
		int frame = elf.s16(ANI + ani(look) * 6 + 2) + shown(look, step);
		PartSprites.Dat dat = sprites.dat(BODY_DAT);
		return frame < 0 || frame >= dat.chain().length ? 0 : dat.chain()[frame][3];
	}

	/** Acts 2, 4 and 0x1d have two direction rows: row dir &amp; 1, mirrored by dir &amp; 2. */
	private static boolean twoRows(int act) {
		return act == 2 || act == ACT_FALL || act == ACT_RISE;
	}

	private static boolean mirrored(Look look) {
		return twoRows(look.act()) ? (look.dir() & 2) != 0 : (look.dir() & 3) == 3;
	}

	/** The direction row of actPart: 0..2, with 3 drawn as 2 mirrored. */
	private static int dirRow(Look look) {
		if (twoRows(look.act())) {
			return look.dir() & 1;
		}
		return (look.dir() & 3) == 3 ? 2 : look.dir() & 3;
	}

	/** The actPart row CalcHeroAnim reads: act 0x1d borrows act 4's. */
	private int partRow(Look look) {
		int act = look.act() == ACT_RISE ? ACT_FALL : look.act();
		return ACT_PART + act * 0x54 + dirRow(look) * 0x1c + look.kind() * 7;
	}

	private int ani(Look look) {
		int act = look.act();
		return act >= ACT_SIT && act < ACT_SIT + DIRECT_ANI.length ? DIRECT_ANI[act - ACT_SIT] : elf.s8(partRow(look));
	}

	/** The ani step drawn for cycle position {@code step}; act 0x1d runs backwards. */
	private int shown(Look look, int step) {
		int n = steps(look);
		int s = Math.floorMod(step, n);
		return look.act() == ACT_RISE ? n - 1 - s : s;
	}

	List<PartSprites.Part> parts(Look look, int step) throws IOException {
		PartSprites.Dat dat = sprites.dat(BODY_DAT);
		int act = look.act();
		int partRow = partRow(look);
		int ani = ani(look);
		int layerBase = elf.s16(ANI + ani * 6);
		int s = shown(look, step);
		int frame = elf.s16(ANI + ani * 6 + 2) + s;

		List<int[]> body = MonsterAnim.chainParts(dat, frame, true);
		List<Entry> list = new ArrayList<>();
		for (int[] b : body) {
			list.add(new Entry(b[0], b[1] != 0, MonsterAnim.part(dat.parts()[b[0]], (dat.parts()[b[0]][0] >>> 16) & 0x7f)));
		}
		int bodyCount = list.size();
		// Anchors are body parts at a list position; CalcHeroAnim resolves them before any layer merges.
		int[] bodyIds = list.stream().mapToInt(e -> e.index).toArray();

		int[][] layers = layers(look);
		for (int layer = 0; layer < 5; layer++) {
			int type = layers[layer][0];
			int lk = layers[layer][1];
			if (type < 0 || lk < 0) {
				continue;
			}
			int offset;
			if (act == ACT_SIT) {
				if (layer < 2) {
					continue;
				}
				offset = layer - 2;
			} else if (act > ACT_SIT) {
				// fp 2d64a 677-681: layers only below 0x26 or for 0x26 itself.
				continue;
			} else {
				offset = elf.s8(partRow + layer + 1);
				if (offset < 0) {
					continue;
				}
			}
			int rec = elf.s16(ANI + (layerBase + offset) * 6 + 2) + s;
			if (rec < 0 || rec >= dat.chain().length) {
				continue;
			}
			int ck = elf.s8(CON_KIND + type);
			int conSlot = elf.u8(CON + ck * 4);
			int first = elf.u8(CON + ck * 4 + 1);
			int count = elf.s8(CON_COUNT + type);
			int[] r = dat.chain()[rec];
			List<Entry> added = new ArrayList<>();
			List<Integer> anchors = new ArrayList<>();
			for (int k = 0; k < r[2]; k++) {
				int ref = dat.refs()[r[1] + k];
				if ((ref & MonsterAnim.TOGGLE) != 0) {
					continue;
				}
				int index = ref & MonsterAnim.PART_INDEX;
				int[] c = dat.parts()[index];
				if ((c[0] & 0x800000) != 0) {
					continue;
				}
				int pos = c[0] >>> 26;
				anchors.add(pos < bodyCount ? bodyIds[pos] : -1);
				PartSprites.Part p = MonsterAnim.part(c, (c[0] >>> 16) & 0x7f);
				if (p.slot() == conSlot && p.frame() >= first && p.frame() < first + count) {
					int row = layers[layer][2];
					if (lk > 0) {
						int lnk = LNK + (elf.s16(CON + (ck + p.frame() - first) * 4 + 2) + lk) * 6;
						int flip = p.flip();
						int dx = elf.s8(lnk + ((flip & 1) == 0 ? 2 : 4));
						int dy = elf.s8(lnk + ((flip & 2) == 0 ? 3 : 5));
						p = new PartSprites.Part(elf.u8(lnk), elf.u8(lnk + 1), p.dx() + dx, p.dy() + dy, flip, row);
					} else {
						p = new PartSprites.Part(p.slot(), p.frame(), p.dx(), p.dy(), p.flip(), row);
					}
				}
				added.add(new Entry(index, (ref & MonsterAnim.HIDDEN) == 0, p));
			}
			for (int i = 0; i < added.size(); i++) {
				int anchor = anchors.get(i);
				int at = -1;
				for (int j = 0; anchor >= 0 && j < list.size(); j++) {
					if (list.get(j).index == anchor) {
						at = j;
						break;
					}
				}
				if (at < 0) {
					list.add(added.get(i));
				} else {
					list.add(at, added.get(i));
				}
			}
		}
		List<PartSprites.Part> out = new ArrayList<>();
		for (int i = list.size() - 1; i >= 0; i--) {
			if (list.get(i).visible) {
				out.add(list.get(i).part);
			}
		}
		return out;
	}

	/** {type, look, colour} for the five equipment layers, as CalcHeroAnim fills them for these acts. */
	private static int[][] layers(Look look) {
		int[][] out = new int[5][];
		int kind = look.kind();
		int weapon = kind == 0 ? 5 : kind == 2 ? 6 : 7;
		int wOff = kind == 0 ? 0xd : kind == 2 ? 0xe : 0xf;
		out[0] = new int[] {weapon, look.looks()[wOff - 8], look.colours()[wOff - 8]};
		if (kind < 2) {
			out[1] = new int[] {1, look.looks()[9 - 8], look.colours()[9 - 8]};
		} else if (kind == 3) {
			out[1] = new int[] {8, look.looks()[0x10 - 8], look.colours()[0x10 - 8]};
		} else {
			out[1] = new int[] {-1, -1, 0};
		}
		for (int layer = 2; layer < 5; layer++) {
			int o = 0xa + layer - 2;
			out[layer] = new int[] {layer, look.looks()[o - 8], look.colours()[o - 8]};
		}
		return out;
	}

	private record Entry(int index, boolean visible, PartSprites.Part part) {
	}
}

package wawa;

/**
 * The original's melee tables and hit geometry (vaddr):
 *
 * <pre>
 * g_anHeroAttactFrame  0xbacec  [form*4 + i][5] s8 {f0, f1 hold steps, f2 hit, f3 second hit, f4 steps};
 *                               i 0 for acts 9/0xf, 1 for 0xa/0x10
 * g_anWeaponAttactDelay 0xbac8c [look (+12 form 2, +24 form 1)][2] s8: frames step f0 / f1 hold
 * g_arcHeroAttactRect  0xbae00  [form*4 + i][4] s8 {x0, y0, x1, y1}, written facing up
 * g_anMonsLook_K       0xbe514  u8 per monster id: its kind (+0x21)
 * g_arcMonsBlow_LE     0xc07b4  [kind][4] s8 hurtbox around the monster's position
 * g_anMonsSize_L       0xc089c  s8 size class 0..3 per kind
 * g_anMonsHeadY_L      0xc08d4  u8 per kind; HalfY (damage numbers) is it &gt;&gt; 1
 * flinch threshold     0xd9620  s16 per size
 * g_anUnitPushed       0xba83c  [(size*4 + form)*2 + (crit ? 0 : 1)] s8, half px
 * g_aptKnock           0xba7bc  [((size*4 + form)*4 + n)*2] s8 {half px, lift px}, the death fling
 * g_anHeroWeapon       0xbace8  per form; the combo display frames at 0xd9664 are indexed by it
 * aim order            0xdc0a8  [facing][4] s8
 * </pre>
 *
 * Forms are hero[4]: 0 a type-1 weapon, 1 type 3, 2 type 2 (3, two type-3 weapons, needs a
 * second slot this port keeps shut).
 */
final class Fight {

	private static final int FRAME = 0xbacec;
	private static final int DELAY = 0xbac8c;
	private static final int RECT = 0xbae00;
	private static final int MONS_LOOK = 0xbe514;
	private static final int BLOW = 0xc07b4;
	private static final int SIZE = 0xc089c;
	private static final int HEAD_Y = 0xc08d4;
	private static final int FLINCH = 0xd9620;
	private static final int PUSHED = 0xba83c;
	private static final int KNOCK = 0xba7bc;
	private static final int HERO_WEAPON = 0xbace8;
	private static final int COMBO_SHOW = 0xd9664;
	private static final int AIM = 0xdc0a8;
	/** g_aAdjDir: GetDegree quadrant (right, down, left, up) to a facing. */
	private static final int[] ADJ_DIR = { 3, 1, 2, 0 };

	private final ElfImage so;

	Fight(ElfImage so) {
		this.so = so;
	}

	/** GetHeroAttackFrame: {f0, f1, f2, f3, f4} for act 9/0xf (i 0) or 0xa/0x10 (i 1). */
	int[] frames(int form, int act) {
		int at = FRAME + (form * 4 + (act == 0xa || act == 0x10 ? 1 : 0)) * 5;
		int[] f = new int[5];
		for (int k = 0; k < 5; k++) {
			f[k] = so.s8(at + k);
		}
		return f;
	}

	/** InitHeroWeaponSpeed: the frames steps f0 and f1 hold for a weapon look. */
	int[] delays(int form, int look) {
		int at = DELAY + (look + (form == 2 ? 12 : form == 1 ? 24 : 0)) * 2;
		return new int[] { Math.max(0, so.s8(at)), Math.max(0, so.s8(at + 1)) };
	}

	/**
	 * CalcHeroAttackRect: the swing's rectangle {x0, y0, x1, y1} in world px for a hero at (hx, hy)
	 * facing {@code dir} (0 up, 1 down, 2 left, 3 right).
	 */
	int[] rect(int form, int act, int dir, int hx, int hy) {
		int at = RECT + (form * 4 + (act == 0xa || act == 0x10 ? 1 : 0)) * 4;
		return rotate(so.s8(at), so.s8(at + 1), so.s8(at + 2), so.s8(at + 3), dir, hx, hy);
	}

	static int[] rotate(int x0, int y0, int x1, int y1, int dir, int hx, int hy) {
		return switch (dir) {
			case 1 -> new int[] { hx + x0, hy - y1, hx + x1, hy - y0 };
			case 2 -> new int[] { hx + y0, hy + x0, hx + y1, hy + x1 };
			case 3 -> new int[] { hx - y1, hy + x0, hx - y0, hy + x1 };
			default -> new int[] { hx + x0, hy + y0, hx + x1, hy + y1 };
		};
	}

	/** The monster kind (+0x21) of an id. */
	int kind(int id) {
		return so.u8(MONS_LOOK + id);
	}

	/** True when the rectangle overlaps the hurtbox of a monster id standing at (mx, my). */
	boolean hits(int[] r, int id, int mx, int my) {
		int at = BLOW + kind(id) * 4;
		return r[0] < mx + so.s8(at + 2) && mx + so.s8(at) < r[2] && r[1] < my + so.s8(at + 3) && my + so.s8(at + 1) < r[3];
	}

	int size(int id) {
		return so.s8(SIZE + kind(id));
	}

	/** g_anMonsHalfY_L, which MainApp fills as g_anMonsHeadY_L &gt;&gt; 1: where the damage number starts. */
	int halfY(int id) {
		return so.u8(HEAD_Y + kind(id)) >> 1;
	}

	int flinch(int size) {
		return so.s16(FLINCH + Math.max(0, Math.min(3, size)) * 2);
	}

	/** CalcMons' push after a hit, in half px. */
	int pushed(int size, int form, boolean crit) {
		return so.s8(PUSHED + (size * 4 + form) * 2 + (crit ? 0 : 1));
	}

	/** State 0xf's frame n (0..3): {half px, lift px}. */
	int[] knock(int size, int form, int n) {
		int at = KNOCK + ((size * 4 + form) * 4 + n) * 2;
		return new int[] { so.s8(at), so.s8(at + 1) };
	}

	/** The combo counter's display frames for a weapon form. */
	int comboFrames(int form) {
		return so.u8(COMBO_SHOW + so.s8(HERO_WEAPON + form));
	}

	/** CheckHeroOnMons' order of facings to try, starting from the current one. */
	int[] aimOrder(int facing) {
		int[] o = new int[4];
		for (int k = 0; k < 4; k++) {
			o[k] = so.s8(AIM + facing * 4 + k);
		}
		return o;
	}

	/** g_aAdjDir[((GetDegree(dx, dy) + 0x2000) &amp; 0xffff) &gt;&gt; 14]: the facing toward an offset. */
	static int facingToward(int dx, int dy) {
		int deg = (int) Math.round(Math.atan2(dy, dx) / (2 * Math.PI) * 65536);
		return ADJ_DIR[((deg + 0x2000) & 0xffff) >> 14];
	}
}

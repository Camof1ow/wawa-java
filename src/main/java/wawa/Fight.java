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

	/** g_arcMonsBlow_LE's hurtbox {x0, y0, x1, y1} of a monster id standing at (mx, my). */
	int[] blow(int id, int mx, int my) {
		int at = BLOW + kind(id) * 4;
		return new int[] { mx + so.s8(at), my + so.s8(at + 1), mx + so.s8(at + 2), my + so.s8(at + 3) };
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

	/** GetDegree: the angle of (dx, dy) as a u16, 0 = +x and 0x4000 = +y (down); exact on the axes, (0, 0) gives 0xc000. */
	static int degree(int dx, int dy) {
		if (dx == 0) {
			return dy > 0 ? 0x4000 : 0xc000;
		}
		if (dy == 0) {
			return dx > 0 ? 0 : 0x8000;
		}
		return (int) Math.round(Math.atan2(dy, dx) / (2 * Math.PI) * 65536) & 0xffff;
	}

	/** g_aAdjDir[((GetDegree(dx, dy) + 0x2000) &amp; 0xffff) &gt;&gt; 14]: the facing toward an offset. */
	static int facingToward(int dx, int dy) {
		return ADJ_DIR[((degree(dx, dy) + 0x2000) & 0xffff) >> 14];
	}

	/** ChangeMonsDetailDir: the 8-way direction toward an offset, g_aAdjDir (0xb9860) {3,7,1,5,2,6,0,4}. */
	static int detailDir(int dx, int dy) {
		return ADJ_DIR8[((degree(dx, dy) + 0x1000) & 0xffff) >> 13];
	}

	private static final int[] ADJ_DIR8 = { 3, 7, 1, 5, 2, 6, 0, 4 };
	private static final int SPEED = 0xbfca8;
	private static final int ATT_FRM = 0xbeae0;
	private static final int ATTACK = 0xc00c8;
	private static final int ATT_BOAR = 0xc05f8;
	private static final int LOOK = 0xe5f30;
	private static final int WAIT_CNT = 0xbe640;
	private static final int MOVE_CNT = 0xbe714;
	private static final int CHASE_LEN = 0xbe8bc;
	private static final int STATE_COND = 0xc1574;
	private static final int BODY = 0xc0830;
	private static final int MASK = 0xbe5e8;
	private static final int TURN = 0xbcab8;
	private static final int ACT = 0xbec2c;
	private static final int SPEC = 0xc14cc;
	private static final int BULLET = 0xbe964;

	/** g_anMonsSpeed[id*5 + k] in half px a frame: k 0 walk, 1 chase and flee, 2 alert, 3 attack lunge, 4 script walk. */
	int speed(int id, int k) {
		return so.s8(SPEED + id * 5 + k);
	}

	/** g_anMonsAttFrm_E: the inclusive animation steps {lo, hi} in which an attack strikes. */
	int[] attackSteps(int id) {
		return new int[] { so.s8(ATT_FRM + id * 2), so.s8(ATT_FRM + id * 2 + 1) };
	}

	/** g_arcMonsAttack_E[id*8 + row*4]: the attack's Rect8 {x0, y0, x1, y1}; row 1 when the hero is above. */
	int[] attackRect(int id, int row) {
		return rect8(ATTACK + id * 8 + row * 4);
	}

	/** g_arcMonsAttBoar[row*4]: the strike rectangle of the boars (ids with StateCond &amp; 6). */
	int[] boarRect(int row) {
		return rect8(ATT_BOAR + row * 4);
	}

	private int[] rect8(int at) {
		return new int[] { so.s8(at), so.s8(at + 1), so.s8(at + 2), so.s8(at + 3) };
	}

	/** g_arcMonsLook_E: s16 {a, b, c, d} per id, which MainApp multiplies by 8 at load (px). */
	int[] look(int id) {
		int at = LOOK + id * 8;
		return new int[] { so.s16(at) * 8, so.s16(at + 2) * 8, so.s16(at + 4) * 8, so.s16(at + 6) * 8 };
	}

	/** g_anMonsWaitCnt / g_anMonsMoveCnt: stand and walk cycles, RandRange(low nibble, high nibble). */
	int waitCount(int id) {
		return so.u8(WAIT_CNT + id);
	}

	int moveCount(int id) {
		return so.u8(MOVE_CNT + id);
	}

	/** g_anMonsChaseLen_E: how far GetMonsRouting may lead a chase. */
	int chaseLen(int id) {
		return so.u8(CHASE_LEN + id);
	}

	/** g_anMonsStateCond_K: 0x01 burrows, 0x06 boar 8-way attack, 0x10 group aggro, 0x40 flees at low HP. */
	int cond(int id) {
		return so.u8(STATE_COND + id);
	}

	/** g_aptMonsBlock_L: the body {width, height} in tiles of a monster kind. */
	int bodyW(int kind) {
		return Math.max(1, so.s8(BODY + kind * 2));
	}

	int bodyH(int kind) {
		return Math.max(1, so.s8(BODY + kind * 2 + 1));
	}

	/** g_anMonsMask_L: the directions a monster kind may face, bit c for g_anMonsTurnDir column c. */
	int mask(int kind) {
		return so.u8(MASK + kind);
	}

	/** g_anMonsTurnDir[dir*8 + c]: the turning order from a direction. */
	int turn(int dir, int c) {
		return so.s8(TURN + dir * 8 + c);
	}

	/** g_anMonsAct[id*20] is -99 when the id has no hidden (state 0) animation. */
	boolean hides(int id) {
		return so.s8(ACT + id * 20) != -99;
	}

	/** g_anMonsStateSpec_E: two nibbles of on-hit statuses (1 stun, 2 knockdown, 3 knockback, ...). */
	int spec(int id) {
		return so.u8(SPEC + id);
	}

	/** g_anMonsAttBullet_E: 0 for a melee attacker. */
	int bullet(int id) {
		return so.u8(BULLET + id);
	}

}

package wawa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The hero's items as the hero record holds them.
 *
 * <pre>
 * equipment  hero+0x87c   50 instances of 0x1e bytes, the first hero[0x877] (InitHero: 20) usable
 * stacks     hero+0x4a8   u16 id, +0x746 s8 count, +0x615 grade (recipes), 0x131 slots in four ranges:
 *            0x00..0x9f   materials (0xd)            up to hero[0x879] (40)
 *            0xa0..0xae   consumables (0xe, 0xf)     up to hero[0x878] (12)
 *            0xaf..0xf4   the rest                   up to hero[0x87a] (30; +10 more for 0x11, 0x14)
 *            0xf5..0x130  recipes (0x13)             up to hero[0x87b] (30)
 * gold       hero+0xa4
 * </pre>
 *
 * The rune options, quick slots and item cooldowns of the record are not ported.
 */
final class Inventory {

	static final int EQUIP = 50;
	static final int STACKS = 0x131;
	static final int CONSUMABLES = 0xa0;

	private final Item items;
	final Item.Equip[] equip = new Item.Equip[EQUIP];
	final int[] stackId = new int[STACKS];
	final int[] stackCount = new int[STACKS];
	final int[] stackGrade = new int[STACKS];
	/** hero[0x877..0x87b]: equipment, consumables, materials, misc and recipe capacities. */
	int equipCap = 20;
	int consumableCap = 12;
	int materialCap = 40;
	int miscCap = 30;
	int recipeCap = 30;
	/** hero+0xa4. */
	int gold;

	Inventory(Item items) {
		this.items = items;
		Arrays.fill(stackId, -1);
	}

	/** AddItem's and GetItemCount's range for a stacking type, capacity applied: {first, end}. */
	private int[] range(int type, boolean add) {
		return switch (type) {
			case 0xd -> new int[] { 0, materialCap };
			case 0xe, 0xf -> new int[] { 0xa0, 0xa0 + consumableCap };
			case 0x13 -> new int[] { 0xf5, 0xf5 + recipeCap };
			// AddItem gives types 0x11 and 0x14 ten more slots, GetItemCount only 0x14.
			default -> new int[] { 0xaf, 0xaf + miscCap + (type == 0x14 || add && type == 0x11 ? 10 : 0) };
		};
	}

	/** DelItem's fixed range for a stacking type, capacity ignored: {first, last}. */
	private static int[] fullRange(int type) {
		return switch (type) {
			case 0xd -> new int[] { 0, 0x9f };
			case 0xe, 0xf -> new int[] { 0xa0, 0xae };
			case 0x13 -> new int[] { 0xf5, 0x130 };
			default -> new int[] { 0xaf, 0xf4 };
		};
	}

	/**
	 * AddItem(id, count, grade, force). Stacks: the room left in the range (an empty slot holds 99,
	 * runes 1) must cover count unless forced, else -1; then the count goes into the first empty
	 * slot whole, or tops up slots of the same id (and grade, for recipes) and spills on. Returns 0
	 * when all went in, the count left when only part did, -1 when none did. Equipment: one instance
	 * in the first free usable slot, returned as its index, -1 when the bag is full.
	 */
	int add(int id, int count, int grade, boolean force, Random rng) {
		int type = items.type(id);
		if (Item.equipment(type)) {
			for (int i = 0; i < equipCap; i++) {
				if (equip[i] == null) {
					equip[i] = items.create(id, grade, rng);
					return i;
				}
			}
			return -1;
		}
		int[] r = range(type, true);
		int max = items.maxStack(id);
		int room = 0;
		for (int i = r[0]; i < r[1]; i++) {
			if (stackId[i] == -1) {
				room += max;
			} else if (sameStack(i, id, type, grade)) {
				room += max - stackCount[i];
			}
		}
		if (room < count && !force) {
			return -1;
		}
		int left = count;
		for (int i = r[0]; i < r[1]; i++) {
			if (stackId[i] == -1) {
				stackId[i] = id;
				stackCount[i] = left;
				stackGrade[i] = type == 0x13 ? grade : 0;
				return 0;
			}
			if (sameStack(i, id, type, grade)) {
				if (stackCount[i] + left <= max) {
					stackCount[i] += left;
					return 0;
				}
				left += stackCount[i] - max;
				stackCount[i] = max;
			}
		}
		return left == count ? -1 : left;
	}

	private boolean sameStack(int i, int id, int type, int grade) {
		return stackId[i] == id && (type != 0x13 || stackGrade[i] == grade);
	}

	/**
	 * DelItem(id, count, grade): stacks lose count from the last slot of the type's whole range back,
	 * equipment drops matching instances from slot 49 down (taking worn ones off first). Then
	 * SortItem. Returns how many could not be taken.
	 */
	int remove(int id, int count, int grade) {
		int type = items.type(id);
		int left = count;
		if (Item.equipment(type)) {
			for (int i = EQUIP - 1; i >= 0; i--) {
				if (equip[i] != null && equip[i].id == id) {
					equip[i].slot = 0;
					equip[i] = null;
					if (--left == 0) {
						break;
					}
				}
			}
		} else {
			int[] r = fullRange(type);
			for (int i = r[1]; i >= r[0]; i--) {
				if (stackId[i] == id && (type != 0x13 || grade < 0 || stackGrade[i] == grade)) {
					if (left <= stackCount[i]) {
						stackCount[i] -= left;
						left = 0;
						break;
					}
					left -= stackCount[i];
					stackCount[i] = 0;
				}
			}
		}
		sort();
		return left;
	}

	/**
	 * SortItem: each equipment and stack range keeps its order with the gaps closed; a stack whose
	 * count fell below 1 empties. (The original skips the second of two adjacent gaps; this port
	 * closes them all.)
	 */
	void sort() {
		int n = 0;
		for (int i = 0; i < EQUIP; i++) {
			if (equip[i] != null) {
				Item.Equip e = equip[i];
				equip[i] = null;
				equip[n++] = e;
			}
		}
		for (int[] r : new int[][] { { 0, 0xa0 }, { 0xa0, 0xaf }, { 0xaf, 0xf5 }, { 0xf5, STACKS } }) {
			int to = r[0];
			for (int i = r[0]; i < r[1]; i++) {
				if (stackId[i] != -1 && stackCount[i] >= 1) {
					int id = stackId[i];
					int c = stackCount[i];
					int g = stackGrade[i];
					stackId[i] = -1;
					stackCount[i] = 0;
					stackGrade[i] = 0;
					stackId[to] = id;
					stackCount[to] = c;
					stackGrade[to] = g;
					to++;
				} else {
					stackId[i] = -1;
					stackCount[i] = 0;
					stackGrade[i] = 0;
				}
			}
		}
	}

	/** GetItemCount(id, grade): stacks within the capacity, or usable equipment slots (grade &lt; 0: any). */
	int count(int id, int grade) {
		int type = items.type(id);
		int n = 0;
		if (Item.equipment(type)) {
			for (int i = 0; i < equipCap; i++) {
				if (equip[i] != null && equip[i].id == id && (grade < 0 || equip[i].grade == grade)) {
					n++;
				}
			}
			return n;
		}
		int[] r = range(type, false);
		for (int i = r[0]; i < r[1]; i++) {
			if (stackId[i] == id && (type != 0x13 || grade < 0 || stackGrade[i] == grade)) {
				n += stackCount[i];
			}
		}
		return n;
	}

	/** GetParameterData kind 5: every stack slot and every instance, worn ones included; a group sums its members. */
	int held(int id) {
		if (items.type(id) == 0) {
			int n = 0;
			for (int m : items.group(id)) {
				n += held(m);
			}
			return n;
		}
		int n = 0;
		for (int i = 0; i < STACKS; i++) {
			if (stackId[i] == id) {
				n += stackCount[i];
			}
		}
		for (Item.Equip e : equip) {
			if (e != null && e.id == id) {
				n++;
			}
		}
		return n;
	}

	/** The instance worn in a slot, or null. */
	Item.Equip worn(int slot) {
		for (Item.Equip e : equip) {
			if (e != null && e.slot == slot) {
				return e;
			}
		}
		return null;
	}

	List<Item.Equip> worn() {
		List<Item.Equip> out = new ArrayList<>();
		for (Item.Equip e : equip) {
			if (e != null && e.slot != 0) {
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * GetGOrderEquipItem without skills: slot 1 takes types 1 and 3, slots 3, 4, 5, 6, 10, 11 and
	 * 12 types 5, 7, 9, 0xb, 6, 0xa and 0xc. Type 2 in slot 1, slots 2, 7, 8 and 9 all need a
	 * skill (S18, S22 or S26) this port does not have yet.
	 */
	static boolean fits(int type, int slot) {
		return switch (slot) {
			case 1 -> type == 1 || type == 3;
			case 3 -> type == 5;
			case 4 -> type == 7;
			case 5 -> type == 9;
			case 6 -> type == 0xb;
			case 10 -> type == 6;
			case 11 -> type == 0xa;
			case 12 -> type == 0xc;
			default -> false;
		};
	}

	/** AnalysisPhaseItem's slot for an instance put on at once, by type 1..0xc. */
	static int slotFor(int type) {
		return switch (type) {
			case 1, 2, 3 -> 1;
			case 4 -> 9;
			case 5 -> 3;
			case 6 -> 10;
			case 7 -> 4;
			case 8 -> 7;
			case 9 -> 5;
			case 0xa -> 0xb;
			case 0xb -> 6;
			default -> 0xc;
		};
	}

	/**
	 * GetGOrderEquipLevel and GetGOrderEquipItem, then SetGOrderEquipItem: whatever wore the slot
	 * comes off and the instance goes on. False when the level or the slot does not allow it.
	 */
	boolean wear(Item.Equip e, int slot, int heroLevel) {
		if (heroLevel < e.level || e.dur < 1 || !fits(e.type, slot)) {
			return false;
		}
		for (Item.Equip o : equip) {
			if (o != null && o.slot == slot) {
				o.slot = 0;
			}
		}
		e.slot = slot;
		return true;
	}

	// ---- save format: properties values ---------------------------------------------------

	/** "equip": id,value,level,dur,durMax,sockets,grade,slot,origin per instance, ';' between. */
	String equipText() {
		StringBuilder b = new StringBuilder();
		for (Item.Equip e : equip) {
			if (e != null) {
				b.append(e.id).append(',').append(e.value).append(',').append(e.level).append(',').append(e.dur)
						.append(',').append(e.durMax).append(',').append(e.sockets).append(',').append(e.grade)
						.append(',').append(e.slot).append(',').append(e.origin).append(';');
			}
		}
		return b.toString();
	}

	/** "stacks": slot,id,count,grade per used slot, ';' between. */
	String stackText() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < STACKS; i++) {
			if (stackId[i] != -1) {
				b.append(i).append(',').append(stackId[i]).append(',').append(stackCount[i]).append(',')
						.append(stackGrade[i]).append(';');
			}
		}
		return b.toString();
	}

	String capText() {
		return equipCap + "," + consumableCap + "," + materialCap + "," + miscCap + "," + recipeCap;
	}

	void readEquip(String s) {
		int n = 0;
		for (String part : s.split(";")) {
			String[] f = part.split(",");
			if (f.length != 9 || n >= EQUIP) {
				continue;
			}
			int id = Integer.parseInt(f[0]);
			Item.Equip e = new Item.Equip(id, items.type(id), Integer.parseInt(f[1]), items.spread(id),
					Integer.parseInt(f[2]), Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]));
			e.dur = Integer.parseInt(f[3]);
			e.slot = Integer.parseInt(f[7]);
			e.origin = Integer.parseInt(f[8]);
			equip[n++] = e;
		}
	}

	void readStacks(String s) {
		for (String part : s.split(";")) {
			String[] f = part.split(",");
			if (f.length != 4) {
				continue;
			}
			int i = Integer.parseInt(f[0]);
			if (i >= 0 && i < STACKS) {
				stackId[i] = Integer.parseInt(f[1]);
				stackCount[i] = Integer.parseInt(f[2]);
				stackGrade[i] = Integer.parseInt(f[3]);
			}
		}
	}

	void readCaps(String s) {
		String[] f = s.split(",");
		if (f.length == 5) {
			equipCap = Integer.parseInt(f[0]);
			consumableCap = Integer.parseInt(f[1]);
			materialCap = Integer.parseInt(f[2]);
			miscCap = Integer.parseInt(f[3]);
			recipeCap = Integer.parseInt(f[4]);
		}
	}
}

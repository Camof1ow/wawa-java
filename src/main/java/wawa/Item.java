package wawa;

import java.util.Random;

/**
 * Item base data the engine keeps in libwawa.so, and the equipment instance AddItem makes from it.
 *
 * <pre>
 * g_anItem        0xc89a8  u32 per id: &amp; 0x1f type (1..0xc equipment, 0xd materials, 0xe/0xf
 *                          consumables, 0x13 recipes, the rest misc), &gt;&gt; 5 &amp; 7 element (the
 *                          cooldown group for 0xe), &gt;&gt; 6 &amp; 3 shield grade (type 8),
 *                          &gt;&gt; 8 &amp; 0xfff name A, &gt;&gt; 20 name B (0xfff: none)
 * g_aItemEx       0xcbffc  record at table + s16[id] + 0x40a. Equipment: +0 u16 value, +2 spread %,
 *                          +3 level, +4 u16 durability, +6 its spread %, +7 look, +8 colour (the
 *                          last two for types below 9). Consumables (0xe, 0xf): +0 u16 global phase
 * g_astrItemName  0xca228  names, at s16 g_anItemNamePos (0xc9bd8)[name index]
 * </pre>
 */
public final class Item {

	private static final int KIND = 0xc89a8;
	private static final int EX = 0xcbffc;
	private static final int EX_BASE = 0x40a;
	private static final int NAMES = 0xca228;
	private static final int NAME_POS = 0xc9bd8;
	private static final int ITEM_LIST = 0xcbb90;
	public static final int COUNT = 517;

	private final ElfImage elf;

	Item(ElfImage elf) {
		this.elf = elf;
	}

	public int type(int id) {
		return elf.u32(KIND + id * 4) & 0x1f;
	}

	/** Types 1..0xc are equipment, kept as instances; the rest stack. */
	public static boolean equipment(int type) {
		return type >= 1 && type <= 0xc;
	}

	/** The shield grade InitHeroState gives a type 8 item in slot 7: (g_anItem &gt;&gt; 6 &amp; 3) + 1. */
	public int shieldGrade(int id) {
		return (elf.u32(KIND + id * 4) >> 6 & 3) + 1;
	}

	public int baseValue(int id) {
		return elf.u16(record(id));
	}

	/** AddItem's grade multiplier on the base value: grades 1..4 add a quarter, a half, three quarters or double. */
	public int graded(int id, int grade) {
		int v = baseValue(id);
		return switch (grade) {
			case 1 -> v + (v >> 2);
			case 2 -> v + (v >> 1);
			case 3 -> v + (v * 3 >> 2);
			case 4 -> v << 1;
			default -> v;
		};
	}

	/**
	 * GetGOrderItemCost's base: the u24 at record +9 (types 1..8), +7 (9..0xc), +2 (0xe, 0xf, 0x12)
	 * or +0 (0xd, 0x10, 0x13, 0x14); 0 for the rest. Buying costs twice this.
	 */
	public int cost(int id) {
		int t = type(id);
		int at = t >= 1 && t <= 8 ? 9 : t >= 9 && t <= 0xc ? 7 : t == 0xe || t == 0xf || t == 0x12 ? 2
				: t == 0xd || t == 0x10 || t == 0x13 || t == 0x14 ? 0 : -1;
		if (at < 0) {
			return 0;
		}
		int r = record(id) + at;
		return elf.u8(r) | elf.u8(r + 1) << 8 | elf.u8(r + 2) << 16;
	}

	public int spread(int id) {
		return elf.u8(record(id) + 2);
	}

	/** Record +3: the level GetGOrderEquipLevel compares with the hero's before equipping. */
	public int level(int id) {
		return elf.u8(record(id) + 3);
	}

	/** Record +4 u16 and +6: durability and its spread, for equipment. */
	public int durability(int id) {
		return elf.u16(record(id) + 4);
	}

	public int durabilitySpread(int id) {
		return elf.u8(record(id) + 6);
	}

	/** Record +7: the look AddItem copies to instance +4 and the hero record shows. */
	public int look(int id) {
		return elf.u8(record(id) + 7);
	}

	/** Record +8: the colour (palette row) AddItem copies to instance +5, plus the grade. */
	public int colour(int id) {
		return elf.u8(record(id) + 8);
	}

	/** A consumable's record +0: the global phase FuncUseItem runs for its effect. */
	public int script(int id) {
		return elf.u16(record(id));
	}

	/** AddItem's stack limit: 99 a slot, runes (type 0x12) one. */
	public int maxStack(int id) {
		return type(id) == 0x12 ? 1 : 99;
	}

	/**
	 * A type-0 item stands for a group (GetParameterData kind 5): at g_anItemList + (g_anItem &gt;&gt; 20)
	 * a u8 count, then that many u16 whose low 12 bits are the members' ids.
	 */
	public int[] group(int id) {
		int at = ITEM_LIST + (elf.u32(KIND + id * 4) >>> 20);
		int[] out = new int[elf.u8(at)];
		for (int i = 0; i < out.length; i++) {
			out[i] = elf.u16(at + 1 + 2 * i) & 0xfff;
		}
		return out;
	}

	/** GetItemName: name A, then " B" (":B" for recipes) unless the type is 0 or B is 0xfff. */
	public String name(int id) {
		int k = elf.u32(KIND + id * 4);
		int a = k >> 8 & 0xfff;
		int b = k >>> 20;
		int t = k & 0x1f;
		String n = nameAt(a);
		return t == 0 || b == 0xfff ? n : n + (t == 0x13 ? ":" : " ") + nameAt(b);
	}

	private String nameAt(int index) {
		String s = elf.cstring(NAMES + elf.s16(NAME_POS + index * 2));
		return s == null ? "" : s;
	}

	/** g_anItem bits 5..7: a weapon's kind (sword, axe, blunt) or a material's use. */
	public int element(int id) {
		return elf.u32(KIND + id * 4) >> 5 & 7;
	}

	/**
	 * The Pascal string GetGOrderItemSummary shows under a stack's name: record +5 for types 0xe
	 * and 0xf, +3 for 0x10 and 0x14, else "".
	 */
	public String description(int id) {
		int t = type(id);
		int at = t == 0xe || t == 0xf ? 5 : t == 0x10 || t == 0x14 ? 3 : -1;
		if (at < 0) {
			return "";
		}
		int r = record(id) + at;
		byte[] b = elf.bytes(r + 1, elf.u8(r));
		return Ksc.decode(b, 0, b.length);
	}

	private int record(int id) {
		return EX + elf.s16(EX + id * 2) + EX_BASE;
	}

	/**
	 * AddItem's equipment instance. A negative grade means ~grade with no sockets. The value is
	 * base * grade multiplier (1..4 add a quarter, a half, three quarters or double) *
	 * rand(100 - spread .. 100 + spread) / 100, durability is record +4 * rand(100 +- record +6) / 10
	 * (100 for types 9 and up), and types below 9 roll 3 sockets on Rand100 &lt; 4, 2 below 0x24, else 1.
	 * AddEquipOption's options for types 9 and up are not ported.
	 */
	public Equip create(int id, int grade, Random rng) {
		int t = type(id);
		int g = grade < 0 ? ~grade : grade;
		int v = graded(id, g);
		int s = spread(id);
		int value = randRange(rng, 100 - s, 100 + s) * v / 100;
		int ds = durabilitySpread(id);
		int durMax = randRange(rng, 100 - ds, 100 + ds) * durability(id) / 10;
		int sockets = 0;
		if (t < 9) {
			if (grade >= 0) {
				int r = rng.nextInt(100);
				sockets = r < 4 ? 3 : r < 0x24 ? 2 : 1;
			}
		} else {
			durMax = 100;
		}
		return new Equip(id, t, value, s, level(id), durMax, sockets, g);
	}

	private static int randRange(Random rng, int lo, int hi) {
		return lo + rng.nextInt(hi - lo + 1);
	}

	/** An equipment instance (hero+0x87c, 0x1e bytes each); what this port keeps of it. */
	public static final class Equip {
		public final int id;
		public final int type;
		/** +6: the rolled attack or defence. */
		public final int value;
		/** +8: the spread InitHeroState rolls a weapon's value by. */
		public final int spread;
		/** +9: the level GetGOrderEquipLevel requires. */
		public final int level;
		/** +0xc and +0xa: maximum and current durability; gear at 0 counts for nothing. */
		public final int durMax;
		public int dur;
		/** +0x1a: sockets (types below 9). */
		public final int sockets;
		/** +0x1b: grade 0..4. */
		public final int grade;
		/** +0x1c: the slot it is worn in, 1..12, or 0 in the bag. */
		public int slot;
		/** +0x1d: 0 found, 1 bought, 2 gambled. */
		public int origin;

		public Equip(int id, int type, int value, int spread, int level, int durMax, int sockets, int grade) {
			this.id = id;
			this.type = type;
			this.value = value;
			this.spread = spread;
			this.level = level;
			this.durMax = durMax;
			this.dur = durMax;
			this.sockets = sockets;
			this.grade = grade;
		}

		/** SetGOrderEquipItem's hero[4] for a weapon in slot 1: type 1 gives 0, type 2 gives 2, type 3 gives 1. */
		public int weaponForm() {
			return type == 2 ? 2 : type == 3 ? 1 : 0;
		}

		/** InitHeroState re-rolls a weapon's value by its spread on every pass. */
		public int roll(Random rng) {
			return value * randRange(rng, 100 - spread, 100 + spread) / 100;
		}
	}
}

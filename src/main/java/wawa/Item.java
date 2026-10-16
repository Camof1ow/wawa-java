package wawa;

import java.util.Random;

/**
 * Item base data the engine keeps in libwawa.so, and the instance AddItem makes from it
 * (docs/FORMATS.md §7).
 *
 * <pre>
 * g_anItem   0xc89a8   u32 per id: &amp; 0x1f type (1..3 weapons, 4..12 gear, 13+ consumables),
 *                      &gt;&gt; 5 &amp; 7 element
 * g_aItemEx  0xcbffc   record at table + s16[id] + 0x40a:
 *                      +0 u16 base value, +2 u8 spread (%), +3 u8 level
 * </pre>
 */
public final class Item {

	private static final int KIND = 0xc89a8;
	private static final int EX = 0xcbffc;
	private static final int EX_BASE = 0x40a;
	public static final int COUNT = 517;

	private final ElfImage elf;

	Item(ElfImage elf) {
		this.elf = elf;
	}

	public int type(int id) {
		return elf.u32(KIND + id * 4) & 0x1f;
	}

	public int baseValue(int id) {
		return elf.u16(record(id));
	}

	public int spread(int id) {
		return elf.u8(record(id) + 2);
	}

	/** Record +3: the level GetGOrderEquipLevel compares with the hero's before equipping. */
	public int level(int id) {
		return elf.u8(record(id) + 3);
	}

	/** Record +7: the look AddItem copies to instance +4 and the hero record shows. */
	public int look(int id) {
		return elf.u8(record(id) + 7);
	}

	/** Record +8: the colour (palette row) AddItem copies to instance +5, plus the grade. */
	public int colour(int id) {
		return elf.u8(record(id) + 8);
	}

	private int record(int id) {
		return EX + elf.s16(EX + id * 2) + EX_BASE;
	}

	/**
	 * AddItem: value = base * grade multiplier * rand(100 - spread .. 100 + spread) / 100, grade
	 * 1..4 adding a quarter, a half, three quarters or doubling.
	 */
	public Instance create(int id, int grade, Random rng) {
		int v = baseValue(id);
		v = switch (grade) {
			case 1 -> v + (v >> 2);
			case 2 -> v + (v >> 1);
			case 3 -> v + (v * 3 >> 2);
			case 4 -> v << 1;
			default -> v;
		};
		int s = spread(id);
		return new Instance(id, type(id), v * (100 - s + rng.nextInt(2 * s + 1)) / 100, s);
	}

	/** A held item: id, type, rolled value (+6) and spread (+8). */
	public record Instance(int id, int type, int value, int spread) {

		/**
		 * The hero's weapon kind at +4. AddItem stores {@code type - 1} in the high nibble of +2 for
		 * weapons; that this is what ends up at +4 is inferred, the equip code setting it was not traced.
		 */
		public int kind() {
			return type >= 1 && type <= 3 ? type - 1 : 0;
		}

		/** InitHeroState re-rolls a weapon's value by its spread on every pass. */
		public int roll(Random rng) {
			return value * (100 - spread + rng.nextInt(2 * spread + 1)) / 100;
		}
	}
}

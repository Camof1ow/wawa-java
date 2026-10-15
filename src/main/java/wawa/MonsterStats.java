package wawa;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The per-monster stat tables libwawa.so keeps as read-only data. InitMonsAbility fills the
 * monster record out of them and is only called for ids below 0xa6, which is exactly how long
 * every table here is.
 *
 * <pre>
 * HP        0xc0da0   166 x u16     this class
 * attack    0xc0eec   166 x u8      this class
 * critical  0xc1424   166 x u8      this class, per-mille, the scale Combat uses
 * accuracy  0xc12d4   166 x u8      not used yet, the port resolves hits from the hero's evade
 * dodge     0xc137c   166 x u8      not used yet
 * element   0xc0f94 / 0xc103c       elemental attack, and defence as 4 u8 per id
 * special   0xc14cc   166 x u8      not used yet
 * </pre>
 *
 * Ids at or above {@link #COUNT} have no row: the field monsters of the later areas fall in that
 * range, so callers must check {@link #has} rather than read a neighbouring monster's row.
 *
 * <p>InitMonsAbility combines these rows with a level curve:
 *
 * <pre>
 * hp  = levelHp[level] * idHp[id] * 5 / 100        levelHp at 0xc9d98 (1616, 1621, 1633, ...)
 * atk = atk[id] * (level * 10) * 4 / 100
 * </pre>
 *
 * Only the id rows are applied so far. The level curve multiplies HP by roughly 25, and the
 * hero's own damage model has not been derived from the engine yet, so enabling it now would
 * make every fight unwinnable rather than accurate. The rows below are the real relative values.
 */
public final class MonsterStats {

	private static final int HP = 0xc0da0;
	private static final int ATTACK = 0xc0eec;
	private static final int CRITICAL = 0xc1424;

	/** Rows in every table, which is the 0xa6 bound InitMonsAbility tests. */
	public static final int COUNT = 166;

	private final ElfImage elf;

	private MonsterStats(ElfImage elf) {
		this.elf = elf;
	}

	public static MonsterStats load(Path so) throws IOException {
		return new MonsterStats(ElfImage.read(so));
	}

	/** False when InitMonsAbility would not run for this id, i.e. the tables have no row. */
	public boolean has(int monsterId) {
		return monsterId >= 0 && monsterId < COUNT;
	}

	public int hp(int monsterId) {
		return has(monsterId) ? elf.u16(HP + monsterId * 2) : 0;
	}

	public int attack(int monsterId) {
		return has(monsterId) ? elf.u8(ATTACK + monsterId) : 0;
	}

	/** Critical chance in per-mille, as AttackHeroHitMons compares it against Rand1000. */
	public int critical(int monsterId) {
		return has(monsterId) ? elf.u8(CRITICAL + monsterId) : 0;
	}
}

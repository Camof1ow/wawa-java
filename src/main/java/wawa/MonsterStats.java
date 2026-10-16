package wawa;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The per-monster stat tables libwawa.so keeps as read-only data, combined the way
 * InitMonsAbility fills a monster record. It only runs for ids below 0xa6, which is exactly how
 * long every id table is.
 *
 * <pre>
 * g_anMonsInc           0xb9d98   u16 per level
 * g_anMonsStateHP_E     0xc0da0   166 x u16
 * g_anMonsStateAtk_E    0xc0eec   166 x u8
 * g_anMonsStateElDef_E  0xc103c   166 x u8 x 4    [3] is the physical defence AttackHeroHitMons reads
 * g_anMonsStateAcr_E    0xc12d4   166 x u8
 * g_anMonsStateDdg_E    0xc137c   166 x u8
 * g_anMonsStateCri_E    0xc1424   166 x u8        per mille
 *
 * hp     = inc[L] * hp[id] * 5 / 100        +0x54/+0x58
 * attack = atk[id] * L * 10 * 4 / 100       +0x5c
 * defend = def[id][3] * 10                  +0x74
 * acc    = acr[id] * L * 10 * 3 / 100       +0x78
 * dodge  = ddg[id] * L * 10 * 6 / 100       +0x7c
 * crit   = cri[id]                          +0x80
 * </pre>
 *
 * L is the area's base level plus the record's level nibble (LoadMonsToMap). Ids at or above
 * {@link #COUNT} have no row: callers must check {@link #has}.
 */
public final class MonsterStats {

	private static final int INC = 0xb9d98;
	private static final int HP = 0xc0da0;
	private static final int ATTACK = 0xc0eec;
	private static final int DEFENCE = 0xc103c;
	private static final int ACCURACY = 0xc12d4;
	private static final int DODGE = 0xc137c;
	private static final int CRITICAL = 0xc1424;
	/** g_anMonsInc holds 201 u16 entries. */
	private static final int MAX_LEVEL = 200;

	/** Rows in every id table, which is the 0xa6 bound InitMonsAbility tests. */
	public static final int COUNT = 166;

	/** What InitMonsAbility writes into the monster record. */
	public record Ability(int level, int hp, int attack, int defence, int accuracy, int dodge, int crit) {
	}

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

	public int critical(int monsterId) {
		return has(monsterId) ? elf.u8(CRITICAL + monsterId) : 0;
	}

	/** InitMonsAbility for one monster at one level, or null when the id has no row. */
	public Ability ability(int monsterId, int level) {
		if (!has(monsterId)) {
			return null;
		}
		int l = Math.max(0, Math.min(MAX_LEVEL, level));
		int ten = l * 10;
		return new Ability(l,
				elf.u16(INC + l * 2) * hp(monsterId) * 5 / 100,
				attack(monsterId) * ten * 4 / 100,
				elf.u8(DEFENCE + monsterId * 4 + 3) * 10,
				elf.u8(ACCURACY + monsterId) * ten * 3 / 100,
				elf.u8(DODGE + monsterId) * ten * 6 / 100,
				critical(monsterId));
	}
}

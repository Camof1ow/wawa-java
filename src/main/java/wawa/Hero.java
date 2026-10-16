package wawa;

import java.util.Random;

/**
 * The hero record as InitHero, InitHeroState and InitHeroLevelUp build it in libwawa.so, without
 * skills, buffs or pets (docs/FORMATS.md §7).
 *
 * <pre>
 * class start stats  g_anHeroDefault 0xb9878   +0xd0 = T[c], +0xcc = T[c+3], +0xc8 = T[c+6], +0xd4 = T[c+9]
 * curves             g_anHeroPow088  0xb9c98 (u16), g_anHeroPow112 0xb98a8 (u32), sampled every 1/8
 * experience         0xdbc00 (s16)   the level-L threshold is exp[L] * (L * 4 + 30)
 * </pre>
 *
 * The four primary stats are named as g_astrProp1st names them on the new-game panel (STR, DEX,
 * CON, INT for T[c], T[c+3], T[c+6], T[c+9]): STR (+0xd0) feeds physical power, DEX (+0xcc)
 * accuracy and dodge, CON (+0xc8) max HP, INT (+0xd4) max MP.
 */
public final class Hero {

	private static final int DEFAULT_STATS = 0xb9878;
	private static final int POW088 = 0xb9c98;
	private static final int POW112 = 0xb98a8;
	private static final int EXP_TABLE = 0xdbc00;
	public static final int MAX_LEVEL = 99;
	/** InitHero's base critical chance at +0xe0, per mille. */
	private static final int BASE_CRIT = 23;

	/** g_anHeroDefault: start value of STR, DEX, CON or INT (i = 0..3) for a class. */
	static int startStat(ElfImage elf, int heroClass, int i) {
		return elf.s8(DEFAULT_STATS + heroClass + i * 3);
	}

	private final ElfImage elf;
	public final int heroClass;
	/** The name typed on the new-game panel; the slot popup shows it. */
	public String name = "";
	public int level = 1;
	public int exp;
	public int statPoints;
	public int skillPoints;
	/** CON +0xc8, DEX +0xcc, STR +0xd0, INT +0xd4. */
	public int con;
	public int dex;
	public int str;
	public int intel;
	public Item.Instance weapon;
	public int armor;

	public int hp;
	public int maxHp;
	public int maxMp;
	public int accuracy;
	public int dodge;
	public int crit;
	/** +0x1f0, the stat part of physical power before the weapon. */
	public int statPower;
	/** +0x164, the weapon's value re-rolled by this InitHeroState pass. */
	public int weaponPower;

	Hero(ElfImage elf, int heroClass) {
		this.elf = elf;
		this.heroClass = heroClass;
		str = elf.s8(DEFAULT_STATS + heroClass);
		dex = elf.s8(DEFAULT_STATS + heroClass + 3);
		con = elf.s8(DEFAULT_STATS + heroClass + 6);
		intel = elf.s8(DEFAULT_STATS + heroClass + 9);
		exp = levelExp(level);
	}

	/** InitHeroState: recompute everything derived from stats and equipment. */
	public void recompute(Random rng) {
		maxHp = (pow112(con) * 9 >> 4) + (level - 1) * 8;
		maxMp = pow112(intel) * 6 >> 4;
		accuracy = pow088(dex);
		dodge = pow088(dex) >> 1;
		crit = BASE_CRIT;
		int p = pow112(str) >> 5;
		// +4 is the weapon kind; bare hands leave it at 0, as InitHero does.
		int kind = weapon == null ? 0 : weapon.kind();
		statPower = switch (kind) {
			case 0 -> p * 5 >> 2;
			case 2 -> p * 3 >> 1;
			case 3 -> p >> 1;
			default -> p;
		};
		weaponPower = weapon == null ? 0 : weapon.roll(rng);
		hp = Math.min(hp, maxHp);
	}

	/** The physical power AttackHeroHitMons uses for the main hand: +0x1f0 + +0x164. */
	public int power() {
		return statPower + weaponPower;
	}

	/** Adds experience and applies InitHeroLevelUp for every threshold crossed; returns levels gained. */
	public int gainExp(int amount, Random rng) {
		exp += amount;
		int gained = 0;
		while (level < MAX_LEVEL && exp >= levelExp(level + 1)) {
			level++;
			statPoints += 3;
			skillPoints++;
			if (heroClass == 2) {
				dex++;
			} else {
				str++;
			}
			con++;
			recompute(rng);
			hp = maxHp;
			gained++;
		}
		return gained;
	}

	/** InitHeroExp: exp[L] * (L * 4 + 30), the running total at which level L starts. */
	public int levelExp(int l) {
		return elf.s16(EXP_TABLE + l * 2) * (l * 4 + 30);
	}

	/** ChangeMonsDown: experience for felling a monster of the given level. */
	public int expFor(int monsterLevel, boolean elite) {
		int base = (level * 4 + 30) * (elite ? 2 : 1);
		int d = monsterLevel - level;
		int e = d < 0 ? ((d * 1000) / (level / 12 + 7) + 1000) * base / 1000 : (d * 25 + 1000) * base / 1000;
		return Math.max(1, e);
	}

	/** CalcHeroPow088. */
	int pow088(int x) {
		if (x >= 1000) {
			return (x - 1000) * 0x30 / 1000 + 0xa3;
		}
		int k = x >> 3;
		int a = elf.u16(POW088 + k * 2);
		return a + (((elf.u16(POW088 + k * 2 + 2) - a) * (x & 7)) >> 3);
	}

	/** CalcHeroPow112; past 1999 the engine extrapolates with the slope between entries 7 and 8. */
	int pow112(int x) {
		if (x > 1999) {
			int a = elf.u32(POW112 + 7 * 4);
			return (elf.u32(POW112 + 8 * 4) - a) * (x - 2000) / 1000 + a;
		}
		int k = x >> 3;
		int a = elf.u32(POW112 + k * 4);
		return a + (((elf.u32(POW112 + k * 4 + 4) - a) * (x & 7)) >> 3);
	}
}

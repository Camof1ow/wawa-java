package wawa;

/**
 * Damage resolution ported from AttackHeroHitMons in libwawa.so.
 *
 * <pre>
 * critCap  = min(monster.critResist, 750)          // DAT_0007542c
 * evaCap   = min(attacker.evade, 750)              // DAT_00075434 (65534 marks "unset")
 * power    = baseAttack + slotBonus
 * damage   = (atkScale + weaponBonus) * power / 1000
 * if (rand1000 &lt; critCap || monster.forceHit) damage += 16
 * if (rand1000 - evaCap &lt; 0)        damage = 1          // whiff
 * else if (!monster.guarded)           damage &gt;&gt;= 1
 * </pre>
 *
 * The original also has a weapon-specific branch for attack type 0x14 that adds 475 before
 * scaling; it is left out because no inventory data is loaded yet.
 */
public final class Combat {

	public static final int CRIT_CAP = 750;
	public static final int EVA_CAP = 750;
	public static final int CRIT_BONUS = 0x10;
	public static final int WHIFF_DAMAGE = 1;

	private Combat() {
	}

	/** Result of one strike, so the caller can report what happened. */
	public record Result(int damage, boolean crit, boolean missed) {
	}

	public static Result strike(int atkScale, int weaponBonus, int baseAttack, int slotBonus,
			int critResist, int evade, boolean forceHit, boolean guarded, int rollCrit, int rollEvade) {
		int power = baseAttack + slotBonus;
		int damage = (atkScale + weaponBonus) * power / 1000;
		boolean crit = rollCrit < critCap(critResist) || forceHit;
		if (crit) {
			damage += CRIT_BONUS;
		}
		boolean missed = rollEvade - evadeCap(evade) < 0;
		if (missed) {
			return new Result(WHIFF_DAMAGE, crit, true);
		}
		if (!guarded) {
			damage >>= 1;
		}
		return new Result(Math.max(0, damage), crit, false);
	}

	public static int critCap(int monsterCritResist) {
		return Math.min(monsterCritResist, CRIT_CAP);
	}

	public static int evadeCap(int evade) {
		// The native code leaves the sentinel values alone, which behave like no evasion.
		if (evade >= 65534) {
			return 0;
		}
		return Math.min(evade, EVA_CAP);
	}
}

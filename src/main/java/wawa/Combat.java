package wawa;

import java.util.Random;

/**
 * The two physical hit resolutions of libwawa.so, without skills, elements or status effects
 * (docs/FORMATS.md §7).
 *
 * <pre>
 * AttackHeroHitMons (hero -&gt; monster)
 *   dmg  = rand(900..1100) * power / 1000                AttackHero's RandRange for a plain swing
 *   dmg  = (1000 - defence) * dmg / 500
 *   miss when rand1000 &lt; min(dodge + 200 - accuracy, 750)
 *   crit when rand1000 &lt; min(crit, 750), otherwise dmg &gt;&gt;= 1
 *
 * AttackMonsHitHeroCore (monster -&gt; hero)
 *   r    = FixSqrt(defence * 256)                        sqrt(defence) in 8.8 fixed point
 *   dmg  = (100 - round(min(r * 65 / 100, 75.0))) * attack / 100 - round(r * 40 / 100)
 *   dmg  = dmg * scale / 1000                            CalcMonsRun: rand(900..1100) for a plain
 *                                                        swing (state 6), rand(2700..3300) for state 7
 *   miss when rand1000 &lt; min(dodge + 200 - accuracy, 750)
 *   crit when rand1000 &lt; min(crit, 750), otherwise dmg &gt;&gt;= 1
 * </pre>
 *
 * 65534 and above mark an unset value; the engine leaves those uncapped.
 */
public final class Combat {

	public static final int CAP = 750;
	private static final int UNSET = 65534;
	/** RandRange(900, 1100): the scale of a plain swing, for the hero (AttackHero) and monsters alike. */
	public static int swingScale(Random rng) {
		return 900 + rng.nextInt(201);
	}

	private Combat() {
	}

	public record Result(int damage, boolean crit, boolean missed) {
	}

	public static Result heroHits(int power, int accuracy, int crit, int defence, int dodge, Random rng) {
		int dmg = swingScale(rng) * power / 1000;
		dmg = (1000 - Math.max(0, defence)) * dmg / 500;
		return resolve(dmg, dodge + 200 - accuracy, crit, rng);
	}

	public static Result monsterHits(int attack, int scale, int accuracy, int crit, int defence, int dodge,
			Random rng) {
		int r = fixSqrt(defence * 256);
		int pct = Math.min(r * 65 / 100, 0x4b00);
		int dmg = (100 - (pct + 0x80 >> 8)) * attack / 100 - (r * 40 / 100 + 0x80 >> 8);
		dmg = dmg * scale / 1000;
		return resolve(dmg, dodge + 200 - accuracy, crit, rng);
	}

	private static Result resolve(int dmg, int missChance, int crit, Random rng) {
		if (rng.nextInt(1000) < cap(missChance)) {
			return new Result(0, false, true);
		}
		boolean critical = rng.nextInt(1000) < cap(crit);
		// ponytail: clamps at 0; the engine's later handling of a non-positive hit was not traced.
		return new Result(Math.max(0, critical ? dmg : dmg >> 1), critical, false);
	}

	private static int cap(int v) {
		return v <= UNSET && v > CAP ? CAP : v;
	}

	/** FixSqrt: Newton's method on x * 256, i.e. sqrt(x) with eight fractional bits for x = v * 256. */
	static int fixSqrt(int x) {
		if (x < 1) {
			return 0;
		}
		long g = Math.max(x, 0x400);
		while (true) {
			long next = ((long) x * 256 / g + g) >> 1;
			if (next >= g) {
				return (int) g;
			}
			g = next;
		}
	}
}

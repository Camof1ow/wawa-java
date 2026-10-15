package wawa;

/**
 * Level curves lifted verbatim from libwawa.so (ARM) at 0xc9c98 / 0xc98a8.
 * Both are sampled every 1/8 level: index = level >> 3, fraction = level & 7.
 * Past the limit the original extrapolates linearly with a /1000 slope.
 */
public final class HeroCurves {

	/** CalcHeroPow088: u16 table, flattening curve, limit 999. */
	private static final int[] POW088 = {
			0, 62, 114, 163, 211, 256, 301, 345, 388, 430, 472, 514, 555, 595, 635, 675,
			715, 754, 793, 831, 870, 908, 946, 984, 1021, 1059, 1096, 1133, 1170, 1206, 1243, 1279,
			1315, 1352, 1388, 1423, 1459, 1495, 1530, 1566, 1601, 1636, 1671, 1706, 1741, 1776, 1811, 1845,
			1880, 1914, 1949, 1983, 2017, 2051, 2085, 2119, 2153, 2187, 2220, 2254, 2288, 2321, 2355, 2388,
			2421, 2455, 2488, 2521, 2554, 2587, 2620, 2653, 2686, 2719, 2751, 2784, 2817, 2849, 2882, 2914,
			2947, 2979, 3012, 3044, 3076, 3108, 3141, 3173, 3205, 3237, 3269, 3301, 3333, 3364, 3396, 3428,
	};

	/** CalcHeroPow112: u32 table, accelerating curve, limit 1999. */
	private static final int[] POW112 = {
			0, 164, 357, 562, 776, 996, 1222, 1452, 1686, 1924, 2165, 2409, 2656, 2905, 3156, 3410,
			3666, 3923, 4182, 4444, 4706, 4971, 5237, 5504, 5773, 6043, 6314, 6587, 6861, 7136, 7412, 7689,
			7967, 8247, 8527, 8809, 9091, 9374, 9659, 9944, 10230, 10517, 10804, 11093, 11382, 11672, 11963, 12255,
	};

	private static final int LIMIT088 = 999;
	private static final int LIMIT112 = 1999;
	private static final int EXTRAPOLATE_FROM = 1000;

	/** Port of CalcHeroPow088. */
	public static int pow088(int level) {
		if (level < 0) {
			return 0;
		}
		if (level > LIMIT088) {
			int i = POW088.length - 2;
			return (POW088[i + 1] - POW088[i]) * (level - EXTRAPOLATE_FROM) / 1000 + POW088[i];
		}
		int k = level >> 3;
		if (k >= POW088.length - 1) {
			return POW088[POW088.length - 1];
		}
		return POW088[k] + (((POW088[k + 1] - POW088[k]) * (level & 7)) >> 3);
	}

	/** Port of CalcHeroPow112. */
	public static int pow112(int level) {
		if (level < 0) {
			return 0;
		}
		if (level > LIMIT112) {
			int i = POW112.length - 2;
			return (POW112[i + 1] - POW112[i]) * (level - EXTRAPOLATE_FROM) / 1000 + POW112[i];
		}
		int k = level >> 3;
		if (k >= POW112.length - 1) {
			return POW112[POW112.length - 1];
		}
		return POW112[k] + (((POW112[k + 1] - POW112[k]) * (level & 7)) >> 3);
	}

	private HeroCurves() {
	}
}

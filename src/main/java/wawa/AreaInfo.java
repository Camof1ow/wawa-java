package wawa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Area metadata from assets/eng/mob&#47;*.png, the second ReadMapFile call in LoadGameMap
 * (param_2 = 1). Layout of the selected language blob:
 *
 * <pre>
 * u8    nameLength
 * byte[nameLength]  area name
 * u8    ambience code
 * u8[3] monster base level - 1 for normal / hard / third difficulty; LoadGameMap stores
 *       header[1 + difficulty] + 1 at game +0x2f, which LoadMonsToMap adds to each record's level
 * u8    brightness
 * u8    flags
 * u8    weather bits; 0xf0 == 0xf0 means scripted rain, otherwise bits 1/2/4 are rain flags
 * u8[3] rain thresholds, absent when the weather is scripted
 * u8    rest-area count, then per area x0, y0, x1, y1 (inclusive cells)
 * u8    portal count, then per portal x0, y0, x1, y1 (inclusive), map letter, map number,
 *       and a byte holding the facing (bits 0..1) and the destination's portal index (bits 2..7)
 * </pre>
 */
public final class AreaInfo {

	public final String name;
	public final int ambience;
	/** Monster base level on normal difficulty. */
	public final int baseLevel;
	public final int brightness;
	public final int flags;
	public final int weatherBits;
	public final boolean scriptedWeather;
	public final int[] rainThresholds;
	/** The exits LoadGameMap reads into map +0x3c/+0x40, in file order (MoveGameMap's index). */
	public final List<Portal> portals;

	/**
	 * One exit as MoveGameMap uses it: an exclusive cell rectangle, the map it leads to, the
	 * facing that marks it (the hero enters facing the opposite way and arrives facing this one),
	 * and which portal of the destination receives the hero.
	 */
	public record Portal(int x0, int y0, int x1, int y1, String dest, int dir, int destIndex) {
		boolean contains(int x, int y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	private AreaInfo(String name, int ambience, int baseLevel, int brightness, int flags,
			int weatherBits, boolean scriptedWeather, int[] rainThresholds, List<Portal> portals) {
		this.name = name;
		this.ambience = ambience;
		this.baseLevel = baseLevel;
		this.brightness = brightness;
		this.flags = flags;
		this.weatherBits = weatherBits;
		this.scriptedWeather = scriptedWeather;
		this.rainThresholds = rainThresholds;
		this.portals = portals;
	}

	/** Some maps ship no area file at all: eng/mob/n.png holds zero languages. */
	public static AreaInfo absent(String code) {
		return new AreaInfo("(no area data) " + code, 0, 1, 0, 0, 0, false, new int[3], List.of());
	}

	/**
	 * LoadGameMap's map+0x2d from the ambience byte's high nibble: 1 gives 2, 2 gives 4, anything
	 * else 1, a town, where InitHeroWeaponSpeed refuses to start an attack.
	 */
	public int mapKind() {
		int n = ambience >> 4;
		return n == 1 ? 2 : n == 2 ? 4 : 1;
	}

	/** A 0xf0 nibble on the weather byte is the "P" branch with RandRange(0,1) inside it. */
	public boolean rainy() {
		if (scriptedWeather) {
			return true;
		}
		return (weatherBits & 1) != 0 || (weatherBits & 2) != 0 || (weatherBits & 4) != 0;
	}

	static AreaInfo load(Assets assets, String file, String code) throws IOException {
		byte[] blob = ResourceFile.pick(assets, file, code);
		if (blob.length < 9) {
			throw new IOException("area blob too small: " + file);
		}
		int nameLen = blob[0] & 0xFF;
		if (1 + nameLen + 7 > blob.length) {
			throw new IOException("area name overruns blob: " + file);
		}
		// EUC-KR (the top-level locale); Ksc, because the browser runtime has no MS949 charset.
		String name = Ksc.decode(blob, 1, nameLen);
		int at = 1 + nameLen;
		int ambience = blob[at] & 0xFF;
		int baseLevel = (blob[at + 1] & 0xFF) + 1;
		int brightness = blob[at + 4] & 0xFF;
		int flags = blob[at + 5] & 0xFF;
		int weather = blob[at + 6] & 0xFF;
		int[] rain = { blob[at + 7] & 0xFF, blob[at + 8] & 0xFF, blob[at + 9] & 0xFF };
		boolean scripted = (weather & 0xf0) == 0xf0;
		// LoadGameMap skips the rain bytes when the weather is scripted, then reads the rest areas.
		at += 7 + (scripted ? 0 : 3);
		return new AreaInfo(name, ambience, baseLevel, brightness, flags, weather, scripted, rain,
				portals(blob, at, file));
	}

	/** The rest areas (skipped) and the portals that follow them, 4 and 7 file bytes each. */
	private static List<Portal> portals(byte[] blob, int at, String file) throws IOException {
		if (at >= blob.length) {
			throw new IOException("rest areas overrun blob: " + file);
		}
		at += 1 + (blob[at] & 0xFF) * 4;
		if (at >= blob.length) {
			throw new IOException("portal table overruns blob: " + file);
		}
		int n = blob[at++] & 0xFF;
		if (at + n * 7 > blob.length) {
			throw new IOException("portal table overruns blob: " + file);
		}
		List<Portal> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++, at += 7) {
			int facing = blob[at + 6] & 0xFF;
			out.add(new Portal(blob[at] & 0xFF, blob[at + 1] & 0xFF, (blob[at + 2] & 0xFF) + 1,
					(blob[at + 3] & 0xFF) + 1, (char) (blob[at + 4] & 0xFF) + String.format("%02d", blob[at + 5] & 0xFF),
					facing & 3, facing >> 2));
		}
		return out;
	}

}

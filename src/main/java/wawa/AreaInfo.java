package wawa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * u8[3] rain thresholds
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

	private AreaInfo(String name, int ambience, int baseLevel, int brightness, int flags,
			int weatherBits, boolean scriptedWeather, int[] rainThresholds) {
		this.name = name;
		this.ambience = ambience;
		this.baseLevel = baseLevel;
		this.brightness = brightness;
		this.flags = flags;
		this.weatherBits = weatherBits;
		this.scriptedWeather = scriptedWeather;
		this.rainThresholds = rainThresholds;
	}

	/** Some maps ship no area file at all: eng/mob/n.png holds zero languages. */
	public static AreaInfo absent(String code) {
		return new AreaInfo("(no area data) " + code, 0, 1, 0, 0, 0, false, new int[3]);
	}

	/** A 0xf0 nibble on the weather byte is the "P" branch with RandRange(0,1) inside it. */
	public boolean rainy() {
		if (scriptedWeather) {
			return true;
		}
		return (weatherBits & 1) != 0 || (weatherBits & 2) != 0 || (weatherBits & 4) != 0;
	}

	public static AreaInfo load(Path file, String code) throws IOException {
		byte[] blob = ResourceFile.pick(file, code);
		if (blob.length < 9) {
			throw new IOException("area blob too small: " + file);
		}
		int nameLen = blob[0] & 0xFF;
		if (1 + nameLen + 7 > blob.length) {
			throw new IOException("area name overruns blob: " + file);
		}
		String name = new String(blob, 1, nameLen, StandardCharsets.UTF_8);
		int at = 1 + nameLen;
		int ambience = blob[at] & 0xFF;
		int baseLevel = (blob[at + 1] & 0xFF) + 1;
		int brightness = blob[at + 4] & 0xFF;
		int flags = blob[at + 5] & 0xFF;
		int weather = blob[at + 6] & 0xFF;
		int[] rain = { blob[at + 7] & 0xFF, blob[at + 8] & 0xFF, blob[at + 9] & 0xFF };
		return new AreaInfo(name, ambience, baseLevel, brightness, flags, weather,
				(weather & 0xf0) == 0xf0, rain);
	}

}

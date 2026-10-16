package wawa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Desktop {@link Storage}: key "slotN" is the file {@code <wawa.save>N.properties} (default prefix
 * "wawa-slot", the name the Swing build always used), any other key {@code wawa-<key>.properties}.
 * Values are stored as UTF-8 text.
 */
final class FileStorage implements Storage {

	private static final String SLOT = "slot";

	static Path file(String key) {
		String name = key.startsWith(SLOT)
				? System.getProperty("wawa.save", "wawa-slot") + key.substring(SLOT.length())
				: "wawa-" + key;
		return Path.of(name + ".properties");
	}

	@Override
	public String load(String key) {
		Path f = file(key);
		try {
			return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public void save(String key, String value) {
		try {
			Files.writeString(file(key), value, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(file(key));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

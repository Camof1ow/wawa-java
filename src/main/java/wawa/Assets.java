package wawa;

import java.io.IOException;
import java.util.Map;

/**
 * Read access to the game files by their path from the APK root, e.g. "assets/img/map.png" or
 * "lib/armeabi/libwawa.so".
 */
interface Assets {

	/** The whole file; IOException when it does not exist. */
	byte[] read(String path) throws IOException;

	boolean exists(String path);

	/** Files already unpacked into memory (the web build). */
	static Assets of(Map<String, byte[]> files) {
		return new Assets() {
			@Override
			public byte[] read(String path) throws IOException {
				byte[] b = files.get(path);
				if (b == null) {
					throw new IOException("no such asset: " + path);
				}
				return b;
			}

			@Override
			public boolean exists(String path) {
				return files.containsKey(path);
			}
		};
	}
}

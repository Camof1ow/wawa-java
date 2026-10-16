package wawa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Desktop {@link Assets} over an unpacked APK: "assets/x" is {@code <assetsDir>/x} and every other
 * path (lib/...) resolves beside the assets directory, as in the APK.
 */
final class DirAssets implements Assets {

	private static final String PREFIX = "assets/";

	private final Path assets;

	DirAssets(Path assetsDir) {
		this.assets = assetsDir.toAbsolutePath();
	}

	Path file(String path) {
		return path.startsWith(PREFIX) ? assets.resolve(path.substring(PREFIX.length()))
				: assets.getParent().resolve(path);
	}

	/** The asset path of a file under the assets directory (for the desktop tools that list it). */
	String path(Path file) {
		return PREFIX + assets.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
	}

	@Override
	public byte[] read(String path) throws IOException {
		return Files.readAllBytes(file(path));
	}

	@Override
	public boolean exists(String path) {
		return Files.isRegularFile(file(path));
	}
}

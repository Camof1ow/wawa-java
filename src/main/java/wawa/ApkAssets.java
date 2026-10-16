package wawa;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Desktop {@link Assets} reading straight out of the APK (a zip), entry names being the paths. */
final class ApkAssets implements Assets {

	private final ZipFile zip;

	ApkAssets(Path apk) throws IOException {
		this.zip = new ZipFile(apk.toFile());
	}

	@Override
	public byte[] read(String path) throws IOException {
		ZipEntry e = zip.getEntry(path);
		if (e == null || e.isDirectory()) {
			throw new IOException("no such asset: " + path);
		}
		return zip.getInputStream(e).readAllBytes();
	}

	@Override
	public boolean exists(String path) {
		ZipEntry e = zip.getEntry(path);
		return e != null && !e.isDirectory();
	}
}

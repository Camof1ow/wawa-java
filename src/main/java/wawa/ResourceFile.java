package wawa;

import java.io.IOException;

/**
 * The container every data file in this game is packed in, exactly as ReadMapFile decodes it:
 * the file holds one blob per language and the caller picks one by code.
 *
 * <pre>
 * u16   n                       number of languages
 * u16[] ids   (n)               the code's last two characters, e.g. "01", as a little-endian u16
 * u16[] sizes (n)               payload size per language
 * byte[] blobs                  n payloads, tiled back to back from 2 + 4n
 * </pre>
 *
 * assets/eng/map (ReadMapFile type 0), assets/eng/mob (type 1) and assets/eng/eve (type 2) all
 * use this rule; type 3 returns the chosen blob unchanged.
 */
final class ResourceFile {

	/** The payload the 3-character code selects, e.g. "a01" picks id "01". */
	static byte[] pick(Assets assets, String file, String code) throws IOException {
		if (code.length() < 3) {
			throw new IOException("resource code must be 3 characters: " + code);
		}
		byte[] d = assets.read(file);
		if (d.length < 2) {
			throw new IOException("truncated resource file: " + file);
		}
		int n = u16(d, 0);
		if (2 + 4 * n > d.length) {
			throw new IOException("bad language count in " + file);
		}
		// The selector is the raw little-endian u16 made of the code's second and third bytes.
		int wanted = (code.charAt(1) & 0xFF) | ((code.charAt(2) & 0xFF) << 8);
		int pick = -1;
		for (int i = 0; i < n; i++) {
			if (u16(d, 2 + i * 2) == wanted) {
				pick = i;
				break;
			}
		}
		if (pick < 0) {
			throw new IOException("language " + wanted + " not in " + file);
		}
		int off = 2 + 4 * n;
		for (int i = 0; i < pick; i++) {
			off += u16(d, 2 + 2 * n + i * 2);
		}
		int size = u16(d, 2 + 2 * n + pick * 2);
		if (off + size > d.length) {
			throw new IOException("payload out of range in " + file);
		}
		byte[] blob = new byte[size];
		System.arraycopy(d, off, blob, 0, size);
		return blob;
	}

	static int u16(byte[] d, int off) {
		return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
	}

	/** A plain PNG. */
	static Img image(Assets assets, String path) throws IOException {
		return Png.decode(assets.read(path));
	}

	private ResourceFile() {
	}
}

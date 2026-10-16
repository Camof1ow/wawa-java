package wawa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * PNG decoder for what the APK ships: 8-bit greyscale, RGB, palette (with tRNS), grey+alpha and
 * RGBA, not interlaced.
 */
final class Png {

	static Img decode(byte[] png) throws IOException {
		if (png.length < 8 || u32(png, 0) != 0x89504e47 || u32(png, 4) != 0x0d0a1a0a) {
			throw new IOException("not a PNG");
		}
		int w = 0;
		int h = 0;
		int type = -1;
		int[] palette = new int[0];
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		for (int at = 8; at + 8 <= png.length;) {
			int len = u32(png, at);
			int tag = u32(png, at + 4);
			int d = at + 8;
			if (len < 0 || d + len > png.length) {
				throw new IOException("truncated PNG chunk");
			}
			if (tag == 0x49484452) { // IHDR
				w = u32(png, d);
				h = u32(png, d + 4);
				type = png[d + 9];
				if (png[d + 8] != 8 || png[d + 12] != 0) {
					throw new IOException("unsupported PNG: bit depth " + png[d + 8] + ", interlace " + png[d + 12]);
				}
			} else if (tag == 0x504c5445) { // PLTE
				palette = new int[len / 3];
				for (int i = 0; i < palette.length; i++) {
					palette[i] = 0xff000000 | (png[d + i * 3] & 0xff) << 16 | (png[d + i * 3 + 1] & 0xff) << 8
							| png[d + i * 3 + 2] & 0xff;
				}
			} else if (tag == 0x74524e53 && type == 3) { // tRNS
				for (int i = 0; i < len && i < palette.length; i++) {
					palette[i] = (png[d + i] & 0xff) << 24 | palette[i] & 0xffffff;
				}
			} else if (tag == 0x49444154) { // IDAT
				idat.write(png, d, len);
			} else if (tag == 0x49454e44) { // IEND
				break;
			}
			at = d + len + 4;
		}
		int channels = switch (type) {
			case 0, 3 -> 1;
			case 4 -> 2;
			case 2 -> 3;
			case 6 -> 4;
			default -> throw new IOException("unsupported PNG colour type " + type);
		};
		int stride = w * channels;
		byte[] raw = new byte[(stride + 1) * h];
		Inflater inf = new Inflater();
		inf.setInput(idat.toByteArray());
		try {
			for (int n = 0; n < raw.length;) {
				int got = inf.inflate(raw, n, raw.length - n);
				if (got == 0 && (inf.finished() || inf.needsInput())) {
					throw new IOException("truncated PNG image data");
				}
				n += got;
			}
		} catch (DataFormatException e) {
			throw new IOException(e);
		} finally {
			inf.end();
		}
		unfilter(raw, h, stride, channels);
		int[] px = new int[w * h];
		for (int y = 0; y < h; y++) {
			int r = y * (stride + 1) + 1;
			for (int x = 0; x < w; x++) {
				int p = r + x * channels;
				px[y * w + x] = switch (type) {
					case 0 -> 0xff000000 | (raw[p] & 0xff) * 0x010101;
					case 3 -> (raw[p] & 0xff) < palette.length ? palette[raw[p] & 0xff] : 0xff000000;
					case 4 -> (raw[p + 1] & 0xff) << 24 | (raw[p] & 0xff) * 0x010101;
					case 2 -> 0xff000000 | (raw[p] & 0xff) << 16 | (raw[p + 1] & 0xff) << 8 | raw[p + 2] & 0xff;
					default -> (raw[p + 3] & 0xff) << 24 | (raw[p] & 0xff) << 16 | (raw[p + 1] & 0xff) << 8
							| raw[p + 2] & 0xff;
				};
			}
		}
		return new Img(w, h, px);
	}

	/** Undoes the per-row filters in place; each row keeps its leading filter byte. */
	private static void unfilter(byte[] raw, int h, int stride, int bpp) throws IOException {
		for (int y = 0; y < h; y++) {
			int r = y * (stride + 1);
			int f = raw[r];
			int prev = r - stride; // first pixel byte of the row above
			for (int i = 0; i < stride; i++) {
				int at = r + 1 + i;
				int a = i >= bpp ? raw[at - bpp] & 0xff : 0;
				int b = y > 0 ? raw[prev + i] & 0xff : 0;
				int c = y > 0 && i >= bpp ? raw[prev + i - bpp] & 0xff : 0;
				int v = raw[at] & 0xff;
				raw[at] = (byte) switch (f) {
					case 0 -> v;
					case 1 -> v + a;
					case 2 -> v + b;
					case 3 -> v + ((a + b) >> 1);
					case 4 -> v + paeth(a, b, c);
					default -> throw new IOException("bad PNG filter " + f);
				};
			}
		}
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
	}

	private static int u32(byte[] d, int at) {
		return (d[at] & 0xff) << 24 | (d[at + 1] & 0xff) << 16 | (d[at + 2] & 0xff) << 8 | d[at + 3] & 0xff;
	}

	private Png() {
	}
}

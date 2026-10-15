package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GSAC sprite archive from wild.apk (lib/armeabi/libwawa.so, InitSprite).
 * Layout verified byte-exact against 105 of 146 files in assets/img:
 * 0x00 "GSAC" | 0x04 u32 | 0x0a u16 paletteRows | 0x0c u16 | 0x0e u16 frames
 * 0x10 u16 paletteCols | 0x12 u16 flags | 0x14 frames * (u16 w, u16 h)
 * then paletteRows*paletteCols RGB triplets, then 8bpp pixel indices per frame.
 */
public final class Gsac {

	public static final int MODE_8BPP = 0;
	public static final int MODE_8BPP_RLE = 1;
	public static final int MODE_1BPP = 2;

	/** The original renderer colour-keys magenta, so it decodes as fully transparent. */
	private static final int KEY_INDEX = 0;

	private final int paletteRows;
	private final int paletteCols;
	private final int flags;
	private final int[] frameWidth;
	private final int[] frameHeight;
	private byte[] palette;
	private byte[] cached;
	/** Image size for a frame-count-0 archive, from the header at 0x04. */
	private int rawW;
	private int rawH;

	int rawWidth() {
		return rawW;
	}

	int rawHeight() {
		return rawH;
	}
	private final int pixelOffset;
	private final Path source;

	/** The archive bytes are read once; every frame decode reuses them. */
	private byte[] bytes() {
		if (cached == null) {
			try {
				cached = Files.readAllBytes(source);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
		return cached;
	}


	private Gsac(Path source, int paletteRows, int paletteCols, int flags,
			int[] frameWidth, int[] frameHeight, byte[] palette, int pixelOffset) {
		this.source = source;
		this.paletteRows = paletteRows;
		this.paletteCols = paletteCols;
		this.flags = flags;
		this.frameWidth = frameWidth;
		this.frameHeight = frameHeight;
		this.palette = palette;
		this.pixelOffset = pixelOffset;
	}

	/**
	 * Expands one 8bpp RLE frame using the scheme in DrawSpriteFull: a control byte with
	 * the high bit set is a run of (next byte + 3) copies of (byte &amp; 0x7f), otherwise the
	 * byte is a literal. The two big-endian bytes before a frame hold its stored length.
	 */
	private byte[] decodeRle(byte[] data, int frame) {
		int at = pixelOffset;
		for (int i = 0; i < frame; i++) {
			at += u16(data, at);
		}
		int end = at + u16(data, at);
		byte[] out = new byte[frameWidth[frame] * frameHeight[frame]];
		int o = 0;
		int i = at + 2;
		while (i < end && o < out.length) {
			int b = data[i++] & 0xFF;
			if ((b & 0x80) != 0) {
				int n = (data[i++] & 0xFF) + 3;
				byte v = (byte) (b & 0x7f);
				for (int k = 0; k < n && o < out.length; k++) {
					out[o++] = v;
				}
			} else {
				out[o++] = (byte) b;
			}
		}
		return out;
	}


	public int frameCount() {
		return frameWidth.length;
	}

	public int paletteSize() {
		return palette.length / 3;
	}

	public int width(int frame) {
		return frameWidth[frame];
	}

	public int height(int frame) {
		return frameHeight[frame];
	}

	public int mode() {
		if ((flags & 0x80) == 0) {
			return MODE_8BPP;
		}
		return (flags & 0x40) == 0 ? MODE_8BPP_RLE : MODE_1BPP;
	}

	/**
	 * Frame count 0 means the archive is a single raw image: a paletteRows*paletteCols RGB
	 * palette followed by that many indexed pixels. InitSprite writes the pixel byte length
	 * back into the frames field at run time, so the stored value is always 0.
	 */
	public boolean isRawImage() {
		return frameWidth.length == 0;
	}

	/** Decodes the single raw image of a frame-count-0 archive; width comes from the caller. */
	public BufferedImage rawImage(int width) {
		byte[] data = bytes();
		int at = 0x14 + palette.length;
		int h = width > 0 ? (data.length - at) / width : 0;
		BufferedImage img = new BufferedImage(width, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < width; x++) {
				int idx = data[at + y * width + x] & 0xFF;
				img.setRGB(x, y, idx == KEY_INDEX ? 0 : argb(idx));
			}
		}
		return img;
	}

	/** 1bpp frames are the only mode still undecoded. */
	public boolean fullyDecodable() {
		return mode() != MODE_1BPP;
	}

	/** Decodes one frame to ARGB; magenta decodes as transparent. */
	public BufferedImage frame(int index) {
		if (!fullyDecodable()) {
			throw new IllegalStateException(source + " mode " + mode() + " not supported yet");
		}
		byte[] data = bytes();
		int w = frameWidth[index];
		int h = frameHeight[index];
		byte[] pixels;
		if (mode() == MODE_8BPP) {
			int at = pixelOffset;
			for (int i = 0; i < index; i++) {
				at += frameWidth[i] * frameHeight[i];
			}
			pixels = new byte[w * h];
			System.arraycopy(data, at, pixels, 0, w * h);
		} else {
			pixels = decodeRle(data, index);
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int idx = pixels[y * w + x] & 0xFF;
				img.setRGB(x, y, idx == KEY_INDEX ? 0 : argb(idx));
			}
		}
		return img;
	}

	private int argb(int index) {
		int p = index * 3;
		if (p + 2 >= palette.length) {
			return 0xFF00FF00; // loud green: index outside the palette
		}
		int r = palette[p] & 0xFF;
		int g = palette[p + 1] & 0xFF;
		int b = palette[p + 2] & 0xFF;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/**
	 * Loads an archive, taking its palette from the companion pal/&lt;name&gt;.png when it carries
	 * none. Twenty-two archives here are palette-less — 023, 028..032, obje0, obje3 and every
	 * tile00..13 sheet — and each one has exactly that companion file, which is what the engine's
	 * LoadSpritePal reads (visible in its getResource log lines).
	 */
	public static Gsac load(Path file, Path paletteFile) throws IOException {
		Gsac g = load(file);
		if (g.palette.length == 0 && paletteFile != null && Files.exists(paletteFile)) {
			g.palette = externalPalette(Files.readAllBytes(paletteFile));
		}
		return g;
	}

	/** pal/&lt;name&gt;.png holds three-byte RGB triples followed by a two-byte trailer. */
	private static byte[] externalPalette(byte[] raw) {
		int n = Math.max(0, (raw.length - 2) / 3) * 3;
		byte[] out = new byte[n];
		System.arraycopy(raw, 0, out, 0, n);
		return out;
	}

	/** Loads img/&lt;name&gt;.png, taking its palette from the sibling pal/ directory when it has none. */
	public static Gsac loadCompanion(Path imgFile) throws IOException {
		Path pal = imgFile.getParent().getParent().resolve("pal").resolve(imgFile.getFileName());
		return load(imgFile, pal);
	}

	public static Gsac load(Path file) throws IOException {
		byte[] d = Files.readAllBytes(file);
		if (d.length < 0x14 || d[0] != 'G' || d[1] != 'S' || d[2] != 'A' || d[3] != 'C') {
			throw new IOException("not a GSAC archive: " + file);
		}
		int paletteRows = u16(d, 0x0a);
		int frames = u16(d, 0x0e);
		int paletteCols = u16(d, 0x10);
		int flags = u16(d, 0x12);
		// For a frame-count-0 archive the header carries the image size as (h<<16)|w in the
		// 32-bit field at 0x04; verified against map.png, which is 400x320.
		int rawW = u16(d, 0x04);
		int rawH = u16(d, 0x06);
		if (0x14 + frames * 4 > d.length) {
			throw new IOException("frame table out of range: " + file);
		}
		int[] fw = new int[frames];
		int[] fh = new int[frames];
		for (int i = 0; i < frames; i++) {
			fw[i] = u16(d, 0x14 + i * 4);
			fh[i] = u16(d, 0x16 + i * 4);
		}
		int pixelOffset = 0x14 + frames * 4 + paletteRows * paletteCols * 3;
		if (pixelOffset > d.length) {
			throw new IOException("palette out of range: " + file);
		}
		byte[] palette = new byte[paletteRows * paletteCols * 3];
		System.arraycopy(d, 0x14 + frames * 4, palette, 0, palette.length);
		Gsac g = new Gsac(file, paletteRows, paletteCols, flags, fw, fh, palette, pixelOffset);
		g.rawW = rawW;
		g.rawH = rawH;
		g.cached = d;
		return g;
	}

	private static int u16(byte[] d, int off) {
		return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
	}
}

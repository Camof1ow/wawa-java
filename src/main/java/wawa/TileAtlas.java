package wawa;

import java.io.IOException;

/**
 * One ground tile atlas, i.e. the sheet DrawTile copies 16x16 cells out of.
 *
 * <p>LoadGameTile names these {@code tile%02d} from the layer id (1..14) in the map data, so
 * the file is img/tile&lt;id-1&gt;.png: a 256x64 sheet of eight-bit indices with no palette of
 * its own. The colours live beside it in pal/tile&lt;id-1&gt;.png as three-byte RGB triples
 * whose first entry is the magenta colour key. DrawTile takes the patch at
 * {@code ((cell & 0x0f) * 16, ((cell & 0x3f) >> 4) * 16)} and treats 0xff as an empty cell.
 */
public final class TileAtlas {

	/** DrawTile copies fixed 16x16 patches. */
	public static final int TILE = 16;

	private final Img sheet;
	private final Img[] patches = new Img[64];

	private TileAtlas(Img sheet) {
		this.sheet = sheet;
	}

	/** Loads the atlas for a one-based layer id, as LoadGameTile does. */
	static TileAtlas load(Assets assets, int id) throws IOException {
		String name = String.format("tile%02d.png", id - 1);
		byte[] raw = assets.read("assets/img/" + name);
		byte[] pal = assets.read("assets/pal/" + name);
		return new TileAtlas(composite(raw, pal));
	}

	/**
	 * The sheet: a 20-byte header (width and height at 0x04/0x06), then one index byte per pixel.
	 * Index 0 (the key) and anything past the palette map to transparent.
	 */
	static Img composite(byte[] raw, byte[] pal) {
		int w = ResourceFile.u16(raw, 0x04);
		int h = ResourceFile.u16(raw, 0x06);
		int colours = (pal.length - 2) / 3;
		Img img = new Img(w, h);
		for (int i = 0; i < w * h; i++) {
			int c = raw[20 + i] & 0xFF;
			if (c < colours && c != 0) {
				int p = c * 3;
				img.px[i] = 0xFF000000 | ((pal[p] & 0xFF) << 16) | ((pal[p + 1] & 0xFF) << 8) | (pal[p + 2] & 0xFF);
			}
		}
		return img;
	}

	/**
	 * The patch a cell byte selects, or null when the cell is empty (0xff) or falls outside the
	 * sheet. Patches are cached because a screen redraw asks for the same cell many times.
	 */
	Img patch(int cell) {
		int idx = cell & 0x3f;
		if (patches[idx] == null) {
			int sx = (idx & 0x0f) * TILE;
			int sy = ((idx & 0x3f) >> 4) * TILE;
			if (sx + TILE <= sheet.w && sy + TILE <= sheet.h) {
				Img p = new Img(TILE, TILE);
				for (int y = 0; y < TILE; y++) {
					System.arraycopy(sheet.px, (sy + y) * sheet.w + sx, p.px, y * TILE, TILE);
				}
				patches[idx] = p;
			}
		}
		return patches[idx];
	}

	public int width() {
		return sheet.w;
	}

	public int height() {
		return sheet.h;
	}
}

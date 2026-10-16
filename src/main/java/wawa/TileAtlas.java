package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

	private final BufferedImage sheet;
	private final BufferedImage[] patches = new BufferedImage[64];

	private TileAtlas(BufferedImage sheet) {
		this.sheet = sheet;
	}

	/** Loads the atlas for a one-based layer id, as LoadGameTile does. */
	public static TileAtlas load(Path assets, int id) throws IOException {
		return load(assets, id, 0);
	}

	/** Loads the atlas with the palette variant the map gives that layer. */
	public static TileAtlas load(Path assets, int id, int paletteVariant) throws IOException {
		String name = String.format("tile%02d.png", id - 1);
		byte[] raw = Files.readAllBytes(assets.resolve("img").resolve(name));
		byte[] pal = Files.readAllBytes(assets.resolve("pal").resolve(name));
		return new TileAtlas(RenderAtlas.composite(raw, pal, paletteVariant));
	}

	/**
	 * The patch a cell byte selects, or null when the cell is empty (0xff) or falls outside the
	 * sheet. Patches are cached because a screen redraw asks for the same cell many times.
	 */
	public BufferedImage patch(int cell) {
		int idx = cell & 0x3f;
		if (patches[idx] == null) {
			int sx = (idx & 0x0f) * TILE;
			int sy = ((idx & 0x3f) >> 4) * TILE;
			if (sx + TILE <= sheet.getWidth() && sy + TILE <= sheet.getHeight()) {
				patches[idx] = sheet.getSubimage(sx, sy, TILE, TILE);
			}
		}
		return patches[idx];
	}

	public int width() {
		return sheet.getWidth();
	}

	public int height() {
		return sheet.getHeight();
	}
}

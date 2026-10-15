package wawa;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The four ground atlases one map draws from.
 *
 * <p>LoadGameTile loads one atlas per layer from the layer id stored in the map data (1..14;
 * ids outside that range leave the layer blank). LoadGameBlock splits a cell into
 * {@code layer = v >> 6} and {@code index = v & 0x3f}, and DrawTile copies the 16x16 patch that
 * index selects out of that layer's 256x64 sheet.
 */
public final class Tiles {

	private final TileAtlas[] layers = new TileAtlas[4];

	private Tiles(TileAtlas[] loaded) {
		System.arraycopy(loaded, 0, layers, 0, 4);
	}

	public static Tiles load(Path assets, MapData map) {
		TileAtlas[] loaded = new TileAtlas[4];
		for (int layer = 0; layer < 4; layer++) {
			int id = map.layerTileSet[layer];
			if (id < 1 || id > 14) {
				continue;
			}
			try {
				loaded[layer] = TileAtlas.load(assets, id);
			} catch (IOException e) {
				// an absent atlas simply leaves that layer blank
			}
		}
		return new Tiles(loaded);
	}

	/** The patch for a cell byte, or null when the layer is missing or the cell is empty. */
	public BufferedImage patch(int layer, int cell) {
		TileAtlas atlas = layers[layer];
		return atlas == null ? null : atlas.patch(cell);
	}

	public boolean has(int layer) {
		return layers[layer] != null;
	}
}

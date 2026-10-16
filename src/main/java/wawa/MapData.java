package wawa;

import java.io.IOException;

/**
 * Map data files from assets/eng/*&#47;*.png, decoded exactly as ReadMapFile + LoadGameMap do.
 *
 * <pre>
 * u16   n                       language count
 * u16[] ids   (n)               language codes, stored as UTF-16 chars ("01".."13")
 * u16[] sizes (n)               payload size per language
 * byte[] blobs                  n payloads, tiled back to back from 2 + 4n
 * </pre>
 *
 * The payload the game asks for is chosen by the second and third characters of the
 * 3-character map code. Inside a payload:
 *
 * <pre>
 * byte[4] layerId, byte[4] paletteId
 * byte    width - 1
 * byte    height - 1
 * byte[]  cells, width * height
 * </pre>
 *
 * LoadGameBlock splits a cell into {@code layer = v >> 6} and {@code frame = v & 0x3f};
 * the layer picks one of the four tile sets, whose id is {@code layerId[layer] - 1}.
 */
public final class MapData {

	public final String name;
	public final int[] layerTileSet;
	public final int[] layerPalette;
	public final int width;
	public final int height;
	public final byte[] cells;

	private MapData(String name, int[] layerTileSet, int[] layerPalette, int width, int height,
			byte[] cells) {
		this.name = name;
		this.layerTileSet = layerTileSet;
		this.layerPalette = layerPalette;
		this.width = width;
		this.height = height;
		this.cells = cells;
	}

	/** Layer index (0..3) of a cell, as LoadGameBlock computes it. */
	public int layer(int x, int y) {
		return cell(x, y) >> 6;
	}

	/** Frame index inside that layer's tile set. */
	public int frame(int x, int y) {
		return cell(x, y) & 0x3f;
	}

	/** Zero-based tile set index 0..13, or -1 when the layer has no tile set. */
	public int tileSet(int layer) {
		return layerTileSet[layer] - 1;
	}

	public int cell(int x, int y) {
		if (x < 0 || y < 0 || x >= width || y >= height) {
			return 0;
		}
		return cells[y * width + x] & 0xFF;
	}

	public boolean inBounds(int x, int y) {
		return x >= 0 && y >= 0 && x < width && y < height;
	}

	/**
	 * Reads one map file and picks the language variant named by the last two characters
	 * of the 3-character code, e.g. "a01" selects the blob with id "01".
	 */
	static MapData load(Assets assets, String file, String code) throws IOException {
		byte[] blob = ResourceFile.pick(assets, file, code);
		if (blob.length < 10) {
			throw new IOException("payload too small in " + file);
		}
		int[] layerTileSet = new int[4];
		int[] layerPalette = new int[4];
		for (int i = 0; i < 4; i++) {
			layerTileSet[i] = blob[i * 2] & 0xFF;
			layerPalette[i] = blob[i * 2 + 1] & 0xFF;
		}
		int w = (blob[8] & 0xFF) + 1;
		int h = (blob[9] & 0xFF) + 1;
		if (10 + w * h > blob.length) {
			throw new IOException("grid overruns payload in " + file + " (" + w + "x" + h + ")");
		}
		byte[] cells = new byte[w * h];
		System.arraycopy(blob, 10, cells, 0, w * h);
		return new MapData(file.substring(file.lastIndexOf('/') + 1), layerTileSet, layerPalette, w, h, cells);
	}
}

package wawa;

/**
 * Cell flag bits the native build ORs into the map grid, read straight from
 * LoadGameBlock and CalcGameMinimap:
 *
 * <ul>
 * <li>{@code 0x80} — inside a scripted obstacle rectangle</li>
 * <li>{@code 0x10} — spawn entry of type 2 or 4..6, i.e. not walkable</li>
 * <li>{@code 0x20} — spawn footprint, {@code 0x28} for the first cell of a type 8 entry</li>
 * <li>{@code 0x08} — spawn footprint for types below 2</li>
 * <li>{@code 0x01} — the entry-point list marks the cell</li>
 * </ul>
 *
 * CalcGameMinimap then samples the grid and treats {@code (value & 3) == 0} as blocked, so the
 * low two bits of a cell carry the walkability that minimap and routing read.
 */
public final class GridFlags {

	public static final int ENTRY = 0x01;
	public static final int SPAWN_LOW = 0x08;
	/*
	 * These bits live on the remapped grid LoadGameBlock builds after the runtime colour
	 * table runs, not on the raw map bytes whose low bits are the tile column.
	 */
	public static final int BLOCKED = 0x10;
	public static final int SPAWN = 0x20;
	public static final int SPAWN_FIRST = 0x28;
	public static final int OBSTACLE = 0x80;


	private GridFlags() {
	}

	/** CalcGameMinimap's test: the low two bits being zero means the cell is solid. */
	public static boolean solid(int cell) {
		return cell != 0xff && (cell & 3) == 0;
	}

	public static boolean obstacle(int cell) {
		return (cell & OBSTACLE) != 0;
	}

	public static boolean blocked(int cell) {
		return (cell & BLOCKED) != 0;
	}

	public static boolean spawnFootprint(int cell) {
		return (cell & (SPAWN | SPAWN_LOW)) != 0;
	}

	public static boolean spawnAnchor(int cell) {
		return (cell & SPAWN_FIRST) == SPAWN_FIRST;
	}
}

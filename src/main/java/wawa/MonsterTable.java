package wawa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The monsters an area spawns, from assets/eng/mob/&lt;letter&gt;.png — the .mbk the engine reads
 * through ReadMapFile with type 1.
 *
 * <p>The blob ends with the monster block: a count byte followed by that many variable-length
 * records. A record is what LoadMonsToMap reads:
 *
 * <pre>
 * byte cellX, byte cellY, byte monsterId, byte flags, byte levelAndDir
 * then, per flag bit: 0x80 +1, 0x40 +1, 0x20 name(1 + 2n), 0x10 +4, 0x08 1+n,
 *                     and monsterId 0xd2 always carries 13 extra bytes
 * </pre>
 *
 * <p>InitMonsOne places the monster at {@code ((cellX * 2 + 1) * 8, (cellY * 2 + 1) * 8)}, the
 * centre of that cell, and takes the direction from {@code levelAndDir & 0x0f}.
 *
 * <p>The header ahead of the block varies in length per blob, so the block is located by the
 * rule that makes it self-consistent: the walk must land exactly on the end of the blob, the
 * count byte must equal the number of records walked, and every cell must lie inside the map.
 */
public final class MonsterTable {

	public static final class Record {
		public final int cellX;
		public final int cellY;
		public final int monsterId;
		public final int flags;
		public final int level;
		public final int direction;

		Record(int cellX, int cellY, int monsterId, int flags, int level, int direction) {
			this.cellX = cellX;
			this.cellY = cellY;
			this.monsterId = monsterId;
			this.flags = flags;
			this.level = level;
			this.direction = direction;
		}

		/** Screen x of the sprite centre, as InitMonsOne computes it. */
		public int pixelX() {
			return (cellX * 2 + 1) * 8;
		}

		public int pixelY() {
			return (cellY * 2 + 1) * 8;
		}
	}

	public final String name;
	public final List<Record> records;

	private MonsterTable(String name, List<Record> records) {
		this.name = name;
		this.records = records;
	}

	public static MonsterTable load(Path file, String code, int mapWidth, int mapHeight)
			throws IOException {
		byte[] blob = ResourceFile.pick(file, code);
		if (blob.length < 9) {
			throw new IOException("monster blob too small: " + file);
		}
		int nameLen = blob[0] & 0xFF;
		if (1 + nameLen > blob.length) {
			throw new IOException("area name overruns blob: " + file);
		}
		String name = new String(blob, 1, nameLen, StandardCharsets.UTF_8);
		return new MonsterTable(name, locate(blob, mapWidth, mapHeight));
	}

	/** Finds the trailing count byte whose records reproduce the rest of the blob exactly. */
	private static List<Record> locate(byte[] blob, int mapWidth, int mapHeight) {
		for (int at = blob.length - 5; at > 15; at--) {
			int count = blob[at] & 0xFF;
			if (count == 0) {
				continue;
			}
			List<Record> found = walk(blob, at + 1, count);
			if (found != null && inside(found, mapWidth, mapHeight)) {
				return found;
			}
		}
		return List.of();
	}

	/** Reads exactly {@code count} records from {@code at}, or null when they overrun the blob. */
	private static List<Record> walk(byte[] blob, int at, int count) {
		List<Record> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			if (at + 5 > blob.length) {
				return null;
			}
			int flags = blob[at + 3] & 0xFF;
			int size = 5;
			if ((flags & 0x80) != 0) {
				size++;
			}
			if ((flags & 0x40) != 0) {
				size++;
			}
			if ((flags & 0x20) != 0) {
				int n = at(blob, at + size);
				if (n < 0) {
					return null;
				}
				size += 1 + n * 2;
			}
			if ((flags & 0x10) != 0) {
				size += 4;
			}
			if ((flags & 0x08) != 0) {
				int n = at(blob, at + size);
				if (n < 0) {
					return null;
				}
				size += 1 + n;
			}
			if ((blob[at + 2] & 0xFF) == 0xd2) {
				size += 13;
			}
			if (at + size > blob.length) {
				return null;
			}
			int levelAndDir = blob[at + 4] & 0xFF;
			out.add(new Record(blob[at] & 0xFF, blob[at + 1] & 0xFF, blob[at + 2] & 0xFF, flags,
					levelAndDir >> 4, levelAndDir & 0x0f));
			at += size;
		}
		return at == blob.length ? out : null;
	}

	private static boolean inside(List<Record> records, int mapWidth, int mapHeight) {
		for (Record r : records) {
			if (r.cellX >= mapWidth || r.cellY >= mapHeight) {
				return false;
			}
		}
		return !records.isEmpty();
	}

	/** Blob byte, or -1 past the end so the walk can bail out instead of throwing. */
	private static int at(byte[] blob, int index) {
		return index >= 0 && index < blob.length ? blob[index] & 0xFF : -1;
	}

	/** Areas that ship no monster file at all, e.g. eng/mob/n.png holds zero languages. */
	public static MonsterTable empty(String code) {
		return new MonsterTable("(no monsters) " + code, List.of());
	}
}

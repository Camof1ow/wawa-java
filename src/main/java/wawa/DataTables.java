package wawa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Record tables from assets/eng/dat&#47;*.png. These files are not the language container
 * the map files use: they are a run of u16 records each closed by 0xffff, which is the
 * pattern the payload bytes show (for example {@code 09 00 ff ff 12 00 09 00 ff ff}).
 */
public final class DataTables {

	public final String name;
	public final List<int[]> records;
	public final int declared;

	private DataTables(String name, int declared, List<int[]> records) {
		this.name = name;
		this.declared = declared;
		this.records = records;
	}

	public int size() {
		return records.size();
	}

	/** Columns of the first record, for a quick shape check. */
	public int columns() {
		return records.isEmpty() ? 0 : records.get(0).length;
	}

	public static DataTables load(Path file) throws IOException {
		byte[] d = Files.readAllBytes(file);
		if (d.length < 2) {
			throw new IOException("truncated data file: " + file);
		}
		int declared = (d[0] & 0xFF) | ((d[1] & 0xFF) << 8);
		List<int[]> recs = new ArrayList<>();
		int at = 2;
		while (at + 1 < d.length) {
			List<Integer> vals = new ArrayList<>();
			boolean closed = false;
			while (at + 1 < d.length) {
				int v = (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8);
				at += 2;
				if (v == 0xffff) {
					closed = true;
					break;
				}
				vals.add(v);
			}
			if (!closed) {
				break;
			}
			int[] row = new int[vals.size()];
			for (int i = 0; i < row.length; i++) {
				row[i] = vals.get(i);
			}
			recs.add(row);
		}
		return new DataTables(file.getFileName().toString(), declared, recs);
	}
}

package wawa;

import java.nio.file.Path;

/** Prints the decoded per-monster tables for a few ids, for spot checks. */
public final class Chk {

	public static void main(String[] args) throws Exception {
		MonsterData d = MonsterData.load(Path.of(args.length > 0 ? args[0]
				: "C:/Users/Administrator/work/libwawa.so"));
		int[] ids = { 0, 1, 52, 166, 173, 180, 187, 194, 202, 203, 204, 209 };
		for (int id : ids) {
			System.out.printf("id %3d: kind=%2d sprite=%3d palette=%2d name=%s%n", id, d.kind(id),
					d.sprite(id), d.palette(id), d.name(id));
		}
	}

	private Chk() {
	}
}

package wawa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Parses every assets/eng data file and reports what the container rule yields. */
public final class CheckMaps {

	public static void main(String[] args) throws Exception {
		Path root = Path.of(args.length > 0 ? args[0] : "assets/eng");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.walk(root)) {
			s.filter(p -> p.toString().endsWith(".png")).sorted().forEach(files::add);
		}
		int ok = 0;
		int bad = 0;
		for (Path p : files) {
			String code = codeFor(p);
			try {
				MapData m = MapData.load(p, code);
				StringBuilder tiles = new StringBuilder();
				for (int i = 0; i < 4; i++) {
					tiles.append(m.tileSet(i)).append(' ');
				}
				System.out.printf("%-8s %-4s %3dx%-3d cells=%-6d tiles=%s%n", code,
						p.getParent().getFileName(), m.width, m.height, m.cells.length, tiles);
				ok++;
			} catch (Exception e) {
				bad++;
			}
		}
		System.out.println("parsed=" + ok + " failed=" + bad + " of " + files.size());
	}

	/** Rebuilds the 3-character code: first char from the file name, next two from the language id. */
	private static String codeFor(Path p) throws Exception {
		byte[] d = Files.readAllBytes(p);
		if (d.length < 4) {
			return p.getFileName().toString().replace(".png", "") + "00";
		}
		int id = (d[2] & 0xFF) | ((d[3] & 0xFF) << 8);
		String first = p.getFileName().toString().replace(".png", "");
		return first + (char) (id & 0xFF) + (char) ((id >>> 8) & 0xFF);
	}

	private CheckMaps() {
	}
}

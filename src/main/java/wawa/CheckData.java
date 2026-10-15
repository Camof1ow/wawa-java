package wawa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reports the record shape of every assets/eng/dat table. */
public final class CheckData {

	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args.length > 0 ? args[0] : "assets/eng/dat");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.sorted().forEach(files::add);
		}
		int ok = 0;
		for (Path p : files) {
			try {
				DataTables t = DataTables.load(p);
				ok++;
				if (p.getFileName().toString().compareTo("08.png") <= 0) {
					StringBuilder head = new StringBuilder();
					for (int i = 0; i < Math.min(3, t.size()); i++) {
						head.append(java.util.Arrays.toString(t.records.get(i))).append(" ");
					}
					System.out.printf("%-10s declared=%-4d records=%-4d cols=%-3d %s%n",
							p.getFileName(), t.declared, t.size(), t.columns(), head);
				}
			} catch (Exception e) {
				System.out.printf("%-10s FAIL %s%n", p.getFileName(), e.getMessage());
			}
		}
		System.out.println("parsed=" + ok + " of " + files.size());
	}

	private CheckData() {
	}
}

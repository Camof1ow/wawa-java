package wawa;

import java.nio.file.Path;

/**
 * Smoke test: loads every shipped map, area record and monster table through the real code path
 * and reports what came back, including whether every spawned monster resolves to a sprite.
 */
public final class CheckMapsRun {

	public static void main(String[] args) throws Exception {
		Path assets = Path.of(args.length > 0 ? args[0] : "assets");
		String[] codes = { "a01", "b01", "c01", "e01", "f01", "g01", "h01", "i01", "j01", "n01",
				"t01", "z01" };
		MonsterSprite sprites = MonsterSprite.load(assets);
		MonsterStats stats = MonsterStats.load(MonsterSprite.nativeLib(assets));
		int failures = 0;
		for (String c : codes) {
			String letter = c.substring(0, 1);
			try {
				MapData m = MapData.load(assets.resolve("eng/map/" + letter + ".png"), c);
				AreaInfo a;
				try {
					a = AreaInfo.load(assets.resolve("eng/mob/" + letter + ".png"), c);
				} catch (java.io.IOException e) {
					a = AreaInfo.absent(c);
				}
				Tiles t = Tiles.load(assets, m);
				StringBuilder tiles = new StringBuilder();
				for (int i = 0; i < 4; i++) {
					tiles.append(t.has(i) ? m.tileSet(i) : "-").append(' ');
				}
				MonsterTable mobs;
				try {
					mobs = MonsterTable.load(assets.resolve("eng/mob/" + letter + ".png"), c,
							m.width, m.height);
				} catch (java.io.IOException e) {
					mobs = MonsterTable.empty(c);
				}
				int missing = 0;
				java.util.LinkedHashSet<String> sample = new java.util.LinkedHashSet<>();
				for (MonsterTable.Record r : mobs.records) {
					if (sprites.standing(r.monsterId) == null) {
						missing++;
					}
					if (sample.size() < 3) {
						String n = sprites.name(r.monsterId);
						sample.add(n != null ? n : "?" + r.monsterId);
					}
				}
				failures += missing;
				String statsText = "";
				if (!mobs.records.isEmpty()) {
					int id = mobs.records.get(0).monsterId;
					statsText = stats.has(id)
							? String.format(" [%s hp=%d atk=%d cri=%d]", sprites.name(id),
									stats.hp(id), stats.attack(id), stats.critical(id))
							: "";
				}
				System.out.printf("%-4s %-26s %2dx%-2d tiles=%s mobs=%-3d noSprite=%d  %s%s%n", c,
						a.name, m.width, m.height, tiles, mobs.records.size(), missing,
						String.join(", ", sample), statsText);
			} catch (Exception e) {
				failures++;
				System.out.printf("%-4s FAIL %s%n", c, e.getMessage());
			}
		}
		System.out.println("failures=" + failures);
	}

	private CheckMapsRun() {
	}
}

package wawa;

import java.io.IOException;

/**
 * The quest file LoadGlobalData reads (dat/quesn, quesh on hell): u16 N, then per quest its title,
 * description, QT byte, task and reward texts (each u8 length + EUC-KR), five typed lists and the
 * reward phase ExecPhase(1, id) runs. Quest ids are file indexes; the hero's slots hold id + 1.
 *
 * <pre>
 * QT    bits 0-1 kind (0 main, slot 0; 1 sub, slots 1-4; 2 and 3 free, slots 5-6; 2 repeats daily),
 *       bits 5-7 the list icon
 * lists 0 kills: 0 kind, count | 1 map letter, number, uid, count
 *       1 conditions: 2 condition (parameter, operator, parameter)
 *       2 skills: 3 skill id, count
 *       3 time: 4..7 u16 seconds (4 always, 5 normal only, 6 hell only, 7 never)
 *       4 drops: 8 kind, u16 item, chance % | 9 map letter, number, uid, u16 item, chance %
 * </pre>
 */
final class QuestBook {

	/** One quest definition; list entries keep their offset into {@link #d}, at the type byte. */
	static final class Def {
		final byte[] title;
		final byte[] desc;
		final byte[] task;
		final byte[] reward;
		final int qt;
		final int[][] lists = new int[5][];

		Def(byte[] title, byte[] desc, int qt, byte[] task, byte[] reward) {
			this.title = title;
			this.desc = desc;
			this.qt = qt;
			this.task = task;
			this.reward = reward;
		}

		/** QT &amp; 3: 0 main, 1 sub, 2 and 3 free. */
		int kind() {
			return qt & 3;
		}
	}

	final byte[] d;
	final Def[] defs;
	/** The reward phases as an event table, ExecPhase's type 1. */
	final EventScript.Table phases;

	private QuestBook(byte[] d) throws IOException {
		this.d = d;
		int n = ResourceFile.u16(d, 0);
		defs = new Def[n];
		int[] phaseAt = new int[n];
		int p = 2;
		for (int i = 0; i < n; i++) {
			int[] at = { p };
			byte[] title = text(at);
			byte[] desc = text(at);
			int qt = d[at[0]++] & 0xff;
			Def def = new Def(title, desc, qt, text(at), text(at));
			for (int list = 0; list < 5; list++) {
				int count = d[at[0]++] & 0xff;
				def.lists[list] = new int[count];
				for (int k = 0; k < count; k++) {
					def.lists[list][k] = at[0];
					at[0] += entryLen(at[0]);
				}
			}
			phaseAt[i] = at[0];
			p = at[0] + EventScript.phaseLen(d, at[0]);
			defs[i] = def;
		}
		if (p != d.length) {
			throw new IOException("quest file walk ended at " + p + " of " + d.length);
		}
		phases = new EventScript.Table(d, phaseAt);
	}

	/** dat/quesn (normal) or quesh (any other difficulty). */
	static QuestBook load(Assets assets, boolean hell) throws IOException {
		return new QuestBook(assets.read("assets/dat/" + (hell ? "quesh" : "quesn") + ".png"));
	}

	int count() {
		return defs.length;
	}

	/** GetQuestLen's entry length by type; types from 10 on are one byte. */
	private int entryLen(int p) {
		return switch (d[p] & 0xff) {
			case 0, 3, 4, 5, 6, 7 -> 3;
			case 1, 8 -> 5;
			case 2 -> 1 + EventScript.conditionLen(d, p + 1);
			case 9 -> 7;
			default -> 1;
		};
	}

	private byte[] text(int[] at) {
		int len = d[at[0]] & 0xff;
		byte[] s = new byte[len];
		System.arraycopy(d, at[0] + 1, s, 0, len);
		at[0] += 1 + len;
		return s;
	}
}

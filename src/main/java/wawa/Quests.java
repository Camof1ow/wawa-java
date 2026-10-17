package wawa;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * The quest runtime: the hero's seven quest slots (hero+0x398, 0x1c each: slot 0 main, 1-4 sub,
 * 5-6 free), the per-quest state bytes (quest+0x10: 0 never done, 1 done, else the day code of a
 * daily quest) and the checks that move them (Quest.c, QuestMons.c, QuestItem.c, QuestSkill.c,
 * QuestTime.c, GOrderQuestSummary.c).
 */
final class Quests {

	/** What the quest code reads from and writes to the game. */
	interface Host {
		/** wawamain+0xfc: frames played with no phase or popup up, 12 a second. */
		int tick();

		/** The phase stack depth (wawamain+0x5f30). */
		int depth();

		/** CheckCondition on a condition at p. */
		int condition(byte[] d, int p);

		/** GetParameterData on a parameter at p. */
		int param(byte[] d, int p);

		String mapCode();

		/** Monster id {@code kind}'s name (g_astrMonsName_K). */
		String monsterName(int kind);

		String itemName(int id);

		int itemType(int id);

		/** The ids a type-0 group item stands for. */
		int[] itemGroup(int id);

		/** AddHeadline(text, -1, wait): wait queues behind one showing, otherwise it shows now. */
		void headline(byte[] text, boolean wait);

		/** AddSysline. */
		void sysline(byte[] text);

		/** SearchPhase. */
		boolean searchPhase();

		/** AnalysisQuest's last part: the NPC quest marks. */
		void marks();
	}

	static final int SLOTS = 7;

	/** A kill goal: monster id {@link #kind}, or the unit {@link #uid} of map {@link #map}. */
	static final class Kill {
		String map;
		int kind;
		int uid;
		int cur;
		int tgt;
	}

	/** A quest drop: from monster id {@link #kind}, or from unit {@link #uid} of map {@link #map}. */
	static final class Drop {
		String map;
		int kind;
		int uid;
		int chance;
		int item;
	}

	static final class Slot {
		/** Quest id + 1; 0 is an empty slot. */
		int id1;
		/** 1 kills, 2 conditions, 4 skills, 8 timed, 0x40 complete, 0x80 failed. */
		int flags;
		/** The tick the time limit runs out at. */
		int deadline;
		final List<Kill> kills = new ArrayList<>();
		/** Each condition's offset into the quest file (InitQuest copies the 10 raw bytes). */
		final List<Integer> conds = new ArrayList<>();
		/** {skill id, count, target}. */
		final List<int[]> skills = new ArrayList<>();
		final List<Drop> drops = new ArrayList<>();

		/** Turn-in and delete: the id and the lists go; flags and deadline keep stale values. */
		void clear() {
			id1 = 0;
			kills.clear();
			conds.clear();
			skills.clear();
			drops.clear();
		}
	}

	private static final int KILLED = 0xdd924;
	private static final int GOAL_DONE = 0xdd984;
	private static final int QUEST_DONE = 0xdd950;
	private static final int QUEST_ACHIEVED = 0xdb614;
	private static final int QUEST_COMPLETE = 0xdb3d8;
	private static final int QUEST_FAILED = 0xdda24;
	private static final int LEFT_SEC = 0xdd9b8;
	private static final int LEFT_MIN = 0xdd9ec;
	private static final int LEFT_MIN_SEC = 0xdda54;
	/** CheckQuestTime's alert table: s16 ticks before the deadline (0, 10 s, 30 s, 1, 2, 3, 5 min). */
	private static final int ALERTS = 0xdda70;
	private static final int ATTACK = 0xdd9a8;
	private static final int SKILL_NAME = 0xe135c;
	private static final int STAT_NAME = 0xe2884;
	private static final int[] SUMMARY = { 0xd8270, 0xd8248, 0xd82a4, 0xd82d4, 0xd8308, 0xd8328, 0xd833c, 0xd8364,
			0xd8374, 0xd83b4, 0xd83c4, 0xd83e4 };
	private static final int LIMIT = 0, TIMEOUT = 1, KILL_KIND = 2, KILL_UID = 3, TASK = 4, LEVEL = 5, COUNT = 6,
			SUM_ATTACK = 7, SKILL = 8, TASK_LABEL = 9, NO_TASK = 10, REWARD = 11;

	final QuestBook book;
	private final ElfImage so;
	private final Host host;
	final Slot[] slots = new Slot[SLOTS];
	final byte[] state;
	/** AnalysisQuest's flags with every open goal taken as met, for the NPC markers. */
	final int[] hypo = new int[SLOTS];
	/** wawamain+0x100: the next tick CheckQuestTime has something to say at. */
	int nextAlert;
	/** The difficulty (diffy): 0 normal. */
	int diffy;
	/** The hero's class, for skill names. */
	int heroClass;

	Quests(QuestBook book, ElfImage so, Host host) {
		this.book = book;
		this.so = so;
		this.host = host;
		state = new byte[book.count()];
		for (int i = 0; i < SLOTS; i++) {
			slots[i] = new Slot();
		}
	}

	QuestBook.Def def(int id) {
		return book.defs[id];
	}

	/** The slot holding quest id, or null. */
	Slot find(int id) {
		for (Slot s : slots) {
			if (s.id1 == id + 1) {
				return s;
			}
		}
		return null;
	}

	/** Every slot and state byte cleared (InitHero). */
	void reset() {
		for (Slot s : slots) {
			s.clear();
			s.flags = 0;
			s.deadline = 0;
		}
		java.util.Arrays.fill(state, (byte) 0);
		nextAlert = 0;
	}

	// ---- InitQuest / MenuQuestAccept ------------------------------------------------------

	/**
	 * InitQuest(slot, id): the five lists of the definition become the slot's goals. A time entry
	 * that applies on this difficulty (4 always, 5 normal, 6 hell) sets the deadline and, when
	 * {@code alert}, runs CheckQuestTime(1) as the original does on every InitQuest.
	 */
	void init(Slot s, int id, boolean alert) {
		byte[] d = book.d;
		int tick = host.tick();
		s.clear();
		s.id1 = id + 1;
		s.flags = 0;
		s.deadline = 0;
		int[][] lists = book.defs[id].lists;
		if (lists[0].length > 0) {
			s.flags |= 1;
		}
		for (int p : lists[0]) {
			int t = d[p] & 0xff;
			if (t == 0) {
				Kill k = new Kill();
				k.kind = d[p + 1] & 0xff;
				k.tgt = d[p + 2] & 0xff;
				s.kills.add(k);
			} else if (t == 1) {
				Kill k = new Kill();
				k.map = code(d[p + 1], d[p + 2]);
				k.uid = d[p + 3] & 0xff;
				k.tgt = d[p + 4] & 0xff;
				s.kills.add(k);
			}
		}
		if (lists[1].length > 0) {
			s.flags |= 2;
		}
		for (int p : lists[1]) {
			if ((d[p] & 0xff) == 2) {
				s.conds.add(p + 1);
			}
		}
		if (lists[2].length > 0) {
			s.flags |= 4;
		}
		for (int p : lists[2]) {
			if ((d[p] & 0xff) == 3) {
				s.skills.add(new int[] { d[p + 1] & 0xff, 0, d[p + 2] & 0xff });
			}
		}
		for (int p : lists[3]) {
			int t = d[p] & 0xff;
			if (t == 4 || t == 5 && diffy == 0 || t == 6 && diffy == 1) {
				s.flags |= 8;
				s.deadline = ResourceFile.u16(d, p + 1) * 12 + tick;
				if (alert) {
					nextAlert = tick;
					checkTime(true);
				}
			}
		}
		for (int p : lists[4]) {
			int t = d[p] & 0xff;
			if (t == 8) {
				Drop e = new Drop();
				e.kind = d[p + 1] & 0xff;
				e.item = ResourceFile.u16(d, p + 2);
				e.chance = d[p + 4] & 0xff;
				s.drops.add(e);
			} else if (t == 9) {
				Drop e = new Drop();
				e.map = code(d[p + 1], d[p + 2]);
				e.uid = d[p + 3] & 0xff;
				e.item = ResourceFile.u16(d, p + 4);
				e.chance = d[p + 6] & 0xff;
				s.drops.add(e);
			}
		}
	}

	private static String code(byte letter, byte number) {
		int n = number & 0xff;
		return (char) (letter & 0xff) + (n < 10 ? "0" : "") + n;
	}

	/**
	 * ResultGMenuQuestAccept's slot choice: a main quest overwrites slot 0; a sub quest takes the
	 * first of slots 1-4, a free one of 5-6, that is empty or holds the same quest. Returns the
	 * slot, or -1 when none is left.
	 */
	int accept(int id) {
		int kind = book.defs[id].kind();
		int lo = kind == 0 ? 0 : kind == 1 ? 1 : 5;
		int hi = kind == 0 ? 0 : kind == 1 ? 4 : 6;
		for (int s = lo; s <= hi; s++) {
			if (kind == 0 || slots[s].id1 == 0 || slots[s].id1 == id + 1) {
				init(slots[s], id, true);
				return s;
			}
		}
		return -1;
	}

	// ---- AnalysisQuest and the condition operators ----------------------------------------

	/**
	 * AnalysisQuest's flag pass: a slot is complete (0x40) when every kill and skill count is
	 * reached and every condition holds, failed (0x80) once a time limit has run out, failure
	 * first. {@link #hypo} keeps the flags as if every open goal were met.
	 */
	void analyze() {
		int tick = host.tick();
		for (int i = 0; i < SLOTS; i++) {
			Slot s = slots[i];
			if (s.id1 == 0) {
				continue;
			}
			int ok = 1;
			if ((s.flags & 1) != 0) {
				for (Kill k : s.kills) {
					if (k.cur < k.tgt) {
						ok = 0;
					}
				}
			}
			if ((s.flags & 2) != 0) {
				for (int c : s.conds) {
					if (host.condition(book.d, c) == 0) {
						ok = 0;
					}
				}
			}
			if ((s.flags & 4) != 0) {
				for (int[] k : s.skills) {
					if (k[1] < k[2]) {
						ok = 0;
					}
				}
			}
			if ((s.flags & 8) != 0 && s.deadline <= tick) {
				ok = -1;
			}
			s.flags &= 0x3f;
			hypo[i] = s.flags;
			if (ok == 1) {
				s.flags |= 0x40;
				hypo[i] = s.flags;
			} else if (ok == -1) {
				s.flags |= 0x80;
				hypo[i] = s.flags;
			} else {
				hypo[i] |= 0x40;
			}
		}
		host.marks();
	}

	/** ExitPhase with nothing left to run: AnalysisQuest, and "quest complete" for each condition quest that just completed. */
	void phaseEnd() {
		int[] old = new int[SLOTS];
		for (int i = 0; i < SLOTS; i++) {
			old[i] = slots[i].flags;
		}
		analyze();
		for (int i = 0; i < SLOTS; i++) {
			Slot s = slots[i];
			if (s.id1 != 0 && (s.flags & 0x42) == 0x42 && (old[i] & 0x40) == 0) {
				host.headline(titled(QUEST_COMPLETE, s.id1 - 1), true);
			}
		}
	}

	/** CheckCondition 0x14 in progress, 0x15 failed, 0x16 complete and not yet reported, 0x17 the state byte. */
	int condition(int op, int x) {
		if (op == 0x17) {
			return x >= 0 && x < state.length ? state[x] & 0xff : 0;
		}
		int want = op == 0x14 ? 0 : op == 0x16 ? 0x40 : -1;
		for (Slot s : slots) {
			if (s.id1 == x + 1 && (op == 0x15 ? (s.flags & 0x80) != 0 : (s.flags & 0xc0) == want)) {
				return 1;
			}
		}
		return 0;
	}

	/** Opcode 31's test: the flags of the slot holding quest id, or -1 when none does. */
	int flagsOf(int id) {
		Slot s = find(id);
		return s == null ? -1 : s.flags;
	}

	/**
	 * Opcode 31 after the reward phase: the slot empties, the state byte becomes 1 (a daily free
	 * quest: today's code) and "quest achieved" shows at once.
	 */
	void turnIn(int id) {
		Slot s = find(id);
		if (s == null) {
			return;
		}
		s.clear();
		state[id] = (byte) (book.defs[id].kind() == 2 ? localDate() % 255 + 1 : 1);
		host.headline(titled(QUEST_ACHIEVED, id), false);
	}

	/**
	 * Opcode 42's board list: the ids shuffled by a local LCG seeded with 1 + LocalDate + the map
	 * code's three bytes (seed * 0x41c64e6d + 0x3039, index ((seed &gt;&gt; 16) &amp; 0x7fff) % n), cut
	 * to five before the quests already done today are taken out, then those held in a slot.
	 */
	int[] boardList(int[] ids, String map) {
		int date = localDate();
		int seed = 1 + date + (map.charAt(0) | map.charAt(1) << 8 | map.charAt(2) << 16);
		int[] arr = ids.clone();
		int n = arr.length;
		for (int i = 0; i < n; i++) {
			seed = seed * 0x41c64e6d + 0x3039;
			int j = ((seed >> 16) & 0x7fff) % n;
			int t = arr[i];
			arr[i] = arr[j];
			arr[j] = t;
		}
		n = Math.min(n, 5);
		int code = date % 255 + 1;
		int last = n - 1;
		for (int i = last; i >= 0; i--) {
			if ((state[arr[i]] & 0xff) == code) {
				System.arraycopy(arr, i + 1, arr, i, last - i);
				last--;
			}
		}
		int[] out = new int[5];
		int cnt = 0;
		for (int i = 0; i <= last && i < 5; i++) {
			if (find(arr[i]) == null) {
				out[cnt++] = arr[i];
			}
		}
		return java.util.Arrays.copyOf(out, cnt);
	}

	/** Delete from the quest list (ResultGMenu 0x3a5): the slot empties, the state byte stays. */
	void delete(int slot) {
		slots[slot].clear();
		analyze();
	}

	// ---- Progress checks ------------------------------------------------------------------

	private boolean open(Slot s, int list) {
		return s.id1 != 0 && ((s.flags & 8) == 0 || host.tick() < s.deadline) && (s.flags & list) != 0;
	}

	/**
	 * CheckQuestMons at InitMonsDown: each kill goal for this monster id (a named unit does not
	 * count) or for this map unit counts up, with its "[name] n/m" line; a goal reached runs
	 * AnalysisQuest and SearchPhase and shows "[name] 완료", or "quest complete" when the quest is.
	 */
	void killed(int kind, int uid, boolean named) {
		for (Slot s : slots) {
			if (!open(s, 1)) {
				continue;
			}
			for (Kill k : s.kills) {
				boolean match = k.map == null ? k.cur < k.tgt && k.kind == kind && !named
						: k.map.equals(host.mapCode()) && k.cur < k.tgt && k.uid == uid;
				if (!match) {
					continue;
				}
				byte[] name = k.map == null ? cfmt(so.cbytes(KILLED), Ksc.encode(host.monsterName(kind))) : def(s.id1 - 1).task;
				k.cur++;
				host.sysline(cfmt(Ksc.encode("\u00075[%s]\u00070 %d/%d"), name, k.cur, k.tgt));
				if (k.tgt <= k.cur) {
					analyze();
					host.searchPhase();
					reached(s, name, true);
				}
			}
		}
	}

	/**
	 * CheckQuestItem(item, n) after n were added: for each condition counting this item that was
	 * short before, "[item] have/need"; reaching it runs AnalysisQuest (only with no phase running)
	 * and shows "[item] 완료" or "quest complete".
	 */
	void itemAdded(int item, int n) {
		byte[] d = book.d;
		for (Slot s : slots) {
			if (!open(s, 2)) {
				continue;
			}
			for (int c : s.conds) {
				if (!counts(c, item)) {
					continue;
				}
				int have = host.param(d, c);
				int need = host.param(d, c + 3);
				if (have - n >= need) {
					continue;
				}
				byte[] name = Ksc.encode(host.itemName(item));
				host.sysline(cfmt(Ksc.encode("\u00075[%s]\u00070 %d/%d"), name, Math.min(have, need), need));
				if (need <= have) {
					if (host.depth() == 0) {
						analyze();
					}
					reached(s, name, false);
				}
			}
		}
	}

	/** CheckQuestSkill(id), with no phase running: like a kill goal, for skill (0: basic attack) uses. */
	void skillUsed(int id) {
		if (host.depth() != 0) {
			return;
		}
		for (Slot s : slots) {
			if (!open(s, 4)) {
				continue;
			}
			for (int[] k : s.skills) {
				if (k[0] != id || k[1] >= k[2]) {
					continue;
				}
				byte[] name = skillName(id);
				k[1]++;
				host.sysline(cfmt(Ksc.encode("\u00075[%s]\u00070 %d/%d"), name, k[1], k[2]));
				if (k[2] <= k[1]) {
					analyze();
					host.searchPhase();
					reached(s, name, false);
				}
			}
		}
	}

	/** The headline when a goal is reached: "[name] 완료" while the quest is open, else "quest complete" (a kill goal also stops the timer). */
	private void reached(Slot s, byte[] name, boolean kill) {
		byte[] msg;
		if ((s.flags & 0x40) == 0) {
			msg = cfmt(so.cbytes(GOAL_DONE), name, name);
		} else {
			if (kill) {
				s.flags &= ~8;
			}
			msg = titled(QUEST_DONE, s.id1 - 1);
		}
		host.headline(msg, true);
	}

	/** GetParameterItem: the condition's first parameter counts this item (kind 5: the id, or a group item's members). */
	private boolean counts(int c, int item) {
		byte[] d = book.d;
		if ((d[c] & 0xf) != 5) {
			return false;
		}
		int id = (d[c] & 0xff) >> 4 | (d[c + 1] & 0xff) << 4;
		if (id == item) {
			return true;
		}
		if (host.itemType(id) == 0) {
			for (int g : host.itemGroup(id)) {
				if (g == item) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * CheckQuestTime(p), at {@link #nextAlert} and on InitQuest: a deadline reached shows "quest
	 * failed" (AnalysisQuest, SearchPhase); 10 s .. 5 min before it a time-left line; with p, a
	 * minute-and-second line for each timed quest otherwise. As in the original, {@code t} is
	 * reused, so a later slot compares against the previous deadline minus 5 minutes.
	 */
	void checkTime(boolean p) {
		int tick = host.tick();
		int t = tick;
		nextAlert = Integer.MAX_VALUE;
		int[] alerts = new int[7];
		for (int k = 0; k < 7; k++) {
			alerts[k] = so.s16(ALERTS + k * 2);
		}
		for (Slot s : slots) {
			if (s.id1 == 0 || (s.flags & 8) == 0 || tick > s.deadline) {
				continue;
			}
			int d = s.deadline;
			int k = 0;
			for (; k < 7; k++) {
				if (d - t == alerts[k]) {
					if (k == 0) {
						host.headline(titled(QUEST_FAILED, s.id1 - 1), true);
						analyze();
						host.searchPhase();
					} else if (alerts[k] < 720) {
						host.sysline(titled(LEFT_SEC, s.id1 - 1, alerts[k] / 12));
					} else {
						host.sysline(titled(LEFT_MIN, s.id1 - 1, alerts[k] / 720));
					}
					break;
				}
			}
			if (k == 7 && p) {
				t = d - t;
				host.sysline(titled(LEFT_MIN_SEC, s.id1 - 1, t / 720, t / 12 % 60));
			}
			for (k = 0; k < 7; k++) {
				t = d - alerts[k];
				if (tick < t && t < nextAlert) {
					nextAlert = t;
				}
			}
		}
	}

	/**
	 * GetItemHeroGather's quest roll for one item slot: the first slot (timed ones before their
	 * deadline) whose drop entry matches the corpse, by monster id or by this map's unit, and
	 * whose Rand100 roll (drawn only on a match) beats the chance. An entry whose condition on
	 * the same item is already met (have &gt;= need, whatever the operator) is skipped. Returns
	 * the item, or -1.
	 */
	int drop(int kind, int uid, Random rng) {
		byte[] d = book.d;
		int tick = host.tick();
		for (Slot s : slots) {
			if (s.id1 == 0 || (s.flags & 8) != 0 && tick >= s.deadline || s.drops.isEmpty()) {
				continue;
			}
			for (Drop e : s.drops) {
				boolean match = e.map != null ? e.map.equals(host.mapCode()) && e.uid == uid && rng.nextInt(100) < e.chance
						: e.kind == kind && rng.nextInt(100) < e.chance;
				if (!match) {
					continue;
				}
				boolean met = false;
				if ((s.flags & 2) != 0) {
					for (int c : s.conds) {
						if (counts(c, e.item) && host.param(d, c + 3) <= host.param(d, c)) {
							met = true;
							break;
						}
					}
				}
				if (!met) {
					return e.item;
				}
			}
		}
		return -1;
	}

	// ---- Summary (GetGOrderQuestSummary) -------------------------------------------------

	/**
	 * GetGOrderQuestSummary(slot, gm): for the offer popup (gm) the description first; then up to
	 * four goal lines (time left, kills, conditions, skills; or the task text alone) in ';' while
	 * open and '7' (gm) or '>' when done, and the reward line in '3' (gm) or '>'.
	 */
	byte[] summary(Slot s, boolean gm) {
		QuestBook.Def def = def(s.id1 - 1);
		byte[] d = book.d;
		int tick = host.tick();
		char done = gm ? '7' : '>';
		List<byte[]> lines = new ArrayList<>();
		if ((s.flags & 8) != 0) {
			lines.add(tick < s.deadline ? cfmt(fmt(LIMIT), done, (s.deadline - tick) / 12) : fmt(TIMEOUT));
		}
		if ((s.flags & 1) != 0) {
			for (Kill k : s.kills) {
				char c = k.tgt <= k.cur ? done : ';';
				lines.add(k.map == null ? cfmt(fmt(KILL_KIND), c, Ksc.encode(host.monsterName(k.kind)), k.cur, k.tgt)
						: cfmt(fmt(KILL_UID), c, def.task.length, def.task, k.cur, k.tgt));
			}
		}
		if ((s.flags & 2) != 0) {
			boolean task = false;
			for (int p : s.conds) {
				char c = host.condition(d, p) != 0 ? done : ';';
				int kind = d[p] & 0xf;
				int len = EventScript.parameterLen(d, p);
				switch (kind) {
					case 2, 3 -> {
						if (!task) {
							task = true;
							lines.add(cfmt(fmt(TASK), c, def.task.length, def.task));
						}
					}
					case 4 -> {
						int b1 = d[p + 1] & 0xff;
						byte[] name = b1 < 0x14 ? so.cbytes(so.u32(STAT_NAME + b1 * 4))
								: b1 < 0x2f ? cfmt(fmt(LEVEL), so.cbytes(so.u32(SKILL_NAME + ((b1 > 0x26 ? 0 : heroClass) * 0x1b + b1 - 0x14) * 4)))
								: null;
						if (name != null) {
							lines.add(cfmt(fmt(COUNT), c, name, host.param(d, p), host.param(d, p + len + 1)));
						}
					}
					case 5 -> {
						int id = (d[p] & 0xff) >> 4 | (d[p + 1] & 0xff) << 4;
						lines.add(cfmt(fmt(COUNT), c, Ksc.encode(host.itemName(id)), host.param(d, p), host.param(d, p + len + 1)));
					}
					case 7 -> {
						int ref = host.param(d, p);
						byte[] title = ref >= 0 && ref < book.count() ? def(ref).title : new byte[0];
						lines.add(cfmt(fmt(TASK), c, title.length, title));
					}
					default -> {
					}
				}
			}
		}
		if ((s.flags & 4) != 0) {
			for (int[] k : s.skills) {
				lines.add(cfmt(fmt(SKILL), k[2] <= k[1] ? done : ';', skillName(k[0]), k[1], k[2]));
			}
		}
		if (lines.isEmpty()) {
			lines.add(def.task.length == 0 ? cfmt(fmt(NO_TASK), ';')
					: cfmt(Ksc.encode("\u0007%c%s:%.*s\n"), ';', fmt(TASK_LABEL), def.task.length, def.task));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (gm) {
			out.write(def.desc, 0, def.desc.length);
			out.write('\n');
		}
		for (int i = 0; i < Math.min(4, lines.size()); i++) {
			out.write(lines.get(i), 0, lines.get(i).length);
		}
		byte[] body = out.toByteArray();
		out.reset();
		if (def.reward.length == 0) {
			out.write(body, 0, body.length > 0 && body[body.length - 1] == '\n' ? body.length - 1 : body.length);
		} else {
			out.write(body, 0, body.length);
			byte[] reward = cfmt(fmt(REWARD), gm ? '3' : '>', def.reward.length, def.reward);
			out.write(reward, 0, reward.length);
		}
		out.write(7);
		out.write('0');
		return out.toByteArray();
	}

	private byte[] fmt(int i) {
		return so.cbytes(SUMMARY[i]);
	}

	private byte[] skillName(int id) {
		if (id == 0) {
			return so.cbytes(ATTACK);
		}
		int i = heroClass * 27 + (id < 14 ? id - 1 : id + 8);
		return so.cbytes(so.u32(SKILL_NAME + i * 4));
	}

	/** A "%.*s"-titled message (headline / sysline) for quest id, with any further int args. */
	byte[] titled(int fmtVa, int id, Object... more) {
		byte[] title = def(id).title;
		Object[] args = new Object[2 + more.length];
		args[0] = title.length;
		args[1] = title;
		System.arraycopy(more, 0, args, 2, more.length);
		return cfmt(so.cbytes(fmtVa), args);
	}

	/**
	 * sprintf over EUC-KR bytes for the conversions these formats use: %s (byte[] or String),
	 * %.*s (int length, then byte[]), %d and %c.
	 */
	static byte[] cfmt(byte[] f, Object... args) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int a = 0;
		for (int i = 0; i < f.length && f[i] != 0; i++) {
			if (f[i] != '%' || i + 1 >= f.length) {
				out.write(f[i]);
				continue;
			}
			char c = (char) f[++i];
			if (c == '.' && i + 2 < f.length && f[i + 1] == '*' && f[i + 2] == 's') {
				int len = (Integer) args[a++];
				byte[] s = bytes(args[a++]);
				out.write(s, 0, Math.min(len, s.length));
				i += 2;
			} else if (c == 's') {
				byte[] s = bytes(args[a++]);
				int n = 0;
				while (n < s.length && s[n] != 0) {
					n++;
				}
				out.write(s, 0, n);
			} else if (c == 'd') {
				byte[] n = String.valueOf(((Number) args[a++]).intValue()).getBytes();
				out.write(n, 0, n.length);
			} else if (c == 'c') {
				Object o = args[a++];
				out.write(o instanceof Character ch ? ch : ((Number) o).intValue());
			} else {
				out.write('%');
				out.write(c);
			}
		}
		return out.toByteArray();
	}

	private static byte[] bytes(Object o) {
		return o instanceof byte[] b ? b : Ksc.encode(String.valueOf(o));
	}

	/** LocalDate: tm_year * 366 + tm_yday of the local time. */
	@SuppressWarnings("deprecation")
	static int localDate() {
		Date now = new Date();
		int y = now.getYear() + 1900;
		int m = now.getMonth();
		int[] before = { 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334 };
		boolean leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
		return (y - 1900) * 366 + before[m] + now.getDate() - 1 + (m > 1 && leap ? 1 : 0);
	}

	// ---- Save / load (SaveSlot keeps the slots, their lists and the state bytes) ------------

	/** The slots as "id1,flags,deadline,kill counts,skill counts" separated by ';'. */
	String saveSlots() {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < SLOTS; i++) {
			Slot s = slots[i];
			if (i > 0) {
				out.append(';');
			}
			out.append(s.id1).append(',').append(s.flags).append(',').append(s.deadline).append(',');
			for (int k = 0; k < s.kills.size(); k++) {
				out.append(k > 0 ? ":" : "").append(s.kills.get(k).cur);
			}
			out.append(',');
			for (int k = 0; k < s.skills.size(); k++) {
				out.append(k > 0 ? ":" : "").append(s.skills.get(k)[1]);
			}
		}
		return out.toString();
	}

	void loadSlots(String text) {
		String[] rows = text.split(";", -1);
		for (int i = 0; i < SLOTS && i < rows.length; i++) {
			String[] f = rows[i].split(",", -1);
			Slot s = slots[i];
			s.clear();
			int id1 = Integer.parseInt(f[0]);
			if (id1 > 0 && id1 <= book.count()) {
				init(s, id1 - 1, false);
				String[] kills = f[3].isEmpty() ? new String[0] : f[3].split(":");
				for (int k = 0; k < kills.length && k < s.kills.size(); k++) {
					s.kills.get(k).cur = Integer.parseInt(kills[k]);
				}
				String[] skills = f[4].isEmpty() ? new String[0] : f[4].split(":");
				for (int k = 0; k < skills.length && k < s.skills.size(); k++) {
					s.skills.get(k)[1] = Integer.parseInt(skills[k]);
				}
			}
			s.flags = Integer.parseInt(f[1]);
			s.deadline = Integer.parseInt(f[2]);
		}
		nextAlert = 0;
	}

	String saveState() {
		StringBuilder out = new StringBuilder();
		for (byte b : state) {
			out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
		}
		return out.toString();
	}

	void loadState(String hex) {
		java.util.Arrays.fill(state, (byte) 0);
		for (int i = 0; i < state.length && 2 * i + 1 < hex.length(); i++) {
			state[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
		}
	}
}

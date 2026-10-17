package wawa;

import java.util.ArrayList;
import java.util.List;

/**
 * The phase interpreter: ExecPhase, AnalysisPhaseAuto, AnalysisPhaseOne (WIPI FUN_000291e8),
 * CalcPhase, ExitPhase, KeyPressPhase, CalcPhaseCondition / NextPhaseCondition, CheckCondition,
 * CalcPhaseAssignment and the SearchPhase triggers.
 *
 * <pre>
 * stack     up to 8 entries {table, event, line offsets, pc, timer, result}
 * results   0 running, 1/2 ended, 3 abort the stack, 4 dialogue, 5 wait for timer and movement,
 *           6 caption, 7 notice, 8 choice; 4..8 block the entry and its parents
 * frame     AnalysisPhaseOne runs the line at pc, then keeps going while the next line has the f0
 *           prefix or the line just run set "continue" (lines 1 failing, 6, 7, b, d, e)
 * triggers  type 1 always and 0xb on its condition (local table, then 0xb in the global one),
 *           2 map exit, 4 NPC, 5 death, 6 area, 7 map object; first match with status 0 runs
 * status    written when the outermost phase ends: types 1/0xb get result + 1, types &gt; 3 get 3
 *           when the result is 2; map exits never; local status resets whenever the map reloads
 * </pre>
 *
 * Quests, buffs, sound, zoom and the GOrder/GMenu screens these commands open are not ported; the
 * commands that need them say so through {@link World#notPorted}.
 */
final class EventRunner {

	static final int QUEST = 1;
	static final int GLOBAL = 2;
	static final int LOCAL = 3;
	static final int HERO = -1;

	/** What the interpreter moves and asks about; Game implements it. */
	interface World {
		/** FUN_0004911e / SetPhaseHeroStop: the hero and monsters come under script control. */
		void heroStop();

		/** 73: place the hero (x = y = 0xff keeps the cell; dir 8 keeps the direction). */
		void heroPlace(int x, int y, int dir);

		/** 83: walk the hero to a cell. */
		void heroWalk(int x, int y, int dir);

		/**
		 * 93 / a3: the hero appears where it is placed, or vanishes. Hero +0x1d is a fade step that
		 * DrawHeroAnim moves up by one per drawn frame at alpha |step|; 4 and up is not drawn and
		 * stops there. 93 sets -3 (0 on a black screen) when the hero is not fully shown, a3 sets 1
		 * (4 on a black screen) when it is not already fading out. Returns 4 when the step was set,
		 * else 0.
		 */
		int heroVisible(boolean visible, int x, int y, int dir);

		/** b3: hero direction, action and its repeat byte (hero+0x45); returns 0, or 3 / 4 (acts 0xc..0xf). */
		int heroAct(int dir, int act, int repeat);

		/**
		 * e3: walk the hero next to a unit, g_aDirX / g_aDirY[side] (0xb9848 / 0xb9854) cells away;
		 * nothing when the unit is gone.
		 */
		void heroToUnit(int unit, int side);

		/** 03: LoadMonsToTemp; returns the frames to wait. */
		int unitSpawn(int a, int b, int c, int d);

		/**
		 * 13..63: unit commands by hi nibble; returns the frames to wait, 0 when the unit is gone.
		 * 43 / 53 return 4 only when the unit's fade actually changes.
		 */
		int unit(int hi, int id, int x, int y, int dir);

		/** True when unit uid is on the map (GetPhaseMons finds it). */
		boolean unitExists(int uid);

		/** c3 / d3: hide or show map object obj. */
		void mapObject(int obj, boolean visible);

		/**
		 * CalcPhase result 5's busy test: the camera still scrolls, the hero walks or plays a b3
		 * action, or a unit has a scripted walk or action lock.
		 */
		boolean moving();

		/**
		 * CalcPhase result 5 on a black screen: units on a scripted walk land on their target cell,
		 * the hero's walk lands, and the units' and hero's script motion bits clear.
		 */
		void arriveAll();

		/** ProcGame once more: the fast modes' extra tick in result 5 (camera, hero action, units). */
		void extraTick();

		/** wawamain+0x2ec0: DrawFade's signed step (-9..8); 8 and up is a fully black screen. */
		int fadeLevel();

		/** ExitPhase when no phase takes over: a black screen (8+) fades in from -7, 1..7 from 1 - v. */
		void fadeRestore();

		/** 14: LoadGameMap. */
		void moveMap(String code);

		/** LoadGameMap(name, 2) after a phase that moved the map: the new map's events load. */
		void reloadEvents();

		/** ExitPhase after a map-exit event: MoveGameMap(exit, 1) through the current map's portal. */
		void mapExit(int exit);

		/** 45: fade/colour step k; returns the frames the phase waits for it. */
		int fade(int k, int mode);

		/** 65: AddSwing. */
		void shake(int n);

		/** 35 and talk records: AddImoticon on a unit, or the hero for {@link #HERO}. */
		void emote(int unit, int kind);

		/** 08..68 and 75..a5: camera. hi as in the opcode, target -1 for the hero. */
		void camera(int hi, int x, int y, int target);

		/** 17: AddItem; false when it cannot be taken. */
		boolean giveItem(int id, int count, int flags);

		/** 07: DelItem. */
		void takeItem(int id, int count);

		/** 4b: SaveSlot. */
		void save();

		/** GetParameterData kind 4 / assignment target 4: hero+0xa4 + 4i. */
		int heroInt(int i);

		void setHeroInt(int i, int v);

		/** GetParameterData kind 5: how many of item id the hero holds. */
		int itemCount(int id);

		/** System variables 0x14.. (clock, difficulty, class, visited maps, time). */
		int systemValue(int i);

		String heroName();

		/** wawamain+0x4d04: the name SearchPhaseNPC copied. */
		String npcName();

		/** A command whose subsystem this port does not have. */
		void notPorted(String what);

		/** Lo 2: GOrderFuncUp(0xd) in shop mode {@code mode} over grades first..last. */
		void openShop(int mode, int first, int last);

		/** 21: GMenuFuncUp(100) offering quest id; the phase waits until it closes. */
		void questOffer(int id);

		/** 31's slot test: the flags of the slot holding quest id, -1 when no slot does. */
		int questFlags(int id);

		/** 31 after its reward phase: the slot empties and the quest's state byte is set. */
		void questTurnIn(int id);

		/** 31 when the reward phase could not give its items: GMenuFuncUp(0x386). */
		void questBagFull();

		/** CheckCondition 0x14..0x17 on quest x. */
		int questCondition(int op, int x);

		/** ExitPhase with nothing left to run: AnalysisQuest and its completion headlines. */
		void questPhaseEnd();

		/** 42: the quest board (GOrderFuncUp(8)) over these quest ids; the phase waits until it closes. */
		void openBoard(int[] ids);

		/** The slot holding quest id, -1 when none does. */
		int questSlot(int id);
	}

	private static final class Entry {
		EventScript.Table t;
		int type;
		int idx;
		int[] lines;
		/** The phase header: LoadMonsToTemp's monster id list. */
		int headerAt;
		int headerLen;
		int pc;
		int timer;
		int result;
	}

	private final World w;
	final EventScript.Vars vars = new EventScript.Vars();
	final TalkBox talk;
	EventScript.Table local = EventScript.EMPTY;
	EventScript.Table global = EventScript.EMPTY;
	/** The quest book's reward phases, ExecPhase's type 1. */
	EventScript.Table quest = EventScript.EMPTY;
	private final List<Entry> stack = new ArrayList<>();
	/** wawamain+0x5f34: 0 normal, 1 fast ('#'), 2 skip (AnalysisPhaseSave 5). */
	int mode;
	/** wawamain+0x5f38: the frame counter shared by every entry. */
	private int shared;
	/** wawamain+0x5f54: AnalysisPhaseSave 2 forbids '#' and hides its button. */
	private boolean noSkip;
	private final byte[] instance = new byte[9];
	private int instanceCounter;
	private final TalkBox.Emotes emotes;

	EventRunner(World w, TalkBox talk) {
		this.w = w;
		this.talk = talk;
		this.emotes = new TalkBox.Emotes() {
			@Override
			public void emote(int unit, int kind) {
				w.emote(unit, kind);
			}

			@Override
			public int instance() {
				return EventRunner.this.instanceByte();
			}
		};
	}

	boolean active() {
		return !stack.isEmpty();
	}

	/** Whether {@link #draw} has anything to show: DrawPhase, or the headline once no phase runs. */
	boolean overlay() {
		return active() || talk.headlineShown();
	}

	/** LoadGameMap with a non-zero mode: the map's events reload and their status returns to 0. */
	void loadMap(EventScript.Table t) {
		local = t;
		stack.clear();
		talk.clear();
		// FuncMoveMap clears system variables 0..9 on every map change.
		for (int i = 0; i < 10; i++) {
			vars.sys[i] = 0;
		}
	}

	/** The running phase's header bytes: the monster ids opcode 03 spawns by index. */
	int[] phaseHeader() {
		if (!active()) {
			return new int[0];
		}
		Entry e = stack.get(stack.size() - 1);
		int[] out = new int[e.headerLen];
		for (int i = 0; i < out.length; i++) {
			out[i] = e.t.d[e.headerAt + i] & 0xff;
		}
		return out;
	}

	private int instanceByte() {
		return instance[Math.max(0, stack.size() - 1)] & 0xff;
	}

	private EventScript.Table table(int type) {
		return type == GLOBAL ? global : type == QUEST ? quest : local;
	}

	// ---- triggers --------------------------------------------------------------------------

	/** SearchPhase: true when a phase started and is still running, or the search is skipped (HP 0). */
	boolean searchPhase() {
		if (active() || w.heroInt(3) <= 0) {
			return true;
		}
		mode = 0;
		for (int i = 0; i < local.count(); i++) {
			int t = local.type(i);
			if (local.status[i] == 0 && (t == 1 || t == 0xb && condition(local.d, local.param(i)) != 0)) {
				return started(execPhase(LOCAL, i, true));
			}
		}
		for (int i = 0; i < global.count(); i++) {
			if (global.status[i] == 0 && global.type(i) == 0xb && condition(global.d, global.param(i)) != 0) {
				return started(execPhase(GLOBAL, i, true));
			}
		}
		return false;
	}

	private static boolean started(int r) {
		return r == 0 || r == -1 || r >= 4;
	}

	/** SearchPhaseMapMove: true when an exit event took over (the event moves the hero itself). */
	boolean searchMapMove(int exit) {
		mode = 0;
		for (int i = 0; i < local.count(); i++) {
			if (local.type(i) == 2 && local.status[i] == 0 && (local.d[local.param(i)] & 0xff) == exit) {
				int r = execPhase(LOCAL, i, true);
				return r == 0 || r == 1;
			}
		}
		return false;
	}

	/** SearchPhaseNPC / Dead / Area / Subj: run the first matching event of the type. */
	boolean searchUnit(int type, int id) {
		if (type == 7 && active()) {
			return false;
		}
		mode = 0;
		for (int i = 0; i < local.count(); i++) {
			if (local.type(i) == type && local.status[i] == 0 && (local.d[local.param(i)] & 0xff) == id) {
				return execPhase(LOCAL, i, true) == 0;
			}
		}
		return false;
	}

	boolean searchArea(int tx, int ty) {
		mode = 0;
		for (int i = 0; i < local.count(); i++) {
			int p = local.param(i);
			if (local.type(i) == 6 && local.status[i] == 0 && (local.d[p] & 0xff) <= tx && tx <= (local.d[p + 2] & 0xff)
					&& (local.d[p + 1] & 0xff) <= ty && ty <= (local.d[p + 3] & 0xff)) {
				return execPhase(LOCAL, i, true) == 0;
			}
		}
		return false;
	}

	// ---- ExecPhase / ExitPhase -------------------------------------------------------------

	/** ExecPhase(type, idx, run). */
	int execPhase(int type, int idx, boolean run) {
		int depth = stack.size();
		instance[depth] = (byte) (instanceCounter + 1);
		instanceCounter = (instanceCounter + 1) & 0xf0;
		Entry e = frame(type, idx);
		if (e == null) {
			return -1;
		}
		stack.add(e);
		shared = 0;
		if (!run) {
			return 0;
		}
		int r = mode == 2 ? analysisOne(e) : analysisAuto(e);
		e.result = r;
		if (r == 1 || r == 2) {
			exitPhase(e, r);
			return r;
		}
		if (r == 3) {
			abortAll();
			return 3;
		}
		return e.result;
	}

	/** The frame ExecPhase pushes for event idx of table type, or null for an empty phase. */
	private Entry frame(int type, int idx) {
		EventScript.Table t = table(type);
		int p = t.phase(idx);
		int n = t.d[p] & 0xff;
		if (n == 0) {
			return null;
		}
		Entry e = new Entry();
		e.t = t;
		e.type = type;
		e.idx = idx;
		e.lines = new int[n];
		e.headerAt = p + 2;
		e.headerLen = t.d[p + 1] & 0xff;
		int q = p + (t.d[p + 1] & 0xff) + 2;
		for (int k = 0; k < n; k++) {
			e.lines[k] = q;
			q += EventScript.phaseOneLen(t.d, q);
		}
		return e;
	}

	/**
	 * AnalysisPhaseQuest on local event idx: its lines walked with nothing shown, only conditions,
	 * assignments and sub-phases taken, until a quest offer (the holding slot + 1, or MAX_VALUE
	 * when no slot holds the quest) or the turn-in of a held quest (~slot); 0 when it reaches
	 * neither. Assignments write the live variables; the caller restores them.
	 */
	int questProbe(int idx) {
		List<Entry> frames = new ArrayList<>();
		Entry first = frame(LOCAL, idx);
		if (first != null) {
			frames.add(first);
		}
		int r = 0;
		for (int budget = 4096; !frames.isEmpty() && budget > 0; budget--) {
			Entry e = frames.get(frames.size() - 1);
			if (e.pc >= e.lines.length) {
				frames.remove(frames.size() - 1);
				continue;
			}
			int[] l = line(e, e.pc);
			byte[] d = e.t.d;
			int op = d[l[0] + l[1] - 1] & 0xff;
			int a = l[0] + l[1];
			int hi = op >> 4;
			switch (op & 0xf) {
				case 0xd -> {
					int pc = calcCondition(e, hi, a);
					e.pc = pc < 0 ? e.lines.length : pc;
				}
				case 0xe -> {
					assign(d, a);
					e.pc++;
				}
				case 1 -> {
					int id = u16(d, a);
					if (hi <= 1) {
						e.pc++;
						Entry sub = frame(hi + 2, id);
						if (sub != null) {
							frames.add(sub);
						}
					} else if (hi == 2) {
						int s = w.questSlot(id);
						r = s >= 0 ? s + 1 : Integer.MAX_VALUE;
						frames.clear();
					} else if (hi == 3 && w.questSlot(id) >= 0) {
						r = ~w.questSlot(id);
						frames.clear();
					} else {
						e.pc++;
					}
				}
				default -> e.pc++;
			}
		}
		return r;
	}

	/**
	 * ExecPhase's result-3 loop: closes the entries running now. The last close may start a new
	 * phase through SearchPhase; that one is left running (the original's loop runs one past).
	 * A GMenu's frame state 3 (a declined quest, no slot left) ends the same way.
	 */
	void abortAll() {
		for (int n = stack.size(); n > 0 && !stack.isEmpty(); n--) {
			exitPhase(stack.get(stack.size() - 1), 3);
		}
	}

	/** wawamain+0x5f30: how many phases are running. */
	int depth() {
		return stack.size();
	}

	/** ExitPhase(e, r). */
	private void exitPhase(Entry e, int r) {
		stack.remove(e);
		int t = e.t.type(e.idx);
		if (e.type == LOCAL && t == 2 && r == 1 && stack.isEmpty() && !reloadAfter) {
			initPhaseZero();
			// ExitPhase always reads the exit at +1, even for shared headers.
			w.mapExit(e.t.d[e.t.offs[e.idx] + 1] & 0xff);
			return;
		}
		if (!stack.isEmpty()) {
			return;
		}
		if (r != 3) {
			if (e.type == LOCAL) {
				if (t == 1 || t == 0xb) {
					e.t.status[e.idx] = (byte) (r + 1);
				} else if (t > 3 && r == 2) {
					e.t.status[e.idx] = 3;
				}
			} else if (e.type == GLOBAL && t == 0xb) {
				e.t.status[e.idx] = (byte) (r + 1);
			}
		}
		initPhaseZero();
		if (reloadAfter) {
			// Opcode 14 loaded the map with mode 0; ExitPhase reloads it with mode 2 now.
			reloadAfter = false;
			w.reloadEvents();
			searchPhase();
		} else if (!searchPhase()) {
			w.questPhaseEnd();
			// No phase took over: the screen comes back from whatever fade the phase left.
			w.fadeRestore();
		}
	}

	/** wawamain+0x5f49: the map moved under a running phase. */
	private boolean reloadAfter;

	/** gmenu+0xe8 / +0xf3 / +0xfe: the choice button labels NextPhaseCondition 2..4 writes. */
	private byte[][] labels = new byte[3][];

	/** InitPhaseZero: control returns to the player. */
	private void initPhaseZero() {
		noSkip = false;
		mode = 0;
		talk.clear();
		w.camera(0, 0, 0, HERO);
	}

	// ---- AnalysisPhaseAuto / AnalysisPhaseOne ----------------------------------------------

	/** The line at index k, through 0f references; {offset of op byte, prefix length}. */
	private int[] line(Entry e, int k) {
		byte[] d = e.t.d;
		int at = e.lines[k];
		int i = (d[at] & 0xff) == 0xf0 ? 2 : 1;
		int op = d[at + i - 1] & 0xff;
		if ((op & 0xf) == 0xf) {
			at = e.lines[d[at + i] & 0xff];
			i = (d[at] & 0xff) == 0xf0 ? 2 : 1;
		}
		return new int[] { at, i };
	}

	/** AnalysisPhaseAuto: run the invisible lines (6, 7, b, d, e) at once, stop at anything else. */
	private int analysisAuto(Entry e) {
		e.timer = 0;
		byte[] d = e.t.d;
		int budget = 4096;
		while (e.pc < e.lines.length) {
			if (--budget < 0) {
				// Same bound as analysisOne; the rest runs from the next frame.
				w.notPorted("한 프레임 안의 끝없는 반복(이벤트 " + e.idx + ", 줄 " + e.pc + ")");
				return 0;
			}
			int[] l = line(e, e.pc);
			int op = d[l[0] + l[1] - 1] & 0xff;
			int a = l[0] + l[1];
			int hi = op >> 4;
			switch (op & 0xf) {
				case 6 -> {
					w.notPorted("버프");
					e.pc++;
				}
				case 7 -> {
					if (!item(hi, d, a)) {
						return 3;
					}
					e.pc++;
				}
				case 0xb -> {
					if (hi == 8) {
						return 3;
					}
					save(hi);
					e.pc++;
					if (mode == 2) {
						return analysisOne(e);
					}
				}
				case 0xd -> {
					if (hi == 3) {
						return 0;
					}
					int pc = calcCondition(e, hi, a);
					if (pc < 0) {
						return -pc;
					}
					e.pc = pc;
				}
				case 0xe -> {
					assign(d, a);
					e.pc++;
				}
				default -> {
					return 0;
				}
			}
		}
		return 1;
	}

	/** AnalysisPhaseOne: one frame of lines, as FUN_000291e8 runs them. */
	private int analysisOne(Entry e) {
		e.timer = 0;
		byte[] d = e.t.d;
		int result = 0;
		boolean cont = true;
		// The original has no bound here; a WHILE whose body this port leaves inert would spin the
		// frame forever, so the frame ends after this many lines and the loop resumes next frame.
		int budget = 4096;
		while (e.pc < e.lines.length) {
			int at = e.lines[e.pc];
			if ((d[at] & 0xff) != 0xf0 && !cont) {
				break;
			}
			if (--budget < 0) {
				w.notPorted("한 프레임 안의 끝없는 반복(이벤트 " + e.idx + ", 줄 " + e.pc + ")");
				break;
			}
			cont = false;
			int[] l = line(e, e.pc);
			int op = d[l[0] + l[1] - 1] & 0xff;
			int a = l[0] + l[1];
			int hi = op >> 4;
			int wait = 0;
			boolean advance = true;
			switch (op & 0xf) {
				case 0 -> {
					w.heroStop();
					talk.startTalk(d, a, mode, w.heroName(), w.npcName(), emotes);
					e.pc++;
					return 4;
				}
				case 1 -> {
					int id = u16(d, a);
					if (hi <= 1) {
						int r = execPhase(hi + 2, id, true);
						if (r == 0 || r == 8) {
							e.pc++;
							return result;
						}
						if (r == 3) {
							return 3;
						}
						cont = true;
					} else if (hi == 2) {
						w.heroStop();
						w.questOffer(id);
						// GMenuFuncUp(100) leaves through the opcode-x2 exit: next line, end of frame.
						e.pc++;
						return 0;
					} else if (hi == 3) {
						// No slot holds the quest: the line passes. A slot that is not complete
						// closes the stack; otherwise the reward phase runs, and a reward the bag
						// cannot take (3) brings up GMenu 0x386 and keeps the quest.
						int f = w.questFlags(id);
						if (f >= 0) {
							if ((f & 0xc0) != 0x40) {
								return 3;
							}
							int r = execPhase(QUEST, id, true);
							if (r == 3) {
								w.questBagFull();
								return 3;
							}
							w.questTurnIn(id);
							if (r == 0 || r == 8) {
								e.pc++;
								return result;
							}
							cont = true;
						}
					}
				}
				case 2 -> {
					w.heroStop();
					// Modes 0, 1 and 6 read the grade range (and an option flag) from three bytes;
					// mode 3 ignores its bytes; 4 is the quest board over u8 n, u16 ids. Gamble (2)
					// and the cash shop (5) are not ported.
					if (hi == 0 || hi == 1 || hi == 6) {
						w.openShop(hi, d[a] & 0xff, d[a + 1] & 0xff);
					} else if (hi == 3) {
						w.openShop(3, 0, 0);
					} else if (hi == 4) {
						int[] ids = new int[d[a] & 0xff];
						for (int k = 0; k < ids.length; k++) {
							ids[k] = u16(d, a + 1 + 2 * k);
						}
						w.openBoard(ids);
					} else {
						w.notPorted("GOrder/GMenu 화면(" + Integer.toHexString(op) + ")");
					}
					e.pc++;
					return 0;
				}
				case 3 -> {
					int r = unitCommand(hi, d, a);
					if (mode != 2 && r >= 0) {
						result = 5;
						wait = r;
					}
				}
				case 4 -> {
					if (hi != 0) {
						w.heroStop();
						e.pc++;
						reloadAfter = true;
						w.moveMap("" + (char) (d[a] & 0xff) + String.format("%02d", d[a + 1] & 0xff));
						return 0;
					}
					w.notPorted("저장해 둔 맵으로 복귀");
				}
				case 5 -> {
					wait = screen(hi, d, a);
					result = 5;
				}
				case 6 -> {
					w.notPorted("버프");
					cont = true;
				}
				case 7 -> {
					if (!item(hi, d, a)) {
						return 3;
					}
					cont = true;
				}
				case 8 -> {
					w.heroStop();
					int h = hi;
					if (w.fadeLevel() > 7 || mode != 0) {
						// Black screen or a fast mode: scrolls become jumps (28 -> 18, 58/68 -> 38/48).
						if (h == 2) {
							h = 1;
						} else if (h > 4) {
							h -= 2;
						}
					}
					if (cameraCommand(h, d, a)) {
						result = 5;
					}
				}
				case 9 -> w.notPorted("사운드");
				case 0xa -> {
					w.heroStop();
					int len = d[a] & 0xff;
					if (hi == 0) {
						talk.startNotice(d, a + 1, len);
						e.pc++;
						return 7;
					}
					if (hi != 1) {
						talk.startCaption(d[a] & 0xff, d, a + 2, d[a + 1] & 0xff, mode);
						e.pc++;
						return 6;
					}
					// AddHeadline; the strip shows once the phase stack is empty.
					talk.headline(d, a + 1, len);
				}
				case 0xb -> {
					if (hi == 8) {
						w.notPorted("엔딩");
						return 3;
					}
					save(hi);
					cont = true;
				}
				case 0xc -> {
					w.heroStop();
					if (mode == 0) {
						// The wait goes to the shared counter (wawamain+0x5f38), not the entry.
						shared = (d[a] & 0xff) + 1;
						result = 5;
					}
				}
				case 0xd -> {
					cont = true;
					int pc = calcCondition(e, hi, a);
					if (pc < 0) {
						return -pc;
					}
					advance = false;
					e.pc = pc;
					if (hi == 3) {
						// CalcPhaseCondition 3 builds the dd button labels after the prompt; the phase
						// then waits for a pick (KeyPressPhaseSwitch), fast mode off, keys locked 4 frames.
						labels = new byte[3][];
						for (int m = 2; m <= 4; m++) {
							nextCondition(e, e.pc, m);
						}
						int n = labels[1] == null ? 1 : labels[2] == null ? 2 : 3;
						byte[][] shown = new byte[n][];
						for (int i = 0; i < n; i++) {
							shown[i] = labels[i] == null ? new byte[0] : labels[i];
						}
						talk.startChoice(d, a, shown);
						mode = 0;
						shared = 4;
						return 8;
					}
				}
				case 0xe -> {
					assign(d, a);
					cont = true;
				}
				default -> advance = false;
			}
			if (advance) {
				e.pc++;
			}
			if (mode == 2) {
				cont = true;
			}
			e.timer = Math.max(e.timer, wait);
		}
		if (e.pc >= e.lines.length && result == 0) {
			result = 1;
		}
		return result;
	}

	private static int u16(byte[] d, int p) {
		return (d[p] & 0xff) | (d[p + 1] & 0xff) << 8;
	}

	/** A unit id operand: id, or ff id with the phase instance byte as the high byte. */
	private int[] unitId(byte[] d, int p) {
		int b = d[p] & 0xff;
		if (b == 0xff) {
			return new int[] { instanceByte() * 256 + (d[p + 1] & 0xff), 2 };
		}
		return new int[] { b, 1 };
	}

	/** AnalysisPhaseUnit (hi 0..6, c, d) and AnalysisPhaseHero (hi 7..b, e). */
	private int unitCommand(int hi, byte[] d, int a) {
		switch (hi) {
			case 0:
				return w.unitSpawn(d[a] & 0xff, d[a + 1] & 0xff, d[a + 2] & 0xff, d[a + 3] & 0xff);
			case 7:
				w.heroStop();
				w.heroPlace(d[a] & 0xff, d[a + 1] & 0xff, d[a + 2] & 0xff);
				return 0;
			case 8:
				w.heroStop();
				w.heroWalk(d[a] & 0xff, d[a + 1] & 0xff, d[a + 2] & 0xff);
				return 0;
			case 9:
				w.heroStop();
				return w.heroVisible(true, d[a] & 0xff, d[a + 1] & 0xff, d[a + 2] & 0xff);
			case 0xa:
				w.heroStop();
				return w.heroVisible(false, 0xff, 0xff, 8);
			case 0xb:
				w.heroStop();
				return w.heroAct(d[a] & 0xff, d[a + 1] & 0xff, d[a + 2] & 0xff);
			case 0xc, 0xd:
				w.mapObject(d[a] & 0xff, hi == 0xd);
				return 0;
			case 0xe: {
				// e3 alone does not call SetPhaseHeroStop.
				int[] id = unitId(d, a);
				w.heroToUnit(id[0], d[a + id[1]] & 0xff);
				return 0;
			}
			default: {
				int[] id = unitId(d, a);
				int p = a + id[1];
				int x = hi == 2 || hi == 3 || hi == 6 ? d[p] & 0xff : 0;
				int y = hi == 2 || hi == 3 || hi == 6 ? d[p + 1] & 0xff : 0;
				int dir = hi == 2 || hi == 3 ? d[p + 2] & 0xff : 0;
				return w.unit(hi, id[0], x, y, dir);
			}
		}
	}

	/** Opcode lo 5: zoom, emote, fade, damage text, shake, camping; returns frames to wait. */
	private int screen(int hi, byte[] d, int a) {
		switch (hi) {
			case 0, 1, 2:
				w.notPorted("화면 줌");
				return 0;
			case 3: {
				// A black screen skips the emote and its wait.
				if (w.fadeLevel() >= 8) {
					return 0;
				}
				w.heroStop();
				if ((d[a] & 0xff) == 0xfe) {
					w.emote(HERO, d[a + 1] & 0xff);
				} else {
					int[] id = unitId(d, a);
					w.emote(id[0], d[a + id[1]] & 0xff);
				}
				return 0x10;
			}
			case 4:
				w.heroStop();
				return w.fade(d[a] & 0xff, mode);
			case 5:
				w.notPorted("숫자 띄우기");
				return 0;
			case 6:
				w.heroStop();
				w.shake(d[a] & 0xff);
				return 4;
			case 7, 8, 9:
				w.heroStop();
				w.camera(hi, d[a] & 0xff, d[a + 1] & 0xff, 0);
				return 0;
			case 0xa:
				w.camera(hi, 0, 0, 0);
				return 0;
			default:
				return 0;
		}
	}

	/**
	 * Opcode lo 8 (after the black-screen remap): stop, jump/scroll to a cell, follow the hero or a
	 * unit. True when result 5 waits for it: 08, 18, 28, and 58/68 that found their target.
	 */
	private boolean cameraCommand(int hi, byte[] d, int a) {
		if (hi == 0) {
			w.camera(0x10, 0, 0, 0);
			return true;
		}
		if (hi <= 2) {
			w.camera(0x10 + hi, d[a] & 0xff, d[a + 1] & 0xff, 0);
			return true;
		}
		if (hi > 6) {
			return false;
		}
		int target = (d[a] & 0xff) == 0xfe ? HERO : unitId(d, a)[0];
		if (target != HERO && !w.unitExists(target)) {
			// GetPhaseMons found nothing: the camera stays as it is.
			return false;
		}
		w.camera(0x10 + hi, 0, 0, target);
		return hi >= 5;
	}

	/** AnalysisPhaseItem: 07 take, 17 give (3 when the hero cannot take it). */
	private boolean item(int hi, byte[] d, int a) {
		int id = u16(d, a);
		int count = (d[a + 2] & 0xff) + 1;
		if (hi == 0) {
			w.takeItem(id, count);
			return true;
		}
		return w.giveItem(id, count, d[a + 3] & 0xff);
	}

	/** AnalysisPhaseSave(hi). */
	private void save(int hi) {
		switch (hi) {
			case 2 -> noSkip = true;
			case 3 -> noSkip = false;
			case 4 -> w.save();
			case 5 -> mode = 2;
			default -> {
			}
		}
	}

	// ---- CalcPhase / KeyPressPhase / DrawPhase ---------------------------------------------

	/** CalcPhase: once per frame. */
	void calcPhase() {
		int k = stack.size();
		while (true) {
			int i = k - 1;
			if (i < 0) {
				return;
			}
			Entry e = stack.get(i);
			switch (e.result) {
				case 4 -> {
					if (talk.talking()) {
						talk.calcTalk(mode, w.heroName(), w.npcName(), emotes);
						return;
					}
					talk.clear();
				}
				case 6 -> {
					if (talk.talking() && !talk.calcCaption()) {
						return;
					}
					talk.clear();
				}
				case 5 -> {
					if (w.fadeLevel() >= 8) {
						// Black screen: scripted moves land at once; only the shared counter is waited.
						w.arriveAll();
						if (--shared >= 0) {
							return;
						}
					} else {
						shared--;
						e.timer--;
						if (busy(e)) {
							if (mode == 0) {
								return;
							}
							// The fast modes run ProcGame once more and count the frame twice.
							w.extraTick();
							shared--;
							e.timer--;
							if (busy(e)) {
								return;
							}
						}
					}
				}
				case 3 -> {
					abortAll();
					return;
				}
				case 7 -> {
					return;
				}
				case 8 -> {
					// The choice keys wait for the shared counter to run out.
					if (shared != 0) {
						shared--;
					}
					return;
				}
				default -> {
				}
			}
			int r = analysisOne(e);
			if (!stack.contains(e)) {
				return;
			}
			e.result = r;
			if (r == 1 || r == 2) {
				exitPhase(e, r);
				k = i;
			} else {
				// Result 3 is only stored here; the next frame's case 3 closes the stack.
				if (i >= stack.size() - 1) {
					return;
				}
				k = k + 1;
			}
		}
	}

	/** Result 5's wait: the entry's timer, the shared counter or a scripted move still runs. */
	private boolean busy(Entry e) {
		return e.timer >= 0 || shared >= 0 || w.moving();
	}

	/** The DrawPhase half that moves the text box state; with no phase, DrawHeadline's timer. */
	void frame() {
		if (active()) {
			talk.frame(mode, w.heroName(), w.npcName(), emotes);
		} else {
			talk.headlineFrame();
		}
	}

	/** KeyPressPhase: true (key consumed) whenever a phase runs. */
	boolean key(int code) {
		if (!active()) {
			return false;
		}
		Entry e = stack.get(stack.size() - 1);
		// Results 7 and 8 take only their own keys; '#' never reaches them.
		if (e.result == 7) {
			if (talk.key(code, mode, w.heroName(), w.npcName(), emotes)) {
				talk.clear();
				e.result = 0;
			}
			return true;
		}
		if (e.result == 8) {
			int pick = talk.choose(code, shared == 0);
			if (pick >= 0) {
				// KeyPressPhaseSwitch: continue at the picked choice's dd line.
				e.pc = nextCondition(e, e.pc, pick + 2);
				e.result = 0;
				talk.clear();
			}
			return true;
		}
		if (code == Keys.NUMBER_SIGN || code == Keys.F) {
			if (!noSkip) {
				if (mode == 0) {
					mode = 1;
					if (e.result == 6) {
						talk.skipCaption();
					} else if (e.result == 4) {
						// SetTalkState(state == 6 ? 0 : 1), as OK does.
						talk.key(Keys.ENTER, mode, w.heroName(), w.npcName(), emotes);
					}
				} else if (mode == 1) {
					mode = 0;
				}
			}
			return true;
		}
		if (e.result == 4 || e.result == 6) {
			talk.key(code, mode, w.heroName(), w.npcName(), emotes);
		}
		return true;
	}

	/** DrawPhase on the 480x320 overlay (letterbox bars, then the text box), or the headline. */
	void draw(Gfx g) {
		if (!active()) {
			// DrawGamePlay calls DrawHeadline only while no phase runs.
			talk.drawHeadline(g);
			return;
		}
		g.fillRect(0, 0, TalkBox.W, 24, 0xff000000);
		g.fillRect(0, TalkBox.H - 24, TalkBox.W, 24, 0xff000000);
		int r = stack.get(stack.size() - 1).result;
		talk.draw(g, mode, noSkip || r == 7 || r == 8);
	}

	// ---- conditions, parameters, assignment -------------------------------------------------

	/** CalcPhaseCondition: the next pc, or -1 / -2 to end the phase with result 1 / 2. */
	private int calcCondition(Entry e, int hi, int a) {
		byte[] d = e.t.d;
		int pc = e.pc;
		return switch (hi) {
			case 0 -> condition(d, a) != 0 ? pc + 1 : nextCondition(e, pc, 0);
			case 1, 2 -> nextCondition(e, pc, 1);
			case 3 -> pc + 1;
			case 4 -> condition(d, a) != 0 ? pc + 1 : nextCondition(e, pc, 6);
			case 5 -> nextCondition(e, pc, 6);
			case 6, 0xb -> nextCondition(e, pc, 5);
			case 7 -> d[a] & 0xff;
			case 8 -> -1;
			case 9 -> -2;
			default -> pc + 1;
		};
	}

	/**
	 * NextPhaseCondition: walk to the matching ELSE / ENDIF / loop line, or for m 2..4 to choice
	 * m - 1's dd line (writing its "%d.%.*s" button label) or past the choice end.
	 */
	private int nextCondition(Entry e, int pc, int m) {
		byte[] d = e.t.d;
		int a = 0;
		int b = 0;
		int c = 0;
		boolean first = true;
		while (true) {
			if (m != 2) {
				pc += m == 5 ? -1 : 1;
			} else if (!first) {
				// m 2 never steps: the line after 3d must be choice 1, or the original spins forever.
				return e.lines.length;
			}
			first = false;
			if (pc < 0 || pc >= e.lines.length) {
				return e.lines.length;
			}
			int[] l = line(e, pc);
			int op = d[l[0] + l[1] - 1] & 0xff;
			if ((op & 0xf) != 0xd) {
				continue;
			}
			int args = l[0] + l[1];
			switch (op >> 4) {
				case 0 -> a++;
				case 1 -> {
					if (a == 0 && m == 0) {
						return pc + 1;
					}
				}
				case 2 -> {
					if (a == 0 && m == 0 && condition(d, args) != 0) {
						return pc + 1;
					}
				}
				case 3 -> c++;
				case 4 -> {
					if (m == 5) {
						if (b == 0) {
							return pc;
						}
						b--;
					} else {
						b++;
					}
				}
				case 0xa -> {
					if (a == 0) {
						if (m == 0 || m == 1) {
							return pc + 1;
						}
					} else {
						a--;
					}
				}
				case 0xd -> {
					if (c == 0 && m >= 2 && m <= 4 && (d[args] & 0xff) + 1 == m) {
						int len = d[args + 1] & 0xff;
						byte[] label = new byte[len + 2];
						label[0] = (byte) ('0' + m - 1);
						label[1] = '.';
						System.arraycopy(d, args + 2, label, 2, len);
						labels[m - 2] = label;
						return pc;
					}
				}
				case 0xb -> {
					if (m == 5) {
						b++;
					} else if (b != 0) {
						b--;
					} else if (m == 6) {
						return pc + 1;
					}
				}
				case 0xc -> {
					if (c != 0) {
						c--;
					} else if (m >= 2 && m <= 4 || m == 6) {
						return pc + 1;
					}
				}
				default -> {
				}
			}
		}
	}

	/** CheckCondition. */
	int condition(byte[] d, int p) {
		int x = param(d, p);
		int len = EventScript.parameterLen(d, p);
		int op = d[p + len] & 0xff;
		int y = op < 0x12 ? param(d, p + len + 1) : 0;
		return switch (op) {
			case 0 -> x + y;
			case 1 -> x - y;
			case 2 -> x * y;
			case 3 -> y == 0 ? 0 : x / y;
			case 4 -> y == 0 ? 0 : x % y;
			case 5 -> x > y ? 1 : 0;
			case 6 -> x >= y ? 1 : 0;
			case 7 -> x < y ? 1 : 0;
			case 8 -> x <= y ? 1 : 0;
			case 9 -> x == y ? 1 : 0;
			case 10 -> x != y ? 1 : 0;
			case 0xb -> x & y;
			case 0xc -> x | y;
			case 0xd -> x ^ y;
			case 0xe -> x << (y & 0xff);
			case 0xf -> x >> (y & 0xff);
			case 0x10 -> x & 1 << (y & 0xff);
			case 0x11 -> (x & 1 << (y & 0xff)) == 0 ? 1 : 0;
			case 0x12 -> x;
			case 0x13 -> x == 0 ? 1 : 0;
			case 0x14, 0x15, 0x16, 0x17 -> w.questCondition(op, x);
			default -> 0;
		};
	}

	/** GetParameterData. */
	int param(byte[] d, int p) {
		int b0 = d[p] & 0xff;
		switch (b0 & 0xf) {
			case 0: {
				int v = b0 >> 4 | (d[p + 1] & 0xff) << 4 | (d[p + 2] & 0xff) << 12;
				return v << 12 >> 12;
			}
			case 1: {
				int hi = (short) u16(d, p + 1);
				int lo = (short) u16(d, p + 3);
				return lo + (int) (Math.random() * (hi - lo + 1));
			}
			case 2:
				return vars.get(b0 >> 4 | (d[p + 1] & 0xff) << 4);
			case 3: {
				int i = d[p + 1] & 0xff;
				return i < 0x14 ? vars.sys[i] : w.systemValue(i);
			}
			case 4:
				return w.heroInt(d[p + 1] & 0xff);
			case 5:
				return w.itemCount(b0 >> 4 | (d[p + 1] & 0xff) << 4);
			case 7:
				return b0 >> 4 | (d[p + 1] & 0xff) << 4;
			default:
				return 0;
		}
	}

	/** CalcPhaseAssignment: target, operator (bit 7: the value is "a op b"), value. */
	private void assign(byte[] d, int p) {
		int kind = d[p] & 0xf;
		int index = kind == 2 ? (d[p] & 0xff) >> 4 | (d[p + 1] & 0xff) << 4 : d[p + 1] & 0xff;
		int op = d[p + 2] & 0xff;
		int q = p + 3;
		int v = param(d, q);
		if ((op & 0x80) != 0) {
			op &= 0x7f;
			int len = EventScript.parameterLen(d, q);
			int bin = d[q + len] & 0xff;
			if (bin < 0xd) {
				int y = param(d, q + len + 1);
				v = switch (bin) {
					case 1 -> v + y;
					case 2 -> v - y;
					case 3 -> v * y;
					case 4 -> y == 0 ? 0 : v / y;
					case 5 -> y == 0 ? 0 : v % y;
					case 6 -> v & y;
					case 7 -> v | y;
					case 8 -> v ^ y;
					case 9 -> v << (y & 0xff);
					case 10 -> v >> (y & 0xff);
					case 0xb -> (v & 1 << (y & 0xff)) != 0 ? 1 : 0;
					case 0xc -> (v & 1 << (y & 0xff)) == 0 ? 1 : 0;
					default -> v;
				};
			} else {
				v = bin == 0xd ? (v != 0 ? 1 : 0) : (v == 0 ? 1 : 0);
			}
		}
		int old = switch (kind) {
			case 2 -> vars.get(index);
			case 3 -> index < 0x14 ? vars.sys[index] : 0;
			default -> w.heroInt(index);
		};
		int nv = switch (op) {
			case 0 -> v;
			case 1 -> old + v;
			case 2 -> old - v;
			case 3 -> old * v;
			case 4 -> v == 0 ? old : old / v;
			case 5 -> v == 0 ? old : old % v;
			case 6 -> old & v;
			case 7 -> old | v;
			default -> old;
		};
		switch (kind) {
			case 2 -> vars.set(index, nv);
			case 3 -> {
				if (index < 0x14) {
					vars.sys[index] = nv;
				}
			}
			default -> w.setHeroInt(index, nv);
		}
	}
}

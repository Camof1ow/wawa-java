package wawa;

/**
 * The phase stack from InitPhase and CalcPhase. InitPhase clears eight slots and CalcPhase
 * walks them from the top down, treating each record as 12 bytes (six shorts) whose byte at
 * offset 3 is the phase opcode. The opcodes seen so far:
 *
 * <ul>
 * <li>{@link #OP_TALK} — start a talk script, then wait for it to finish</li>
 * <li>{@link #OP_WAIT} — decrement a countdown and re-enter</li>
 * <li>{@link #OP_TALK_END} — end the talk script, or wait for it to end</li>
 * </ul>
 *
 * The rectangle fields keep the four shorts CalcGamePlay tests the hero cell against.
 */
public final class PhaseQueue {

	public static final int SLOTS = 8;
	public static final int RECORD_SHORTS = 6;

	public static final int OP_TALK = 0x04;
	public static final int OP_WAIT = 0x05;
	public static final int OP_TALK_END = 0x06;

	/** One queued step: opcode, the four rectangle shorts, and one spare short. */
	public record Step(int opcode, int x1, int y1, int x2, int y2, int extra) {
	}

	private final Step[] slots = new Step[SLOTS];
	private int count;

	public void push(Step s) {
		if (count >= SLOTS) {
			for (int i = 0; i < SLOTS - 1; i++) {
				slots[i] = slots[i + 1];
			}
			count--;
		}
		slots[count++] = s;
	}

	public Step pop() {
		return count == 0 ? null : slots[--count];
	}

	public Step peek() {
		return count == 0 ? null : slots[count - 1];
	}

	public int size() {
		return count;
	}

	public void clear() {
		count = 0;
	}

	/** True when the cell lies inside the queued rectangle, as CalcGamePlay tests it. */
	public static boolean contains(Step s, int x, int y) {
		return s != null && x >= s.x1() && x < s.x2() && y >= s.y1() && y < s.y2();
	}
}

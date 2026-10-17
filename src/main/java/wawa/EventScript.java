package wawa;

import java.io.IOException;

/**
 * Event script ("phase") data as LoadGameMap / LoadGlobalData hold it, with the byte length rules
 * every reader walks it by. Ported from the WIPI build, whose switches decompile whole.
 *
 * <pre>
 * table    u16 count, then per event: header (eventLen) and, unless the header borrows another
 *          event's phase (b0 bit 4), the phase body right behind it (eve/&lt;c&gt;.evk per map,
 *          dat/event.dat for the global table)
 * header   b0: bits 0..3 trigger type, bit 4 shared phase, ref = b0 &gt;&gt; 5 | b1 &lt;&lt; 3;
 *          the trigger parameter starts at +1 (+2 when shared)
 * phase    u8 lines, u8 hdrLen, hdr[hdrLen], then the command lines (phaseOneLen each)
 * command  [f0] op, op = hi &lt;&lt; 4 | lo; f0 runs the line in the same frame as the one before
 * talk     u8 flags, [face L], [face R], [name], [emote [unit]], u8 len, text[len] (EUC-KR)
 * param    b0 &amp; 0xf kind: 0 20-bit signed literal, 1 random range, 2 script variable,
 *          3 system variable, 4 hero integer, 5 item count, 6 unset, 7 12-bit literal
 * </pre>
 */
final class EventScript {

	/**
	 * One event table: the blob, each event's start and its status byte (0 until it has run). The
	 * quest book's table instead lists each quest's reward phase directly (no event headers).
	 */
	static final class Table {
		final byte[] d;
		final int[] offs;
		final byte[] status;
		private final int[] phases;

		Table(byte[] blob) {
			d = blob;
			phases = null;
			int n = blob.length < 2 ? 0 : ResourceFile.u16(blob, 0);
			offs = new int[n];
			status = new byte[n];
			int p = 2;
			for (int i = 0; i < n; i++) {
				offs[i] = p;
				int len = eventLen(blob, p);
				p += len;
				if (eventRef(blob, p - len) < 0) {
					p += phaseLen(blob, p);
				}
			}
		}

		Table(byte[] blob, int[] phases) {
			d = blob;
			offs = phases;
			status = new byte[phases.length];
			this.phases = phases;
		}

		int count() {
			return offs.length;
		}

		/** The trigger type, b0 &amp; 0xf. */
		int type(int i) {
			return d[offs[i]] & 0x0f;
		}

		/** Where the trigger parameter starts: +1, or +2 when the header borrows a phase. */
		int param(int i) {
			return offs[i] + ((d[offs[i]] & 0x10) == 0 ? 1 : 2);
		}

		/** The phase body this event runs: its own, or the referenced event's. */
		int phase(int i) {
			if (phases != null) {
				return phases[i];
			}
			int ref = eventRef(d, offs[i]);
			int p = ref < 0 ? offs[i] : offs[ref];
			return p + eventLen(d, p);
		}
	}

	static final Table EMPTY = new Table(new byte[0]);

	/** The map's local_event table from eve/&lt;letter&gt;.evk, or an empty table when it has none. */
	static Table map(Assets assets, String code) {
		try {
			return new Table(ResourceFile.pick(assets, "assets/eve/" + code.charAt(0) + ".png", code));
		} catch (IOException e) {
			return EMPTY;
		}
	}

	/** globl_event: dat/event.dat, read whole (ReadMapFile type 3). */
	static Table global(Assets assets) throws IOException {
		return new Table(assets.read("assets/dat/event.png"));
	}

	/** GetEventLen's ref: the event whose phase this header borrows, or -1. */
	static int eventRef(byte[] d, int p) {
		int b0 = d[p] & 0xff;
		return (b0 & 0x10) == 0 ? -1 : b0 >> 5 | (d[p + 1] & 0xff) << 3;
	}

	/** GetEventLen: the header length by type b0 &amp; 0x1f. */
	static int eventLen(byte[] d, int p) {
		int t = d[p] & 0x1f;
		return switch (t) {
			case 0 -> 7;
			case 1, 0xc -> 1;
			case 2, 3, 4, 5, 7, 8, 0x11, 0x1c -> 2;
			case 6 -> 5;
			case 0xa, 0x12, 0x13, 0x14, 0x15, 0x17, 0x18 -> 3;
			case 0xb -> 1 + conditionLen(d, p + 1);
			case 0x10 -> 8;
			case 0x16 -> 6;
			case 0x1a -> 4;
			case 0x1b -> 2 + conditionLen(d, p + 2);
			default -> throw new IllegalStateException("event type without a length rule: " + t);
		};
	}

	/** GetPhaseLen: the line count byte, the header and every line. */
	static int phaseLen(byte[] d, int p) {
		int lines = d[p] & 0xff;
		int len = (d[p + 1] & 0xff) + 2;
		for (int i = 0; i < lines; i++) {
			len += phaseOneLen(d, p + len);
		}
		return len;
	}

	/** GetParameterLen. */
	static int parameterLen(byte[] d, int p) {
		int b = d[p] & 0xff;
		return switch (b & 0xf) {
			case 0 -> 3;
			case 1 -> 5;
			case 2, 3, 4, 5, 7 -> 2;
			case 6 -> (b >> 4) == 0 ? 2 : (d[p + 2] & 0xff) + 4;
			default -> 0;
		};
	}

	/** A condition: parameter, operator, and a second parameter when the operator is below 0x12. */
	static int conditionLen(byte[] d, int p) {
		int a = parameterLen(d, p);
		return (d[p + a] & 0xff) < 0x12 ? a + 1 + parameterLen(d, p + a + 1) : a + 1;
	}

	/** GetTalkOneLen. */
	static int talkOneLen(byte[] d, int p) {
		int f = d[p] & 0xff;
		int len = 1;
		if ((f & 0x80) != 0) {
			len++;
		}
		if ((f & 0x40) != 0) {
			len++;
		}
		if ((f & 2) != 0) {
			len++;
		}
		if ((f & 1) != 0) {
			len += (d[p + len] & 0x80) == 0 ? 2 : 1;
		}
		return len + (d[p + len] & 0xff) + 1;
	}

	/** GetPhaseOneLen: one command line, including an f0 prefix. */
	static int phaseOneLen(byte[] d, int p) {
		int op = d[p] & 0xff;
		int i = 1;
		if (op == 0xf0) {
			op = d[p + 1] & 0xff;
			i = 2;
		}
		int hi = op >> 4;
		switch (op & 0xf) {
			case 0xf:
				return i + 1;
			case 0: {
				int n = d[p + i] & 0xff;
				int q = i + 1;
				for (int k = 0; k < n; k++) {
					q += talkOneLen(d, p + q);
				}
				return q;
			}
			case 1:
				return i + 2;
			case 2:
				return hi < 3 ? i + 3 : hi == 4 ? i + 1 + 2 * (d[p + i] & 0xff) : i;
			case 3: {
				if (hi == 0) {
					return i + 4;
				}
				if (hi < 7) {
					int x = (d[p + i] & 0xff) == 0xff ? 2 : 1;
					return i + x + (hi == 6 ? 2 : hi >= 2 && hi <= 4 ? 3 : 0);
				}
				if (hi < 10 || hi == 0xb) {
					return i + 3;
				}
				if (hi == 0xc || hi == 0xd) {
					return i + 1;
				}
				if (hi == 0xe) {
					return i + ((d[p + i] & 0xff) == 0xff ? 2 : 1) + 1;
				}
				return i;
			}
			case 4:
				return hi == 1 ? i + 2 : i;
			case 5:
				if (hi < 3 || hi == 4 || hi == 6) {
					return i + 1;
				}
				if (hi == 3) {
					return i + ((d[p + i] & 0xff) == 0xff ? 3 : 2);
				}
				if (hi == 5) {
					return i + ((d[p + i + 3] & 0xff) == 0xff ? 5 : 4);
				}
				return hi >= 7 && hi <= 9 ? i + 2 : i;
			case 6:
				return hi == 1 || hi == 3 || hi == 5 ? i + 5 : hi == 4 || hi == 6 ? i + 1 : i;
			case 7:
				return hi == 0 ? i + 3 : i + 4;
			case 8:
				if (hi == 0 || hi > 6) {
					return i;
				}
				if (hi < 3) {
					return i + 2;
				}
				return (d[p + i] & 0xff) == 0xff ? i + 2 : i + 1;
			case 9:
				return hi == 0 ? i + 2 : hi == 1 ? i : i + 1;
			case 0xa: {
				int q = hi == 2 ? i + 1 : i;
				return q + (d[p + q] & 0xff) + 1;
			}
			case 0xb:
				return i;
			case 0xc:
				return i + 1;
			case 0xd:
				return switch (hi) {
					case 0, 2, 4 -> i + conditionLen(d, p + i);
					case 3 -> i + (d[p + i] & 0xff) + 1;
					case 7 -> i + 1;
					case 0xd -> i + (d[p + i + 1] & 0xff) + 2;
					default -> i;
				};
			case 0xe: {
				int a = parameterLen(d, p + i);
				int bin = d[p + i + a] & 0xff;
				int q = i + a + 1;
				q += parameterLen(d, p + q);
				if ((bin & 0x80) == 0) {
					return q;
				}
				q++;
				return q + parameterLen(d, p + q);
			}
			default:
				return i;
		}
	}

	/**
	 * The script variables CalcPhaseAssignment writes and GetParameterData kinds 2 and 3 read:
	 * 12-bit indexes into u8 (0..0x13f), 2-bit (0x140..0x33f) and 1-bit (0x340..) stores, and
	 * the 20 system integers (0..9 cleared by FuncMoveMap on every map change).
	 */
	static final class Vars {
		final byte[] u8 = new byte[0x140];
		final byte[] two = new byte[0x80];
		final byte[] one = new byte[0x40];
		final int[] sys = new int[0x14];

		int get(int n) {
			if (n < 0x140) {
				return u8[n] & 0xff;
			}
			if (n < 0x340) {
				int k = n - 0x140;
				return (two[k >> 2] >> ((k & 3) * 2)) & 3;
			}
			int k = n - 0x340;
			return (one[k >> 3] >> (k & 7)) & 1;
		}

		void set(int n, int v) {
			if (n < 0x140) {
				u8[n] = (byte) v;
			} else if (n < 0x340) {
				int k = n - 0x140;
				int s = (k & 3) * 2;
				two[k >> 2] = (byte) (two[k >> 2] & ~(3 << s) | (v & 3) << s);
			} else {
				int k = n - 0x340;
				one[k >> 3] = (byte) (one[k >> 3] & ~(1 << (k & 7)) | (v & 1) << (k & 7));
			}
		}
	}

	private EventScript() {
	}
}

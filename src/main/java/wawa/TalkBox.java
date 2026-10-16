package wawa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The text an event phase shows, on the original 480x320 screen: the dialogue box (phase result
 * 4, InitTalkScript / GetTalkOne / SetTalkState / DrawTalkboxOne / DrawTalkAnimate), the timed
 * caption (result 6, opcode 2a), the notice box (result 7, opcode 0a), the choice prompt (result
 * 8, opcode 3d) and opcode 1a's headline strip.
 *
 * <pre>
 * box      talk/btn_dialogue_base (533x95) at (0, 225); text box (10, 240, 420, 85), line pitch 23,
 *          4 lines visible, 3 per page; Hangul 16 px, space 5, other ASCII 8, '\n' breaks
 * faces    FACE[id - 1] = {group, 0, expr}: expr -1 -&gt; talk/face&lt;g&gt;.png, else face&lt;g&gt;_&lt;expr-1&gt;;
 *          left flipped at (0, 60), right at (320, 60), 160x180
 * name     "\x07 2[name]\x07 0" before the text, shown at once; 1 hero, 2 last NPC, 3.. g_aTalkName
 * colour   "\x07 d": '0' black, else g_anTextColor[d - '1'] (0xBBGGRR00)
 * typing   one Hangul or two ASCII bytes per frame; a full third line scrolls the box by one
 * states   0 face animation (enter = read next record), 2 text start, 3 scroll, 4 typing,
 *          5 page full with more lines, 6 all shown
 * keys     OK: state 6 -&gt; next record, otherwise SetTalkState(1); up/down page by 4 in state 6
 * menus    0a / 3d: DrawGMenuBack (screen black 3/8, imenu frame 2 at x 140), a clix_gmenu text
 *          box in 0x1a2a4300 at a 17 px pitch and 70x20 DrawGMenuButton slots
 * </pre>
 *
 * The original draws with its own 16x15 bitmap font; {@link Gfx#drawText} draws that font, and line
 * breaks follow InitTextboxWidth's advances.
 */
final class TalkBox {

	static final int W = SlotScreen.W;
	static final int H = SlotScreen.H;
	private static final int TALK_NAME = 0xe5b80;
	private static final int TEXT_COLOR = 0xe58f4;
	private static final int FACE = 0xddbc4;
	private static final int BOX_X = 10;
	private static final int BOX_Y = 240;
	private static final int BOX_W = 420;
	private static final int BOX_H = 85;
	private static final int PITCH = 23;
	private static final int VISIBLE = 4;
	private static final int PAGE = 3;
	private static final int ANIM_FRAMES = 4;
	/** DrawTalkAnimate's T1V: below 0 hides a face while it is being swapped. */
	private static final int[] SWAP_VISIBLE = { -1, 3, 2, 1, 0 };

	/** What the box is showing: nothing, dialogue (4), caption (6), notice (7), choice (8) or GMenu 0x74's warning. */
	enum Kind { NONE, TALK, CAPTION, NOTICE, CHOICE, WARNING }

	/** SetTextboxColor of the notice and choice text, also DrawTextOut's button outline. */
	private static final int MENU_TEXT = 0x1a2a4300;
	private static final byte[] CONFIRM = Ksc.encode("확인");
	/** clix_gameui+0x308 (0, 55, 480, 19): the headline strip's top and its height per line. */
	private static final int HEAD_Y = 55;
	private static final int HEAD_LINE = 19;

	/** Who a talk record's emote points at; AddImoticon draws it on the map. */
	interface Emotes {
		void emote(int unitId, int kind);

		/** The phase instance byte the record's high unit byte comes from. */
		int instance();
	}

	private final ElfImage so;
	private final Assets assets;
	private final Img base;
	private final Img fast;
	private final Img slow;
	private final Gsac imenu;
	/** DrawGMenuTitle's title band is a gorder frame. */
	private final Gsac gorder;
	/** GMenu 0x74: its title (gmenu+0x48). */
	private byte[] warnTitle;
	private final Map<String, Img> faces = new HashMap<>();

	Kind kind = Kind.NONE;
	private byte[] d;
	private int pos;
	private int n;
	private int idx;
	private int animLen;
	private int animFrame;
	int state;
	private int quick;
	private int flags;
	private int animL;
	private int animR;
	private int faceL;
	private int faceR;
	private int prevL;
	private int prevR;
	private int textLen;
	/** The assembled text, NUL-terminated, and its wrapped lines as {start, length}. */
	private byte[] text = new byte[1];
	private final List<int[]> lines = new ArrayList<>();
	private int top;
	private int typePos;
	private int typeLine;
	/** How much of the typing line DrawTalkboxOne showed this frame. */
	private int typeShown;
	private boolean scrolling;
	/** Caption (opcode 2a): style byte and the frames it stays once fully shown. */
	private int captionStyle;
	private int captionHold;
	private int captionCount;
	/** Result 8: the button labels and the one under the cursor (gmenu+0xc). */
	private byte[][] choiceLabels = new byte[0][];
	private int cursor;
	/** Opcode 1a: the headline text (NUL-terminated), its lines, frames left and the one queued. */
	private byte[] head;
	private List<int[]> headLines = List.of();
	private int headTimer;
	private byte[] headNext;

	TalkBox(Assets assets, ElfImage so) throws IOException {
		this.so = so;
		this.assets = assets;
		base = ResourceFile.image(assets, "assets/talk/btn_dialogue_base.png");
		fast = ResourceFile.image(assets, "assets/talk/btn_fast_base.png");
		slow = ResourceFile.image(assets, "assets/talk/btn_slow_base.png");
		imenu = Gsac.loadCompanion(assets, "assets/img/imenu.png");
		gorder = Gsac.loadCompanion(assets, "assets/img/gorder.png");
	}

	/** InitTalkScript: n records from d[off + 1]; the first is read at once. */
	void startTalk(byte[] d, int off, int mode, String heroName, String npcName, Emotes emotes) {
		clear();
		kind = Kind.TALK;
		this.d = d;
		n = d[off] & 0xff;
		idx = -1;
		pos = off + 1;
		readNext(mode, heroName, npcName, emotes);
	}

	/** Opcode 2a: style 0 types the text, any other shows it at once and holds len + 24 frames. */
	void startCaption(int style, byte[] d, int off, int len, int mode) {
		clear();
		kind = Kind.CAPTION;
		captionStyle = style;
		captionHold = style != 0 ? len + 0x18 : 0x18;
		n = 1;
		idx = 0;
		if (mode != 0) {
			captionStyle = 1;
			captionHold = 3;
		}
		state = 2;
		// The caption box is the game view, (0, 0, viewW, viewH): lines break at the screen width.
		setText(slice(d, off, len), 0, W);
	}

	/** Opcode 0a: the narration box that waits for OK; clix_gmenu+0x240 breaks lines at 214. */
	void startNotice(byte[] d, int off, int len) {
		clear();
		kind = Kind.NOTICE;
		setText(slice(d, off, len), 0, 214);
	}

	/**
	 * CalcPhaseCondition 3: the question (length byte at off) in clix_gmenu+0x1e8, 200 wide, and
	 * the button labels; the cursor starts on the first.
	 */
	void startChoice(byte[] d, int off, byte[][] labels) {
		clear();
		kind = Kind.CHOICE;
		choiceLabels = labels;
		cursor = 0;
		setText(slice(d, off + 1, d[off] & 0xff), 0, 200);
	}

	/**
	 * InitGMenu 0x74 (style 3, buttons 0x8100500): the title, the text box clix_gmenu+0x30, 215
	 * wide, and Enter / Cancel with the cursor (gmenu+0xc) on Enter.
	 */
	void startWarning(byte[] title, byte[] body, byte[] enter, byte[] cancel) {
		clear();
		kind = Kind.WARNING;
		warnTitle = title;
		choiceLabels = new byte[][] { enter, cancel };
		cursor = 0;
		setText(body, 0, 215);
	}

	/**
	 * KeyPressGMenu with button flag 0x8000000: left / right ('4' / '6') swap the cursor between the
	 * two buttons, OK gives ResultGMenu(cursor == 0) and the back key (here Escape) ResultGMenu(0).
	 * Returns 1 for Enter, 0 for Cancel, -1 while the menu stays.
	 */
	int warningKey(int code) {
		if (code == Keys.LEFT || code == Keys.RIGHT || code == Keys.K4 || code == Keys.K6) {
			cursor ^= 1;
		} else if (ok(code)) {
			return cursor == 0 ? 1 : 0;
		} else if (code == Keys.ESCAPE) {
			return 0;
		}
		return -1;
	}

	/**
	 * KeyPressPhase for result 8: '1'..'3' pick that button and OK / '5' the one under the cursor,
	 * both only when ready (the shared counter ran out); right/down/'6' and left/up/'4' move the
	 * cursor round. Returns the picked index, or -1.
	 */
	int choose(int code, boolean ready) {
		int n = choiceLabels.length;
		if (code >= Keys.K1 && code <= Keys.K3) {
			int i = code - Keys.K1;
			return i < n && ready ? i : -1;
		}
		if (code == Keys.RIGHT || code == Keys.DOWN || code == Keys.K6) {
			cursor = cursor + 1 < n ? cursor + 1 : 0;
		} else if (code == Keys.LEFT || code == Keys.UP || code == Keys.K4) {
			cursor = cursor > 0 ? cursor - 1 : n - 1;
		} else if (ready && ok(code)) {
			return cursor;
		}
		return -1;
	}

	/** AddHeadline: shown at once when none is up, otherwise it replaces the one queued text. */
	void headline(byte[] d, int off, int len) {
		byte[] t = slice(d, off, len);
		if (head == null) {
			showHeadline(t);
		} else {
			headNext = t;
		}
	}

	/** AddHeadlinePure: lines break at 480; 1a sets the talk state to 6, so it shows whole. */
	private void showHeadline(byte[] t) {
		head = Arrays.copyOf(t, t.length + 1);
		headLines = wrap(head, W);
		// The original's timer starts at 0x12 and counts down after each draw (19 frames).
		headTimer = 0x13;
	}

	boolean headlineShown() {
		return head != null;
	}

	/** DrawHeadline's countdown, once per frame while no phase runs; then the queued one shows. */
	void headlineFrame() {
		if (head == null || --headTimer >= 0) {
			return;
		}
		head = null;
		if (headNext != null) {
			showHeadline(headNext);
			headNext = null;
		}
	}

	/** FreeTalkScript. */
	void clear() {
		kind = Kind.NONE;
		state = 0;
		quick = 0;
		animFrame = 0;
		faceL = faceR = prevL = prevR = 0;
		animL = animR = 0;
		flags = 0;
		lines.clear();
		top = 0;
		textLen = 0;
	}

	/** CalcPhase's talk test: records remain. */
	boolean talking() {
		return idx < n;
	}

	/** CalcPhase for result 4: the fast mode advances by itself, state 0 counts animation frames. */
	void calcTalk(int mode, String heroName, String npcName, Emotes emotes) {
		if (mode != 0 && ++quick > 3) {
			quick = 1;
			setState(state == 6 ? 0 : 1, mode, heroName, npcName, emotes);
		}
		if (state == 0) {
			animFrame++;
		}
	}

	/** CalcPhase for result 6: true once the caption has been held long enough. */
	boolean calcCaption() {
		return state == 6 && ++captionCount >= captionHold;
	}

	/** The DrawPhase half that moves state: run once per frame after CalcPhase. */
	void frame(int mode, String heroName, String npcName, Emotes emotes) {
		if (kind == Kind.TALK) {
			int still = (animL == 0 ? 1 : 0) + (animR == 0 ? 1 : 0);
			if (state == 0 && (animLen <= animFrame || still == 2 && (flags & 1) == 0)) {
				setState(textLen == 0 ? 0 : 2, mode, heroName, npcName, emotes);
			}
		}
		if (kind != Kind.TALK && kind != Kind.CAPTION || state < 2) {
			return;
		}
		if (state == 2) {
			boolean instant = kind == Kind.CAPTION ? captionStyle != 0 : quick != 0;
			state = !instant ? 4 : top + PAGE < lines.size() && kind == Kind.TALK ? 5 : 6;
		}
		if (!talking()) {
			return;
		}
		scrolling = false;
		if (state == 3) {
			scrolling = true;
			top++;
			state = 4;
		} else if (state == 4) {
			typeOne();
		}
	}

	/** KeyPressPhase for results 4, 6 and 7; true when a notice is dismissed. */
	boolean key(int code, int mode, String heroName, String npcName, Emotes emotes) {
		boolean ok = ok(code);
		if (kind == Kind.NOTICE) {
			return ok;
		}
		if (kind == Kind.TALK) {
			if (ok) {
				setState(state == 6 ? 0 : 1, mode, heroName, npcName, emotes);
			} else if (state == 6 && (code == Keys.UP || code == Keys.K2)) {
				top = Math.max(0, top - VISIBLE);
			} else if (state == 6 && (code == Keys.DOWN || code == Keys.K8)) {
				if (VISIBLE < lines.size() && top < lines.size() - VISIBLE) {
					top += VISIBLE;
				}
			}
		}
		return false;
	}

	/** The OK key (-5) or '5'. */
	private static boolean ok(int code) {
		return code == Keys.ENTER || code == Keys.SPACE || code == Keys.K5;
	}

	/** Caption '#': jump to its end. */
	void skipCaption() {
		idx = n;
	}

	private void setState(int s, int mode, String heroName, String npcName, Emotes emotes) {
		if (s == 1) {
			if (state == 0) {
				quick = 1;
			} else if (state == 5) {
				top += PAGE;
				if (lines.size() <= top + PAGE) {
					state = 6;
				}
			} else {
				state = top + PAGE < lines.size() ? 5 : 6;
			}
			return;
		}
		state = s;
		if (s == 0) {
			readNext(mode, heroName, npcName, emotes);
		}
	}

	/** GetTalkOne. */
	private void readNext(int mode, String heroName, String npcName, Emotes emotes) {
		quick = 0;
		int i = idx++;
		if (i >= n - 1) {
			return;
		}
		int f = d[pos++] & 0xff;
		flags = f;
		animL = (f & 0x30) >> 4;
		animR = (f & 0xc) >> 2;
		prevL = faceL;
		prevR = faceR;
		if ((f & 0x80) != 0) {
			faceL = d[pos++];
		}
		if ((f & 0x40) != 0) {
			faceR = d[pos++];
		}
		int nameSel = 0;
		if ((f & 2) != 0) {
			nameSel = d[pos++] & 0x7f;
		}
		animLen = ANIM_FRAMES;
		if ((f & 1) != 0) {
			int e = d[pos++] & 0xff;
			int k = e & 0xf;
			if ((e & 0x80) != 0) {
				// k 0xf is the hero's special pose (wawamain+0x59ee = 5), not an icon.
				if (k != 0xf) {
					emotes.emote(-1, k);
				}
			} else {
				int m = d[pos++] & 0xff;
				int id = (e & 0x40) != 0 ? emotes.instance() * 256 + m : m;
				if (k != 0xf) {
					emotes.emote(id, k);
				}
			}
			animLen = 8;
		}
		int len = d[pos++] & 0xff;
		byte[] body = slice(d, pos, len);
		pos += len;
		animFrame = 0;
		typeLine = 0;
		if (mode != 0) {
			animLen = 4;
			animFrame = 4;
			quick = 1;
		}
		textLen = len;
		lines.clear();
		top = 0;
		if (len == 0) {
			return;
		}
		String name = switch (nameSel) {
			case 0 -> null;
			case 1 -> heroName;
			case 2 -> npcName;
			default -> so.cstring(so.u32(TALK_NAME + (nameSel - 3) * 4));
		};
		if (name == null) {
			setText(body, 0, BOX_W);
			return;
		}
		byte[] nb = Ksc.encode(name);
		byte[] t = new byte[nb.length + 6 + body.length];
		t[0] = 7;
		t[1] = '2';
		t[2] = '[';
		System.arraycopy(nb, 0, t, 3, nb.length);
		t[nb.length + 3] = ']';
		t[nb.length + 4] = 7;
		t[nb.length + 5] = '0';
		System.arraycopy(body, 0, t, nb.length + 6, body.length);
		setText(t, nb.length + 6, BOX_W);
	}

	private static byte[] slice(byte[] d, int off, int len) {
		byte[] out = new byte[len];
		System.arraycopy(d, off, out, 0, len);
		return out;
	}

	private void setText(byte[] body, int typed, int width) {
		text = new byte[body.length + 1];
		System.arraycopy(body, 0, text, 0, body.length);
		textLen = body.length;
		lines.clear();
		lines.addAll(wrap(text, width));
		top = 0;
		typePos = typed;
		typeLine = 0;
		captionCount = 0;
	}

	/** InitTextboxWidth's line breaking of NUL-terminated s at width (the first pass's rule). */
	private static List<int[]> wrap(byte[] s, int width) {
		List<int[]> lines = new ArrayList<>();
		int start = 0;
		int line = 0;
		while (s[start] != 0) {
			if (line > 0 && s[start - 1] != '\n' && s[start] == ' ') {
				start++;
			}
			if (line > 0 && s[start - 1] != '\n' && s[start] == '\n') {
				start++;
			}
			if (s[start] == 0) {
				break;
			}
			int w = 0;
			int i = start;
			int lastSpace = start;
			while (w <= width) {
				int c = s[i] & 0xff;
				if (c == 7) {
					i++;
				} else if (c >= 0x80) {
					w += 16;
					i++;
				} else if (c == 0) {
					i++;
					break;
				} else {
					w += advance(c);
					if (c == ' ' || c == '\n') {
						lastSpace = i;
					}
				}
				i++;
			}
			int len = (s[i - 1] & 0xff) >= 0x80 ? i - start - 2 : i - start - 1;
			if (i >= 2 && word(s[i - 2]) && word(s[i - 1])) {
				len = lastSpace + 1 - start;
			}
			boolean skipNl = false;
			for (int k = start; k < start + len; k++) {
				if (s[k] == '\n') {
					len = k - start;
					skipNl = true;
					break;
				}
			}
			lines.add(new int[] { start, len });
			start += len + (skipNl ? 1 : 0);
			line++;
		}
		return lines;
	}

	private static boolean word(byte b) {
		return b >= 0x21 && b <= 0x7e;
	}

	private static int advance(int c) {
		return c == ' ' ? 5 : c == '\n' ? 0 : 8;
	}

	/** DrawTalkboxOne's typing step on the current line. */
	private void typeOne() {
		int cur = top + typeLine;
		if (cur >= lines.size()) {
			state = 6;
			return;
		}
		int[] ln = lines.get(cur);
		int p = typePos;
		int at = ln[0] + p;
		if ((text[at] & 0xff) >= 0x80) {
			p += 2;
		} else if ((text[at + 1] & 0xff) >= 0x80) {
			p += 3;
		} else {
			p += 2;
		}
		typeShown = Math.min(ln[1], p);
		if (typeShown < ln[1]) {
			typePos = typeShown;
			return;
		}
		typePos = 0;
		if (cur < lines.size() - 1) {
			if (typeLine == PAGE - 1) {
				state = 3;
			} else {
				typeLine++;
			}
		} else {
			state = 6;
		}
	}

	/** DrawPhase for the box's kind, on the 480x320 overlay. */
	void draw(Gfx g, int mode, boolean noSkip) {
		switch (kind) {
			case TALK -> drawTalk(g);
			case CAPTION -> drawLines(g, 0, 24, W, H - 48, true, state == 4);
			case NOTICE -> drawNotice(g);
			case CHOICE -> drawChoice(g);
			case WARNING -> drawWarning(g);
			default -> {
			}
		}
		if (!noSkip && mode < 2 && kind != Kind.NOTICE) {
			g.drawImage(mode == 0 ? fast : slow, 400, 0);
		}
	}

	private void drawTalk(Gfx g) {
		g.drawImage(base, 0, 225);
		int f = animFrame;
		int a = Math.min(f, 4);
		int b = Math.max(4 - f, 0);
		for (int side = 0; side < 2; side++) {
			int type = side == 0 ? animL : animR;
			int now = side == 0 ? faceL : faceR;
			int old = side == 0 ? prevL : prevR;
			if (type == 1) {
				if (SWAP_VISIBLE[b] >= 0) {
					face(g, old, side);
				}
				if (SWAP_VISIBLE[a] >= 0) {
					face(g, now, side);
				}
			} else {
				face(g, now, side);
			}
		}
		if (state > 1 && talking() && !lines.isEmpty()) {
			drawLines(g, BOX_X, scrolling ? BOX_Y - 11 : BOX_Y, BOX_W, BOX_H, false, state == 4);
		}
	}

	/** Lines from the top: while typing only up to the typing line, cut at what has been typed. */
	private void drawLines(Gfx g, int x, int y, int w, int h, boolean centre, boolean typing) {
		if (lines.isEmpty()) {
			return;
		}
		int[] clip = g.getClip();
		// DrawTextbox clips the dialogue box (flag 0x80); the caption box has no clip flag.
		if (!typing && kind == Kind.TALK) {
			g.clipRect(BOX_X, BOX_Y, BOX_W, BOX_H);
		}
		int count = Math.min(lines.size() - top, VISIBLE);
		int cy = centre ? y + (h - count * PITCH + (PITCH - 15)) / 2 : y;
		for (int k = 0; k < count; k++) {
			int line = top + k;
			if (typing && line > top + typeLine) {
				break;
			}
			int[] ln = lines.get(line);
			int len = typing && line == top + typeLine ? typeShown : ln[1];
			int lx = centre ? x + (w - width(text, ln[0], ln[1])) / 2 : x;
			// The caption box gets no SetTextboxColor and sits on the faded screen; white is inferred.
			drawBytes(g, text, ln[0], len, lx, cy + k * PITCH, kind == Kind.CAPTION ? 0xffffff00 : 0);
		}
		g.setClip(clip);
	}

	private static int width(byte[] s, int from, int len) {
		int w = 0;
		for (int i = from; i < from + len; i++) {
			int c = s[i] & 0xff;
			if (c == 7) {
				i++;
			} else if (c >= 0x80) {
				w += 16;
				i++;
			} else {
				w += advance(c);
			}
		}
		return w;
	}

	/** DrawString: EUC-KR pairs and ASCII through the original font, "\x07 d" switches colour. */
	private void drawBytes(Gfx g, byte[] s, int from, int len, int x, int y, int defaultColor) {
		int argb = rgb(defaultColor);
		for (int i = from; i < from + len && i < s.length && s[i] != 0; i++) {
			int c = s[i] & 0xff;
			if (c == 7) {
				int digit = s[++i] & 0xff;
				argb = digit == '0' ? rgb(defaultColor) : rgb(so.u32(TEXT_COLOR + (digit - '1') * 4));
			} else if (c >= 0x80) {
				x += g.drawText(s, i, 2, x, y, argb);
				i++;
			} else if (c != '\n') {
				x += g.drawText(s, i, 1, x, y, argb);
			}
		}
	}

	private static int rgb(int bbggrr00) {
		return 0xff000000 | ((bbggrr00 >>> 8) & 0xff) << 16 | ((bbggrr00 >>> 16) & 0xff) << 8 | bbggrr00 >>> 24;
	}

	private void face(Gfx g, int id, int side) {
		if (id - 1 < 0) {
			return;
		}
		int group = so.u8(FACE + (id - 1) * 3);
		int expr = (byte) so.u8(FACE + (id - 1) * 3 + 2);
		String file = expr == -1 ? "talk/face" + group + ".png" : "talk/face" + group + "_" + (expr - 1) + ".png";
		Img img = faces.computeIfAbsent(file, f -> {
			try {
				return ResourceFile.image(assets, "assets/" + f);
			} catch (IOException e) {
				return null;
			}
		});
		if (img == null) {
			return;
		}
		if (side == 0) {
			g.drawImage(img, 0, 60, -img.w, img.h);
		} else {
			g.drawImage(img, 320, 60);
		}
	}

	/**
	 * Result 7: DrawGMenuBack style 1 (panel at y 55 + 0x23), the text box clix_gmenu+0x240
	 * (152, 105, 214, 120) top-aligned with each line centred across, and the confirm button
	 * selected at clix_gmenu+0x248.
	 */
	private void drawNotice(Gfx g) {
		menuBack(g, 90);
		// (120 + 2) / 17 lines fit; DrawTextbox does not clip this box.
		for (int k = 0; k < Math.min(lines.size(), 7); k++) {
			int[] ln = lines.get(k);
			drawBytes(g, text, ln[0], ln[1], 152 + ((214 - width(text, ln[0], ln[1])) >> 1), 105 + 17 * k, MENU_TEXT);
		}
		button(g, CONFIRM, 225, 200, true);
	}

	/**
	 * Result 8: DrawGMenuBack style 2 (panel at y 55), the question in clix_gmenu+0x1e8
	 * (159, 97, 200, 30), which holds (30 + 2) / 17 = 1 line centred both ways, then the buttons
	 * in the style-2 slots clix_gmenu+0x220 + 8p (x 160 + 65p, y 160): one choice takes the middle
	 * slot, two the outer ones, three all.
	 */
	private void drawChoice(Gfx g) {
		menuBack(g, 55);
		if (!lines.isEmpty()) {
			int[] ln = lines.get(0);
			drawBytes(g, text, ln[0], ln[1], 159 + ((200 - width(text, ln[0], ln[1])) >> 1), 97 + ((30 - 17 + 2) >> 1), MENU_TEXT);
		}
		int n = choiceLabels.length;
		for (int i = 0; i < n; i++) {
			int slot = n == 1 ? 1 : n == 2 ? i * 2 : i;
			button(g, choiceLabels[i], 160 + 65 * slot, 160, cursor == i);
		}
	}

	/**
	 * DrawGMenu for 0x74, style 3. DrawGMenuBack: black 3/8 over the screen, imenu frame 1 at
	 * clix_imenu+8 (140, 40). DrawGMenuTitle: gorder frame 17 (clix_gorder+0x98) at clix_gmenu+0x10
	 * (191, 49), 1 px rules in 0x2463a3 over +0x18 (151, 66, 218) and 0x1a2a43 over +0x20 (150, 67,
	 * 220), the title centred in +0x08 (150, 49, 220, 15). Then black 3/8 over +0x28 (145, 75, 225,
	 * 140), the text box +0x30 (150, 80, 215, 130) centred both ways (flag 0x11), and the buttons in
	 * the style-3 slots +0xc0 (160, 220) and +0xd0 (290, 220): Enter lit on cursor 0, Cancel on 1.
	 */
	private void drawWarning(Gfx g) {
		menuBack3(g, warnTitle);
		g.fillRect(145, 75, 225, 140, (3 * 32) << 24);
		int n = Math.min(lines.size(), (130 + 2) / 17);
		int y = 80 + ((130 - 17 * n + 2) >> 1);
		for (int k = 0; k < n; k++) {
			int[] ln = lines.get(k);
			drawBytes(g, text, ln[0], ln[1], 150 + ((215 - width(text, ln[0], ln[1])) >> 1), y + 17 * k, MENU_TEXT);
		}
		button(g, choiceLabels[0], 160, 220, cursor == 0);
		button(g, choiceLabels[1], 290, 220, cursor == 1);
	}

	/** DrawGMenuBack and DrawGMenuTitle for a style-3 GMenu (0x74, the shop's popups). */
	void menuBack3(Gfx g, byte[] title) {
		g.fillRect(0, 0, W, H, (3 * 32) << 24);
		g.drawImage(imenu.frame(1), 140, 40);
		g.drawImage(gorder.frame(17), 191, 49);
		g.fillRect(151, 66, 218, 1, rgb(0x2463a300));
		g.fillRect(150, 67, 220, 1, rgb(MENU_TEXT));
		drawBytes(g, title, 0, title.length, 150 + ((220 - width(title, 0, title.length)) >> 1), 49, MENU_TEXT);
	}

	/** DrawGMenuBack(0, 0, 1): DrawWholeFade (black 3/8 over the screen), then imenu frame 2 at x 140. */
	private void menuBack(Gfx g, int y) {
		g.fillRect(0, 0, W, H, (3 * 32) << 24);
		g.drawImage(imenu.frame(2), 140, y);
	}

	/**
	 * DrawGMenuButton on a 70x20 slot. Selected: black 4/8, a corner-less 1 px black 5/8 ring
	 * around it and white text outlined in {@link #MENU_TEXT}; otherwise black 2/8 and text in
	 * 0xbbe9ff00. The text is centred both ways (flag 0x11).
	 */
	void button(Gfx g, byte[] label, int x, int y, boolean selected) {
		g.fillRect(x, y, 70, 20, ((selected ? 4 : 2) * 32) << 24);
		int tx = x + ((70 - width(label, 0, label.length)) >> 1);
		int ty = y + ((20 - 15) >> 1);
		if (selected) {
			int ring = (5 * 32) << 24;
			g.fillRect(x, y - 1, 70, 1, ring);
			g.fillRect(x, y + 20, 70, 1, ring);
			g.fillRect(x - 1, y, 1, 20, ring);
			g.fillRect(x + 70, y, 1, 20, ring);
			// DrawTextOut's outline is not traced; one pixel all round is assumed.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (dx != 0 || dy != 0) {
						drawBytes(g, label, 0, label.length, tx + dx, ty + dy, MENU_TEXT);
					}
				}
			}
		}
		drawBytes(g, label, 0, label.length, tx, ty, selected ? 0xffffff00 : 0xbbe9ff00);
	}

	/**
	 * DrawHeadline: a black 4/8 strip from y 55, 19 px per line and the screen wide, then the
	 * lines in RGB(232, 232, 232) centred across from y 57 at a 17 px pitch.
	 */
	void drawHeadline(Gfx g) {
		if (head == null) {
			return;
		}
		g.fillRect(0, HEAD_Y, W, HEAD_LINE * headLines.size(), (4 * 32) << 24);
		for (int k = 0; k < headLines.size(); k++) {
			int[] ln = headLines.get(k);
			drawBytes(g, head, ln[0], ln[1], (W - width(head, ln[0], ln[1])) >> 1, HEAD_Y + 2 + 17 * k, 0xe8e8e800);
		}
	}
}

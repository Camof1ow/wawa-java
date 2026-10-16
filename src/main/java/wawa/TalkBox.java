package wawa;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The text an event phase shows, on the original 480x320 screen: the dialogue box (phase result
 * 4, InitTalkScript / GetTalkOne / SetTalkState / DrawTalkboxOne / DrawTalkAnimate), the timed
 * caption (result 6, opcode 2a) and the notice box (result 7, opcode 0a).
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
 * </pre>
 *
 * The original draws with its own 16x15 bitmap font; this port draws each glyph with a Java font at
 * the original advances, so line breaks fall where the game puts them.
 */
final class TalkBox {

	static final int W = SlotScreen.W;
	static final int H = SlotScreen.H;
	private static final Charset KOREAN = Charset.forName("MS949");
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

	/** What the box is showing: nothing, dialogue (4), caption (6) or notice (7). */
	enum Kind { NONE, TALK, CAPTION, NOTICE }

	/** Who a talk record's emote points at; AddImoticon draws it on the map. */
	interface Emotes {
		void emote(int unitId, int kind);

		/** The phase instance byte the record's high unit byte comes from. */
		int instance();
	}

	private final ElfImage so;
	private final Path assets;
	private final BufferedImage base;
	private final BufferedImage fast;
	private final BufferedImage slow;
	private final Gsac imenu;
	private final Map<String, BufferedImage> faces = new HashMap<>();
	private final Font font = new Font("SansSerif", Font.PLAIN, 14);

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
	private int noticeColor;

	TalkBox(Path assets, ElfImage so) throws IOException {
		this.so = so;
		this.assets = assets;
		base = ImageIO.read(assets.resolve("talk/btn_dialogue_base.png").toFile());
		fast = ImageIO.read(assets.resolve("talk/btn_fast_base.png").toFile());
		slow = ImageIO.read(assets.resolve("talk/btn_slow_base.png").toFile());
		imenu = Gsac.loadCompanion(assets.resolve("img/imenu.png"));
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
		setText(slice(d, off, len), 0);
	}

	/** Opcode 0a: the narration box that waits for OK. */
	void startNotice(byte[] d, int off, int len) {
		clear();
		kind = Kind.NOTICE;
		noticeColor = 0x1a2a4300;
		setText(slice(d, off, len), 0);
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
		boolean ok = code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE || code == KeyEvent.VK_5;
		if (kind == Kind.NOTICE) {
			return ok;
		}
		if (kind == Kind.TALK) {
			if (ok) {
				setState(state == 6 ? 0 : 1, mode, heroName, npcName, emotes);
			} else if (state == 6 && (code == KeyEvent.VK_UP || code == KeyEvent.VK_2)) {
				top = Math.max(0, top - VISIBLE);
			} else if (state == 6 && (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_8)) {
				if (VISIBLE < lines.size() && top < lines.size() - VISIBLE) {
					top += VISIBLE;
				}
			}
		}
		return false;
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
			default -> so.cstring(so.u32(TALK_NAME + (nameSel - 3) * 4), KOREAN);
		};
		if (name == null) {
			setText(body, 0);
			return;
		}
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		byte[] nb = name.getBytes(KOREAN);
		b.writeBytes(new byte[] { 7, '2', '[' });
		b.writeBytes(nb);
		b.writeBytes(new byte[] { ']', 7, '0' });
		b.writeBytes(body);
		setText(b.toByteArray(), nb.length + 6);
	}

	private static byte[] slice(byte[] d, int off, int len) {
		byte[] out = new byte[len];
		System.arraycopy(d, off, out, 0, len);
		return out;
	}

	private void setText(byte[] body, int typed) {
		text = new byte[body.length + 1];
		System.arraycopy(body, 0, text, 0, body.length);
		textLen = body.length;
		wrap();
		top = 0;
		typePos = typed;
		typeLine = 0;
		captionCount = 0;
	}

	/** InitTextboxWidth's line breaking at BOX_W (the first pass's rule). */
	private void wrap() {
		lines.clear();
		byte[] s = text;
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
			while (w <= BOX_W) {
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
	void draw(Graphics2D g, int mode, boolean noSkip) {
		switch (kind) {
			case TALK -> drawTalk(g);
			case CAPTION -> drawLines(g, 0, 24, W, H - 48, true, state == 4);
			case NOTICE -> drawNotice(g);
			default -> {
			}
		}
		if (!noSkip && mode < 2 && kind != Kind.NOTICE) {
			g.drawImage(mode == 0 ? fast : slow, 400, 0, null);
		}
	}

	private void drawTalk(Graphics2D g) {
		g.drawImage(base, 0, 225, null);
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
	private void drawLines(Graphics2D g, int x, int y, int w, int h, boolean centre, boolean typing) {
		if (lines.isEmpty()) {
			return;
		}
		Shape clip = g.getClip();
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
			int lx = centre ? x + (w - width(ln[0], ln[1])) / 2 : x;
			// The caption box gets no SetTextboxColor and sits on the faded screen; white is inferred.
			drawBytes(g, ln[0], len, lx, cy + k * PITCH, kind == Kind.CAPTION ? 0xffffff00 : 0);
		}
		g.setClip(clip);
	}

	private int width(int from, int len) {
		int w = 0;
		for (int i = from; i < from + len; i++) {
			int c = text[i] & 0xff;
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

	/** DrawString: EUC-KR pairs 16 px, ASCII 8 (space 5), "\x07 d" switches colour. */
	private void drawBytes(Graphics2D g, int from, int len, int x, int y, int defaultColor) {
		g.setFont(font);
		g.setColor(rgb(defaultColor));
		int asc = g.getFontMetrics().getAscent();
		int baseline = y + Math.min(asc, 15);
		for (int i = from; i < from + len && i < text.length - 1; i++) {
			int c = text[i] & 0xff;
			if (c == 7) {
				int digit = text[++i] & 0xff;
				g.setColor(digit == '0' ? rgb(defaultColor) : rgb(so.u32(TEXT_COLOR + (digit - '1') * 4)));
			} else if (c >= 0x80) {
				g.drawString(new String(text, i, 2, KOREAN), x, baseline);
				x += 16;
				i++;
			} else if (c != '\n') {
				if (c != ' ') {
					g.drawString(String.valueOf((char) c), x, baseline);
				}
				x += advance(c);
			}
		}
	}

	private static Color rgb(int bbggrr00) {
		return new Color((bbggrr00 >>> 8) & 0xff, (bbggrr00 >>> 16) & 0xff, bbggrr00 >>> 24);
	}

	private void face(Graphics2D g, int id, int side) {
		if (id - 1 < 0) {
			return;
		}
		int group = so.u8(FACE + (id - 1) * 3);
		int expr = (byte) so.u8(FACE + (id - 1) * 3 + 2);
		String file = expr == -1 ? "talk/face" + group + ".png" : "talk/face" + group + "_" + (expr - 1) + ".png";
		BufferedImage img = faces.computeIfAbsent(file, f -> {
			try {
				return ImageIO.read(assets.resolve(f).toFile());
			} catch (IOException e) {
				return null;
			}
		});
		if (img == null) {
			return;
		}
		if (side == 0) {
			g.drawImage(img, img.getWidth(), 60, -img.getWidth(), img.getHeight(), null);
		} else {
			g.drawImage(img, 320, 60, null);
		}
	}

	/**
	 * Result 7: DrawGMenuBack style 1, the text box and a confirm button. The panel's rectangle
	 * (DAT_014014c0) is not traced; imenu frame 3 centred on the screen stands in for it.
	 */
	private void drawNotice(Graphics2D g) {
		BufferedImage panel = imenu.frame(3);
		int px = (W - panel.getWidth()) / 2;
		int py = (H - panel.getHeight()) / 2;
		g.drawImage(panel, px, py, null);
		int count = Math.min(lines.size(), VISIBLE);
		int cy = py + (panel.getHeight() - count * 18) / 2;
		for (int k = 0; k < count; k++) {
			int[] ln = lines.get(k);
			drawBytes(g, ln[0], ln[1], px + (panel.getWidth() - width(ln[0], ln[1])) / 2, cy + k * 18, noticeColor);
		}
	}
}

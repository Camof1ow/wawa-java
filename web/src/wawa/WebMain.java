package wawa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.canvas.ImageData;
import org.teavm.jso.dom.events.InputEvent;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.HTMLInputElement;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Browser entry point (TeaVM): takes the player's own {@code wild.apk} from the page (picked once and
 * kept in IndexedDB by index.html), unpacks the files the game reads, and drives {@link GameCore}
 * from a 62 ms interval onto the {@code #screen} canvas.
 * Keys come from the document, typed text from the hidden {@code #ime} input (which also hosts
 * input method composition), and saves go to localStorage.
 */
public final class WebMain {
	private static final String SAVE_PREFIX = "wawa-";

	private static HTMLElement status;
	private static int timer = -1;

	private WebMain() {
	}

	public static void main(String[] args) {
		HTMLDocument doc = Window.current().getDocument();
		status = doc.getElementById("status");
		// index.html has read the player's file into window.wawaApk before calling main.
		ArrayBuffer apk = pageApk();
		status.setInnerText("푸는 중…");
		// Let the status line paint before the unpacking blocks the page.
		Window.setTimeout(() -> start(doc, apk), 0);
	}

	@JSBody(script = "return window.wawaApk;")
	private static native ArrayBuffer pageApk();

	@JSBody(script = "window.wawaReject();")
	private static native void rejectApk();

	private static void start(HTMLDocument doc, ArrayBuffer apk) {
		GameCore game;
		try {
			Map<String, byte[]> files = unpack(new Int8Array(apk).copyToJavaArray());
			// Not Wild Frontier's APK: the page forgets it and asks for the file again.
			if (!files.containsKey("lib/armeabi/libwawa.so") || !files.containsKey("assets/img/map.png")) {
				rejectApk();
				return;
			}
			game = new GameCore(Assets.of(files), new LocalStorage());
		} catch (Exception ex) {
			fail(ex);
			return;
		}
		HTMLCanvasElement canvas = (HTMLCanvasElement) doc.getElementById("screen");
		HTMLInputElement ime = (HTMLInputElement) doc.getElementById("ime");
		CanvasRenderingContext2D ctx = (CanvasRenderingContext2D) canvas.getContext("2d");
		// The game's pixels go to an offscreen W x H canvas; the visible one runs at display resolution
		// so the glyphs, drawn on it with the vector font, stay sharp.
		HTMLCanvasElement pixels = (HTMLCanvasElement) doc.createElement("canvas");
		pixels.setWidth(GameCore.W);
		pixels.setHeight(GameCore.H);
		CanvasRenderingContext2D pctx = (CanvasRenderingContext2D) pixels.getContext("2d");
		ImageData frame = pctx.createImageData(GameCore.W, GameCore.H);
		// ImageData is RGBA bytes; an int view over it takes one little-endian 0xAABBGGRR store per pixel.
		Int32Array rgba = new Int32Array(frame.getData().getBuffer());
		Img img = new Img(GameCore.W, GameCore.H);

		doc.addEventListener("keydown", e -> {
			KeyboardEvent k = (KeyboardEvent) e;
			// While an input method composes, Enter, Backspace and Escape edit the composition.
			boolean composing = k.isComposing() || k.getKeyCode() == 229;
			int code = keyCode(k.getCode(), k.getKey());
			if (code < 0 || composing && (code == Keys.ENTER || code == Keys.BACK_SPACE || code == Keys.ESCAPE)) {
				return;
			}
			// Printable keys keep their default so keypress still follows with the character.
			if (!composing && k.getKey().length() != 1) {
				e.preventDefault();
			}
			game.key(code);
		});
		doc.addEventListener("keyup", e -> {
			KeyboardEvent k = (KeyboardEvent) e;
			int code = keyCode(k.getCode(), k.getKey());
			if (code >= 0) {
				game.keyUp(code);
			}
		});
		// A key held while the tab loses focus never reports its release.
		Window.current().addEventListener("blur", e -> game.releaseKeys());
		ime.addEventListener("keypress", e -> {
			String key = ((KeyboardEvent) e).getKey();
			if (key.length() == 1) {
				game.typed(key.charAt(0));
			}
			e.preventDefault();
		});
		ime.addEventListener("compositionend", e -> {
			String text = ((InputEvent) e).getData();
			for (int i = 0; text != null && i < text.length(); i++) {
				game.typed(text.charAt(i));
			}
			ime.setValue("");
		});
		canvas.addEventListener("mousedown", e -> {
			MouseEvent m = (MouseEvent) e;
			int w = canvas.getClientWidth();
			int h = canvas.getClientHeight();
			if (w > 0 && h > 0) {
				game.click(m.getOffsetX() * GameCore.W / w, m.getOffsetY() * GameCore.H / h);
			}
			e.preventDefault();
			ime.focus();
		});
		ime.focus();

		Runnable frameStep = () -> {
			game.render(new Gfx(img));
			int[] px = img.px;
			for (int i = 0; i < px.length; i++) {
				int p = px[i];
				rgba.set(i, 0xff000000 | (p & 0xff00) | (p >> 16 & 0xff) | (p & 0xff) << 16);
			}
			pctx.putImageData(frame, 0, 0);
			int cw = (int) Math.round(canvas.getClientWidth() * devicePixelRatio());
			int ch = (int) Math.round(canvas.getClientHeight() * devicePixelRatio());
			if (cw > 0 && ch > 0 && (canvas.getWidth() != cw || canvas.getHeight() != ch)) {
				canvas.setWidth(cw);
				canvas.setHeight(ch);
			}
			smoothing(ctx, false);
			ctx.drawImage(pixels, 0, 0, canvas.getWidth(), canvas.getHeight());
			drawText(ctx, img, canvas.getWidth() / (double) GameCore.W);
		};
		frameStep.run();
		status.setInnerText("불러오는 중…");
		// The first frame (the logo) is painted before the rest of the world loads and blocks the page.
		Window.requestAnimationFrame(t -> Window.setTimeout(() -> {
			try {
				game.loadWorld();
			} catch (Exception ex) {
				fail(ex);
				return;
			}
			status.setInnerText("방향키 이동, Enter 확인·대화, F 빨리 넘기기, 1 공격, 2 물약, F5 저장, F9 불러오기");
		}, 0));
		timer = Window.setInterval(() -> {
			try {
				game.tick();
				frameStep.run();
			} catch (RuntimeException ex) {
				fail(ex);
			}
		}, 62);
	}

	/** Draws the frame's glyphs over it, {@code scale} display pixels per canvas pixel. */
	private static void drawText(CanvasRenderingContext2D ctx, Img img, double scale) {
		double size = -1;
		for (Img.Glyph gl : img.text) {
			int a = gl.argb >>> 24;
			if (a == 0) {
				continue;
			}
			double s = Img.Glyph.EM * gl.scale * scale;
			if (s != size) {
				size = s;
				ctx.setFont(s + "px " + Img.Glyph.FONT);
			}
			ctx.setFillStyle("rgba(" + (gl.argb >> 16 & 0xff) + "," + (gl.argb >> 8 & 0xff) + "," + (gl.argb & 0xff)
					+ "," + a / 255.0 + ")");
			double x = gl.x * scale;
			double y = (gl.y + Img.Glyph.BASELINE * gl.scale) * scale;
			// Most glyphs sit wholly inside their clip; only the others pay for a canvas clip.
			if (gl.x >= gl.cx0 && gl.y >= gl.cy0 && gl.x + gl.width() <= gl.cx1 && gl.y + gl.height() <= gl.cy1) {
				ctx.fillText(gl.ch, x, y);
				continue;
			}
			ctx.save();
			ctx.beginPath();
			ctx.rect(gl.cx0 * scale, gl.cy0 * scale, (gl.cx1 - gl.cx0) * scale, (gl.cy1 - gl.cy0) * scale);
			ctx.clip();
			ctx.fillText(gl.ch, x, y);
			ctx.restore();
		}
	}

	@JSBody(script = "return window.devicePixelRatio || 1;")
	private static native double devicePixelRatio();

	@JSBody(params = { "ctx", "on" }, script = "ctx.imageSmoothingEnabled = on;")
	private static native void smoothing(CanvasRenderingContext2D ctx, boolean on);

	/** Unpacks {@code assets/} and the native library (its tables); the rest of the APK is unused. */
	private static Map<String, byte[]> unpack(byte[] apk) throws IOException {
		Map<String, byte[]> files = new HashMap<>();
		byte[] buf = new byte[65536];
		try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(apk))) {
			for (ZipEntry e; (e = z.getNextEntry()) != null;) {
				String name = e.getName();
				if (e.isDirectory() || !name.startsWith("assets/") && !name.equals("lib/armeabi/libwawa.so")) {
					continue;
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				for (int n; (n = z.read(buf)) > 0;) {
					out.write(buf, 0, n);
				}
				files.put(name, out.toByteArray());
			}
		}
		return files;
	}

	/** Maps {@code KeyboardEvent.code} (layout-independent) to {@link Keys}, or -1 for keys the game ignores. */
	static int keyCode(String code, String key) {
		if ("#".equals(key)) {
			return Keys.NUMBER_SIGN;
		}
		if (code.length() == 4 && code.startsWith("Key")) {
			return code.charAt(3);
		}
		if (code.length() == 6 && code.startsWith("Digit")) {
			return code.charAt(5);
		}
		if (code.length() == 7 && code.startsWith("Numpad") && Character.isDigit(code.charAt(6))) {
			return code.charAt(6);
		}
		return switch (code) {
			case "Enter", "NumpadEnter" -> Keys.ENTER;
			case "Space" -> Keys.SPACE;
			case "ArrowLeft" -> Keys.LEFT;
			case "ArrowUp" -> Keys.UP;
			case "ArrowRight" -> Keys.RIGHT;
			case "ArrowDown" -> Keys.DOWN;
			case "Escape" -> Keys.ESCAPE;
			case "Backspace" -> Keys.BACK_SPACE;
			case "Delete" -> Keys.DELETE;
			case "Minus" -> Keys.MINUS;
			case "Equal" -> Keys.EQUALS;
			case "Backquote" -> Keys.BACK_QUOTE;
			case "BracketLeft" -> Keys.OPEN_BRACKET;
			case "F5" -> Keys.F5;
			case "F9" -> Keys.F9;
			default -> -1;
		};
	}

	/** Shows the error and stops the game loop; the console gets the stack trace. */
	private static void fail(Throwable ex) {
		if (timer >= 0) {
			Window.clearInterval(timer);
		}
		status.setInnerText("오류: " + ex);
		ex.printStackTrace();
	}

	/** Slots in localStorage, prefixed because every github.io project of one user shares the origin. */
	private static final class LocalStorage implements Storage {
		private final org.teavm.jso.browser.Storage local = org.teavm.jso.browser.Storage.getLocalStorage();

		@Override
		public String load(String key) {
			return local.getItem(SAVE_PREFIX + key);
		}

		@Override
		public void save(String key, String value) {
			local.setItem(SAVE_PREFIX + key, value);
		}

		@Override
		public void delete(String key) {
			local.removeItem(SAVE_PREFIX + key);
		}
	}
}

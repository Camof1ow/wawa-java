package wawa;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * Desktop (Swing) shell around {@link GameCore}: the window scales the fixed canvas to fit, a
 * Swing timer drives the game, and keys, input method text and clicks are forwarded to it.
 * Usage: {@code Game [wild.apk | assets-dir] [--selftest]}.
 */
public final class Game extends JFrame {

	private final GameCore core;
	/** The frame GameCore draws into; its pixels are the BufferedImage's, so painting copies nothing. */
	private final BufferedImage frame = new BufferedImage(GameCore.W, GameCore.H, BufferedImage.TYPE_INT_ARGB);
	private final Img img = new Img(GameCore.W, GameCore.H,
			((DataBufferInt) frame.getRaster().getDataBuffer()).getData());
	/** Where and how big the canvas last landed in the window, for mapping clicks back. */
	private double viewScale = 1;
	private int viewX;
	private int viewY;

	/**
	 * Input methods need somewhere to anchor their composition window; giving them one makes them
	 * deliver Hangul as committed text (the listener in the constructor).
	 */
	@Override
	public java.awt.im.InputMethodRequests getInputMethodRequests() {
		return new java.awt.im.InputMethodRequests() {
			@Override
			public java.awt.Rectangle getTextLocation(java.awt.font.TextHitInfo offset) {
				java.awt.Point p = getLocationOnScreen();
				return new java.awt.Rectangle(p.x + getWidth() / 2, p.y + getHeight() / 2, 0, 16);
			}

			@Override
			public java.awt.font.TextHitInfo getLocationOffset(int x, int y) {
				return null;
			}

			@Override
			public int getInsertPositionOffset() {
				return 0;
			}

			@Override
			public java.text.AttributedCharacterIterator getCommittedText(int begin, int end,
					java.text.AttributedCharacterIterator.Attribute[] attributes) {
				return new java.text.AttributedString("").getIterator();
			}

			@Override
			public int getCommittedTextLength() {
				return 0;
			}

			@Override
			public java.text.AttributedCharacterIterator cancelLatestCommittedText(
					java.text.AttributedCharacterIterator.Attribute[] attributes) {
				return null;
			}

			@Override
			public java.text.AttributedCharacterIterator getSelectedText(
					java.text.AttributedCharacterIterator.Attribute[] attributes) {
				return new java.text.AttributedString("").getIterator();
			}
		};
	}

	private Game(GameCore core) {
		super("Wawa - wild.apk Java port");
		this.core = core;
		setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		// The game draws on a fixed W x H canvas; the window scales it to whatever size it has,
		// keeping the aspect, so any screen shows all of it.
		javax.swing.JPanel canvas = new javax.swing.JPanel() {
			@Override
			protected void paintComponent(java.awt.Graphics g) {
				super.paintComponent(g);
				core.render(new Gfx(img));
				viewScale = Math.min(getWidth() / (double) GameCore.W, getHeight() / (double) GameCore.H);
				int w = (int) (GameCore.W * viewScale);
				int h = (int) (GameCore.H * viewScale);
				viewX = (getWidth() - w) / 2;
				viewY = (getHeight() - h) / 2;
				g.setColor(java.awt.Color.BLACK);
				g.fillRect(0, 0, getWidth(), getHeight());
				((Graphics2D) g).setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(frame, viewX, viewY, w, h, null);
				Awt.drawText((Graphics2D) g, img, viewX, viewY, viewScale);
			}
		};
		canvas.setPreferredSize(new java.awt.Dimension(GameCore.W, GameCore.H));
		setContentPane(canvas);
		pack();
		// Hangul arrives from an input method as committed text, not as KEY_TYPED events.
		enableInputMethods(true);
		addInputMethodListener(new java.awt.event.InputMethodListener() {
			@Override
			public void inputMethodTextChanged(java.awt.event.InputMethodEvent e) {
				java.text.AttributedCharacterIterator text = e.getText();
				if (text != null) {
					int n = e.getCommittedCharacterCount();
					for (char c = text.first(); n-- > 0 && c != java.text.CharacterIterator.DONE; c = text.next()) {
						core.typed(c);
					}
					repaint();
				}
				e.consume();
			}

			@Override
			public void caretPositionChanged(java.awt.event.InputMethodEvent e) {
			}
		});
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				core.key(e.getKeyCode());
				afterInput();
			}

			@Override
			public void keyTyped(KeyEvent e) {
				core.typed(e.getKeyChar());
				repaint();
			}
		});
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				// Window pixel -> canvas pixel.
				core.click((int) ((e.getX() - getInsets().left - viewX) / viewScale),
						(int) ((e.getY() - getInsets().top - viewY) / viewScale));
				afterInput();
			}
		});

		// CalcGamePlay runs on the native timer, whose delay the game changes (45 07 slows it).
		javax.swing.Timer loop = new javax.swing.Timer(core.delay(), null);
		loop.addActionListener(e -> {
			core.tick();
			loop.setDelay(core.delay());
			repaint();
		});
		loop.start();
	}

	private void afterInput() {
		if (core.quit()) {
			dispose();
		} else {
			repaint();
		}
	}

	public static void main(String[] args) throws Exception {
		Path src = Path.of(args.length > 0 ? args[0] : System.getProperty("wawa.assets", "assets"));
		Assets assets = src.toString().endsWith(".apk") ? new ApkAssets(src) : new DirAssets(src);
		GameCore core = new GameCore(assets, new FileStorage());
		if (args.length > 1 && "--selftest".equals(args[1])) {
			// No window and no timer: the script alone drives the game.
			core.loadWorld();
			Path out = Path.of("out");
			Files.createDirectories(out);
			// Twice the canvas size, so the vector text in the frames is legible.
			core.selfTest((name, frame) -> Files.write(out.resolve(name), Awt.png(Awt.frame(frame, 2))));
			System.exit(0);
		}
		javax.swing.SwingUtilities.invokeLater(() -> {
			Game g = new Game(core);
			g.setLocationRelativeTo(null);
			g.setVisible(true);
		});
		new Thread(() -> {
			try {
				core.loadWorld();
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, "loadWorld").start();
	}
}

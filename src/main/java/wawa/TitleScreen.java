package wawa;

import java.io.IOException;

/**
 * The opening: DrawLogo's three logo screens, then DrawTitle and its Play / Options / Help menu.
 *
 * <pre>
 * logo     white screen (SetColorDraw 0xffffff00), one counter step per frame
 *          0..24   img/logo_01 (130x170) centred
 *          25..49  img/logo_02 (192x56) centred
 *          50..74  img/logo_03 (350x34) centred, img/logo_04 at (W - 150, 0)
 *          75      MainFuncHz -> title
 * title    x = (W - 533) / 2; img/ui_background at (x, 0)
 *          menu closed: img/ui_title at (x, 0), img/touchtostart at (x + 0xba, 0x104) while
 *                       (blink / 6) is even; any key opens the menu on Play
 *          menu open:   DrawFillAlphaRect (0, 70, W, 170) level 2 and (0, 75, W, 160) level 5;
 *                       DrawMenu: menu/menu_deco at (0x82, 0x4f) and (0x82, 0xe0), item i
 *                       menu/menu_i_0 (or _1 when chosen) at ITEM_XY
 *          "Ver. 1.0.0" in white at (x + 2, 2)
 * </pre>
 *
 * KeyPressTitle: up / down move through items 1..3 with wrap, OK picks. Play (1) loads a13 and
 * opens the slot screen; Options (2) is GMenuFuncUp(0x6d), Help (3) GMenuFuncUp(0x6b).
 */
final class TitleScreen {

	private static final int W = SlotScreen.W;
	private static final int H = SlotScreen.H;
	private static final int LOGO_END = 75;
	private static final int TITLE_X = (W - 533) / 2;
	/** DrawMenu's local table: per item {normal x, y, chosen x, y}. */
	private static final int[][] ITEM_XY = { { 0xe6, 0x71, 0xd0, 0x6c }, { 0xdc, 0x92, 0xbb, 0x8f },
		{ 0xe5, 0xb5, 0xce, 0xb2 } };

	private final Img[] logos = new Img[4];
	private final Img background;
	private final Img title;
	private final Img touch;
	private final Img deco;
	private final Img[][] items = new Img[3][2];
	private int logoFrame;
	private int blink;
	/** 0 while the menu is closed, else the chosen item 1..3 (KeyPressTitle's counter). */
	private int menu;

	TitleScreen(Assets assets) throws IOException {
		for (int i = 0; i < logos.length; i++) {
			logos[i] = image(assets, "img/logo_0" + (i + 1) + ".png");
		}
		background = image(assets, "img/ui_background.png");
		title = image(assets, "img/ui_title.png");
		touch = image(assets, "img/touchtostart.png");
		deco = image(assets, "menu/menu_deco.png");
		for (int i = 0; i < items.length; i++) {
			for (int s = 0; s < 2; s++) {
				items[i][s] = image(assets, "menu/menu_" + i + "_" + s + ".png");
			}
		}
	}

	private static Img image(Assets assets, String name) throws IOException {
		return ResourceFile.image(assets, "assets/" + name);
	}

	/** True when Play was chosen and the slot screen should take over. */
	boolean key(int code) {
		if (logoFrame < LOGO_END) {
			return false;
		}
		if (menu == 0) {
			menu = 1;
			return false;
		}
		switch (code) {
			case Keys.UP -> menu = menu == 1 ? 3 : menu - 1;
			case Keys.DOWN -> menu = menu == 3 ? 1 : menu + 1;
			// ponytail: Options and Help open GMenus that are not ported; picking them does nothing.
			case Keys.ENTER, Keys.SPACE -> {
				return menu == 1;
			}
			default -> {
			}
		}
		return false;
	}

	Img render() {
		Img img = new Img(W, H);
		Gfx g = new Gfx(img);
		g.fillRect(0, 0, W, H, 0xff000000);
		if (logoFrame < LOGO_END) {
			drawLogo(g);
			logoFrame++;
		} else {
			drawTitle(g);
		}
		return img;
	}

	private void drawLogo(Gfx g) {
		g.fillRect(0, 0, W, H, 0xffffffff);
		if (logoFrame < 25) {
			g.drawImage(logos[0], (W - 0x82) / 2, (H - 0xaa) / 2);
		} else if (logoFrame < 50) {
			g.drawImage(logos[1], (W - 0xc0) / 2, (H - 0x38) / 2);
		} else {
			g.drawImage(logos[2], (W - 0x15e) / 2, (H - 0x22) / 2);
			g.drawImage(logos[3], W - 0x96, 0);
		}
	}

	private void drawTitle(Gfx g) {
		g.drawImage(background, TITLE_X, 0);
		if (menu == 0) {
			g.drawImage(title, TITLE_X, 0);
			if ((blink++ / 6 & 1) == 0) {
				g.drawImage(touch, TITLE_X + 0xba, 0x104);
			}
		} else {
			// DrawFillAlpha levels 1..7 go through a jump table; level / 8 black is inferred.
			g.fillRect(0, 70, W, 0xaa, (2 * 32) << 24);
			g.fillRect(0, 75, W, 0xa0, (5 * 32) << 24);
			int x = (W - 480) / 2;
			g.drawImage(deco, x + 0x82, 0x4f);
			g.drawImage(deco, x + 0x82, 0xe0);
			for (int i = 0; i < items.length; i++) {
				boolean chosen = i == menu - 1;
				g.drawImage(items[i][chosen ? 1 : 0], x + ITEM_XY[i][chosen ? 2 : 0], ITEM_XY[i][chosen ? 3 : 1]);
			}
		}
		g.drawString("Ver. 1.0.0", TITLE_X + 2, 2, 0xffffffff);
	}
}

package com.flintclient.client.ui.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.ui.screen.MenuGeometry.Box;
import com.flintclient.client.ui.screen.MenuGeometry.Input;
import com.flintclient.client.ui.screen.MenuGeometry.Part;
import com.flintclient.client.ui.screen.MenuGeometry.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MenuGeometryTest {
	/** Windows from the menu's least size (520 × 240 units) to a large one. */
	private static final int[][] WINDOWS = windows();
	private static final float[] UNITS = {1.0F, 1.6F, 2.0F, 2.7F, 3.7F};

	private static int[][] windows() {
		List<int[]> out = new ArrayList<>();
		for (int w = 520; w <= 1500; w += 61) {
			for (int h = 240; h <= 560; h += 23) {
				out.add(new int[] {w, h});
			}
		}
		out.add(new int[] {520, 240});
		out.add(new int[] {662, 372});
		out.add(new int[] {691, 370});
		return out.toArray(int[][]::new);
	}

	private static Input input(int w, int h, float unit, int footerWidth, int slots) {
		Input in = new Input();
		in.width = w;
		in.height = h;
		in.unit = unit;
		in.logo = s -> Math.round(58 * s);
		in.footer = s -> Math.round(footerWidth * s);
		in.pill = s -> Math.round(62 * s);
		in.toggle = s -> Math.round(71 * s);
		in.footerCap = 2.54F;
		in.footerDescent = 1.08F;
		in.slots = slots;
		in.copyright = (int) Math.ceil(11.0F * 3 / unit) + 3;
		return in;
	}

	// ---------- 100 %: exactly the layout from before menu sizes ----------

	/** The main menu's layout as it was before part sizes (1.0.2's MenuLayout.apply), for comparison. */
	private record Old(int topBarBottom, int logoY, int[][] column, int[] quit, int footerY, int[][] icons, int[] pill, int[] toggle,
		boolean rail, int[] railBox, int[] news, boolean newsShown, int[] rewards, boolean rewardsShown) {
	}

	private static Old old(Input in, int pillW, int toggleW) {
		int width = in.width;
		int height = in.height;
		int marginTop = Math.clamp(Math.round(height * 0.028F), 6, 11);
		int marginSide = Math.clamp(Math.round(width * 0.018F), 6, 12);
		int marginBottom = Math.clamp(Math.round(height * 0.066F), 8, 18);
		int topBarBottom = marginTop + 13;
		int logoGap = 14;
		int buttonGap = 4;
		int storeGap = 6;
		int quitGap = 20;
		int footerGap = 9;
		int available = height - topBarBottom - 6 - marginBottom;
		int total = 15 + logoGap + (5 * 15 + 3 * buttonGap + storeGap) + quitGap + 13 + footerGap;
		if (total > available) {
			logoGap = 8;
			quitGap = 10;
			storeGap = 4;
			buttonGap = 3;
			footerGap = 7;
			total = 15 + logoGap + (5 * 15 + 3 * buttonGap + storeGap) + quitGap + 13 + footerGap;
		}
		int top = Math.round(height * 0.54F - total / 2.0F);
		top = Math.max(top, topBarBottom + 6);
		top = Math.min(top, Math.max(topBarBottom + 6, height - marginBottom - total));
		int columnX = (width - 96) / 2;
		int y = top + 15 + logoGap;
		int[][] column = new int[5][];
		for (int i = 0; i < 5; i++) {
			if (i == 4) {
				y += storeGap - buttonGap;
			}
			column[i] = new int[] {columnX, y, 96, 15};
			y += 15 + buttonGap;
		}
		y += quitGap - buttonGap;
		int[] quit = {(width - 66) / 2, y, 66, 13};
		int footerY = y + 13 + footerGap;
		int x = width - marginSide;
		int[][] icons = new int[4][];
		for (int i = 3; i >= 0; i--) {
			x -= 13;
			icons[i] = new int[] {x, marginTop, 13, 13};
			x -= 3;
		}
		int[] pill = {x - pillW, marginTop, pillW, 13};
		int[] toggle = {marginSide, marginTop, toggleW, 13};
		int railW = 13 + 6;
		int railH = in.slots * 13 + (in.slots - 1) * 4 + 6;
		int railX = Math.clamp(Math.round(width * 0.013F), 4, 8);
		int railY = Math.round(height / 2.0F - railH / 2.0F);
		boolean rail = in.slots > 0 && railX + railW + 8 <= columnX && railY >= topBarBottom + 4 && railY + railH <= height - 4;
		int clearRight = columnX + 96 + 8;
		int cardSide = Math.clamp(Math.round(width * 0.036F), 8, 20);
		int cardBottom = Math.max(Math.clamp(Math.round(height * 0.066F), 8, 18), in.copyright);
		int cardW = Math.min(88, width - cardSide - clearRight);
		int cardY = height - cardBottom - 33;
		int[] news = {width - cardSide - Math.max(cardW, 1), cardY, Math.max(cardW, 1), 33};
		boolean newsShown = cardW >= 64 && cardY >= topBarBottom + 8;
		cardY -= 33 + 5;
		int[] rewards = {width - cardSide - Math.max(cardW, 1), cardY, Math.max(cardW, 1), 33};
		boolean rewardsShown = cardW >= 64 && cardY >= topBarBottom + 8;
		return new Old(topBarBottom, top, column, quit, footerY, icons, pill, toggle, rail, new int[] {railX, railY, railW, railH}, news,
			newsShown, rewards, rewardsShown);
	}

	private static int[] rect(Box box) {
		return new int[] {box.x(), box.y(), box.w(), box.h()};
	}

	@Test
	void atOneHundredPercentEverythingIsWhereItAlwaysWas() {
		for (int footerWidth : new int[] {70, 100, 130}) {
			for (int slots = 0; slots <= 5; slots += 5) {
				for (float unit : UNITS) {
					for (int[] window : WINDOWS) {
						Input in = input(window[0], window[1], unit, footerWidth, slots);
						Result r = MenuGeometry.compute(in);
						Old o = old(in, 62, 71);
						String at = window[0] + "×" + window[1] + " @" + unit + " footer " + footerWidth;
						assertEquals(o.topBarBottom(), r.topBarBottom, at);
						assertEquals(o.logoY(), r.logo.y(), at);
						assertEquals(15, r.logo.h(), at);
						for (int i = 0; i < 5; i++) {
							assertArrayEquals(o.column()[i], rect(r.column[i]), at + " button " + i);
						}
						assertArrayEquals(o.quit(), rect(r.quit), at);
						assertEquals(o.footerY(), r.footerY, at);
						for (int i = 0; i < 4; i++) {
							assertArrayEquals(o.icons()[i], rect(r.icons[i]), at + " icon " + i);
						}
						assertArrayEquals(o.pill(), rect(r.pill), at);
						assertArrayEquals(o.toggle(), rect(r.toggle), at);
						assertEquals(o.rail(), r.railVisible, at);
						assertArrayEquals(o.railBox(), new int[] {r.railX, r.railY, r.railW, r.railH}, at);
						assertEquals(o.newsShown(), r.news.shown(), at);
						assertArrayEquals(o.news(), rect(r.news), at);
						assertEquals(o.rewardsShown(), r.rewards.shown(), at);
						if (o.rewardsShown()) {
							assertArrayEquals(o.rewards(), rect(r.rewards), at);
						}
						for (Part part : Part.values()) {
							assertEquals(1.0F, r.scale(part), at + " " + part);
						}
					}
				}
			}
		}
	}

	@Test
	void minecraftsTitleScreenPlacesItsCardsAsBeforeAtOneHundredPercent() {
		// Minecraft's own title screen keeps its cards right of its button column, wherever that ends.
		for (int[] window : WINDOWS) {
			for (int clearRight = window[0] / 2; clearRight < window[0]; clearRight += 17) {
				int width = window[0];
				int height = window[1];
				int topBarBottom = Math.clamp(Math.round(height * 0.028F), 6, 11) + 13;
				int copyright = 14;
				Box[] cards = MenuGeometry.cards(true, true, 1.0F, 1.0F, 2.7F, width, height, clearRight, topBarBottom, copyright);
				int cardSide = Math.clamp(Math.round(width * 0.036F), 8, 20);
				int cardBottom = Math.max(Math.clamp(Math.round(height * 0.066F), 8, 18), copyright);
				int cardW = Math.min(88, width - cardSide - clearRight);
				int cardY = height - cardBottom - 33;
				String at = width + "×" + height + " clear " + clearRight;
				boolean newsShown = cardW >= 64 && cardY >= topBarBottom + 8;
				assertEquals(newsShown, cards[0].shown(), at);
				assertArrayEquals(new int[] {width - cardSide - Math.max(cardW, 1), cardY, Math.max(cardW, 1), 33}, rect(cards[0]), at);
				cardY -= 38;
				boolean rewardsShown = cardW >= 64 && cardY >= topBarBottom + 8;
				assertEquals(rewardsShown, cards[1].shown(), at);
				if (rewardsShown) {
					assertArrayEquals(new int[] {width - cardSide - cardW, cardY, cardW, 33}, rect(cards[1]), at);
				}
			}
		}
	}

	// ---------- any sizes: nothing overlaps, nothing leaves the screen ----------

	private static List<Box> shown(Result r, Input in) {
		List<Box> boxes = new ArrayList<>();
		boxes.add(r.logo);
		for (Box b : r.column) {
			boxes.add(b);
		}
		boxes.add(r.quit);
		boxes.add(r.footer);
		boxes.add(r.toggle);
		boxes.add(r.pill);
		for (Box b : r.icons) {
			boxes.add(b);
		}
		if (r.news != null && r.news.shown()) {
			boxes.add(r.news);
		}
		if (r.rewards != null && r.rewards.shown()) {
			boxes.add(r.rewards);
		}
		if (r.railVisible) {
			boxes.add(new Box(r.railX, r.railY, r.railW, r.railH, 1.0F, true));
		}
		return boxes;
	}

	private static void assertSound(Result r, Input in, String at) {
		List<Box> boxes = shown(r, in);
		for (Box b : boxes) {
			assertTrue(b.x() >= 0 && b.y() >= 0 && b.right() <= in.width && b.bottom() <= in.height, at + ": off screen " + b);
			assertTrue(b.w() > 0 && b.h() > 0, at + ": empty " + b);
		}
		for (int i = 0; i < boxes.size(); i++) {
			for (int j = i + 1; j < boxes.size(); j++) {
				assertFalse(boxes.get(i).overlaps(boxes.get(j)), at + ": " + boxes.get(i) + " overlaps " + boxes.get(j));
			}
		}
		// Clear room round the parts that hold text: the top bar's two ends, the stack under the bar.
		assertTrue(r.toggle.right() + MenuGeometry.CLEAR <= r.pill.x(), at + ": the switch comes too near the account");
		assertTrue(r.logo.y() >= r.topBarBottom + 6, at + ": the logo under the top bar");
		float floor = MenuGeometry.least(in.unit);
		for (Part part : Part.values()) {
			float asked = Math.max(floor, in.asked[part.ordinal()]);
			float drawn = r.scale(part);
			assertTrue(drawn <= asked + 0.0001F, at + ": " + part + " drawn larger than asked");
			assertTrue(drawn >= Math.min(asked, 1.0F) - 0.0001F, at + ": " + part + " drawn " + drawn + ", under both 100 % and " + asked);
			assertTrue(drawn * in.unit >= 0.999F, at + ": " + part + " under a pixel per unit");
		}
		// The hit areas are the drawn sizes.
		float sb = r.scale(Part.BUTTONS);
		for (Box b : r.column) {
			assertEquals(MenuGeometry.scaled(MenuGeometry.BUTTON_W, sb), b.w(), at);
			assertEquals(MenuGeometry.scaled(MenuGeometry.BUTTON_H, sb), b.h(), at);
			assertEquals(sb, b.scale(), at);
		}
		float st = r.scale(Part.TOP_RIGHT);
		for (Box b : r.icons) {
			assertEquals(MenuGeometry.scaled(MenuGeometry.ICON_BUTTON, st), b.w(), at);
		}
		assertEquals(Math.round(62 * st), r.pill.w(), at);
		assertEquals(Math.round(71 * r.scale(Part.SWITCH)), r.toggle.w(), at);
		for (Box card : new Box[] {r.news, r.rewards}) {
			if (card != null && card.shown()) {
				assertEquals(MenuGeometry.scaled(MenuGeometry.CARD_H, card.scale()), card.h(), at);
				assertTrue(card.w() >= MenuGeometry.scaled(MenuGeometry.CARD_MIN_W, card.scale()) - 1, at + ": a card narrower than its least");
			}
		}
	}

	@Test
	void noSizesOverlapOrLeaveTheScreen() {
		float[] sizes = {0.5F, 0.6F, 0.8F, 1.0F, 1.3F, 1.5F, 1.8F, 2.0F};
		Random random = new Random(1_003);
		for (float unit : UNITS) {
			for (int[] window : WINDOWS) {
				for (int round = 0; round < 60; round++) {
					Input in = input(window[0], window[1], unit, 70 + random.nextInt(70), random.nextInt(6));
					for (Part part : Part.values()) {
						in.size(part, round == 0 ? 2.0F : round == 1 ? 0.5F : sizes[random.nextInt(sizes.length)]);
					}
					in.news = round % 7 != 3;
					assertSound(MenuGeometry.compute(in), in, window[0] + "×" + window[1] + " @" + unit + " round " + round);
				}
			}
		}
	}

	@Test
	void everyPartAtTwiceItsSizeFitsTheSmallestWindow() {
		Input in = input(520, 240, 2.0F, 110, 5);
		for (Part part : Part.values()) {
			in.size(part, 2.0F);
		}
		Result r = MenuGeometry.compute(in);
		assertSound(r, in, "520×240 at 200 %");
		assertEquals(2.0F, r.scale(Part.SWITCH), "the top bar has room for both ends at 200 %");
		assertEquals(2.0F, r.scale(Part.TOP_RIGHT));
		assertTrue(r.scale(Part.BUTTONS) < 2.0F, "the stack gives way in a short window");
		assertTrue(r.scale(Part.BUTTONS) > 1.0F, "but only as far as it must");
	}

	@Test
	void eachPartGrowsAboutItsOwnAnchor() {
		int w = 800;
		int h = 450;
		Input base = input(w, h, 2.7F, 100, 0);
		Result normal = MenuGeometry.compute(base);
		for (float s : new float[] {0.5F, 1.5F, 2.0F}) {
			Input in = input(w, h, 2.7F, 100, 0);
			for (Part part : Part.values()) {
				in.size(part, s);
			}
			Result r = MenuGeometry.compute(in);
			assertSound(r, in, "800×450 at " + s);
			// The logo and the buttons about the middle.
			assertEquals(w / 2.0F, r.logo.x() + r.logo.w() / 2.0F, 1.0F);
			assertEquals(w / 2.0F, r.column[0].x() + r.column[0].w() / 2.0F, 1.0F);
			assertEquals(w / 2.0F, r.footer.x() + r.footer.w() / 2.0F, 1.0F);
			// The switch from its top-left corner, the account and icons from the top-right one.
			assertEquals(normal.toggle.x(), r.toggle.x());
			assertEquals(normal.toggle.y(), r.toggle.y());
			assertEquals(normal.icons[3].right(), r.icons[3].right());
			assertEquals(normal.icons[3].y(), r.icons[3].y());
			// The cards from the bottom-right one.
			assertEquals(normal.news.right(), r.news.right());
			assertEquals(normal.news.bottom(), r.news.bottom());
			assertEquals(s, r.news.scale(), 0.0001F);
			assertEquals(MenuGeometry.scaled(MenuGeometry.CARD_W, s), r.news.w());
		}
	}

	@Test
	void aCardWithoutRoomIsDrawnSmallerThenHides() {
		// 200 % asked, room for a card a little over 1.5 times its least width: drawn at 1.5.
		int width = 600;
		int clearRight = width - 20 - 100;
		Box[] cards = MenuGeometry.cards(true, true, 2.0F, 2.0F, 2.7F, width, 400, clearRight, 24, 14);
		assertTrue(cards[0].shown());
		assertEquals(1.55F, cards[0].scale(), 0.0001F);
		assertTrue(cards[0].w() <= 100);
		assertTrue(cards[1].bottom() <= cards[0].y(), "Daily rewards stays above What's new");
		// Less room than the card's least width at 100 %: hidden, as it always was.
		Box[] none = MenuGeometry.cards(true, true, 2.0F, 2.0F, 2.7F, width, 400, width - 20 - 60, 24, 14);
		assertFalse(none[0].shown());
		assertFalse(none[1].shown());
		// A card asked smaller fits where 100 % wouldn't.
		Box[] small = MenuGeometry.cards(true, false, 0.5F, 1.0F, 2.7F, width, 400, width - 20 - 60, 24, 14);
		assertTrue(small[0].shown());
		assertEquals(0.5F, small[0].scale(), 0.0001F);
		assertEquals(44, small[0].w());
	}

	@Test
	void fittingCapsTheLargestPartsFirst() {
		// Room for a sum of 3.5: the 2.0 and the 1.8 give way to 1.25 together; the 0.5 keeps its size.
		float[] fitted = MenuGeometry.fit(new float[] {2.0F, 1.8F, 0.5F}, 0.5F, s -> s[0] + s[1] + s[2] <= 3.0F);
		assertArrayEquals(new float[] {1.25F, 1.25F, 0.5F}, fitted, 0.0001F);
		assertArrayEquals(new float[] {1.0F, 2.0F}, MenuGeometry.fit(new float[] {1.0F, 2.0F}, 0.5F, s -> true), "fits as asked");
		assertArrayEquals(new float[] {1.0F, 1.0F}, MenuGeometry.fit(new float[] {1.0F, 2.0F}, 0.5F, s -> false), "never under 100 %");
	}

	@Test
	void theSizesAreGameOptionsInThePartsOrder() {
		assertEquals(Part.values().length, GameOptions.MENU_SIZES.size());
		for (IntSetting size : GameOptions.MENU_SIZES) {
			assertTrue(GameOptions.ALL.contains(size), size.id() + " is saved with the game options");
			assertEquals(100, size.defaultValue());
			assertEquals(50, size.min());
			assertEquals(200, size.max());
			assertEquals(10, size.step());
			assertEquals("100%", size.format(100));
		}
		assertNotNull(GameOptions.MENU_SIZE_LOGO);
		assertEquals("menuSizeLogo", GameOptions.MENU_SIZES.get(Part.LOGO.ordinal()).id());
		assertEquals("menuSizeTopRight", GameOptions.MENU_SIZES.get(Part.TOP_RIGHT.ordinal()).id());
	}
}

package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.render.TextLine;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

class NodeTest {
	/** A fixed-size box that asks for its own size. */
	private static final class Box extends Node {
		private final boolean interactive;

		Box(float w, float h, boolean interactive) {
			this.w = w;
			this.h = h;
			this.interactive = interactive;
		}

		@Override
		public boolean interactive() {
			return interactive;
		}

		@Override
		public float prefWidth() {
			return w;
		}

		@Override
		public float prefHeight(float width) {
			return h;
		}
	}

	@Test
	void aDisabledNodesTooltipIsItsTitleAndTheReason() {
		Node item = new Node().tooltip("HUD Editor");
		assertEquals("HUD Editor", item.tooltipLines()[0].text());
		item.setDisabled("Join a world to edit your HUD");
		TextLine[] lines = item.tooltipLines();
		assertEquals(2, lines.length);
		assertEquals("HUD Editor", lines[0].text());
		assertEquals("Join a world to edit your HUD", lines[1].text());
		assertEquals("HUD Editor. Unavailable: Join a world to edit your HUD", item.narration());
		item.setEnabled(true);
		assertEquals(1, item.tooltipLines().length);
		assertNull(item.disabledReason());
		Node bare = new Node();
		bare.setDisabled("Only one layout is left");
		assertEquals("Only one layout is left", bare.tooltipLines()[0].text());
	}

	@Test
	void columnsStackTheirVisibleChildren() {
		Column column = new Column(6).padding(12, 10);
		Box a = column.add(new Box(0, 16, false));
		Box hidden = column.add(new Box(0, 30, false));
		hidden.setVisible(false);
		Box b = column.add(new Box(0, 54, false));
		assertEquals(10 + 16 + 6 + 54 + 10, column.prefHeight(200));
		column.setBounds(100, 50, 200, column.prefHeight(200));
		assertEquals(112, a.x());
		assertEquals(60, a.y());
		assertEquals(176, a.width());
		assertEquals(82, b.y());
	}

	@Test
	void flowsWrapAndCentreEachRow() {
		Flow flow = new Flow(4, 6);
		Box a = flow.add(new Box(60, 16, false));
		Box b = flow.add(new Box(60, 22, false));
		Box c = flow.add(new Box(60, 16, false));
		assertEquals(22 + 6 + 16, flow.prefHeight(130));
		flow.setBounds(0, 0, 130, 44);
		assertEquals(0, a.x());
		assertEquals(3, a.y(), "centred in the 22-unit row");
		assertEquals(64, b.x());
		assertEquals(0, b.y());
		assertEquals(0, c.x());
		assertEquals(28, c.y());
	}

	@Test
	void hitTestingFindsTheTopmostInteractiveNode() {
		Node root = new Node();
		root.setBounds(0, 0, 200, 200);
		Node group = root.add(new Node());
		Box under = group.add(new Box(100, 100, true));
		Box over = group.add(new Box(50, 50, true));
		Box label = group.add(new Box(20, 20, false));
		under.setBounds(0, 0, 100, 100);
		over.setBounds(10, 10, 50, 50);
		label.setBounds(15, 15, 20, 20);
		assertSame(over, root.hit(20, 20), "text over a control doesn't take the click");
		assertSame(under, root.hit(80, 80));
		assertNull(root.hit(150, 150));
		assertSame(label, root.deepest(20, 20), "wheel events start at the deepest node");
		over.setVisible(false);
		assertSame(under, root.hit(20, 20));
	}

	@Test
	void offsetMovesAWholeSubtree() {
		Node parent = new Node();
		Box child = parent.add(new Box(10, 10, false));
		parent.setBounds(0, 0, 50, 50);
		child.setBounds(5, 5, 10, 10);
		parent.offset(0, -20);
		assertEquals(-20, parent.y());
		assertEquals(-15, child.y());
		assertTrue(parent.contains(child));
		assertFalse(child.contains(parent));
		parent.remove(child);
		assertNull(child.parent());
		assertEquals(0, parent.childCount());
	}

	private static KeyEvent key(int key) {
		return new KeyEvent(key, 0, 0);
	}

	@Test
	void aScrollingViewKeepsItsBarStripFreeAndTakesPressesThere() {
		Column column = new Column(0);
		Box row = column.add(new Box(0, 300, true));
		ScrollView view = new ScrollView(column);
		view.setBounds(0, 0, 100, 50);
		assertTrue(view.model().scrollable());
		assertEquals(100 - ScrollView.GUTTER, row.width(), "content narrower by the gutter");
		assertSame(view, view.hit(98, 20), "the bar's strip is the view's");
		assertSame(row, view.hit(50, 20));
		assertNull(view.hit(150, 20), "clipped to the view");

		Column shortColumn = new Column(0);
		Box shortRow = shortColumn.add(new Box(0, 30, true));
		ScrollView still = new ScrollView(shortColumn);
		still.setBounds(0, 0, 100, 50);
		assertFalse(still.model().scrollable());
		assertEquals(100 - ScrollView.GUTTER, shortRow.width(), "the gutter is kept even with nothing to scroll, so rows line up");
		assertSame(shortRow, still.hit(50, 20));
	}

	@Test
	void onlyAFieldThatAsksForEscapeGetsIt() {
		assertFalse(new Node().handlesEscape());
		TextField plain = new TextField("Name", 32);
		assertFalse(plain.handlesEscape(), "Escape closes the dialog or screen as usual");
		assertFalse(plain.keyDown(key(InputConstants.KEY_ESCAPE)));
		int[] cancelled = {0};
		TextField rename = new TextField("Name", 32).onEscape(() -> cancelled[0]++);
		assertTrue(rename.handlesEscape());
		assertTrue(rename.keyDown(key(InputConstants.KEY_ESCAPE)));
		assertEquals(1, cancelled[0]);
	}

	@Test
	void emptyChoicesIgnoreKeysInsteadOfThrowing() {
		int[] changes = {0};
		Segmented segmented = new Segmented(new String[0], 0, index -> changes[0]++);
		segmented.setSelected(2);
		assertTrue(segmented.keyDown(key(InputConstants.KEY_RIGHT)));
		Swatches swatches = new Swatches(new int[0], new String[0], 0, index -> changes[0]++);
		assertTrue(swatches.keyDown(key(InputConstants.KEY_HOME)));
		assertEquals(0, changes[0]);
	}

	@Test
	void unavailableSwatchesSayWhy() {
		Swatches swatches = new Swatches(new int[] {0xF3ECE2}, new String[] {"White"}, 0, null);
		assertEquals("White", swatches.tooltipLines()[0].text());
		swatches.setDisabled("The Classic style uses Minecraft's colours");
		assertEquals("The Classic style uses Minecraft's colours", swatches.tooltipLines()[0].text());
	}
}

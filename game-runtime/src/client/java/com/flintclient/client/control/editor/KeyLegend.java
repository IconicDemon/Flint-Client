package com.flintclient.client.control.editor;

import com.flintclient.client.control.menu.MenuScreen;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;
import net.minecraft.client.input.InputQuirks;

/**
 * The "?" in the editor's bottom-left corner: hovering it (or clicking it, which keeps it open until the next click)
 * shows a card listing the editor's mouse and keyboard controls in two columns. Each line names a control the editor
 * really has. It hides with the other tools (H).
 */
final class KeyLegend extends Pressable {
	static final float SIZE = 16;
	private static final float MARGIN = 8;
	private static final float PAD = 9;
	private static final float LINE = 11;
	private static final float COLUMN_GAP = 16;
	private static final float KEY_GAP = 8;
	private static final float FADE_TAU = 0.05F;

	private final TextLine mark = new TextLine(Type.NAME, "?");
	private final TextLine title = new TextLine(Type.NAME, "Editing the HUD");
	private final TextLine[] keys;
	private final TextLine[] actions;
	private boolean pinned;
	private float shown;

	KeyLegend() {
		super(null);
		setAction(() -> pinned = !pinned);
		name("Editor controls");
		String mod = InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY ? "Cmd" : "Ctrl";
		String[][] lines = {
			{"Drag", "Move a widget"},
			{"Shift+click", "Add to the selection"},
			{"Right-drag", "Select several"},
			{"Scroll", "Resize the widget under the pointer"},
			{"[  ]", "Smaller, larger"},
			{"Space", "Reset the size"},
			{"Alt (hold)", "Move without snapping"},
			{"Arrows", "Move by 1"},
			{mod + "+arrows", "Move by 10"},
			{"Shift+arrows", "Move by half a unit"},
			{"Tab", "Next widget (Shift: previous)"},
			{mod + "+Z", "Undo (Shift: redo)"},
			{"I", "The widget's settings"},
			{"H", "Hide the tools"},
			{"Esc", "Deselect, then save and close"},
			{"", ""}};
		keys = new TextLine[lines.length];
		actions = new TextLine[lines.length];
		for (int i = 0; i < lines.length; i++) {
			keys[i] = new TextLine(Type.META, lines[i][0]);
			actions[i] = new TextLine(Type.META, lines[i][1]);
		}
	}

	@Override
	public String narration() {
		StringBuilder out = new StringBuilder("Editor controls. ");
		for (int i = 0; i < keys.length; i++) {
			if (!keys[i].text().isEmpty()) {
				out.append(keys[i].text()).append(": ").append(actions[i].text()).append(". ");
			}
		}
		return out.toString();
	}

	/** Bottom-left of a screen {@code height} UI units tall. */
	void place(float height) {
		setBounds(MARGIN, height - MARGIN - SIZE, SIZE, SIZE);
	}

	/** Whether the card is showing (to let Escape close it first). */
	boolean open() {
		return pinned;
	}

	void close() {
		pinned = false;
	}

	@Override
	protected void paint(Canvas c) {
		// The last line names the open key, which can be rebound.
		keys[keys.length - 1].set(MenuScreen.openKeyName());
		actions[actions.length - 1].set("Save and close");
		int x0 = px0(c);
		int y0 = py0(c);
		int pw = pxW(c);
		c.roundRect(x0, y0 + c.px(1), pw, pw, pw / 2, 0x30000000);
		c.roundRect(x0, y0, pw, pw, pw / 2, Palette.lerp(0xF0131211, 0xF0201D1A, Math.max(hover, pinned ? 1 : 0)));
		c.ring(x0, y0, pw, pw, pw / 2, 1, pinned ? Palette.withAlpha(Tokens2.EMBER, 0.8F) : Palette.lerp(0x22FFFFFF, 0x40FFFFFF, hover));
		int tw = c.width(mark);
		c.text(mark, x0 + (pw - tw) / 2, c.baselineFor(mark, y0 + pw / 2), Palette.lerp(Tokens2.TEXT_DIM, Tokens2.TEXT, hover));
		Kit.focusRing(c, x, y, w, h, h / 2, focus);
		shown = Kit.approach(shown, pinned || hover > 0.5F || focus > 0.5F ? 1 : 0, FADE_TAU);
	}

	/** The card, over everything else in the editor, above the button. */
	@Override
	protected void paintOver(Canvas c) {
		if (shown < 0.01F) {
			return;
		}
		int half = (keys.length + 1) / 2;
		int keyW = 0;
		int actionW = 0;
		for (int i = 0; i < keys.length; i++) {
			keyW = Math.max(keyW, c.width(keys[i]));
			actionW = Math.max(actionW, c.width(actions[i]));
		}
		int column = keyW + c.px(KEY_GAP) + actionW;
		int cardW = 2 * column + c.px(COLUMN_GAP) + 2 * c.px(PAD);
		int cardH = c.px(PAD) + c.px(LINE + 4) + half * c.px(LINE) + c.px(PAD);
		int left = px0(c);
		int top = py0(c) - c.px(6) - cardH;
		float previous = c.opacity();
		c.setOpacity(previous * shown);
		EditorToolbar.paintCard(c, (float) left / c.scale(), (float) top / c.scale(), (float) cardW / c.scale(), (float) cardH / c.scale(), Tokens2.RADIUS_DECK);
		int tx = left + c.px(PAD);
		int line = top + c.px(PAD) + c.px(LINE) / 2;
		c.text(title, tx, c.baselineFor(title, line), Tokens2.TEXT);
		for (int i = 0; i < keys.length; i++) {
			int col = i < half ? 0 : 1;
			int row = i < half ? i : i - half;
			int cx = tx + col * (column + c.px(COLUMN_GAP));
			int cy = top + c.px(PAD) + c.px(LINE + 4) + row * c.px(LINE) + c.px(LINE) / 2;
			c.text(keys[i], cx, c.baselineFor(keys[i], cy), Tokens2.EMBER_TEXT);
			c.text(actions[i], cx + keyW + c.px(KEY_GAP), c.baselineFor(actions[i], cy), Tokens2.TEXT_DIM);
		}
		c.setOpacity(previous);
	}
}

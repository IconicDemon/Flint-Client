package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import org.jspecify.annotations.Nullable;

/**
 * One setting: its label at the left ({@link Type#LABEL}, dim text), its control at the right, 26 units tall. A
 * description is the label's tooltip (and the control's, unless it has its own), so the default view stays quiet;
 * a setting that needs it explained up front shows it as a second line under the label instead (faint body text).
 * A segmented control that doesn't fit beside the label becomes a cycler ({@link Segmented#cyclerWidth()}); any
 * control that still doesn't fit goes under the label. The control is named after the label for the narrator.
 * Clicking the label of a switch flips it, as a label does.
 */
public class SettingRow extends Node {
	public static final float HEIGHT = 26;
	private static final float LINE_CENTER = HEIGHT / 2;
	private static final float GAP = 10;
	private static final float STACK_GAP = 2;
	private static final float EXPLAIN_TOP = 20;

	private final TextLine label;
	private final @Nullable Node control;
	private final @Nullable Wrap explanation;
	private boolean stacked;

	public SettingRow(String label, @Nullable String description, @Nullable Node control) {
		this(label, description, control, false);
	}

	/** A row whose description, when {@code explain}, is shown under the label rather than as its tooltip. */
	public SettingRow(String label, @Nullable String description, @Nullable Node control, boolean explain) {
		this.label = new TextLine(Type.LABEL, label);
		this.control = control;
		this.explanation = explain && description != null ? new Wrap(Type.NOTE, description) : null;
		if (control != null) {
			add(control);
			if (!control.hasName()) {
				control.name(label);
			}
			if (description != null && control.tooltipText() == null && explanation == null) {
				control.tooltip(label + "\n" + description);
			}
		}
		if (description != null && explanation == null) {
			tooltip(label + "\n" + description);
		}
		this.h = HEIGHT;
	}

	public @Nullable Node control() {
		return control;
	}

	public String label() {
		return label.text();
	}

	/** Takes the pointer over the label, for its tooltip (and a switch's label flips it). */
	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		return button == Kit.LEFT_BUTTON && control instanceof Switch;
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		if (button == Kit.LEFT_BUTTON && containsPoint(mx, my) && control instanceof Switch toggle && toggle.enabled() && toggle.visible()) {
			toggle.activate();
		}
	}

	private float labelWidth() {
		Canvas c = measure();
		return (float) c.width(label) / c.scale();
	}

	/** The control's width beside the label in a row {@code width} wide, or -1 when it has to go under it. */
	private float besideWidth(float width) {
		Node node = control;
		if (node == null) {
			return 0;
		}
		float room = width - labelWidth() - GAP;
		if (node instanceof Segmented segmented) {
			float natural = segmented.prefWidth();
			if (natural <= room) {
				return natural;
			}
			float cycler = segmented.cyclerWidth();
			return cycler <= room ? cycler : -1;
		}
		float wanted = node.prefWidth();
		return wanted <= room ? wanted : -1;
	}

	private static float controlWidthUnder(Node node, float width) {
		if (node instanceof Segmented segmented) {
			return segmented.prefWidth() <= width ? segmented.prefWidth() : Math.min(width, segmented.cyclerWidth());
		}
		return Math.min(width, node.prefWidth());
	}

	/** The explanation's width: beside the control, or the whole row when the control is under it. */
	private float explanationWidth(float width, float beside) {
		return beside > 0 ? Math.max(40, width - beside - GAP) : width;
	}

	private float explanationHeight(float width, float beside) {
		Wrap text = explanation;
		return text == null ? 0 : text.height(measure(), explanationWidth(width, beside)) + 4;
	}

	@Override
	public float prefHeight(float width) {
		Node node = control;
		float beside = node == null || !node.visible() ? 0 : besideWidth(width);
		float explain = explanation == null ? 0 : EXPLAIN_TOP - HEIGHT + 2 + explanationHeight(width, beside);
		if (beside >= 0) {
			return HEIGHT + Math.max(0, explain);
		}
		float cw = controlWidthUnder(node, width);
		return HEIGHT - 4 + Math.max(0, explain) + node.prefHeight(cw) + STACK_GAP + 2;
	}

	@Override
	protected void layout() {
		Node node = control;
		if (node == null) {
			return;
		}
		float cw = besideWidth(w);
		stacked = cw < 0;
		if (stacked) {
			cw = controlWidthUnder(node, w);
			float ch = node.prefHeight(cw);
			float explain = explanation == null ? 0 : EXPLAIN_TOP - HEIGHT + 2 + explanationHeight(w, -1);
			node.setBounds(x + w - cw, y + HEIGHT - 4 + Math.max(0, explain) + STACK_GAP, cw, ch);
		} else {
			float ch = node.prefHeight(cw);
			node.setBounds(x + w - cw, y + Math.round(LINE_CENTER - ch / 2), cw, ch);
		}
	}

	@Override
	protected void paint(Canvas c) {
		Node node = control;
		boolean off = node != null && !node.enabled();
		int center = c.px(y + (stacked && explanation == null ? (HEIGHT - 4) / 2 : LINE_CENTER));
		int max = node == null || stacked ? pxW(c) : c.px(node.x() - GAP / 2) - px0(c);
		int ink = off ? Palette.withAlpha(Tokens2.TEXT_DIM, 0.55F) : Tokens2.TEXT_DIM;
		c.text(label, px0(c), c.baselineFor(label, center), ink, Math.max(0, max));
		Wrap text = explanation;
		if (text != null) {
			float beside = node == null ? 0 : stacked ? -1 : node.width();
			text.paint(c, x, y + EXPLAIN_TOP, explanationWidth(w, beside), Tokens2.faint());
		}
	}
}

package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * An element of a {@link KitScreen}: bounds in UI units, children, and the shared behaviour of the kit's
 * controls (eased hover, press and focus; a tooltip; a disabled state with its reason). Bounds are
 * absolute (screen) coordinates, set by the parent's {@link #layout()}; a {@link ScrollView} moves its
 * content by {@link #offset}. Drawing, hit testing and focus walks use index loops, so a frame allocates
 * nothing. Render thread.
 */
public class Node {
	protected float x;
	protected float y;
	protected float w;
	protected float h;
	/** 0..1, eased each frame: the pointer over it, a press in progress, keyboard focus (ring shown). */
	protected float hover;
	protected float press;
	protected float focus;

	@Nullable Node parent;
	@Nullable KitScreen screen;
	private final ArrayList<Node> children = new ArrayList<>();
	private boolean visible = true;
	/** Drawn but out of reach: no pointer or keyboard focus (a page fading out). */
	private boolean inert;
	private boolean enabled = true;
	private @Nullable String disabledReason;
	private @Nullable String name;
	private @Nullable String tooltip;
	private TextLine @Nullable [] tooltipLines;
	private TextLine @Nullable [] reasonLines;

	// ---------- tree ----------

	public <T extends Node> T add(T child) {
		if (child.parent != null) {
			child.parent.remove(child);
		}
		child.parent = this;
		children.add(child);
		child.attach(screen);
		invalidate();
		return child;
	}

	public void remove(Node child) {
		if (children.remove(child)) {
			child.detach();
			child.parent = null;
			invalidate();
		}
	}

	public void clearChildren() {
		for (int i = 0; i < children.size(); i++) {
			Node child = children.get(i);
			child.detach();
			child.parent = null;
		}
		children.clear();
		invalidate();
	}

	public int childCount() {
		return children.size();
	}

	public Node child(int index) {
		return children.get(index);
	}

	public @Nullable Node parent() {
		return parent;
	}

	public @Nullable KitScreen screen() {
		return screen;
	}

	final void attach(@Nullable KitScreen screen) {
		this.screen = screen;
		for (int i = 0; i < children.size(); i++) {
			children.get(i).attach(screen);
		}
	}

	private void detach() {
		if (screen != null) {
			screen.forget(this);
		}
		attach(null);
	}

	/** True when {@code node} is this node or inside it. */
	public final boolean contains(Node node) {
		for (Node n = node; n != null; n = n.parent) {
			if (n == this) {
				return true;
			}
		}
		return false;
	}

	// ---------- geometry ----------

	public float x() {
		return x;
	}

	public float y() {
		return y;
	}

	public float width() {
		return w;
	}

	public float height() {
		return h;
	}

	public float right() {
		return x + w;
	}

	public float bottom() {
		return y + h;
	}

	/** Places the node (UI units, screen coordinates) and lays out its children. */
	public final void setBounds(float x, float y, float w, float h) {
		this.x = x;
		this.y = y;
		this.w = Math.max(0, w);
		this.h = Math.max(0, h);
		layout();
	}

	/** Positions the children inside the bounds; called after every {@link #setBounds}. */
	protected void layout() {
	}

	/** Moves the node and everything in it (a scroll view scrolling its content). */
	public void offset(float dx, float dy) {
		x += dx;
		y += dy;
		for (int i = 0; i < children.size(); i++) {
			children.get(i).offset(dx, dy);
		}
	}

	/** The height the node wants at {@code width} (columns and scroll views ask). */
	public float prefHeight(float width) {
		return h;
	}

	/** The width the node wants (rows, dialogs and setting rows ask). */
	public float prefWidth() {
		return w;
	}

	public boolean containsPoint(float px, float py) {
		return px >= x && py >= y && px < x + w && py < y + h;
	}

	/** Asks for a new layout before the next frame (the content or a size changed). */
	public void invalidate() {
		if (screen != null) {
			screen.invalidateLayout();
		}
	}

	/** A canvas for measuring text at the screen's interface scale (inside or outside a frame). */
	protected final Canvas measure() {
		return Canvas.measuring(screen != null ? screen.uiScale() : 1);
	}

	// ---------- state ----------

	public boolean visible() {
		return visible;
	}

	public void setVisible(boolean visible) {
		if (this.visible != visible) {
			this.visible = visible;
			if (!visible && screen != null) {
				screen.forget(this);
			}
			invalidate();
		}
	}

	public boolean enabled() {
		return enabled;
	}

	/** True while the node is drawn but takes no input (it and everything in it). */
	public boolean inert() {
		return inert;
	}

	/**
	 * Keeps the node drawn but out of reach of the pointer and the keyboard (a page fading out while the next
	 * one comes in): focus, hover and a capture inside it are dropped.
	 */
	public void setInert(boolean inert) {
		if (this.inert != inert) {
			this.inert = inert;
			if (inert && screen != null) {
				screen.forget(this);
			}
		}
	}

	/** Enables the node (any disabled reason is dropped). */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (enabled) {
			disabledReason = null;
			reasonLines = null;
		}
	}

	/**
	 * Disables the node and says why: the reason is its tooltip, and it stays reachable by keyboard so
	 * that the reason can be read (activating it does nothing).
	 */
	public void setDisabled(String reason) {
		enabled = false;
		if (!reason.equals(disabledReason)) {
			disabledReason = reason;
			reasonLines = reasonLines();
		}
	}

	/** The tooltip while disabled: the tooltip's title (if any), then the reason. */
	private TextLine[] reasonLines() {
		if (tooltipLines == null) {
			return lines(disabledReason);
		}
		TextLine[] out = new TextLine[2];
		out[0] = tooltipLines[0];
		out[1] = new TextLine(Type.META, disabledReason);
		return out;
	}

	public @Nullable String disabledReason() {
		return disabledReason;
	}

	/** The accessible name the narrator says (a setting row gives its control the row's label). */
	public Node name(String name) {
		this.name = name;
		return this;
	}

	/** The accessible name: the one set, else the tooltip's text, else empty. */
	public String accessibleName() {
		return name != null ? name : tooltip != null ? tooltip : "";
	}

	public boolean hasName() {
		return name != null;
	}

	/** Sets the tooltip (lines split on newlines; the first is the title). */
	public Node tooltip(@Nullable String text) {
		if (text == null || text.isEmpty()) {
			tooltip = null;
			tooltipLines = null;
		} else if (!text.equals(tooltip)) {
			tooltip = text;
			tooltipLines = lines(text);
		}
		if (disabledReason != null) {
			reasonLines = reasonLines();
		}
		return this;
	}

	public @Nullable String tooltipText() {
		return tooltip;
	}

	/** The tooltip to show now: the disabled reason (after the tooltip's title, if any) or the tooltip. */
	public TextLine @Nullable [] tooltipLines() {
		if (!enabled && reasonLines != null) {
			return reasonLines;
		}
		return tooltipLines;
	}

	protected static TextLine[] lines(String text) {
		String[] parts = text.split("\n");
		TextLine[] out = new TextLine[parts.length];
		for (int i = 0; i < parts.length; i++) {
			out[i] = new TextLine(Type.META, parts[i]);
		}
		return out;
	}

	/** Takes pointer input (hit testing finds it). Containers and text don't. */
	public boolean interactive() {
		return false;
	}

	/** Takes keyboard focus (a tab stop). */
	public boolean focusable() {
		return false;
	}

	/** Focusable now: shown, and enabled or disabled with a reason to read. */
	public final boolean canFocus() {
		return visible && focusable() && (enabled || disabledReason != null);
	}

	/** Wants text input (charTyped and the IME) while focused. */
	public boolean acceptsText() {
		return false;
	}

	/** Takes every key and mouse button first while focused (a key capture waiting for a key). */
	public boolean capturesKeys() {
		return false;
	}

	/**
	 * Gets Escape in {@link #keyDown} while focused, before the screen uses it to close the top dialog or
	 * itself (a field being renamed cancels the rename); returning false from keyDown passes it on.
	 */
	public boolean handlesEscape() {
		return false;
	}

	/** What the narrator says when the node gains focus: its name and state. */
	public String narration() {
		return withReason(accessibleName());
	}

	/** {@code text}, followed by why the node is unavailable when it is. */
	protected final String withReason(String text) {
		return enabled || disabledReason == null ? text : text + ". Unavailable: " + disabledReason;
	}

	/** The area its tooltip belongs to (the bounds; a row of swatches gives the hovered swatch). */
	protected void tooltipAnchor(float[] out) {
		out[0] = x;
		out[1] = y;
		out[2] = w;
		out[3] = h;
	}

	// ---------- input (UI units) ----------

	/** A press on the node; true starts a pointer capture (drag and release come here until released). */
	public boolean mouseDown(float mx, float my, int button) {
		return false;
	}

	/** The pointer moved while this node holds the capture. */
	public void mouseDrag(float mx, float my) {
	}

	/** The capture ended: the button was released. */
	public void mouseUp(float mx, float my, int button) {
	}

	/** A wheel turn over the node ({@code amount} in notches, positive = up); false passes it to the parent. */
	public boolean scroll(float mx, float my, double amount) {
		return false;
	}

	/** A key while focused; by default Enter and Space activate. */
	public boolean keyDown(KeyEvent event) {
		if (event.isSelection()) {
			if (enabled) {
				press = 1.0F;
				activate();
			}
			return true;
		}
		return false;
	}

	/** While {@link #capturesKeys()}: a mouse button pressed anywhere. */
	public boolean captureMouseButton(int button) {
		return false;
	}

	/** A typed character (only while focused and {@link #acceptsText()}). */
	public boolean charTyped(int codepoint) {
		return false;
	}

	/** The node's action (a click or Enter/Space). */
	public void activate() {
	}

	/** Focus arrived or left. */
	protected void focusChanged(boolean focused) {
	}

	/** Scrolls so {@code descendant} is in view (scroll views); called for each ancestor on keyboard focus. */
	protected void reveal(Node descendant) {
	}

	protected final boolean hovered() {
		return screen != null && screen.hovered() == this;
	}

	protected final boolean focused() {
		return screen != null && screen.focused() == this;
	}

	/** Focused from the keyboard: the focus ring shows (never for mouse users). */
	protected final boolean keyboardFocused() {
		return screen != null && screen.focused() == this && screen.focusVisible();
	}

	/** Pressed and held by the pointer right now. */
	protected final boolean held() {
		return screen != null && screen.captured() == this;
	}

	// ---------- hit testing ----------

	/** Clips hit testing (and drawing) to the bounds: a scroll view. */
	protected boolean clips() {
		return false;
	}

	/** The topmost interactive node at a point, or null. */
	@Nullable Node hit(float px, float py) {
		if (!visible || inert || (clips() && !containsPoint(px, py))) {
			return null;
		}
		for (int i = children.size() - 1; i >= 0; i--) {
			Node found = children.get(i).hit(px, py);
			if (found != null) {
				return found;
			}
		}
		return interactive() && containsPoint(px, py) ? this : null;
	}

	/** The deepest visible node at a point, interactive or not (wheel events bubble up from it). */
	@Nullable Node deepest(float px, float py) {
		if (!visible || inert || !containsPoint(px, py) && clips()) {
			return null;
		}
		for (int i = children.size() - 1; i >= 0; i--) {
			Node found = children.get(i).deepest(px, py);
			if (found != null) {
				return found;
			}
		}
		return containsPoint(px, py) ? this : null;
	}

	/** Adds the focusable nodes in tab order (tree order) to {@code out}. */
	void collectFocusable(List<Node> out) {
		if (!visible || inert) {
			return;
		}
		if (canFocus()) {
			out.add(this);
		}
		for (int i = 0; i < children.size(); i++) {
			children.get(i).collectFocusable(out);
		}
	}

	// ---------- drawing ----------

	/** Eases the states, then draws the node, its children and anything over them. */
	final void paintTree(Canvas c) {
		if (!visible) {
			return;
		}
		hover = Kit.approach(hover, enabled && hovered() ? 1.0F : 0.0F, Motion.HOVER);
		focus = Kit.approach(focus, keyboardFocused() ? 1.0F : 0.0F, Motion.HOVER);
		press = Kit.approach(press, held() && hovered() ? 1.0F : 0.0F, Motion.PRESS);
		paint(c);
		paintChildren(c);
		paintOver(c);
	}

	/** Draws the node itself (bounds via {@link #px0} etc.). */
	protected void paint(Canvas c) {
	}

	protected void paintChildren(Canvas c) {
		for (int i = 0; i < children.size(); i++) {
			children.get(i).paintTree(c);
		}
	}

	/** Draws one child (and everything in it) now: for containers that wrap each child in a fade or an offset. */
	protected static void paintChild(Canvas c, Node child) {
		child.paintTree(c);
	}

	/** Draws over the children (focus rings of containers, scroll bars). */
	protected void paintOver(Canvas c) {
	}

	/** The left edge in pixels. Edges are rounded one by one so neighbours never leave a gap. */
	protected final int px0(Canvas c) {
		return c.px(x);
	}

	protected final int py0(Canvas c) {
		return c.px(y);
	}

	protected final int pxW(Canvas c) {
		return c.px(x + w) - c.px(x);
	}

	protected final int pxH(Canvas c) {
		return c.px(y + h) - c.px(y);
	}
}

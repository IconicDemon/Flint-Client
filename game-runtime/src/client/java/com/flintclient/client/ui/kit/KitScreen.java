package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Backdrop;
import com.flintclient.client.ui.FlintFrame;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A screen drawn entirely by the kit: a tree of {@link Node}s at its own interface scale, with focus,
 * pointer capture, hover, tooltips, modal dialogs and text input handled here once for every control.
 *
 * <p><b>Scale.</b> The interface scale ({@link #uiScale()}, whole pixels per UI unit) comes from the window's
 * own size, not Minecraft's GUI scale (the HUD keeps following that): automatic, moved a step by
 * {@link KitPrefs#uiScaleDelta()}, and limited so the screen's minimum layout fits the window ({@link UiScale}).
 * Mouse positions arrive in GUI units; they are turned into physical pixels exactly as
 * {@code MouseHandler.getScaledXPos} scaled them ({@code × framebuffer width / GUI width}) and then into
 * UI units ({@code ÷ uiScale}). Drawing uses {@code Canvas.begin(graphics, uiScale)}.
 *
 * <p><b>Input.</b> Every mouse handler returns true, so no click or wheel reaches the game behind. A
 * press on a control holds the pointer until that button is released; other buttons meanwhile are
 * swallowed. Keys go first to a focused node that captures keys (a key capture waiting for a key), then:
 * Escape goes to a focused node that {@linkplain Node#handlesEscape() handles it} (a field being renamed),
 * else closes the top dialog or the screen; Tab and Shift+Tab move focus (inside the top dialog while one
 * is open); the focused node gets the key; unused arrows move focus spatially. Focus from the keyboard
 * shows the focus ring and is narrated when Minecraft's narrator is on; focus from a click never shows
 * the ring.
 *
 * <p><b>Text input.</b> While a node that accepts text is focused, Minecraft's text input is started for
 * this screen ({@code Minecraft.onTextInputFocusChange}, as {@code EditBox.setFocused} does), so SDL
 * delivers typed characters ({@link #charTyped}); it stops when focus moves away and when the screen is
 * removed.
 *
 * <p>Subclasses build the tree once ({@link #build}) and place it for the current size
 * ({@link #layoutContent}); resizing keeps the tree, its focus and scroll positions. Kit screens don't
 * pause the game ({@link #isPauseScreen()}). Render thread.
 */
public abstract class KitScreen extends Screen {
	private final Node root = new Node();
	private final ArrayList<Node> modals = new ArrayList<>();
	private final ArrayList<@Nullable Node> modalReturnFocus = new ArrayList<>();
	/** Reused for focus walks (tab order). */
	private final ArrayList<Node> focusOrder = new ArrayList<>();
	private final KitTooltip tooltip = new KitTooltip();
	private final int minWidth;
	private final int minHeight;
	private int uiScale = 1;
	private float uiWidth;
	private float uiHeight;
	private float opacity = 1.0F;
	/** The content's vertical shift in UI units (open and close animations); dialogs and tooltips stay put. */
	private float contentShift;
	private @Nullable Node focused;
	private @Nullable Node hovered;
	private @Nullable Node captured;
	/** The mouse button holding {@link #captured}; only its release ends the capture. */
	private int capturedButton;
	private boolean focusVisible;
	private boolean layoutDirty = true;
	private boolean textInput;
	private boolean built;
	private @Nullable Node hoverTipNode;
	private TextLine @Nullable [] hoverTipLines;
	private long hoverSince;
	private final float[] anchor = new float[4];
	private float mouseX = -1;
	private float mouseY = -1;

	/** {@code minWidth × minHeight}: the smallest layout in UI units, which the interface scale keeps in the window. */
	protected KitScreen(Component title, int minWidth, int minHeight) {
		super(title);
		this.minWidth = minWidth;
		this.minHeight = minHeight;
		root.attach(this);
	}

	/** Adds the screen's nodes to {@code root}; called once, before the first layout. */
	protected abstract void build(Node root);

	/** Places the top-level nodes for a screen of {@code width × height} UI units. */
	protected abstract void layoutContent(float width, float height);

	// ---------- lifecycle ----------

	@Override
	protected final void init() {
		updateScale();
		if (!built) {
			built = true;
			build(root);
		}
		invalidateLayout();
	}

	/** A resize keeps the tree (focus, scroll positions, text being typed); only the scale and layout change. */
	@Override
	protected void repositionElements() {
		updateScale();
		invalidateLayout();
	}

	@Override
	protected void setInitialFocus() {
		// As vanilla: a screen opened from the keyboard starts with its first control focused.
		if (minecraft.getLastInputType().isKeyboard()) {
			layoutIfNeeded();
			setFocus(FocusNav.next(collectFocusable(), null, true), true);
		}
	}

	/** Shown again (returning from another screen): a focused text field takes typing again. */
	@Override
	public void added() {
		textInput = false;
		updateTextInput();
	}

	@Override
	public void removed() {
		captured = null;
		if (textInput) {
			textInput = false;
			minecraft.onTextInputFocusChange(this, false);
		}
	}

	/** Recomputes the interface scale (the preferences or window changed). */
	public void refreshScale() {
		updateScale();
		invalidateLayout();
	}

	private void updateScale() {
		Window window = minecraft.getWindow();
		uiScale = Math.max(1, computeUiScale(window.getWidth(), window.getHeight()));
		uiWidth = window.getWidth() / uiScale;
		uiHeight = window.getHeight() / uiScale;
	}

	/**
	 * The interface scale for a window of {@code windowW × windowH} pixels: by default the kit's automatic scale moved by
	 * the interface size setting and kept so the minimum layout fits ({@link UiScale#compute}). A screen with its own
	 * proportions to keep may choose differently.
	 */
	protected int computeUiScale(int windowW, int windowH) {
		return UiScale.compute(Kit.prefs().uiScaleDelta(), windowW, windowH, minWidth, minHeight);
	}

	public int uiScale() {
		return uiScale;
	}

	public float uiWidth() {
		return uiWidth;
	}

	public float uiHeight() {
		return uiHeight;
	}

	public Node root() {
		return root;
	}

	/** The whole screen's opacity (open and close fades); 1 draws normally. */
	public float opacity() {
		return opacity;
	}

	public void setOpacity(float opacity) {
		this.opacity = Math.clamp(opacity, 0.0F, 1.0F);
	}

	/**
	 * Moves everything but dialogs and tooltips down by {@code units} (snapped to whole pixels), for open
	 * and close animations; pointer input follows the shift, so nothing needs a new layout.
	 */
	public void setContentShift(float units) {
		this.contentShift = units;
	}

	/** The shift in whole pixels, and in UI units after snapping. */
	private int shiftPx() {
		return Math.round(contentShift * uiScale);
	}

	/** A pointer's y in the coordinates of whatever takes input now (the content is shifted; dialogs aren't). */
	private float localY(double guiY) {
		return toUiY(guiY) - (modals.isEmpty() ? (float) shiftPx() / uiScale : 0);
	}

	void invalidateLayout() {
		layoutDirty = true;
	}

	private void layoutIfNeeded() {
		if (!layoutDirty) {
			return;
		}
		layoutDirty = false;
		root.setBounds(0, 0, uiWidth, uiHeight);
		layoutContent(uiWidth, uiHeight);
		for (int i = 0; i < modals.size(); i++) {
			modals.get(i).setBounds(0, 0, uiWidth, uiHeight);
		}
	}

	/** False ignores (and swallows) all input, for example while a closing animation runs. */
	protected boolean acceptsInput() {
		return true;
	}

	// ---------- coordinates ----------

	/** GUI units (from Minecraft's mouse events) to UI units. */
	public float toUiX(double guiX) {
		Window window = minecraft.getWindow();
		return (float) (guiX * window.getWidth() / Math.max(1, window.getGuiScaledWidth()) / uiScale);
	}

	public float toUiY(double guiY) {
		Window window = minecraft.getWindow();
		return (float) (guiY * window.getHeight() / Math.max(1, window.getGuiScaledHeight()) / uiScale);
	}

	/** Where the IME's candidate window goes (the caret), in UI units; from a focused text field's drawing. */
	public void setTextInputArea(float x0, float y0, float x1, float y1) {
		int guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
		float k = (float) uiScale / guiScale;
		minecraft.textInputManager().setTextInputArea(Math.round(x0 * k), Math.round(y0 * k), Math.round(x1 * k), Math.round(y1 * k));
	}

	// ---------- state for nodes ----------

	public @Nullable Node hovered() {
		return hovered;
	}

	public @Nullable Node focused() {
		return focused;
	}

	public @Nullable Node captured() {
		return captured;
	}

	/** The pointer in UI units (updated every frame and on every mouse event). */
	public float mouseX() {
		return mouseX;
	}

	public float mouseY() {
		return mouseY;
	}

	/** True while focus came from the keyboard (the focus ring shows). */
	public boolean focusVisible() {
		return focusVisible;
	}

	/** True while a focused node takes every key (a key capture waiting for a key). */
	public boolean capturingKeys() {
		return focused != null && focused.capturesKeys();
	}

	/** True while a text field has focus (typing must not trigger shortcuts). */
	public boolean typing() {
		return textInput;
	}

	/**
	 * Typing in a field or waiting for a key to bind: Minecraft then leaves keys it would otherwise take
	 * for itself alone ({@code Minecraft.handleGlobalKeyPress} skips the friends key; screenshot and
	 * fullscreen keys still act first, as on Minecraft's own Key Binds screen).
	 */
	@Override
	public boolean isInputCaptured() {
		return textInput || capturingKeys();
	}

	/** Kit screens sit over a live game, as the inventory does; a subclass that should pause overrides this. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Moves focus to {@code node} as a click would (no focus ring). */
	public void focus(@Nullable Node node) {
		setFocus(node, false);
	}

	/** Moves focus to {@code node} as the keyboard would: ring, scrolled into view, narrated. */
	public void focusFromKeyboard(@Nullable Node node) {
		setFocus(node, true);
	}

	private void setFocus(@Nullable Node node, boolean keyboard) {
		focusVisible = keyboard && node != null;
		if (node != focused) {
			Node old = focused;
			focused = node;
			if (old != null) {
				old.focusChanged(false);
			}
			if (node != null) {
				node.focusChanged(true);
			}
			updateTextInput();
			if (node != null && keyboard) {
				narrate(node);
			}
		}
		if (node != null && keyboard) {
			for (Node p = node.parent; p != null; p = p.parent) {
				p.reveal(node);
			}
		}
	}

	private void updateTextInput() {
		boolean wanted = focused != null && focused.acceptsText() && focused.enabled();
		if (wanted != textInput) {
			textInput = wanted;
			minecraft.onTextInputFocusChange(this, wanted);
		}
	}

	/** Narrates {@code node} again (its state changed while focused), when the narrator is on. */
	public void announce(Node node) {
		narrate(node);
	}

	/** Speaks the node's name and state when Minecraft's narrator is on (built only then). */
	private void narrate(Node node) {
		if (minecraft.options.narrator().get().shouldNarrateSystem() && minecraft.getNarrator().isActive()) {
			String text = node.narration();
			if (!text.isEmpty()) {
				minecraft.getNarrator().saySystemNow(text);
			}
		}
	}

	/** The screen's own narration says only its title; the kit narrates focus itself. */
	@Override
	protected boolean shouldNarrateNavigation() {
		return false;
	}

	/** A node was removed or hidden: drop any focus, hover or capture inside it. */
	void forget(Node node) {
		if (focused != null && node.contains(focused)) {
			setFocus(null, false);
		}
		if (hovered != null && node.contains(hovered)) {
			hovered = null;
		}
		if (captured != null && node.contains(captured)) {
			captured = null;
		}
		if (hoverTipNode != null && node.contains(hoverTipNode)) {
			hoverTipNode = null;
		}
	}

	/** Called when a control is activated: Minecraft's button click, unless a subclass changes it. */
	protected void playClick() {
		Kit.clickSound();
	}

	// ---------- modals ----------

	/** Shows {@code modal} over everything (with a scrim); focus moves into it and returns when it closes. */
	public void showModal(Node modal) {
		modalReturnFocus.add(focused);
		modals.add(modal);
		modal.parent = null;
		modal.attach(this);
		modal.setBounds(0, 0, uiWidth, uiHeight);
		captured = null;
		Node first = modal instanceof Dialog dialog ? dialog.initialFocus() : null;
		if (first == null || !first.canFocus()) {
			focusOrder.clear();
			modal.collectFocusable(focusOrder);
			first = focusOrder.isEmpty() ? null : focusOrder.getFirst();
		}
		setFocus(first, focusVisible);
	}

	public void closeModal(Node modal) {
		int index = modals.indexOf(modal);
		if (index < 0) {
			return;
		}
		forget(modal);
		modals.remove(index);
		Node back = modalReturnFocus.remove(index);
		modal.attach(null);
		boolean keyboard = focusVisible;
		setFocus(back != null && back.screen == this && back.canFocus() ? back : null, keyboard);
	}

	public boolean hasModal() {
		return !modals.isEmpty();
	}

	private Node scope() {
		return modals.isEmpty() ? root : modals.getLast();
	}

	private ArrayList<Node> collectFocusable() {
		focusOrder.clear();
		scope().collectFocusable(focusOrder);
		return focusOrder;
	}

	// ---------- drawing ----------

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		FlintFrame.begin(graphics);
		Canvas c = Canvas.begin(graphics);
		try {
			paintBackdrop(c);
		} finally {
			c.end();
		}
		// As Screen.extractBackground: subtitles stay readable under the screen.
		minecraft.gui.hud.extractDeferredSubtitles();
	}

	/**
	 * Behind the screen, in GUI units: the menu artwork with the page veil on the title screen, or a dim
	 * over the game that fades with the screen's opacity (no blur pass).
	 */
	protected void paintBackdrop(Canvas c) {
		if (minecraft.level == null) {
			Backdrop.paint(c, width, height, Backdrop.PAGE);
		} else {
			float previous = c.opacity();
			c.setOpacity(previous * opacity);
			c.fill(0, 0, c.px(width), c.px(height), Tokens.DIM);
			c.setOpacity(previous);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Canvas c = Canvas.begin(graphics, uiScale);
		try {
			layoutIfNeeded();
			updatePointer();
			c.setOpacity(opacity);
			int shift = shiftPx();
			if (shift != 0) {
				// Through the canvas, so scroll views clip exactly where the shifted content is.
				c.pushOffset(0, shift);
			}
			root.paintTree(c);
			if (shift != 0) {
				c.popOffset();
			}
			int fullW = c.px(uiWidth) + uiScale;
			int fullH = c.px(uiHeight) + uiScale;
			for (int i = 0; i < modals.size(); i++) {
				graphics.nextStratum();
				c.fill(0, 0, fullW, fullH, Tokens.SCRIM);
				modals.get(i).paintTree(c);
			}
			paintTooltip(c, graphics);
		} finally {
			c.end();
		}
		FlintFrame.end();
	}

	/** Hover from the pointer's exact position (the extract arguments are whole GUI units). */
	private void updatePointer() {
		Window window = minecraft.getWindow();
		mouseX = toUiX(minecraft.mouseHandler.getScaledXPos(window));
		mouseY = localY(minecraft.mouseHandler.getScaledYPos(window));
		if (captured != null) {
			hovered = captured.containsPoint(mouseX, mouseY) ? captured : null;
		} else {
			hovered = scope().hit(mouseX, mouseY);
		}
	}

	private void paintTooltip(Canvas c, GuiGraphicsExtractor graphics) {
		Node tip = null;
		TextLine[] lines = null;
		if (focused != null && focusVisible && focused.tooltipLines() != null) {
			// Keyboard focus shows the tooltip at once.
			tip = focused;
			lines = focused.tooltipLines();
		} else if (hovered != null && captured == null && hovered.tooltipLines() != null) {
			long now = Util.getMillis();
			// The delay restarts for another control, or another part of one (each swatch has its own).
			if (hovered != hoverTipNode || hovered.tooltipLines() != hoverTipLines) {
				hoverTipNode = hovered;
				hoverTipLines = hovered.tooltipLines();
				hoverSince = now;
			}
			if (now - hoverSince >= Kit.TOOLTIP_DELAY_MILLIS) {
				tip = hovered;
				lines = hovered.tooltipLines();
			}
		} else {
			hoverTipNode = null;
		}
		if (tip != null && lines != null && lines.length > 0) {
			graphics.nextStratum();
			tip.tooltipAnchor(anchor);
			tooltip.paint(c, lines, anchor[0], anchor[1], anchor[2], anchor[3], uiWidth, uiHeight);
		}
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!acceptsInput()) {
			return true;
		}
		float mx = toUiX(event.x());
		float my = localY(event.y());
		int button = event.button();
		if (captured != null) {
			if (button != capturedButton) {
				// Another button during a drag: swallowed, so the drag goes on.
				return true;
			}
			// The held button pressed again: its release was missed, so that capture ends first.
			Node old = captured;
			captured = null;
			old.mouseUp(mx, my, button);
		}
		if (focused != null && focused.capturesKeys() && focused.captureMouseButton(button)) {
			return true;
		}
		Node target = scope().hit(mx, my);
		focusVisible = false;
		if (target == null) {
			if (modals.isEmpty()) {
				setFocus(null, false);
				clickedOutside(mx, my, button);
			}
			return true;
		}
		Node focusTarget = target;
		while (focusTarget != null && !focusTarget.canFocus()) {
			focusTarget = focusTarget.parent;
		}
		setFocus(focusTarget, false);
		if (target.enabled() && target.mouseDown(mx, my, button)) {
			captured = target;
			capturedButton = button;
		}
		return true;
	}

	/**
	 * A press of any button that hit no interactive node while no dialog is open (focus was just cleared).
	 * That includes the non-interactive parts of the screen's own window (its background, labels), not
	 * only the space outside it, so a subclass that closes on a click outside checks the point against
	 * its window's bounds (UI units, content coordinates).
	 */
	protected void clickedOutside(float x, float y, int button) {
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		Node node = captured;
		if (node == null || event.button() != capturedButton) {
			return true;
		}
		captured = null;
		if (acceptsInput()) {
			node.mouseUp(toUiX(event.x()), localY(event.y()), event.button());
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (captured != null && acceptsInput()) {
			captured.mouseDrag(toUiX(event.x()), localY(event.y()));
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (!acceptsInput() || scrollY == 0) {
			return true;
		}
		float mx = toUiX(x);
		float my = localY(y);
		for (Node node = scope().deepest(mx, my); node != null; node = node.parent) {
			if (node.scroll(mx, my, scrollY)) {
				break;
			}
		}
		return true;
	}

	@Override
	public void mouseMoved(double x, double y) {
		mouseX = toUiX(x);
		mouseY = localY(y);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!acceptsInput()) {
			return true;
		}
		if (focused != null && focused.capturesKeys()) {
			focused.keyDown(event);
			return true;
		}
		if (event.isEscape()) {
			if (focused == null || !focused.enabled() || !focused.handlesEscape() || !focused.keyDown(event)) {
				escape();
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_TAB) {
			focusVisible = true;
			setFocus(FocusNav.next(collectFocusable(), focused, !event.hasShiftDown()), true);
			return true;
		}
		if (focused != null && focused.enabled() && focused.keyDown(event)) {
			return true;
		}
		int dx = event.isLeft() ? -1 : event.isRight() ? 1 : 0;
		int dy = event.isUp() ? -1 : event.isDown() ? 1 : 0;
		if (dx != 0 || dy != 0) {
			ArrayList<Node> order = collectFocusable();
			Node next = focused == null ? FocusNav.next(order, null, dx + dy > 0) : FocusNav.spatial(order, focused, dx, dy);
			if (next != null) {
				setFocus(next, true);
			} else if (focused != null) {
				// At the edge: keep focus, but show where it is.
				setFocus(focused, true);
			}
			return true;
		}
		return false;
	}

	/** Escape: closes the top dialog (cancelling it), otherwise {@link #onEscape()}. */
	private void escape() {
		if (!modals.isEmpty()) {
			Node top = modals.getLast();
			if (top instanceof Dialog dialog) {
				dialog.cancel();
			} else {
				closeModal(top);
			}
			return;
		}
		onEscape();
	}

	/** Escape with nothing else to close: closes the screen. */
	protected void onEscape() {
		if (shouldCloseOnEsc()) {
			onClose();
		}
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (acceptsInput() && focused != null && focused.acceptsText() && focused.enabled() && event.isAllowedChatCharacter()) {
			return focused.charTyped(event.codepoint());
		}
		return false;
	}
}

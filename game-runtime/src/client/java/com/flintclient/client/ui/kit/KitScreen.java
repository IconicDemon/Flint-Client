package com.flintclient.client.ui.kit;

import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.HostScreen;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.Keys;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.FlintFrame;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/**
 * A screen drawn entirely by the kit: a tree of {@link Node}s at its own interface scale, with focus,
 * pointer capture, hover, tooltips, modal dialogs and text input handled here once for every control.
 *
 * <p><b>Scale.</b> The interface scale ({@link #uiScale()}, whole pixels per UI unit) comes from the window's
 * own size, not Minecraft's GUI scale (the HUD keeps following that): automatic, moved a step by
 * {@link KitPrefs#uiScaleDelta()}, and limited so the screen's minimum layout fits the window ({@link UiScale}).
 * Mouse positions arrive in GUI units; they are turned into physical pixels exactly as the game scaled them
 * ({@code × framebuffer width / GUI width}) and then into UI units ({@code ÷ uiScale}). Drawing uses
 * {@code Canvas.begin(frame, uiScale)}.
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
 * <p><b>Text input.</b> While a node that accepts text is focused, the game's text input is started for
 * this screen, so typed characters arrive ({@link #typed}); it stops when focus moves away and when the screen is
 * removed.
 *
 * <p>Subclasses build the tree once ({@link #build}) and place it for the current size
 * ({@link #layoutContent}); resizing keeps the tree, its focus and scroll positions. Kit screens don't
 * pause the game ({@link #pausesGame()}).
 *
 * <p>It is the same on every Minecraft line: the engine's own screen is {@link HostScreen} (a platform class), which hands
 * this class every event in GUI units and answers its questions about the window, the pointer and the narrator. Render
 * thread.
 */
public abstract class KitScreen extends HostScreen {
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
	/** The content's scale about the screen's middle, for open and close animations (1: none). */
	private float contentZoom = 1;
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
	private final PaintMeter meter = new PaintMeter();

	/** {@code minWidth × minHeight}: the smallest layout in UI units, which the interface scale keeps in the window. */
	protected KitScreen(String title, int minWidth, int minHeight) {
		super(title);
		this.minWidth = minWidth;
		this.minHeight = minHeight;
		root.attach(this);
		meter.opening();
	}

	/** Adds the screen's nodes to {@code root}; called once, before the first layout. */
	protected abstract void build(Node root);

	/** Places the top-level nodes for a screen of {@code width × height} UI units. */
	protected abstract void layoutContent(float width, float height);

	// ---------- lifecycle ----------

	@Override
	protected final void opened() {
		updateScale();
		if (!built) {
			built = true;
			build(root);
		}
		invalidateLayout();
	}

	/** A resize keeps the tree (focus, scroll positions, text being typed); only the scale and layout change. */
	@Override
	protected void resized() {
		updateScale();
		invalidateLayout();
	}

	@Override
	protected void initialFocus(boolean keyboard) {
		// As vanilla: a screen opened from the keyboard starts with its first control focused.
		if (keyboard) {
			layoutIfNeeded();
			setFocus(FocusNav.next(collectFocusable(), null, true), true);
		}
	}

	/** Shown again (returning from another screen): a focused text field takes typing again. */
	@Override
	protected void shown() {
		textInput = false;
		updateTextInput();
		meter.opening();
	}

	@Override
	protected void hidden() {
		meter.report(getClass().getSimpleName());
		captured = null;
		if (textInput) {
			textInput = false;
			hostTextInput(false);
		}
	}

	/** Once per game tick while shown (nothing by default). */
	@Override
	protected void update() {
	}

	/** Something asked the screen to close: by default it closes at once. */
	@Override
	protected void closeRequested() {
		hostCloseNow();
	}

	/** Recomputes the interface scale (the preferences or window changed). */
	public void refreshScale() {
		updateScale();
		invalidateLayout();
	}

	private void updateScale() {
		int windowW = hostWindowWidth();
		int windowH = hostWindowHeight();
		uiScale = Math.max(1, computeUiScale(windowW, windowH));
		uiWidth = windowW / uiScale;
		uiHeight = windowH / uiScale;
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

	/**
	 * Scales everything but dialogs and tooltips about the screen's middle (a window growing into place). Pointer input
	 * doesn't follow it, so it is only for brief animations.
	 */
	public void setContentZoom(float scale) {
		this.contentZoom = scale;
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
		return (float) (guiX * hostWindowWidth() / hostScaledWidth() / uiScale);
	}

	public float toUiY(double guiY) {
		return (float) (guiY * hostWindowHeight() / hostScaledHeight() / uiScale);
	}

	/** Where the IME's candidate window goes (the caret), in UI units; from a focused text field's drawing. */
	public void setTextInputArea(float x0, float y0, float x1, float y1) {
		float k = (float) uiScale / hostGuiScale();
		hostTextInputArea(Math.round(x0 * k), Math.round(y0 * k), Math.round(x1 * k), Math.round(y1 * k));
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
	 * fullscreen keys still act first, as on Minecraft's own Key Binds screen). Screen's own hook from 26.3 on; on older
	 * versions nothing asks.
	 */
	@Override
	protected boolean holdsInput() {
		return textInput || capturingKeys();
	}

	/** Kit screens sit over a live game, as the inventory does; a subclass that should pause overrides this. */
	@Override
	protected boolean pausesGame() {
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
			hostTextInput(wanted);
		}
	}

	/** Narrates {@code node} again (its state changed while focused), when the narrator is on. */
	public void announce(Node node) {
		narrate(node);
	}

	/** Speaks the node's name and state when Minecraft's narrator is on (built only then). */
	private void narrate(Node node) {
		String text = node.narration();
		if (!text.isEmpty()) {
			hostNarrate(text);
		}
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
	protected void paintBackground(Frame frame) {
		FlintFrame.begin(frame.target());
		Canvas c = Canvas.begin(frame);
		try {
			paintBackdrop(c);
		} finally {
			c.end();
		}
	}

	/**
	 * Behind the screen, in GUI units: the menu artwork with the page veil on the title screen, or a dim
	 * over the game that fades with the screen's opacity (no blur pass).
	 */
	protected void paintBackdrop(Canvas c) {
		if (!hostInWorld()) {
			hostPaintMenuBackdrop(c);
		} else {
			float previous = c.opacity();
			c.setOpacity(previous * opacity);
			c.fill(0, 0, c.px(hostGuiWidth()), c.px(hostGuiHeight()), Tokens.DIM);
			c.setOpacity(previous);
		}
	}

	@Override
	protected void paint(Frame frame) {
		long began = System.nanoTime();
		try {
			paintScreen(frame);
		} finally {
			meter.painted(began, System.nanoTime());
		}
	}

	private void paintScreen(Frame frame) {
		Canvas c = Canvas.begin(frame, uiScale);
		int font = PixelFace.begin(fontMode(minecraftFont()));
		try {
			layoutIfNeeded();
			updatePointer();
			c.setOpacity(opacity);
			int shift = shiftPx();
			if (shift != 0) {
				// Through the canvas, so scroll views clip exactly where the shifted content is.
				c.pushOffset(0, shift);
			}
			boolean zoomed = Math.abs(contentZoom - 1) > 0.0005F;
			if (zoomed) {
				c.pushZoom(contentZoom, c.px(uiWidth) / 2.0F, c.px(uiHeight) / 2.0F);
			}
			root.paintTree(c);
			if (zoomed) {
				c.popOffset();
			}
			if (shift != 0) {
				c.popOffset();
			}
			int fullW = c.px(uiWidth) + uiScale;
			int fullH = c.px(uiHeight) + uiScale;
			for (int i = 0; i < modals.size(); i++) {
				frame.nextLayer();
				c.fill(0, 0, fullW, fullH, Tokens.SCRIM);
				modals.get(i).paintTree(c);
			}
			int tips = PixelFace.begin(fontMode(minecraftFont() || minecraftTooltips()));
			try {
				paintTooltip(c, frame);
			} finally {
				PixelFace.end(tips);
			}
		} finally {
			PixelFace.end(font);
			c.end();
		}
		FlintFrame.end();
	}

	/** Hover from the pointer's exact position (the draw call's arguments are whole GUI units). */
	private void updatePointer() {
		mouseX = toUiX(hostMouseX());
		mouseY = localY(hostMouseY());
		if (captured != null) {
			hovered = captured.containsPoint(mouseX, mouseY) ? captured : null;
		} else {
			hovered = scope().hit(mouseX, mouseY);
		}
	}

	private void paintTooltip(Canvas c, Frame frame) {
		Node tip = null;
		TextLine[] lines = null;
		if (focused != null && focusVisible && focused.tooltipLines() != null) {
			// Keyboard focus shows the tooltip at once.
			tip = focused;
			lines = focused.tooltipLines();
		} else if (hovered != null && captured == null && hovered.tooltipLines() != null) {
			long now = Clock.millis();
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
			frame.nextLayer();
			tip.tooltipAnchor(anchor);
			float k = tooltipScale();
			tooltip.paint(c, k == 1 ? lines : scaled(lines, k), anchor[0], anchor[1], anchor[2], anchor[3], uiWidth, uiHeight, k, tooltipLook());
		}
	}

	/**
	 * How much larger than the kit's own tooltips are on this screen (1: as the kit draws them). A screen whose type is
	 * sized its own way (the Right Shift menu) keeps its tooltips as readable as its rows.
	 */
	protected float tooltipScale() {
		return 1;
	}

	/** A tooltip's panel ({@code radius} in the kit's units, before {@link #tooltipScale}), its edge, and its first and other lines. */
	public record TooltipLook(int fill, int border, float radius, int title, int text) {
	}

	/** How this screen's tooltips look: the kit's own (a dark panel, a faint edge, a bright title over muted lines). */
	protected TooltipLook tooltipLook() {
		return new TooltipLook(Tokens.TOOLTIP_FILL, Tokens.TOOLTIP_BORDER, 4, Tokens2.TEXT, Tokens2.muted());
	}

	/**
	 * Whether every piece of text on this screen is drawn in Minecraft's own font (the Right Shift menu), Flint's faces
	 * standing in at their sizes; measured the same way while it handles input, so a caret lands where it is drawn.
	 */
	protected boolean minecraftFont() {
		return false;
	}

	/** Whether this screen's tooltips are in Minecraft's font even though the rest of it is not (see {@link #minecraftFont()}). */
	protected boolean minecraftTooltips() {
		return false;
	}

	/**
	 * The pixel scale Minecraft's font is drawn at on this screen, the same for every piece of text, or
	 * {@link PixelFace#BY_SIZE} for each piece at the scale nearest its own size.
	 */
	protected int minecraftFontScale() {
		return PixelFace.BY_SIZE;
	}

	private int fontMode(boolean minecraft) {
		return minecraft ? minecraftFontScale() : PixelFace.OFF;
	}

	private TextLine @Nullable [] scaledSource;
	private TextLine[] scaledLines = new TextLine[0];
	private float scaledBy;

	/** {@code lines} with their type {@code k} times the kit's tooltip size; remade only when the text or size changes. */
	private TextLine[] scaled(TextLine[] lines, float k) {
		boolean same = lines == scaledSource || scaledLines.length == lines.length && k == scaledBy;
		for (int i = 0; same && lines != scaledSource && i < lines.length; i++) {
			same = lines[i].text().equals(scaledLines[i].text());
		}
		if (!same || k != scaledBy) {
			com.flintclient.client.render.FontFace face = com.flintclient.client.render.ScaledFont.of(com.flintclient.client.render.FlintFonts.LABEL,
				5.2F * k);
			scaledLines = new TextLine[lines.length];
			for (int i = 0; i < lines.length; i++) {
				scaledLines[i] = new TextLine(face, lines[i].text());
			}
			scaledBy = k;
		}
		scaledSource = lines;
		return scaledLines;
	}

	// ---------- input ----------

	/** A mouse button went down at ({@code x}, {@code y}) GUI units. */
	@Override
	protected boolean pointerDown(double x, double y, int button) {
		int font = PixelFace.begin(fontMode(minecraftFont()));
		try {
			return clicked(x, y, button);
		} finally {
			PixelFace.end(font);
		}
	}

	private boolean clicked(double x, double y, int button) {
		if (!acceptsInput()) {
			return true;
		}
		float mx = toUiX(x);
		float my = localY(y);
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
	protected boolean pointerUp(double x, double y, int button) {
		Node node = captured;
		if (node == null || button != capturedButton) {
			return true;
		}
		captured = null;
		if (acceptsInput()) {
			node.mouseUp(toUiX(x), localY(y), button);
		}
		return true;
	}

	@Override
	protected boolean pointerDragged(double x, double y, int button) {
		int font = PixelFace.begin(fontMode(minecraftFont()));
		try {
			if (captured != null && acceptsInput()) {
				captured.mouseDrag(toUiX(x), localY(y));
			}
			return true;
		} finally {
			PixelFace.end(font);
		}
	}

	@Override
	protected boolean wheel(double x, double y, double scrollY) {
		if (!acceptsInput() || scrollY == 0) {
			return true;
		}
		float mx = toUiX(x);
		float my = localY(y);
		for (Node node = scope().deepest(mx, my); node != null; node = node.parent) {
			if (node.scroll(mx, my, scrollY)) {
				return true;
			}
		}
		// Nothing on the way up took it: a layer drawn over another (the HUD page over the HUD editor) passes the wheel to
		// what a click there would reach.
		for (Node node = scope().hit(mx, my); node != null; node = node.parent) {
			if (node.scroll(mx, my, scrollY)) {
				break;
			}
		}
		return true;
	}

	@Override
	protected void pointerMoved(double x, double y) {
		mouseX = toUiX(x);
		mouseY = localY(y);
	}

	@Override
	protected boolean keyDown(KeyInput event) {
		int font = PixelFace.begin(fontMode(minecraftFont()));
		try {
			return pressed(event);
		} finally {
			PixelFace.end(font);
		}
	}

	/** A key was let go (nothing by default). */
	@Override
	protected boolean keyUp(KeyInput event) {
		return false;
	}

	private boolean pressed(KeyInput event) {
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
		if (event.key() == Keys.TAB) {
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
		if (hostClosesOnEscape()) {
			closeRequested();
		}
	}

	/** A character the game allows in text was typed. */
	@Override
	protected boolean typed(int codepoint) {
		int font = PixelFace.begin(fontMode(minecraftFont()));
		try {
			if (acceptsInput() && focused != null && focused.acceptsText() && focused.enabled()) {
				return focused.charTyped(codepoint);
			}
			return false;
		} finally {
			PixelFace.end(font);
		}
	}
}

package com.flintclient.client.control;

import com.flintclient.client.control.editor.HudEditor;
import com.flintclient.client.control.model.ToggleKeyLogic;
import com.flintclient.client.control.platform.ControlKeys;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The visual HUD editor (in a world only), over the live game: what Right Shift opens on in a world, with Flint's hub in
 * the middle leading to the module menu and the widget palette. Right Shift again (a fresh press, not while typing)
 * saves and closes it, as Escape does once nothing is open or selected. The editing itself lives in {@link HudEditor};
 * this screen gives it the kit's input, focus and dialogs, and draws its editing surface behind the controls in
 * place of the usual dim. It doesn't pause the game, and like every kit screen it takes all input, so nothing
 * reaches gameplay while it is open (Minecraft also releases every key mapping when a screen opens). Flint's
 * HUD isn't drawn while it is open; the editor draws the modules itself. Leaving returns to {@code parent}
 * (null = the game). If drawing the editor fails it closes itself at the next tick (see {@link HudEditor}).
 */
public class HudEditorScreen extends KitScreen {
	/** The smallest layout in UI units: the toolbar, and the inspector beside a module. */
	private static final int MIN_WIDTH = 300;
	private static final int MIN_HEIGHT = 180;

	protected final @Nullable Screen parent;
	private final HudEditor editor;
	private final ToggleKeyLogic toggleKey = new ToggleKeyLogic();

	public HudEditorScreen(@Nullable Screen parent) {
		this(parent, null);
	}

	/** The editor with {@code moduleId} selected once the first frame has placed it (null: nothing selected). */
	public HudEditorScreen(@Nullable Screen parent, @Nullable String moduleId) {
		super(Component.literal("HUD editor"), MIN_WIDTH, MIN_HEIGHT);
		this.parent = parent;
		this.editor = new HudEditor(this, parent, ControlState.layout());
		if (moduleId != null) {
			editor.selectWhenShown(moduleId);
		}
	}

	/** Where closing the editor goes (the game, when null). */
	public @Nullable Screen returnTo() {
		return parent;
	}

	/** Selects {@code moduleId} once the next frame has placed it (back from the menu's "Edit position"). */
	public void selectWhenShown(String moduleId) {
		editor.selectWhenShown(moduleId);
	}

	/** Shown, first or again (back from the module menu): the key that opened it closes it only once let go. */
	@Override
	public void added() {
		super.added();
		ControlKeys keys = ControlCenter.keys();
		toggleKey.opened(keys != null && Mc263ControlPlatform.physicallyDown(keys.controlCenter()));
		editor.shown();
	}

	@Override
	protected void build(Node root) {
		editor.build(root);
	}

	@Override
	protected void layoutContent(float width, float height) {
		editor.layout(width, height);
	}

	/** The editing surface (light dim, dot grid, Minecraft's HUD areas, the modules, guides) in GUI units. */
	@Override
	protected void paintBackdrop(Canvas c) {
		editor.paint(c, width, height);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (toggleKey.keyPressed(ControlCenter.isOpenKey(event), typing() || hasModal(), capturingKeys())) {
			editor.saveAndClose();
			return true;
		}
		if (acceptsInput() && editor.keyPressed(event)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		ControlKeys keys = ControlCenter.keys();
		if (keys != null && keys.controlCenter().matches(event)) {
			toggleKey.keyReleased(true);
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The open key bound to a mouse button other than the two the editor uses closes it too.
		if (event.button() != Kit.LEFT_BUTTON && event.button() != Kit.RIGHT_BUTTON && toggleKey.keyPressed(ControlCenter.isOpenKey(event), typing() || hasModal(), capturingKeys())) {
			editor.saveAndClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		ControlKeys keys = ControlCenter.keys();
		if (keys != null && keys.controlCenter().matchesMouse(event)) {
			toggleKey.keyReleased(true);
		}
		return super.mouseReleased(event);
	}

	@Override
	protected void onEscape() {
		editor.requestLeave();
	}

	/** Any other way of closing goes the same way as Escape. */
	@Override
	public void onClose() {
		editor.requestLeave();
	}

	@Override
	public void removed() {
		super.removed();
		editor.removed();
	}

	@Override
	public void tick() {
		super.tick();
		editor.tick();
	}
}

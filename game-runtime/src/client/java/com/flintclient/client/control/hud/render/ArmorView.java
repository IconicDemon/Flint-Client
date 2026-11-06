package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.platform.HudData;

/**
 * Armor in the HUD editor. Armor draws itself from the hotbar's hook, in Minecraft's look ({@code Mc263ArmorHud} and 1.8.9's
 * {@code ArmorStatus}), so the HUD leaves this view out; the editor uses it for Armor's box: the strip with its durability
 * text and warning signs, the item slots included. While its own anchor places it (against the hotbar or a screen edge) the
 * box is where that puts it, at size 1; once Free, its HUD placement places it at its size step, like every other module.
 */
final class ArmorView extends ModuleView {
	private static final int ANCHORED = 4;
	/** {left, top, width, height, anchored}: GUI units ({@link HudData#armorBox}). */
	private final float[] box = new float[5];
	private boolean shown;
	private boolean anchored;
	private boolean damagedOnly;
	private boolean items;

	ArmorView(int surface) {
		super(ModuleId.ARMOR, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		damagedOnly = state.option(ModuleSettings.Armor.WIDGET_SHOWN) == 3;
		items = com.flintclient.client.control.feature.ArmorPieces.wanted(state, com.flintclient.client.control.feature.ArmorPieces.ALL) != 0;
		// Live in the editor (HUD), or the size of four empty slots where it would be (PLACEHOLDER); never a made-up preview.
		shown = (mode == HUD || mode == PLACEHOLDER) && HudData.armorBox(box, mode == PLACEHOLDER);
		anchored = shown && box[ANCHORED] != 0;
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		if (!shown) {
			width = 0;
			height = 0;
		} else if (anchored) {
			// Its own anchor draws it at Minecraft's size.
			width = Math.round(box[2] * p.s.unitPx);
			height = Math.round(box[3] * p.s.unitPx);
		} else {
			width = p.s.px(box[2]);
			height = p.s.px(box[3]);
		}
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		// Armor draws itself here, from the hotbar's hook (under the editor), so the editor shows exactly what the HUD shows.
	}

	@Override
	boolean ownSpot(float[] out) {
		if (!anchored) {
			return false;
		}
		out[0] = box[0];
		out[1] = box[1];
		return true;
	}

	@Override
	boolean editorOnly() {
		return true;
	}

	@Override
	boolean panelled() {
		return false;
	}

	@Override
	boolean ownLook() {
		return true;
	}

	@Override
	String emptyReason() {
		if (damagedOnly) {
			return "Shows when a piece is about to break";
		}
		return items ? "Shows while you wear armor or carry its items" : "Shows while you wear armor";
	}
}

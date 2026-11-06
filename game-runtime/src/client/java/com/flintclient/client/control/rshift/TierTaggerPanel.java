package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.feature.tiers.TierLists;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.platform.TierSearchScreen;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.ui.kit.Node;
import net.minecraft.client.Minecraft;

/** Under Tier Tagger's settings: the player search, and fetching every tier again. */
public final class TierTaggerPanel extends Node {
	private static final float H = 100;

	private final Label title = new Label(Type.ROW_LABEL, "Players");
	private final Label note = new Label(Type.ROW_NOTE, "Look a player's tiers up, or fetch everyone's again");
	private final PageControls.TextButton search;
	private final PageControls.TextButton refresh;

	public TierTaggerPanel() {
		search = add(new PageControls.TextButton("Search a player", Glyph.SEARCH, PageControls.Look.PLAIN,
			() -> Minecraft.getInstance().gui.setScreen(new TierSearchScreen(Mc263ControlPlatform.currentScreen()))));
		refresh = add(new PageControls.TextButton("Refresh tiers", Glyph.RESET, PageControls.Look.PLAIN, TierLists::clear));
	}

	@Override
	public float prefHeight(float width) {
		return r(H);
	}

	@Override
	protected void layout() {
		float bh = r(PageControls.TextButton.H);
		float by = y + (h - bh) / 2;
		float at = x + w - r(24) - refresh.prefWidth();
		refresh.setBounds(at, by, refresh.prefWidth(), bh);
		at -= r(16) + search.prefWidth();
		search.setBounds(at, by, search.prefWidth(), bh);
	}

	@Override
	protected void paint(Canvas c) {
		MenuStyle.block(c, x, y, w, h, MenuStyle.radius(), 0, 0);
		float room = search.x() - r(40) - x - r(24);
		MenuStyle.fitted(c, title, x + r(24), y + h / 2 - r(12), MenuStyle.TAB_TEXT_SELECTED, room);
		MenuStyle.fitted(c, note, x + r(24), y + h / 2 + r(22), MenuStyle.NEUTRAL, room);
	}
}

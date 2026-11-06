package com.flintclient.legacy.mods;

import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.platform.HudData;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardScore;
import net.minecraft.scoreboard.team.Team;

/**
 * The server's sidebar, drawn by Flint where the HUD editor puts it (Minecraft's spot by default: the right edge, a third of
 * the way down from the middle), with its numbers hidden if you like, and its own colours. The same rows as Minecraft's: the
 * last fifteen scores whose holders don't start with #, highest at the top. The rows are read again at most once a tick. The
 * 1.8.9 backend of Scoreboard: hiding it, its numbers, colours, opacity, border and shadow are the shared settings.
 */
public final class Scoreboard extends Module {
	private static final int PAD_X = 2;
	private static final int PAD_Y = 0;
	private final float[] rect = new float[4];
	private int[] size = new int[2];
	/** Minecraft's red for the scores. */
	private static final int NUMBER = 0xFFFF5555;
	public final Setting.Bool hide = bool(UtilityMods.Scoreboard.HIDE);
	public final Setting.Bool numbers = bool(UtilityMods.Scoreboard.NUMBERS);
	public final Setting.Bool textShadow = bool(UtilityMods.Scoreboard.SHADOW);
	public final Setting.Color textColor = color(UtilityMods.Scoreboard.TEXT_COLOR);
	public final Setting.Color backgroundColor = color(UtilityMods.Scoreboard.BACKGROUND_COLOR, UtilityMods.Scoreboard.OPACITY, true);
	public final Setting.Color titleBackground = color(UtilityMods.Scoreboard.TITLE_BACKGROUND);
	public final Setting.Bool border = bool(UtilityMods.Scoreboard.BORDER);
	public final Setting.Color borderColor = color(UtilityMods.Scoreboard.BORDER_COLOR);

	private ScoreboardObjective objective;
	private long readAt = -1;
	private final List<String> names = new ArrayList<String>();
	private final List<String> scores = new ArrayList<String>();
	private final List<Integer> scoreWidths = new ArrayList<Integer>();
	private String title = "";
	private int widest;
	/** Whether Minecraft has a sidebar to show this frame (set by the GUI hook). */
	private boolean present;

	public Scoreboard() {
		super("scoreboard", "scoreboard");
	}

	/**
	 * Minecraft would draw {@code o} now: Flint draws it instead, where the shared HUD placed the Scoreboard module in its last
	 * frame (the module only holds the sidebar's place and size, as on every line, so the HUD editor moves and sizes it).
	 */
	public void draw(ScoreboardObjective o, long tick) {
		present = true;
		if (o != objective || tick != readAt) {
			read(o);
			readAt = tick;
		}
		if (hide.on()) {
			HudData.setSidebar(null);
			return;
		}
		int lines = names.size();
		if (size == null || size[0] != widest || size[1] != lines) {
			size = new int[] {widest, lines};
		}
		HudData.setSidebar(size);
		if (!HudRenderer.lastRect(id, rect)) {
			return;
		}
		float w = width();
		GlStateManager.pushMatrix();
		GlStateManager.translatef(rect[0], rect[1], 0);
		float k = rect[2] / Math.max(1, w);
		GlStateManager.scalef(k, k, 1);
		try {
			draw();
		} finally {
			GlStateManager.popMatrix();
			GlStateManager.color4f(1, 1, 1, 1);
			GlStateManager.enableBlend();
		}
	}

	/** After the HUD: no sidebar drawn this frame means none to place. */
	public void frameEnd() {
		if (!present) {
			HudData.setSidebar(null);
		}
		present = false;
	}

	private void read(ScoreboardObjective o) {
		objective = o;
		names.clear();
		scores.clear();
		scoreWidths.clear();
		net.minecraft.scoreboard.Scoreboard board = o.getScoreboard();
		Collection<ScoreboardScore> all = board.getScores(o);
		List<ScoreboardScore> rows = new ArrayList<ScoreboardScore>();
		for (ScoreboardScore s : all) {
			if (s.getOwner() != null && !s.getOwner().startsWith("#")) {
				rows.add(s);
			}
		}
		if (rows.size() > 15) {
			rows = rows.subList(rows.size() - 15, rows.size());
		}
		title = o.getDisplayName();
		widest = font().getWidth(title);
		// Highest first (Minecraft's list is lowest first and draws it from the bottom up).
		for (int i = rows.size() - 1; i >= 0; i--) {
			ScoreboardScore s = rows.get(i);
			String name = Team.getMemberDisplayName(board.getTeamOfMember(s.getOwner()), s.getOwner());
			String score = Integer.toString(s.get());
			names.add(name);
			scores.add(score);
			int sw = font().getWidth(score);
			scoreWidths.add(sw);
			widest = Math.max(widest, font().getWidth(name) + (numbers.on() ? font().getWidth(": ") + sw : 0));
		}
	}

	private static net.minecraft.client.render.TextRenderer font() {
		return net.minecraft.client.Minecraft.getInstance().textRenderer;
	}

	private float width() {
		return widest + 2 * PAD_X;
	}

	/** Its size as the shared module holds it: the title's band and nine units a row (Minecraft's own measure). */
	private float height() {
		return names.size() * 9 + 10;
	}

	private void draw() {
		float w = width();
		int h = Math.round(height());
		int x1 = Math.round(w);
		int pad = PAD_X;
		int top = PAD_Y;
		int panel = backgroundColor.argb();
		if ((panel >>> 24) != 0) {
			// The title's band at the panel's opacity, a little darker as Minecraft's is.
			GuiElement.fill(0, 0, x1, top + 10, (Math.min(255, (panel >>> 24) + 0x18) << 24) | titleBackground.rgb());
			GuiElement.fill(0, top + 10, x1, h, panel);
		}
		if (border.on()) {
			int t = 1;
			int c = 0xFF000000 | borderColor.rgb();
			GuiElement.fill(0, 0, x1, t, c);
			GuiElement.fill(0, h - t, x1, h, c);
			GuiElement.fill(0, t, t, h - t, c);
			GuiElement.fill(x1 - t, t, x1, h - t, c);
		}
		GlStateManager.enableBlend();
		int ink = 0xFF000000 | textColor.rgb();
		boolean shadow = textShadow.on();
		com.flintclient.legacy.ui.FastText.draw(title, Math.round((w - font().getWidth(title)) / 2), top + 1, ink, shadow);
		for (int i = 0; i < names.size(); i++) {
			int y = top + 10 + i * 9;
			com.flintclient.legacy.ui.FastText.draw(names.get(i), pad, y, ink, shadow);
			if (numbers.on()) {
				com.flintclient.legacy.ui.FastText.draw(scores.get(i), x1 - pad - scoreWidths.get(i) + 1, y, NUMBER, shadow);
			}
		}
	}

	public boolean present() {
		return present;
	}
}

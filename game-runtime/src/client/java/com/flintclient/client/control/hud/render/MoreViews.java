package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.TextOut;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreHudMods;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.ItemPainter;
import com.flintclient.client.render.Palette;
import org.jspecify.annotations.Nullable;

/**
 * The second batch's HUD views: Block Indicator, Damage Indicator, Player Model, Pack Display, Mousestrokes, Jump Reset
 * and Item Info. Each draws on the shared HUD panel in the module's look (Jump Reset in its own box style); text is
 * rebuilt only when it changes.
 */
final class MoreViews {
	private static final int GREEN = 0xFF58D26A;
	private static final int AMBER = 0xFFF2C14E;
	private static final int RED = 0xFFEF5350;

	private MoreViews() {
	}

	/** Green when full, amber at half, red when nearly empty. */
	static int healthColor(float fraction) {
		return fraction > 0.5F ? Palette.lerp(AMBER, GREEN, (fraction - 0.5F) * 2) : Palette.lerp(RED, AMBER, Math.max(0, fraction) * 2);
	}

	/** Block Indicator: the block's icon and name, and while it's being mined how far (a thin bar and a percentage). */
	static final class BlockView extends ModuleView {
		private static @Nullable Object sample;
		private final HudText name = new HudText("");
		private final HudText percent = new HudText("");
		private Object icon = ItemPainter.none();
		private int shownPercent = Integer.MIN_VALUE;
		private int progress = -1;
		private boolean breaking;
		private boolean visible;
		private int iconPx;
		private int gap;
		private int percentW;

		BlockView(int surface) {
			super(MoreHudMods.BlockIndicator.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			breaking = state.bool(MoreHudMods.BlockIndicator.BREAKING);
			HudLooks.Block look = mode == HUD || mode == PREVIEW ? HudData.block(state.integer(MoreHudMods.BlockIndicator.RANGE)) : null;
			if (look != null) {
				visible = true;
				icon = look.icon == null ? ItemPainter.none() : look.icon;
				name.set(look.name);
				progress(look.percent);
			} else if (mode == HUD) {
				visible = false;
			} else {
				visible = true;
				if (sample == null) {
					sample = ItemPainter.sample("oak_log", 1);
				}
				icon = mode == PLACEHOLDER ? ItemPainter.none() : sample;
				name.set(mode == PLACEHOLDER ? "—" : "Oak Log");
				progress(mode == PLACEHOLDER ? -1 : 45);
			}
		}

		private void progress(int value) {
			progress = breaking ? value : -1;
			if (progress >= 0 && progress != shownPercent) {
				shownPercent = progress;
				percent.set(progress + "%");
			}
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			if (!visible) {
				width = 0;
				height = 0;
				return;
			}
			HudStyle s = p.s;
			iconPx = ItemPainter.empty(icon) ? 0 : Math.max(8, s.px(16));
			gap = iconPx > 0 ? s.px(5) : 0;
			percentW = progress >= 0 ? s.space + p.width(percent, HudFaces.LABEL) : 0;
			int column = p.width(name, HudFaces.VALUE) + percentW;
			if (breaking) {
				column = Math.max(column, s.px(56));
			}
			width = iconPx + gap + column;
			height = Math.max(iconPx, s.lineH + (breaking ? s.px(4) : 0));
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			HudStyle s = p.s;
			if (iconPx > 0) {
				p.item(icon, x, y + (height - iconPx) / 2, iconPx, null);
			}
			int left = x + iconPx + gap;
			int column = width - iconPx - gap;
			int textH = s.lineH + (breaking ? s.px(4) : 0);
			int top = y + (height - textH) / 2;
			int baseline = p.baseline(top);
			p.text(name, HudFaces.VALUE, left, baseline, s.text, column - percentW);
			if (progress >= 0) {
				p.text(percent, HudFaces.LABEL, left + column - percentW + s.space, baseline, s.muted);
				int barH = Math.max(1, s.px(1.5F));
				int barY = top + s.lineH + s.px(1.5F);
				p.box(left, barY, column, barH, barH / 2, Palette.withAlpha(s.text, 0.16F));
				p.box(left, barY, Math.max(barH, column * progress / 100), barH, barH / 2, s.accent);
			}
		}

		@Override
		void forget() {
			icon = ItemPainter.none();
		}

		@Override
		String emptyReason() {
			return "Look at a block";
		}
	}

	/** Damage Indicator: the mob's name and health, and a health bar that eases to each hit; it lingers a moment after you look away. */
	static final class TargetView extends ModuleView {
		private static final Object HEART = ItemPainter.sprite("hud/heart/full");
		private static final long LINGER = 1_200_000_000L;
		private static final long FADE = 250_000_000L;
		private final HudText name = new HudText("");
		private final HudText health = new HudText("");
		private int healthKey = Integer.MIN_VALUE;
		private int targetId = Integer.MIN_VALUE;
		private float fraction;
		private float absorb;
		private float shown = -1;
		private long seenAt;
		private long frameAt;
		private float alpha = 1;
		private boolean visible;
		private int healthW;
		private int heartPx;

		TargetView(int surface) {
			super(MoreHudMods.DamageIndicator.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			float dt = frameAt == 0 ? 0 : Math.min(0.1F, (nowNanos - frameAt) / 1.0E9F);
			frameAt = nowNanos;
			HudLooks.Target look = mode == HUD || mode == PREVIEW ? HudData.target(state.integer(MoreHudMods.DamageIndicator.RANGE)) : null;
			if (look != null) {
				visible = true;
				seenAt = nowNanos;
				name.set(look.name);
				health(look.health, look.maxHealth);
				fraction = Math.clamp(look.health / look.maxHealth, 0, 1);
				absorb = Math.clamp(look.absorption / look.maxHealth, 0, 1);
				if (look.id != targetId) {
					targetId = look.id;
					shown = fraction;
				}
			} else if (mode != HUD) {
				visible = true;
				seenAt = nowNanos;
				name.set(mode == PLACEHOLDER ? "—" : "Zombie");
				health(14, 20);
				fraction = 0.7F;
				absorb = 0;
				shown = fraction;
			} else if (nowNanos - seenAt > LINGER) {
				visible = false;
				targetId = Integer.MIN_VALUE;
			}
			shown = shown < 0 ? fraction : shown + (fraction - shown) * Math.min(1, dt * 10);
			alpha = mode != HUD ? 1 : Math.clamp((LINGER - (nowNanos - seenAt)) / (float) FADE, 0, 1);
		}

		private void health(float value, float max) {
			int key = Math.round(value * 2) * 8192 + Math.round(max * 2);
			if (key != healthKey) {
				healthKey = key;
				health.set(half(value) + " / " + half(max));
			}
		}

		/** To the nearest half: "14", "14.5". */
		private static String half(float value) {
			float rounded = Math.round(value * 2) / 2.0F;
			return rounded == (int) rounded ? Integer.toString((int) rounded) : Float.toString(rounded);
		}

		@Override
		float opacity() {
			return alpha;
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			if (!visible) {
				width = 0;
				height = 0;
				return;
			}
			HudStyle s = p.s;
			heartPx = Math.max(7, s.px(8));
			healthW = p.width(health, HudFaces.VALUE);
			width = Math.max(p.width(name, HudFaces.VALUE) + s.px(10) + heartPx + s.px(3) + healthW, s.px(110));
			height = s.lineH + s.px(3) + Math.max(2, s.px(3));
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			HudStyle s = p.s;
			int baseline = p.baseline(y);
			int healthX = x + width - healthW;
			int heartX = healthX - s.px(3) - heartPx;
			p.text(name, HudFaces.VALUE, x, baseline, s.text, heartX - s.px(7) - x);
			p.sprite(HEART, heartX, y + (s.lineH - heartPx) / 2, heartPx, 0xFFFFFFFF);
			p.text(health, HudFaces.VALUE, healthX, baseline, s.text);
			int barH = Math.max(2, s.px(3));
			int barY = y + s.lineH + s.px(3);
			p.box(x, barY, width, barH, barH / 2, Palette.withAlpha(0xFFFFFFFF, 0.14F));
			int filled = Math.round(width * Math.clamp(shown, 0, 1));
			if (filled > 0) {
				p.box(x, barY, Math.max(barH, filled), barH, barH / 2, healthColor(shown));
			}
			if (absorb > 0) {
				int extra = Math.min(width - filled, Math.round(width * absorb));
				p.box(x + filled, barY, extra, barH, barH / 2, AMBER);
			}
		}

		@Override
		void forget() {
			visible = false;
			targetId = Integer.MIN_VALUE;
		}

		@Override
		String emptyReason() {
			return "Look at a mob";
		}
	}

	/** Player Model: you, as the inventory draws you, turned a little towards the screen. */
	static final class PlayerView extends ModuleView {
		private boolean visible;

		PlayerView(int surface) {
			super(MoreHudMods.PlayerModel.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			visible = mode == PLACEHOLDER || HudData.inWorld();
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			width = visible ? p.s.px(46) : 0;
			height = visible ? p.s.px(72) : 0;
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			if (p.recording() || !HudData.inWorld()) {
				// The miniature and the outline: a soft silhouette where the model stands.
				int w = width / 3;
				p.box(x + (width - w) / 2, y + p.s.px(4), w, height - p.s.px(8), w / 3, Palette.withAlpha(p.s.text, 0.25F));
				return;
			}
			ItemPainter.player(p.frame(), x, y, width, height, state.bool(MoreHudMods.PlayerModel.HEAD));
		}

		/** Just you, as Feather's Player Model: no panel behind the model. */
		@Override
		boolean panelled() {
			return false;
		}

		@Override
		boolean ownLook() {
			return true;
		}

		@Override
		@Nullable String noPaddingReason() {
			return "The model has no panel around it";
		}

		@Override
		String emptyReason() {
			return "Shows in a world";
		}
	}

	/** Mousestrokes: a dot that leans the way you turn, and by how fast, with a fading trail. */
	static final class MouseView extends ModuleView {
		private static final int TRAIL = 14;
		private final float[] trailX = new float[TRAIL];
		private final float[] trailY = new float[TRAIL];
		private int head;
		private float dotX;
		private float dotY;
		private float lastYaw = Float.NaN;
		private float lastPitch;
		private long frameAt;
		private long sampledAt;

		MouseView(int surface) {
			super(MoreHudMods.Mousestrokes.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			float dt = frameAt == 0 ? 0 : Math.min(0.1F, (nowNanos - frameAt) / 1.0E9F);
			frameAt = nowNanos;
			float yaw = HudData.viewYaw();
			float pitch = HudData.viewPitch();
			float targetX = 0;
			float targetY = 0;
			if (mode == HUD && dt > 0 && !Float.isNaN(yaw) && !Float.isNaN(lastYaw)) {
				// Degrees a second, against a brisk 400°/s flick at 1× sensitivity reaching the edge.
				float sensitivity = state.decimal(MoreHudMods.Mousestrokes.SENSITIVITY);
				targetX = Math.clamp(HudFormat.wrapDegrees(yaw - lastYaw) / dt / 400.0F * sensitivity, -1, 1);
				targetY = Math.clamp((pitch - lastPitch) / dt / 400.0F * sensitivity, -1, 1);
			}
			lastYaw = yaw;
			lastPitch = pitch;
			if (mode != HUD) {
				dotX = 0.45F;
				dotY = -0.25F;
				for (int i = 0; i < TRAIL; i++) {
					float t = i / (float) TRAIL;
					trailX[i] = dotX * (1 - t);
					trailY[i] = dotY * (1 - t);
				}
				head = 0;
				return;
			}
			float k = 1 - (float) Math.exp(-dt * 16);
			dotX += (targetX - dotX) * k;
			dotY += (targetY - dotY) * k;
			if (nowNanos - sampledAt >= 16_000_000L) {
				sampledAt = nowNanos;
				head = (head + 1) % TRAIL;
				trailX[head] = dotX;
				trailY[head] = dotY;
			}
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			width = p.s.px(36);
			height = width;
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			HudStyle s = p.s;
			int cx = x + width / 2;
			int cy = y + height / 2;
			int line = Math.max(1, s.px(0.5F));
			int guide = Palette.withAlpha(s.text, 0.12F);
			p.fill(cx - line / 2, y + s.px(3), cx - line / 2 + line, y + height - s.px(3), guide);
			p.fill(x + s.px(3), cy - line / 2, x + width - s.px(3), cy - line / 2 + line, guide);
			int range = width / 2 - s.px(5);
			int color = 0xFF000000 | state.rgb(MoreHudMods.Mousestrokes.COLOR);
			if (state.bool(MoreHudMods.Mousestrokes.TRAIL)) {
				for (int i = TRAIL - 1; i >= 1; i--) {
					int at = Math.floorMod(head - i, TRAIL);
					float age = i / (float) TRAIL;
					int r = Math.max(1, Math.round(s.px(2.5F) * (1 - age * 0.6F)));
					int px = cx + Math.round(trailX[at] * range);
					int py = cy + Math.round(trailY[at] * range);
					p.box(px - r, py - r, 2 * r, 2 * r, r, Palette.withAlpha(color, 0.35F * (1 - age)));
				}
			}
			int r = Math.max(2, s.px(3));
			p.box(cx + Math.round(dotX * range) - r, cy + Math.round(dotY * range) - r, 2 * r, 2 * r, r, color);
		}

		@Override
		void forget() {
			lastYaw = Float.NaN;
		}
	}

	/** Jump Reset: "Perfect jump reset", or how many ticks early or late, for a few seconds after a hit. */
	static final class JumpView extends TextModuleView {
		private final TextOut o = new TextOut();

		JumpView(int surface) {
			super(MoreHudMods.JumpReset.ID, surface, MoreHudMods.JumpReset.STYLE);
		}

		@Override
		int compose(ModuleState state, int mode, long nowNanos, char[] out) {
			int ticks;
			if (mode == SAMPLE) {
				ticks = 0;
			} else {
				long at = HudData.jumpResultAt();
				if (at == 0 || nowNanos - at > (long) (state.decimal(MoreHudMods.JumpReset.SHOW_FOR) * 1.0E9)) {
					return -1;
				}
				ticks = HudData.jumpResult();
			}
			o.start(out);
			int off = Math.abs(ticks);
			if (off == 0) {
				o.text("Perfect jump reset");
			} else {
				o.integer(off).text(off == 1 ? " tick " : " ticks ").text(ticks < 0 ? "early" : "late");
			}
			if (state.bool(MoreHudMods.JumpReset.TIMING_COLORS)) {
				colors[0] = off == 0 ? GREEN : off == 1 ? AMBER : RED;
			}
			return o.length();
		}

		@Override
		String emptyReason() {
			return "Shows when you're hit and jump";
		}
	}

	/**
	 * Team Tracker's compass: a strip a half-turn wide around where you look, with the compass points on it and a dot for
	 * each teammate the way they are (one beyond the strip waits at its edge, smaller).
	 */
	static final class CompassView extends ModuleView {
		private static final HudText[] POINTS = {new HudText("S"), new HudText("W"), new HudText("N"), new HudText("E")};
		private final float[] bearings = new float[HudData.TEAM_MAX];
		private final int[] colors = new int[HudData.TEAM_MAX];
		private int count;
		private float yaw;
		private boolean visible;

		CompassView(int surface) {
			super(com.flintclient.client.control.model.mods.LookMods.TeamTracker.ID, surface);
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			if (mode == HUD || mode == PREVIEW) {
				count = HudData.teamBearings(bearings, colors);
				float now = HudData.viewYaw();
				yaw = Float.isNaN(now) ? 0 : now;
			} else {
				count = 0;
			}
			if (count == 0 && mode != HUD && state.bool(com.flintclient.client.control.model.mods.LookMods.TeamTracker.COMPASS)) {
				bearings[0] = -40;
				bearings[1] = 25;
				bearings[2] = 120;
				colors[0] = 0xFF55FF55;
				colors[1] = 0xFF55FF55;
				colors[2] = 0xFF55FF55;
				count = mode == PLACEHOLDER ? 0 : 3;
				yaw = 170;
			}
			visible = count > 0 || mode == PLACEHOLDER;
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			width = visible ? p.s.px(140) : 0;
			height = visible ? p.s.lineH + p.s.px(4) : 0;
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			HudStyle s = p.s;
			int mid = x + width / 2;
			float perDegree = width / 180.0F;
			int line = Math.max(1, s.px(0.5F));
			int trackY = y + height - s.px(2);
			p.fill(x, trackY, x + width, trackY + line, Palette.withAlpha(s.text, 0.18F));
			p.fill(mid - line / 2, y + height - s.px(5), mid - line / 2 + line, trackY + line, Palette.withAlpha(s.text, 0.5F));
			// A tick every 45 degrees; compass points: Minecraft's yaw 0 faces south, 90 west, 180 north, 270 east.
			for (int i = 0; i < 8; i++) {
				float off = HudFormat.wrapDegrees(i * 45 - yaw);
				if (Math.abs(off) <= 88 && (i & 1) == 1) {
					int px = mid + Math.round(off * perDegree);
					p.fill(px - line / 2, trackY - s.px(2), px - line / 2 + line, trackY, Palette.withAlpha(s.text, 0.35F));
				}
			}
			for (int i = 0; i < 4; i++) {
				float off = HudFormat.wrapDegrees(i * 90 - yaw);
				if (Math.abs(off) > 84) {
					continue;
				}
				int px = mid + Math.round(off * perDegree);
				int w = p.width(POINTS[i], HudFaces.LABEL);
				p.text(POINTS[i], HudFaces.LABEL, px - w / 2, p.baseline(y), i == 2 ? s.accent : s.muted);
			}
			int r = Math.max(2, s.px(2.5F));
			for (int i = 0; i < count; i++) {
				float off = bearings[i];
				boolean beyond = Math.abs(off) > 88;
				int px = mid + Math.round(Math.clamp(off, -88, 88) * perDegree);
				int radius = beyond ? Math.max(1, r * 2 / 3) : r;
				p.box(px - radius, trackY - radius, 2 * radius, 2 * radius, radius, colors[i]);
			}
		}

		@Override
		String emptyReason() {
			return "Shows when you have teammates";
		}
	}

	/**
	 * The scoreboard or the boss bars: Minecraft draws them; this view only holds their place and size (Minecraft's own, in
	 * GUI units, times the placement's scale), so the HUD editor can move and resize them. With nothing to show on the HUD
	 * it is empty; in the editor and previews it draws a plain sample so it can still be placed.
	 */
	static final class VanillaSpotView extends ModuleView {
		private static final HudText SIDEBAR = new HudText("Scoreboard");
		private static final HudText BOSS = new HudText("Boss bar");
		private final boolean sidebar;
		private float w;
		private float h;
		private boolean real;
		private boolean sample;

		VanillaSpotView(String id, int surface, boolean sidebar) {
			super(id, surface);
			this.sidebar = sidebar;
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			real = false;
			if (sidebar) {
				int[] size = mode == PLACEHOLDER ? null : HudData.sidebarCached();
				if (size != null) {
					real = true;
					w = size[0] + 4;
					h = Math.min(size[1], 15) * 9 + 10;
				} else {
					w = 92;
					h = 4 * 9 + 10;
				}
			} else {
				int bars = mode == PLACEHOLDER ? 0 : HudData.bossBars();
				real = bars > 0;
				w = 182;
				h = 14 + 19 * (Math.max(bars, 1) - 1);
			}
			sample = !real && mode != HUD;
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			boolean shown = real || sample;
			width = shown ? p.s.px(w) : 0;
			height = shown ? p.s.px(h) : 0;
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			if (real) {
				// Minecraft draws it here (Mc263Overlay maps its own spot onto this rectangle).
				return;
			}
			HudStyle s = p.s;
			if (sidebar) {
				int title = s.px(10);
				p.fill(x, y, x + width, y + title, 0x66000000);
				p.fill(x, y + title, x + width, y + height, 0x4D000000);
				p.textCentered(SIDEBAR, HudFaces.LABEL, x + width / 2, y + (title + p.cap(HudFaces.LABEL)) / 2, s.text, width);
				for (int i = 0; i < 4; i++) {
					int ly = y + title + s.px(4) + i * s.px(9);
					p.fill(x + s.px(3), ly + s.px(2), x + s.px(3) + width * (5 - i) / 8, ly + s.px(5), Palette.withAlpha(s.text, 0.35F));
				}
			} else {
				p.textCentered(BOSS, HudFaces.LABEL, x + width / 2, y + p.cap(HudFaces.LABEL), s.text, width);
				int bar = y + s.px(9);
				p.fill(x, bar, x + width, bar + s.px(5), 0x66000000);
				p.fill(x, bar, x + width * 3 / 4, bar + s.px(5), 0xFFE5484D);
			}
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
			return sidebar ? "Shows while the server has a scoreboard up" : "Shows while a boss bar is up";
		}
	}

	/** Item Info: the item you just took in hand (its name, count, enchantments, durability...), fading after a moment. */
	static final class HeldView extends ModuleView {
		private static final int MAX = 12;
		private static final String[] SAMPLE = {"Diamond Sword", "Sharpness V", "Unbreaking III", "1404 / 1561"};
		private static final int[] SAMPLE_COLORS = {0x55FFFF, 0xB9A7FF, 0xB9A7FF, 0xC0C0C0};
		private final HudText[] lines = new HudText[MAX];
		private final int[] colors = new int[MAX];
		private final int[] widths = new int[MAX];
		/** The count after the name, in its own colour, and its width. */
		private final HudText amount = new HudText("");
		private int countColor;
		private int countWidth;
		private boolean hasCount;
		private int count;
		private int revision = Integer.MIN_VALUE;
		private float alpha = 1;
		private boolean visible;

		HeldView(int surface) {
			super(MoreHudMods.ItemInfo.ID, surface);
			for (int i = 0; i < MAX; i++) {
				lines[i] = new HudText("");
			}
		}

		@Override
		void update(ModuleState state, int mode, long nowNanos) {
			HudLooks.Held held = mode == HUD || mode == PREVIEW ? HudData.held() : null;
			if (held != null) {
				if (held.revision != revision) {
					revision = held.revision;
					count = Math.min(MAX, held.lines.size());
					for (int i = 0; i < count; i++) {
						lines[i].set(held.lines.get(i));
						colors[i] = 0xFF000000 | held.colors.get(i);
					}
					amount.set(held.count);
					hasCount = !held.count.isEmpty();
					countColor = 0xFF000000 | held.countColor;
				}
				if (mode == HUD && state.bool(MoreHudMods.ItemInfo.FADE)) {
					float shown = (nowNanos - held.since) / 1.0E9F;
					alpha = Math.clamp(1 - (shown - state.decimal(MoreHudMods.ItemInfo.SHOW_FOR)) / 0.35F, 0, 1);
				} else {
					alpha = 1;
				}
				visible = count > 0 && alpha > 0;
			} else if (mode == HUD) {
				visible = false;
			} else {
				if (revision != Integer.MIN_VALUE + 1) {
					revision = Integer.MIN_VALUE + 1;
					count = SAMPLE.length;
					for (int i = 0; i < count; i++) {
						lines[i].set(mode == PLACEHOLDER ? "—" : SAMPLE[i]);
						colors[i] = 0xFF000000 | SAMPLE_COLORS[i];
					}
					amount.set(mode == PLACEHOLDER ? "" : "  x1");
					hasCount = mode != PLACEHOLDER;
					countColor = 0xFF000000 | state.rgb(MoreHudMods.ItemInfo.COUNT_COLOR);
				}
				alpha = 1;
				visible = true;
			}
		}

		@Override
		float opacity() {
			return alpha;
		}

		@Override
		void measure(HudPen p, ModuleState state) {
			if (!visible) {
				width = 0;
				height = 0;
				return;
			}
			int w = 0;
			countWidth = !hasCount ? 0 : p.width(amount, HudFaces.VALUE, p.s.px(60));
			for (int i = 0; i < count; i++) {
				widths[i] = p.width(lines[i], i == 0 ? HudFaces.VALUE : HudFaces.LABEL, p.s.px(220)) + (i == 0 ? countWidth : 0);
				w = Math.max(w, widths[i]);
			}
			width = w;
			height = linesHeight(p, count);
		}

		@Override
		void draw(HudPen p, ModuleState state, int x, int y) {
			HudStyle s = p.s;
			for (int i = 0; i < count; i++) {
				int top = y + i * (s.lineH + s.lineGap);
				int left = x + (width - widths[i]) / 2;
				p.text(lines[i], i == 0 ? HudFaces.VALUE : HudFaces.LABEL, left, p.baseline(top), colors[i], s.px(220));
				if (i == 0 && countWidth > 0) {
					p.text(amount, HudFaces.VALUE, left + widths[0] - countWidth, p.baseline(top), countColor, s.px(60));
				}
			}
		}

		@Override
		void forget() {
			visible = false;
		}

		@Override
		String emptyReason() {
			return "Take an item in hand";
		}
	}
}

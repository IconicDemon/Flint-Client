package com.flintclient.client.ui.widget;

import com.flintclient.client.module.servers.QuickPlay;
import com.flintclient.client.module.servers.ServerShortcuts;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.function.Consumer;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * One Quick Play shortcut on the main menu's left rail: a pinned server, shown by its own server icon (or, without one,
 * a two-letter monogram of its name). Click or Enter joins it through Minecraft's normal connection screen. The tooltip
 * gives its full name, address and last real status; the dot shows that status (green only after a successful ping).
 * Pins are added and removed in Multiplayer.
 */
public final class ServerSlot extends FlintButton {
	private final QuickPlay.Entry pin;
	private final @Nullable Identifier icon;
	private final TextLine monogram;
	private final Consumer<QuickPlay.Entry> onJoin;
	private ServerShortcuts.@Nullable Status shownStatus;

	/** {@code icon}: the server's icon texture (64 × 64), or null for the monogram. */
	public ServerSlot(QuickPlay.Entry pin, @Nullable Identifier icon, Consumer<QuickPlay.Entry> onJoin) {
		super(Component.translatable("flintclient.quickplay.join", pin.name()), () -> {
		});
		this.pin = pin;
		this.icon = icon;
		this.monogram = new TextLine(FlintFonts.SMALL_BOLD, QuickPlay.monogram(pin.name()));
		this.onJoin = onJoin;
	}

	public QuickPlay.Entry pin() {
		return pin;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		super.onPress(input);
		onJoin.accept(pin);
	}

	private void updateTooltip(ServerShortcuts.Status status) {
		if (status == shownStatus) {
			return;
		}
		shownStatus = status;
		Component state = switch (status) {
			case ONLINE -> Component.translatable("flintclient.shortcut.status.online", ServerShortcuts.pingMillis(pin));
			case INCOMPATIBLE -> Component.translatable("flintclient.shortcut.status.incompatible", String.valueOf(ServerShortcuts.reportedVersion(pin)));
			case OFFLINE -> Component.translatable("flintclient.shortcut.status.offline");
			case CHECKING -> Component.translatable("flintclient.shortcut.status.checking");
			default -> Component.translatable("flintclient.shortcut.status.unknown");
		};
		tooltip(Component.translatable("flintclient.quickplay.tooltip", pin.name(), pin.address(), state));
	}

	@Override
	protected void paint(Canvas c) {
		ServerShortcuts.Status status = ServerShortcuts.status(pin);
		updateTooltip(status);
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover), Palette.SURFACE_PRESS, press);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
		c.panel(x, y, w, h, c.px(MenuButton.RADIUS), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);
		if (icon != null) {
			// The server's own icon, inset from the border.
			int inset = c.px(1.6F);
			int size = Math.min(w, h) - 2 * inset;
			int color = Palette.lerp(0xFFD8D8D8, 0xFFFFFFFF, Math.max(hover, focus));
			c.graphics().blit(RenderPipelines.GUI_TEXTURED, icon, x + (w - size) / 2, y + (h - size) / 2, 0, 0, size, size, 64, 64, 64, 64, c.tint(color));
		} else {
			int ink = Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(hover, focus));
			c.text(monogram, x + (w - c.width(monogram)) / 2, c.baselineFor(monogram, y + h / 2), ink);
		}

		int dot = c.px(2.6F);
		int ring = Math.max(1, Math.round(c.scale() / 3.0F));
		int dx = x + w - dot + c.px(0.6F);
		int dy = y - c.px(0.6F);
		int color = switch (status) {
			case ONLINE -> Palette.STATUS_ONLINE;
			case INCOMPATIBLE -> Palette.STATUS_INCOMPATIBLE;
			case OFFLINE -> Palette.STATUS_OFFLINE;
			default -> Palette.STATUS_UNKNOWN;
		};
		// A ring in the background colour separates the dot from the slot's border.
		c.roundRect(dx - ring, dy - ring, dot + 2 * ring, dot + 2 * ring, (dot + 2 * ring) / 2, Palette.BACKGROUND_TOP);
		c.roundRect(dx, dy, dot, dot, dot / 2, color);
	}
}

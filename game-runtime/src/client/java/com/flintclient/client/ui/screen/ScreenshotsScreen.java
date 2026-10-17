package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.GpuResourceTracker;
import com.flintclient.client.module.screenshots.ScreenshotLibrary;
import com.flintclient.client.module.screenshots.Thumbnails;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Paragraph;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.widget.FlintButton;
import com.flintclient.client.ui.widget.MenuButton;
import com.mojang.blaze3d.Blaze3D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A gallery of the profile's real screenshots: a paged grid of thumbnails and a viewer with previous /
 * next, "Open" (the system image viewer) and "Show in folder". Only the visible page is decoded, off
 * the render thread, at the size it's shown; decodes for a page or size no longer shown are cancelled
 * before they start, and all textures are freed when the page changes or closes.
 */
public final class ScreenshotsScreen extends FlintPageScreen {
	private static final int TILE_W = 76;
	private static final int TILE_H = 43;
	private static final int GAP = 6;
	private static final int BAR_H = 15;
	private static final float TILE_RADIUS = 2.2F;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ROOT);

	private record Picture(Identifier id, int width, int height, int sourceWidth, int sourceHeight) {
	}

	private @Nullable List<ScreenshotLibrary.Shot> shots;
	private final Thumbnails<Picture> thumbnails = new Thumbnails<>();
	private int generation;
	private int page;
	private int pages = 1;
	private int viewing = -1;
	private int contentTop;
	private int areaBottom;
	private final TextLine status = new TextLine(FlintFonts.SMALL, "");
	private final TextLine info = new TextLine(FlintFonts.SMALL, "");
	private final Paragraph emptyText = new Paragraph(FlintFonts.LABEL, Component.translatable("flintclient.screenshots.empty").getString());

	public ScreenshotsScreen(Screen parent) {
		super(parent, Component.translatable("flintclient.menu.screenshots"));
	}

	@Override
	protected void initPage(int top) {
		contentTop = top;
		areaBottom = height - 14 - BAR_H - 8;
		if (shots == null) {
			int requested = generation;
			ScreenshotLibrary.list().thenAcceptAsync(list -> {
				if (requested == generation) {
					shots = list;
					rebuildWidgets();
				}
			}, minecraft);
		}
		if (viewing >= 0 && shots != null && viewing < shots.size()) {
			initViewer();
		} else {
			viewing = -1;
			initGrid();
		}
	}

	private void initGrid() {
		MenuButton folder = addRenderableWidget(new MenuButton(Component.translatable("flintclient.screenshots.folder"), Icon.FOLDER, MenuButton.Style.NEUTRAL, this::openFolder));
		folder.setRectangle(84, BAR_H, MARGIN, height - 14 - BAR_H);
		if (shots == null || shots.isEmpty()) {
			status.set(shots == null ? Component.translatable("flintclient.screenshots.loading").getString() : "");
			return;
		}
		int areaW = width - 2 * MARGIN;
		int cols = Math.max(1, (areaW + GAP) / (TILE_W + GAP));
		int rows = Math.max(1, (areaBottom - contentTop + GAP) / (TILE_H + GAP));
		int perPage = cols * rows;
		pages = Math.max(1, (shots.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int gridW = cols * TILE_W + (cols - 1) * GAP;
		int left = (width - gridW) / 2;
		int first = page * perPage;
		for (int i = first; i < Math.min(shots.size(), first + perPage); i++) {
			int slot = i - first;
			Tile tile = addRenderableWidget(new Tile(i));
			tile.setRectangle(TILE_W, TILE_H, left + (slot % cols) * (TILE_W + GAP), contentTop + (slot / cols) * (TILE_H + GAP));
		}
		status.set(Component.translatable("flintclient.screenshots.count", shots.size(), page + 1, pages).getString());
		if (pages > 1) {
			MenuButton previous = addRenderableWidget(new MenuButton(Component.translatable("flintclient.page.previous"), Icon.CHEVRON_LEFT, MenuButton.Style.NEUTRAL, () -> turn(-1)));
			previous.active = page > 0;
			previous.setRectangle(56, BAR_H, width - MARGIN - 2 * 56 - 4, height - 14 - BAR_H);
			MenuButton next = addRenderableWidget(new MenuButton(Component.translatable("flintclient.page.next"), Icon.CHEVRON_RIGHT, MenuButton.Style.NEUTRAL, () -> turn(1)));
			next.active = page < pages - 1;
			next.setRectangle(56, BAR_H, width - MARGIN - 56, height - 14 - BAR_H);
		}
		int scale = Canvas.measuring().scale();
		int border = tileBorder(scale);
		// Thumbnails are cut to the tile's inner size with its rounded corners, drawn 1:1.
		keepOnly(first, Math.min(shots.size(), first + perPage), TILE_W * scale - 2 * border, TILE_H * scale - 2 * border, true,
			Math.round(TILE_RADIUS * scale) - border);
	}

	private void initViewer() {
		ScreenshotLibrary.Shot shot = shots.get(viewing);
		int bar = height - 14 - BAR_H;
		MenuButton previous = addRenderableWidget(new MenuButton(Component.translatable("flintclient.page.previous"), Icon.CHEVRON_LEFT, MenuButton.Style.NEUTRAL, () -> view(viewing - 1)));
		previous.active = viewing > 0;
		previous.setRectangle(56, BAR_H, MARGIN, bar);
		MenuButton next = addRenderableWidget(new MenuButton(Component.translatable("flintclient.page.next"), Icon.CHEVRON_RIGHT, MenuButton.Style.NEUTRAL, () -> view(viewing + 1)));
		next.active = viewing < shots.size() - 1;
		next.setRectangle(56, BAR_H, MARGIN + 60, bar);
		MenuButton folder = addRenderableWidget(new MenuButton(Component.translatable("flintclient.screenshots.reveal"), Icon.FOLDER, MenuButton.Style.NEUTRAL, this::openFolder));
		folder.setRectangle(84, BAR_H, width - MARGIN - 84, bar);
		MenuButton open = addRenderableWidget(new MenuButton(Component.translatable("flintclient.screenshots.open"), null, MenuButton.Style.NEUTRAL, () -> Blaze3D.openPath(shot.path())));
		open.setRectangle(56, BAR_H, width - MARGIN - 84 - 60, bar);
		setInitialFocus(next.active ? next : previous);
		int scale = Canvas.measuring().scale();
		keepOnly(viewing, viewing + 1, (width - 2 * MARGIN) * scale, (areaBottom - contentTop) * scale, false, Math.round(TILE_RADIUS * scale));
		info.set(shot.name() + "  ·  " + DATE.format(Instant.ofEpochMilli(shot.modified()).atZone(ZoneId.systemDefault())));
	}

	private void turn(int delta) {
		page += delta;
		rebuildWidgets();
	}

	private void view(int index) {
		viewing = index;
		rebuildWidgets();
	}

	@Override
	public void onClose() {
		if (viewing >= 0) {
			viewing = -1;
			rebuildWidgets();
		} else {
			super.onClose();
		}
	}

	private void openFolder() {
		try {
			java.nio.file.Files.createDirectories(ScreenshotLibrary.folder());
		} catch (Exception ignored) {
			// The system reports it if the folder can't be opened.
		}
		Blaze3D.openPath(ScreenshotLibrary.folder());
	}

	/** Frees pictures outside {@code [from, to)} or at a different display size, and loads missing ones. */
	private void keepOnly(int from, int to, int maxW, int maxH, boolean cover, int radius) {
		thumbnails.keepOnly(from, to, maxW, maxH, cover, this::free, (want, ticket) -> load(want, ticket, radius));
	}

	private void free(Picture picture) {
		minecraft.getTextureManager().release(picture.id());
		GpuResourceTracker.get().release(GpuResourceTracker.Kind.SCREENSHOT, GpuResourceTracker.rgba8(picture.width(), picture.height()));
	}

	private static int tileBorder(int scale) {
		return Math.max(1, Math.round(scale / 4.0F));
	}

	private void load(Thumbnails.Want want, Thumbnails.Ticket ticket, int radius) {
		int requested = generation;
		int[] size = new int[2];
		ScreenshotLibrary.load(shots.get(want.index()).path(), want.width(), want.height(), want.cover(), radius, size, ticket::wanted).whenCompleteAsync((image, error) -> {
			if (image == null) {
				// Skipped because it was no longer wanted, or failed.
				if (thumbnails.finish(want, ticket, null) && error != null) {
					FlintClient.LOGGER.warn("Couldn't load a screenshot thumbnail", error);
				}
				return;
			}
			Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "dynamic/screenshot_" + requested + "_" + want.index() + "_"
				+ want.width() + "x" + want.height() + (want.cover() ? "c" : "f"));
			if (!thumbnails.finish(want, ticket, new Picture(id, image.getWidth(), image.getHeight(), size[0], size[1]))) {
				// Arrived after its page, size or screen was left: never shown.
				image.close();
				return;
			}
			minecraft.getTextureManager().register(id, new DynamicTexture(() -> "Flint screenshot", image));
			GpuResourceTracker.get().register(GpuResourceTracker.Kind.SCREENSHOT, GpuResourceTracker.rgba8(image.getWidth(), image.getHeight()));
		}, minecraft);
	}

	@Override
	public void removed() {
		generation++;
		thumbnails.clear(this::free);
	}

	@Override
	protected void paintPage(Canvas c) {
		int barCenter = c.px(height - 14 - BAR_H / 2.0F);
		if (viewing >= 0) {
			Picture picture = thumbnails.picture(viewing);
			int boxX = c.px(MARGIN);
			int boxY = c.px(contentTop);
			int boxW = c.px(width - 2 * MARGIN);
			int boxH = c.px(areaBottom - contentTop);
			if (picture != null) {
				int x = boxX + (boxW - picture.width()) / 2;
				int y = boxY + (boxH - picture.height()) / 2;
				c.graphics().blit(RenderPipelines.GUI_TEXTURED, picture.id(), x, y, 0, 0, picture.width(), picture.height(), picture.width(), picture.height());
				TextLine dims = info;
				c.text(dims, (c.px(width) - c.width(dims)) / 2, c.baselineFor(dims, barCenter), Palette.MUTED);
			}
			return;
		}
		if (shots != null && shots.isEmpty()) {
			int w = c.px(Math.min(190, width - 2 * MARGIN));
			int baseline = c.px(height / 2.0F - 8);
			for (TextLine line : emptyText.lines(c, w)) {
				c.text(line, (c.px(width) - c.width(line)) / 2, baseline, Palette.TEXT_DIM);
				baseline += emptyText.lineHeightPx(c);
			}
			return;
		}
		c.text(status, (c.px(width) - c.width(status)) / 2, c.baselineFor(status, barCenter), Palette.MUTED);
	}

	/** One thumbnail; opens the viewer. Its name and date are the tooltip and narration. */
	private final class Tile extends FlintButton {
		private final int index;

		Tile(int index) {
			super(Component.literal(shots.get(index).name()), () -> view(index));
			this.index = index;
			ScreenshotLibrary.Shot shot = shots.get(index);
			tooltip(Component.literal(shot.name() + "\n" + DATE.format(Instant.ofEpochMilli(shot.modified()).atZone(ZoneId.systemDefault()))));
		}

		@Override
		protected void paint(Canvas c) {
			int x = c.px(getX());
			int y = c.px(getY());
			int w = c.px(getWidth());
			int h = c.px(getHeight());
			int border = tileBorder(c.scale());
			int radius = Math.round(TILE_RADIUS * c.scale());
			int ring = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, Math.max(focus, hover * 0.35F));
			Picture picture = thumbnails.picture(index);
			if (picture == null) {
				c.roundRect(x, y, w, h, radius, Palette.SURFACE);
			} else {
				// Cut to the tile's inner size with rounded corners when loaded, so it's drawn 1:1.
				int cw = Math.min(w - 2 * border, picture.width());
				int ch = Math.min(h - 2 * border, picture.height());
				c.graphics().blit(RenderPipelines.GUI_TEXTURED, picture.id(), x + (w - cw) / 2, y + (h - ch) / 2, 0, 0, cw, ch, picture.width(), picture.height());
			}
			c.ring(x, y, w, h, radius, border + (focus > 0.5F ? border : 0), ring);
		}
	}
}

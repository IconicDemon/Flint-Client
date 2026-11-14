package com.flintclient.client.ui.widget;

import com.flintclient.client.module.social.MenuArt;
import com.flintclient.client.module.social.MenuFeeds;
import com.flintclient.client.module.social.MenuLink.Slide;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.Motion;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The main menu's showcase: a small 16:9 card that turns through the slides Flint's admins put up (partners, events), each
 * {@code rotate_ms} long, with a thin bar per slide under it filling as its time runs. The pointer over it holds the slide; a
 * click opens the slide's link after Minecraft's link confirmation (a slide without a link isn't clickable). Slides change with
 * a short cross-fade. A slide whose picture can't be had is skipped, and the card shows nothing until a picture is there: never
 * an empty frame. Everything comes from the Flint app ({@link MenuFeeds}); with no slides there is no card at all.
 */
public final class ShowcaseCard extends FlintButton {
	public static final float BARS = 4.0F;
	private static final float FADE_MILLIS = 380.0F;

	private final MenuFeeds feeds;
	private final Consumer<String> open;
	private List<Slide> slides = List.of();
	private int current;
	private int previous = -1;
	private float elapsed;
	private float fade = 1.0F;
	private @Nullable String tooltipFor;

	public ShowcaseCard(MenuFeeds feeds, Consumer<String> open) {
		super(Component.translatable("flintclient.showcase.open"), () -> {
		});
		this.feeds = feeds;
		this.open = open;
	}

	/** The slides to turn through (the feed's, running now). */
	public void slides(List<Slide> next) {
		Slide shown = slide(current);
		slides = List.copyOf(next);
		current = 0;
		if (shown != null) {
			for (int i = 0; i < slides.size(); i++) {
				if (slides.get(i).id().equals(shown.id())) {
					current = i;
				}
			}
		}
		previous = -1;
	}

	private @Nullable Slide slide(int i) {
		return i >= 0 && i < slides.size() ? slides.get(i) : null;
	}

	private static MenuArt.Entry art(Slide slide) {
		String id = slide.id();
		return MenuArt.art("slide/" + id, 640, 360, link -> link.showcaseArt(id));
	}

	/** The slides whose pictures are there now (in order); the card shows only these. */
	private List<Integer> ready() {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < slides.size(); i++) {
			MenuArt.Entry entry = art(slides.get(i));
			if (!entry.failed() && entry.texture() != null) {
				out.add(i);
			}
		}
		return out;
	}

	/** Whether there is something to show (a slide with its picture). */
	public boolean showable() {
		return !ready().isEmpty();
	}

	@Override
	public void activate() {
		Slide slide = slide(current);
		if (slide != null && slide.link() != null) {
			super.activate();
			open.accept(slide.link());
		}
	}

	private boolean broken;

	/** A fault while drawing takes the card off the menu (logged once); the menu itself carries on. */
	@Override
	protected void paint(Canvas c) {
		if (broken) {
			return;
		}
		try {
			paintCard(c);
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			active = false;
			visible = false;
			com.flintclient.client.FlintClient.LOGGER.warn("Flint: the main menu's " + getClass().getSimpleName() + " failed and is hidden", e);
		}
	}

	private void paintCard(Canvas c) {
		List<Integer> ready = ready();
		if (ready.isEmpty()) {
			return;
		}
		if (!ready.contains(current)) {
			current = ready.get(0);
			elapsed = 0;
		}
		long rotate = Math.max(1000, feeds.rotateMillis());
		boolean held = isHovered() || isFocused();
		if (!held && ready.size() > 1) {
			elapsed += Motion.frameSeconds() * 1000.0F;
			if (elapsed >= rotate) {
				int at = ready.indexOf(current);
				previous = current;
				current = ready.get((at + 1) % ready.size());
				elapsed = 0;
				fade = 0;
			}
		}
		fade = Motion.reduced() ? 1.0F : Motion.toward(fade, 1.0F, FADE_MILLIS);
		Slide slide = slides.get(current);
		if (!slide.id().equals(tooltipFor)) {
			tooltipFor = slide.id();
			String words = slide.title().isEmpty() ? slide.alt() : slide.title();
			tooltip(Component.literal(words.isEmpty() ? "Flint showcase" : words + (slide.link() == null ? "" : "\n" + host(slide.link()))));
			setMessage(Component.literal(words.isEmpty() ? "Flint showcase" : words));
		}

		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int bars = c.px(BARS);
		int h = c.px(getHeight()) - bars;
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int radius = c.px(3.2F);
		boolean link = slide.link() != null;
		float lit = link ? Math.max(hover, focus) : 0;
		// A soft shadow under the card, then the picture in it.
		c.roundRect(x - borderPx, y, w + 2 * borderPx, h + 2 * borderPx, radius + borderPx, 0x33000000);
		float zoom = 1.0F + 0.02F * Motion.smooth(lit);
		if (previous >= 0 && fade < 1.0F && slide(previous) != null) {
			picture(c, slides.get(previous), x, y, w, h, radius, 1.0F, zoom);
		}
		picture(c, slide, x, y, w, h, radius, Motion.smooth(fade), zoom);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, lit), Palette.ACCENT, focus * 0.85F);
		c.ring(x, y, w, h, radius, borderPx + (focus > 0.5F ? borderPx : 0), border);

		// One thin bar per slide, the shown one filling as its time runs.
		if (ready.size() > 1) {
			int gap = Math.max(1, c.px(1.6F));
			int barH = Math.max(1, Math.round(c.scale() * 0.55F));
			int top = y + h + bars - barH;
			int n = ready.size();
			int each = (w - (n - 1) * gap) / n;
			int at = ready.indexOf(current);
			for (int i = 0; i < n; i++) {
				int bx = x + i * (each + gap);
				int bw = i == n - 1 ? x + w - bx : each;
				c.roundRect(bx, top, bw, barH, barH / 2, 0x33FFFFFF);
				float done = i < at ? 1.0F : i == at ? Math.min(1.0F, elapsed / rotate) : 0.0F;
				if (done > 0) {
					c.roundRect(bx, top, Math.max(barH, Math.round(bw * done)), barH, barH / 2, Palette.withAlpha(Palette.TEXT, held ? 0.95F : 0.8F));
				}
			}
		}
	}

	/** A slide's picture covering the box with the card's corners, at {@code alpha}, slightly enlarged by {@code zoom}. */
	private static void picture(Canvas c, Slide slide, int x, int y, int w, int h, int radius, float alpha, float zoom) {
		MenuArt.Entry entry = art(slide);
		Texture texture = entry.texture();
		if (texture != null && entry.width() > 0) {
			Pictures.cover(c, texture, entry.width(), entry.height(), x, y, w, h, radius, zoom, Palette.withAlpha(0xFFFFFFFF, alpha));
		}
	}

	private static String host(String url) {
		String rest = url.substring(url.indexOf("//") + 2);
		int end = rest.indexOf('/');
		return end < 0 ? rest : rest.substring(0, end);
	}
}

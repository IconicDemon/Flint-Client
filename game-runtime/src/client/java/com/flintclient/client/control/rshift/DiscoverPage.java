package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.module.social.MenuArt;
import com.flintclient.client.module.social.MenuFeeds;
import com.flintclient.client.module.social.MenuLink;
import com.flintclient.client.module.social.MenuLink.Partner;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The Discover window's content ({@link FlintDiscoverScreen}): DISCOVER and how many partner servers there are across the top,
 * a filter by kind when there is more than one, and a grid of listings as the launcher's Library → Partnerships shows them.
 * Each tile has the listing's wide picture (Flint's own colours while it loads, or when there is none), its label, logo, name
 * and kind, its short description, the server's address, versions and the players the Store counted in the last ten minutes,
 * then its main button (Join, Join Discord, Visit website) and its verified links. When there is nothing to show yet (no
 * Flint, an older Flint, the Store offline at first) the page says why. Sizes in design pixels ({@link MenuStyle#d}).
 */
final class DiscoverPage extends Node {
	private static final float PAD = 28;
	private static final float HEAD = 64;
	private static final float GAP = 18;
	private static final float TILE_MIN = 380;
	private static final float BODY = 244;
	private static final float CHIP_H = 34;
	private static final long NOTE_MILLIS = 5000;

	private enum Filter {
		ALL("All", null),
		SERVERS("Servers", "minecraft_server"),
		COMMUNITIES("Communities", "discord"),
		HOSTING("Hosting", "hosting");

		final String text;
		final @Nullable String kind;

		Filter(String text, @Nullable String kind) {
			this.text = text;
			this.kind = kind;
		}
	}

	private final Object screen;
	private final Consumer<String> openLink;
	private final MenuFeeds feeds = MenuFeeds.get();
	private final Label title = new Label(Type.HEADER_TITLE, "DISCOVER");
	private final Label count = new Label(Type.CHIP, "");
	private final Lines intro = new Lines(Type.ROW_NOTE, "Servers and communities that partner with Flint. Every listing is checked by Flint before it shows.");
	private final List<FilterChip> chips = new ArrayList<>();
	private final Grid grid = new Grid();
	private final ScrollView scroll;
	private final StatePanel state;
	private final Lines note = new Lines(Type.ROW_NOTE, "");
	private boolean noteBad;
	private long noteAt = Long.MIN_VALUE;
	private Filter filter = Filter.ALL;
	private int shownRevision = -1;
	private Filter shownFilter = Filter.ALL;
	private @Nullable String joining;

	DiscoverPage(Object screen, Consumer<String> openLink) {
		this.screen = screen;
		this.openLink = openLink;
		for (Filter f : Filter.values()) {
			chips.add(add(new FilterChip(f)));
		}
		scroll = add(new ScrollView(grid));
		scroll.flatBar(MenuStyle.SCROLL_THUMB, d(10), d(70));
		state = add(new StatePanel());
		feeds.version(MenuHost.minecraftVersion());
		feeds.want(MenuFeeds.Feed.PARTNERS, null, System.currentTimeMillis());
	}

	/** Asks for the listings when due, takes answers, and rebuilds the grid when they change. */
	void step() {
		feeds.want(MenuFeeds.Feed.PARTNERS, null, System.currentTimeMillis());
		feeds.tick();
		if (feeds.revision() != shownRevision || filter != shownFilter) {
			shownRevision = feeds.revision();
			shownFilter = filter;
			rebuild();
		}
	}

	private List<Partner> shown() {
		List<Partner> out = new ArrayList<>();
		for (Partner p : feeds.partners().partners()) {
			if (filter.kind == null || filter.kind.equals(p.kind())) {
				out.add(p);
			}
		}
		return out;
	}

	private void rebuild() {
		List<Partner> all = feeds.partners().partners();
		for (FilterChip chip : chips) {
			boolean present = chip.filter == Filter.ALL || all.stream().anyMatch(p -> p.kind().equals(chip.filter.kind));
			chip.setVisible(present && kinds(all) > 1);
		}
		if (filter != Filter.ALL && all.stream().noneMatch(p -> p.kind().equals(filter.kind))) {
			filter = Filter.ALL;
			shownFilter = filter;
		}
		int servers = feeds.partners().servers();
		count.set(servers == 1 ? "1 server" : servers + " servers");
		grid.set(shown());
		boolean empty = all.isEmpty();
		scroll.setVisible(!empty);
		state.setVisible(empty);
		invalidate();
	}

	private static int kinds(List<Partner> all) {
		return (int) all.stream().map(Partner::kind).distinct().count();
	}

	void say(String text, boolean bad) {
		note.set(text);
		noteBad = bad;
		noteAt = Clock.millis();
	}

	// ---------- actions ----------

	/** Join: the launcher checks the server again (still listed, public, this version), then Minecraft's own screen joins it. */
	private void join(Partner partner) {
		if (joining != null) {
			return;
		}
		String blocked = MenuHost.multiplayerBlocked();
		if (blocked != null) {
			say(blocked, true);
			return;
		}
		joining = partner.id();
		String version = MenuHost.minecraftVersion();
		feeds.run(link -> link.partnerJoin(partner.id(), version), target -> {
			joining = null;
			MenuHost.joinServer(screen, target.name(), target.address());
		}, why -> {
			joining = null;
			say("unreachable".equals(why) ? "Flint isn't running. Open the Flint app and try again." : why, true);
		});
	}

	private void primary(Partner partner) {
		if (partner.server()) {
			join(partner);
			return;
		}
		String url = "discord".equals(partner.kind()) ? partner.links().getOrDefault("discord", partner.primaryUrl())
			: partner.primaryUrl() != null ? partner.primaryUrl() : partner.links().get("website");
		if (url != null) {
			openLink.accept(url);
		}
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		float left = x + d(PAD);
		float inner = w - 2 * d(PAD);
		float chipX = x + w - d(PAD);
		for (int i = chips.size() - 1; i >= 0; i--) {
			FilterChip chip = chips.get(i);
			if (!chip.visible()) {
				continue;
			}
			float cw = chip.prefWidth();
			chipX -= cw;
			chip.setBounds(chipX, y + d(PAD) + d(HEAD) / 2 - d(CHIP_H) / 2, cw, d(CHIP_H));
			chipX -= d(8);
		}
		float top = y + d(PAD) + d(HEAD) + d(36);
		float bottom = y + h - d(PAD) - d(24);
		scroll.setBounds(left - d(6), top, inner + d(12), Math.max(d(40), bottom - top));
		state.setBounds(x, y + d(HEAD), w, h - d(HEAD));
	}

	@Override
	protected void paint(Canvas c) {
		float left = x + d(PAD);
		float headY = y + d(PAD) + d(HEAD) / 2;
		Glyph.COMPASS.draw(c, left + d(14), headY, d(26), MenuStyle.ACCENT);
		MenuStyle.text(c, title, left + d(40), headY, MenuStyle.TEXT);
		if (!feeds.partners().partners().isEmpty()) {
			float tx = left + d(40) + title.width(c) + d(16);
			float cw = count.width(c) + d(20);
			MenuStyle.box(c, tx, headY - d(13), cw, d(26), d(13), MenuStyle.PILL);
			MenuStyle.text(c, count, tx + d(10), headY + d(0.75F), MenuStyle.BADGE_TEXT);
		}
		intro.paint(c, left, y + d(PAD) + d(HEAD) - d(2), w - 2 * d(PAD), MenuStyle.NEUTRAL);
		long since = Clock.millis() - noteAt;
		if (since < NOTE_MILLIS) {
			float k = Math.min(1, (NOTE_MILLIS - since) / 400.0F);
			note.paint(c, left, y + h - d(PAD) - d(14), w - 2 * d(PAD), Palette.withAlpha(noteBad ? MenuStyle.DANGER_TEXT : MenuStyle.TEXT, k));
		}
	}

	// ---------- the grid ----------

	/** The tiles, as many to a row as fit {@link #TILE_MIN}, sharing the row out evenly. */
	private final class Grid extends Node {
		private final List<Tile> tiles = new ArrayList<>();

		void set(List<Partner> partners) {
			// Tiles are kept by id, so a refreshed feed doesn't make the pictures blink.
			List<Tile> next = new ArrayList<>();
			for (Partner p : partners) {
				Tile tile = null;
				for (Tile t : tiles) {
					if (t.partner.id().equals(p.id())) {
						tile = t;
						break;
					}
				}
				if (tile == null) {
					tile = new Tile(p);
				} else {
					tile.update(p);
				}
				next.add(tile);
			}
			clearChildren();
			tiles.clear();
			for (Tile tile : next) {
				tiles.add(add(tile));
			}
			invalidate();
		}

		private int columns(float width) {
			return Math.max(1, (int) ((width + d(GAP)) / (d(TILE_MIN) + d(GAP))));
		}

		private float tileH(float tileW) {
			return tileW / 2.6F + d(BODY);
		}

		@Override
		public float prefHeight(float width) {
			int cols = columns(width - 2 * d(6));
			float tw = (width - 2 * d(6) - (cols - 1) * d(GAP)) / cols;
			int rows = (tiles.size() + cols - 1) / cols;
			return rows == 0 ? 0 : rows * tileH(tw) + (rows - 1) * d(GAP) + 2 * d(6);
		}

		@Override
		protected void layout() {
			float inner = w - 2 * d(6);
			int cols = columns(inner);
			float tw = (inner - (cols - 1) * d(GAP)) / cols;
			float th = tileH(tw);
			for (int i = 0; i < tiles.size(); i++) {
				int col = i % cols;
				int row = i / cols;
				tiles.get(i).setBounds(x + d(6) + col * (tw + d(GAP)), y + d(6) + row * (th + d(GAP)), tw, th);
			}
		}
	}

	/** One listing. */
	private final class Tile extends Node {
		private Partner partner;
		private final Label name = new Label(Type.NAME, "");
		private final Label kind = new Label(Type.CAPTION, "");
		private final Label label = new Label(Type.BADGE, "");
		private final Lines summary = new Lines(Type.ROW_NOTE, "");
		private final Label address = new Label(Type.CAPTION, "");
		private final Label versions = new Label(Type.CAPTION, "");
		private final Label live = new Label(Type.CAPTION, "");
		private final Label monogram = new Label(Type.PAGE_TITLE, "");
		private final Button main;
		private String mainText = "Join";
		private final List<LinkChip> links = new ArrayList<>();

		Tile(Partner partner) {
			main = add(new Button("Join", Glyph.PLAY, PageControls.Look.ACCENT, () -> primary(this.partner)));
			update(partner);
		}

		void update(Partner p) {
			partner = p;
			name.set(p.name());
			kind.set(switch (p.kind()) {
				case "minecraft_server" -> "Minecraft server";
				case "discord" -> "Discord community";
				default -> "Server hosting";
			});
			label.set(p.label().toUpperCase(Locale.ROOT));
			summary.set(p.summary());
			address.set(p.address() == null ? "" : p.address());
			versions.set(p.versions() == null ? "" : p.versions());
			monogram.set(p.name().substring(0, 1).toUpperCase(Locale.ROOT));
			String liveText = "";
			if (p.stats() != null) {
				liveText = !p.stats().online() ? "Offline now"
					: p.stats().players() >= 0 ? String.format(Locale.ROOT, "%,d online", p.stats().players()) : "Online";
			} else if (p.members() >= 0) {
				liveText = String.format(Locale.ROOT, "%,d members", p.members());
			}
			live.set(liveText);
			name(p.name());
			mainText = p.server() ? p.primaryLabel() != null ? p.primaryLabel() : "Join"
				: "discord".equals(p.kind()) ? "Join Discord" : p.primaryLabel() != null ? p.primaryLabel() : "Visit website";
			main.setText(mainText);
			if (p.server() && !p.joinable()) {
				main.setDisabled(p.versions() == null ? "This server doesn't take Minecraft " + MenuHost.minecraftVersion()
					: "This server takes Minecraft " + p.versions());
			} else {
				main.setEnabled(true);
			}
			for (LinkChip chip : links) {
				remove(chip);
			}
			links.clear();
			for (Map.Entry<String, String> link : p.links().entrySet()) {
				if (!p.server() && "discord".equals(p.kind()) && "discord".equals(link.getKey())) {
					continue;
				}
				links.add(add(new LinkChip(link.getKey(), link.getValue())));
			}
			invalidate();
		}

		private float bannerH() {
			return w / 2.6F;
		}

		@Override
		protected void layout() {
			float pad = d(18);
			float bottom = y + h - pad;
			float bw = Math.max(d(120), main.prefWidth());
			main.setBounds(x + pad, bottom - d(Button.H), bw, d(Button.H));
			float lx = x + w - pad;
			for (int i = links.size() - 1; i >= 0; i--) {
				LinkChip chip = links.get(i);
				float cw = chip.prefWidth();
				if (lx - cw < x + pad + bw + d(10)) {
					chip.setVisible(false);
					continue;
				}
				chip.setVisible(true);
				lx -= cw;
				chip.setBounds(lx, bottom - d(Button.H) / 2 - d(CHIP_H) / 2, cw, d(CHIP_H));
				lx -= d(6);
			}
		}

		@Override
		protected void paint(Canvas c) {
			main.setText(joining != null && joining.equals(partner.id()) ? "Checking…" : mainText);
			float rad = MenuStyle.radius();
			int fill = Palette.lerp(MenuStyle.TILE, MenuStyle.TILE_HOVER, hover);
			{
				MenuStyle.framed(c, x, y, w, h, rad, fill, Palette.lerp(MenuStyle.TILE_EDGE, MenuStyle.CARD_EDGE_HOVER, hover));
				paintBanner(c, fill, rad);
				float pad = d(18);
				float bodyY = y + bannerH();
				// The logo, half over the picture; beside it the name and what's live (players online, members), or the kind.
				float logo = d(52);
				float lx = x + pad;
				float ly = bodyY - logo / 2;
				paintLogo(c, lx, ly, logo, fill);
				float textX = lx + logo + d(14);
				float textW = x + w - pad - textX;
				MenuStyle.fitted(c, name, textX, bodyY + d(12), MenuStyle.TEXT, textW);
				float liveY = bodyY + d(39);
				if (!live.text().isEmpty()) {
					boolean up = partner.stats() == null || partner.stats().online();
					SocialParts.dot(c, textX + d(4), liveY, d(8), up ? SocialParts.ONLINE : MenuStyle.NEUTRAL);
					MenuStyle.fitted(c, live, textX + d(14), liveY, up ? MenuStyle.ROW_TEXT : MenuStyle.NEUTRAL, textW - d(14));
				} else {
					MenuStyle.fitted(c, kind, textX, liveY, MenuStyle.NEUTRAL, textW);
				}
				// Its short description, two lines at most.
				float sy = bodyY + logo / 2 + d(36);
				List<com.flintclient.client.render.TextLine> lines = summary.lines(c, w - 2 * pad);
				float pitch = Lines.pitch(c);
				for (int i = 0; i < Math.min(2, lines.size()); i++) {
					com.flintclient.client.render.TextLine line = lines.get(i);
					int max = c.px(w - 2 * pad);
					if (i == 1 && lines.size() > 2) {
						line = new com.flintclient.client.render.TextLine(line.face(), line.text() + "…");
					}
					c.text(line, c.px(x + pad), c.baselineFor(line, c.px(sy + pitch * i + pitch / 2)), MenuStyle.SENTENCE_TEXT, max);
				}
				// A server's address and the versions it takes, on their own line.
				float fy = sy + 2 * pitch + d(16);
				if (partner.address() != null) {
					Glyph.GLOBE.draw(c, x + pad + d(7), fy, d(13), MenuStyle.NEUTRAL);
					float vw = versions.text().isEmpty() ? 0 : Math.min(versions.width(c), w * 0.4F);
					float ax = x + pad + d(20);
					MenuStyle.fitted(c, address, ax, fy, MenuStyle.ROW_TEXT, x + w - pad - ax - (vw > 0 ? vw + d(14) : 0));
					if (vw > 0) {
						MenuStyle.fitted(c, versions, x + w - pad - vw, fy, MenuStyle.NEUTRAL, vw);
					}
				}
			}
		}

		/** The wide picture, cropped to the tile's shape (Flint's own colours and the listing's initial while it loads). */
		private void paintBanner(Canvas c, int fill, float rad) {
			float bh = bannerH();
			int px = c.px(x) + 1;
			int py = c.px(y) + 1;
			int pw = c.px(w) - 2;
			int ph = c.px(bh) - 1;
			Texture texture = null;
			MenuArt.Entry entry = null;
			MenuLink.Picture banner = partner.banner();
			if (banner != null) {
				String sha = banner.sha();
				entry = MenuArt.art("partner/" + sha, 720, 400, link -> link.partnerArt(sha));
				texture = entry.texture();
			}
			if (texture != null && entry.width() > 0) {
				// Cover: the middle of the picture at the box's shape.
				float boxRatio = pw / (float) ph;
				float texRatio = entry.width() / (float) entry.height();
				int sw = entry.width();
				int sh = entry.height();
				int u = 0;
				int v = 0;
				if (texRatio > boxRatio) {
					sw = Math.round(entry.height() * boxRatio);
					u = (entry.width() - sw) / 2;
				} else {
					sh = Math.round(entry.width() / boxRatio);
					v = (entry.height() - sh) / 2;
				}
				c.textureRegion(texture, px, py, pw, ph, u, v, sw, sh, entry.width(), entry.height(), 0xFFFFFFFF);
			} else {
				int hue = hue(partner.id());
				int top = Palette.lerp(hsv(hue, 0.55F, 0.36F), MenuStyle.TILE, 0.15F);
				int bottom = Palette.lerp(hsv((hue + 40) % 360, 0.6F, 0.18F), MenuStyle.TILE, 0.2F);
				c.gradient(px, py, px + pw, py + ph, top, bottom);
			}
			// A soft shade at the foot, so the logo and label read over any picture.
			c.gradient(px, py + ph - c.px(d(46)), px + pw, py + ph, 0x00000000, Palette.withAlpha(fill, 0.85F));
			c.roundCorners(px, py, pw, ph, c.px(rad), fill);
			// The label (PARTNER or SPONSORED), top left.
			float lw = label.width(c) + d(16);
			MenuStyle.box(c, x + d(12), y + d(12), lw, d(22), d(11), 0xCC0A0A0A);
			MenuStyle.text(c, label, x + d(20), y + d(23.5F), "SPONSORED".equals(label.text()) ? MenuStyle.WARN_TEXT : MenuStyle.TEXT);
		}

		private void paintLogo(Canvas c, float lx, float ly, float size, int fill) {
			MenuStyle.box(c, lx - d(3), ly - d(3), size + d(6), size + d(6), MenuStyle.radius() + d(3), fill);
			String sha = partner.logo();
			Texture texture = null;
			MenuArt.Entry entry = null;
			if (sha != null) {
				entry = MenuArt.art("partner/" + sha, 128, 128, link -> link.partnerArt(sha));
				texture = entry.texture();
			}
			int px = c.px(lx);
			int py = c.px(ly);
			int ps = c.px(size);
			if (texture != null) {
				c.texture(texture, px, py, ps, ps, entry.width(), entry.height(), 0xFFFFFFFF);
				c.roundCorners(px, py, ps, ps, c.px(MenuStyle.radius()), fill);
			} else {
				MenuStyle.box(c, lx, ly, size, size, MenuStyle.radius(), hsv(hue(partner.id()), 0.5F, 0.42F));
				MenuStyle.centered(c, monogram, lx + size / 2, ly + size / 2 + d(1), MenuStyle.TEXT, size);
			}
		}
	}

	/** A verified link of a listing: Website, Discord, Store, X, YouTube (after Minecraft's link confirmation). */
	private final class LinkChip extends Pressable {
		private final Label label;
		private final String url;

		LinkChip(String kind, String url) {
			super(null);
			this.url = url;
			String text = switch (kind) {
				case "website" -> "Website";
				case "discord" -> "Discord";
				case "store" -> "Store";
				case "x" -> "X";
				default -> "YouTube";
			};
			this.label = new Label(Type.CHIP, text);
			setAction(() -> openLink.accept(this.url));
			name(text);
			tooltip(url.length() > 60 ? url.substring(0, 60) + "…" : url);
		}

		@Override
		public float prefWidth() {
			return label.width(measure()) + d(18) + d(22);
		}

		@Override
		public String narration() {
			return accessibleName() + ", link";
		}

		@Override
		protected void paint(Canvas c) {
			float rad = Math.min(MenuStyle.radius(), h / 2);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(Palette.lerp(MenuStyle.CHIP, MenuStyle.CHIP_HOVER, hover), 0xFF000000, press * 0.2F));
			int ink = Palette.lerp(MenuStyle.CHIP_TEXT, MenuStyle.TEXT, hover);
			Glyph.EXTERNAL.draw(c, x + d(16), y + h / 2, d(11), ink);
			MenuStyle.text(c, label, x + d(28), y + h / 2 + d(0.75F), ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** One kind of listing to show. */
	private final class FilterChip extends Pressable {
		private final Filter filter;
		private final Label label;
		private float on = -1;

		FilterChip(Filter filter) {
			super(null);
			this.filter = filter;
			this.label = new Label(Type.CHIP, filter.text);
			setAction(() -> DiscoverPage.this.filter = filter);
			name(filter.text);
			setVisible(false);
		}

		@Override
		public float prefWidth() {
			return label.width(measure()) + d(32);
		}

		@Override
		public String narration() {
			return accessibleName() + (DiscoverPage.this.filter == filter ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, DiscoverPage.this.filter == filter);
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(Palette.lerp(MenuStyle.CHIP, MenuStyle.ACCENT, on), 0xFF000000, press * 0.15F));
			int ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover * 0.6F), MenuStyle.ON_ACCENT, on);
			MenuStyle.centered(c, label, x + w / 2, y + h / 2 + d(0.75F), ink, w);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	// ---------- why there is nothing to show ----------

	private final class StatePanel extends Node {
		private final Label heading = new Label(Type.EMPTY, "");
		private final Lines body = new Lines(Type.EMPTY_NOTE, "");

		@Override
		protected void paint(Canvas c) {
			String[] words;
			boolean waiting = false;
			if (feeds.link() == null) {
				words = new String[] {"Open Flint to discover servers", "Discover comes from the Flint app. Start Minecraft from Flint to see the servers and communities Flint partners with."};
			} else if (feeds.partnersAbsent()) {
				words = new String[] {"Update Flint to use Discover", "This copy of the Flint app doesn't offer Discover in game yet. Update Flint, then start Minecraft from it again."};
			} else if (!feeds.partnersKnown()) {
				waiting = true;
				words = new String[] {"Finding partners…", "Flint's partner servers and communities come from the Flint app."};
			} else if (feeds.partners().problem() != null) {
				words = new String[] {"Discover isn't available right now", "Flint can't reach its Store at the moment. This page tries again by itself."};
			} else {
				words = new String[] {"No partners right now", "Flint has no partner listings at the moment. Check back soon."};
			}
			heading.set(words[0]);
			body.set(words[1]);
			float cx = x + w / 2;
			float cy = y + h / 2 - d(40);
			float disc = d(64);
			MenuStyle.box(c, cx - disc / 2, cy - disc / 2 - d(30), disc, disc, disc / 2, Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
			(waiting ? Glyph.HOURGLASS : Glyph.COMPASS).draw(c, cx, cy - d(30), d(26), MenuStyle.ACCENT);
			if (waiting && !Kit.reduced()) {
				long t = Clock.millis();
				for (int i = 0; i < 3; i++) {
					float a = 0.25F + 0.75F * (float) Math.max(0, Math.sin((t / 260.0) - i * 0.8));
					SocialParts.dot(c, cx - d(14) + i * d(14), cy + d(14), d(6), Palette.withAlpha(MenuStyle.TEXT, a));
				}
			}
			MenuStyle.centered(c, heading, cx, cy + d(40), MenuStyle.EMPTY_TEXT, w - d(80));
			body.paintCentered(c, cx, cy + d(58), Math.min(w - d(120), d(640)), MenuStyle.EMPTY_NOTE);
		}
	}

	// ---------- colours ----------

	/** A stable hue (0–359) for a listing without pictures, from its id (as the launcher's hueOf). */
	static int hue(String id) {
		int h = 0;
		for (int i = 0; i < id.length(); i++) {
			h = (h * 31 + id.charAt(i)) % 360;
		}
		return h;
	}

	static int hsv(int hue, float s, float v) {
		float c = v * s;
		float hp = hue / 60.0F;
		float xx = c * (1 - Math.abs(hp % 2 - 1));
		float r = 0;
		float g = 0;
		float b = 0;
		if (hp < 1) {
			r = c;
			g = xx;
		} else if (hp < 2) {
			r = xx;
			g = c;
		} else if (hp < 3) {
			g = c;
			b = xx;
		} else if (hp < 4) {
			g = xx;
			b = c;
		} else if (hp < 5) {
			r = xx;
			b = c;
		} else {
			r = c;
			b = xx;
		}
		float m = v - c;
		return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
	}
}

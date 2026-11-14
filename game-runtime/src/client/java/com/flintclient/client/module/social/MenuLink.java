package com.flintclient.client.module.social;

import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What Flint's main menu shows from the Flint app, through the game link ({@link LauncherLink}; the launcher's
 * {@code partners/game.rs}, {@code store/community_game.rs} and its showcase): the partner listings for Discover and their
 * pictures, joining a partner's server (the launcher checks the server again first), the Discord community rewards for the
 * "Join our Discord" card and Friends settings, starting a Discord link, and the admin's showcase slides and their pictures.
 *
 * <p>Answers are untrusted: every field is checked here as {@link SocialLink} checks Friends (ids, kinds and states from known
 * lists, text cut short without control characters or formatting codes, links https only, lists capped, pictures checked by
 * their first bytes) and anything malformed is left out. A Flint app that doesn't know an operation answers "bad request":
 * the feature is then simply not shown ({@link #missing}). Calls block: run them off the render thread. Platform-free (Gson
 * and sockets): every Minecraft line compiles it, 1.8.9 included.
 */
public final class MenuLink {
	private static final int QUICK_MILLIS = 8000;
	/** The partner feed or the showcase may be fetched first (the launcher allows 15 s). */
	private static final int FEED_MILLIS = 25_000;
	/** A picture may be downloaded first (the launcher allows 25 s). */
	private static final int ART_MILLIS = 30_000;
	/** The community view reads the catalog, the connection and the rewards at the Store. */
	private static final int STORE_MILLIS = 45_000;
	private static final int MAX_FEED_REPLY = 512 * 1024;
	/** A picture: at most 2 MB (a converted one a little more) as base64, with room for the rest of the line. */
	private static final int MAX_ART_REPLY = 4 * 1024 * 1024;
	static final int MAX_ART_BYTES = 3 * 1024 * 1024;
	static final int MAX_PARTNERS = 200;
	static final int MAX_REWARDS = 20;
	static final int MAX_SLIDES = 10;

	public static final List<String> KINDS = List.of("minecraft_server", "discord", "hosting");
	public static final List<String> LINK_KINDS = List.of("website", "discord", "store", "x", "youtube");
	public static final List<String> REWARD_STATUSES = List.of("owned", "eligible", "checking", "unavailable", "locked");

	private final LauncherLink link;

	public MenuLink(LauncherLink link) {
		this.link = link;
	}

	/** This game's link, or null when Flint didn't start it. */
	public static @Nullable MenuLink get() {
		LauncherLink link = LauncherLink.get();
		return link == null ? null : new MenuLink(link);
	}

	/** Whether a failure means this Flint app doesn't have the operation (an older app): the feature is left out, quietly. */
	public static boolean missing(LinkException e) {
		return !e.unreachable() && "bad request".equals(e.getMessage());
	}

	// ---------- what comes back ----------

	/** A server's numbers, checked by the Store in the last ten minutes; -1 where it didn't say. */
	public record Stats(boolean online, long players, long max) {
	}

	/** A picture of a listing, by its checksum (ask {@link #partnerArt} for it), and its size. */
	public record Picture(String sha, int width, int height) {
	}

	/**
	 * One partner listing as the launcher's Library → Partnerships shows it. {@code address}: a server's {@code host[:port]};
	 * {@code joinable}: a Java Edition server that takes this game's version; {@code links}: verified https links by kind
	 * ({@link #LINK_KINDS}); {@code primaryUrl}: where a Discord or hosting listing's button goes.
	 */
	public record Partner(String id, String name, String kind, String label, String summary, boolean featured, @Nullable String address,
		boolean joinable, @Nullable String versions, @Nullable Stats stats, long members, long membersOnline, @Nullable Picture banner,
		@Nullable String logo, Map<String, String> links, String primaryKind, @Nullable String primaryLabel, @Nullable String primaryUrl) {
		public boolean server() {
			return "minecraft_server".equals(kind);
		}
	}

	/** The listings running now, in the Store's order; {@code servers}: how many are Minecraft servers. */
	public record Partners(List<Partner> partners, int servers, @Nullable String problem) {
		public static final Partners NONE = new Partners(List.of(), 0, null);
	}

	/** A picture's bytes: a PNG or a JPEG (the launcher turns WebP into PNG). */
	public record Art(boolean png, byte[] bytes) {
	}

	/** A partner's server, checked again by the launcher (public address, this game's version): what to join. */
	public record Join(String name, String address) {
	}

	/** The Discord account linked to the player's Minecraft account, as Flint's Store sees it. */
	public record Discord(String username, @Nullable String displayName, @Nullable Boolean member, @Nullable Boolean booster, String guild) {
		public String shownName() {
			return displayName == null || displayName.isBlank() ? username : displayName;
		}
	}

	/** One community reward: its status is the Store's word ({@link #REWARD_STATUSES}); {@code art} its picture's version. */
	public record Reward(String id, String kind, String name, String requirement, String label, String status, boolean animated, long art) {
		public boolean discord() {
			return requirement.startsWith("discord_");
		}

		/** The player has it now (for good, or while the condition holds). */
		public boolean unlocked() {
			return "owned".equals(status) || "eligible".equals(status);
		}
	}

	/**
	 * The community rewards for the account the game plays as. {@code state}: ok, off (this Store has none: show nothing),
	 * signedOut (no Store session: rewards listed, locked) or unavailable (keep what was shown). {@code member}: the Store's
	 * bot says the linked Discord account is in Flint's server.
	 */
	public record Community(String state, boolean discordEnabled, boolean canLink, String invite, @Nullable Discord discord, boolean linked,
		boolean member, boolean pending, List<Reward> rewards) {
		public List<Reward> discordRewards() {
			List<Reward> out = new ArrayList<>();
			for (Reward reward : rewards) {
				if (reward.discord()) {
					out.add(reward);
				}
			}
			return out;
		}
	}

	/** A Discord link started: Discord's page to open ({@code url}); {@code off} when the Store has linking switched off. */
	public record LinkStart(boolean off, @Nullable String url) {
	}

	/** One showcase slide: its picture ({@link #showcaseArt}), words, where a click goes, and when it ends (-1: open). */
	public record Slide(String id, String title, String alt, @Nullable String link, int width, int height, long endsAt) {
	}

	public record Showcase(List<Slide> slides, long rotateMillis) {
		public static final Showcase NONE = new Showcase(List.of(), 6000);
	}

	// ---------- requests ----------

	/** The partner listings, with {@code version} (this game's Minecraft) deciding which servers it can join. */
	public Partners partners(String version) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("version", version);
		return readPartners(link.request("partners", fields, FEED_MILLIS, MAX_FEED_REPLY));
	}

	public Art partnerArt(String sha) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("sha", sha);
		return readArt(link.request("partnerArt", fields, ART_MILLIS, MAX_ART_REPLY));
	}

	/** The server of listing {@code id} to join from this game ({@code version}); the launcher's words when it won't. */
	public Join partnerJoin(String id, String version) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("id", id);
		fields.addProperty("version", version);
		return readJoin(link.request("partnerJoin", fields, QUICK_MILLIS + 12_000, 16 * 1024));
	}

	public Community community(String playing) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		return readCommunity(link.request("community", fields, STORE_MILLIS, 128 * 1024));
	}

	/** A listed reward's picture (public: anyone may look at a reward before having it). */
	public byte[] rewardArt(String id) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("id", id);
		return SocialLink.readArt(link.request("rewardArt", fields, ART_MILLIS, MAX_ART_REPLY));
	}

	/** Starts linking Discord to the account the game plays as; the game opens Discord's page itself. */
	public LinkStart discordLink(String playing) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("playing", playing);
		return readLinkStart(link.request("discordLink", fields, STORE_MILLIS, 16 * 1024));
	}

	public Showcase showcase() throws LinkException {
		return readShowcase(link.request("showcase", new JsonObject(), FEED_MILLIS, 64 * 1024));
	}

	public Art showcaseArt(String id) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("id", id);
		return readArt(link.request("showcaseArt", fields, ART_MILLIS, MAX_ART_REPLY));
	}

	// ---------- reading answers ----------

	static Partners readPartners(JsonObject reply) {
		List<Partner> partners = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		int servers = 0;
		for (JsonObject entry : objects(reply, "partners", MAX_PARTNERS)) {
			Partner partner = readPartner(entry);
			if (partner != null && seen.add(partner.id())) {
				partners.add(partner);
				if (partner.server()) {
					servers++;
				}
			}
		}
		String problem = SocialLink.string(reply, "problem");
		return new Partners(List.copyOf(partners), servers, problem == null ? null : SocialLink.code(problem));
	}

	static @Nullable Partner readPartner(JsonObject entry) {
		String id = SocialLink.cosmeticId(SocialLink.string(entry, "id"));
		String name = text(entry, "name", 60);
		String kind = oneOf(entry, "kind", KINDS, null);
		String label = oneOf(entry, "label", List.of("Partner", "Sponsored"), "Partner");
		if (id == null || name == null || kind == null) {
			return null;
		}
		String summary = text(entry, "summary", 160);
		String address = null;
		boolean joinable = false;
		if ("minecraft_server".equals(kind)) {
			address = address(SocialLink.string(entry, "address"));
			if (address == null) {
				return null;
			}
			joinable = bool(entry, "joinable");
		}
		Stats stats = null;
		JsonElement s = entry.get("stats");
		if (s != null && s.isJsonObject()) {
			JsonObject so = s.getAsJsonObject();
			stats = new Stats(bool(so, "online"), count(so, "players"), count(so, "max"));
		}
		Picture banner = null;
		JsonElement b = entry.get("banner");
		if (b != null && b.isJsonObject()) {
			JsonObject bo = b.getAsJsonObject();
			String sha = sha(SocialLink.string(bo, "sha"));
			int w = (int) Math.min(8192, count(bo, "width"));
			int h = (int) Math.min(8192, count(bo, "height"));
			if (sha != null && w >= 16 && h >= 16) {
				banner = new Picture(sha, w, h);
			}
		}
		String logo = null;
		JsonElement l = entry.get("logo");
		if (l != null && l.isJsonObject()) {
			logo = sha(SocialLink.string(l.getAsJsonObject(), "sha"));
		}
		Map<String, String> links = new LinkedHashMap<>();
		JsonElement linkJson = entry.get("links");
		if (linkJson != null && linkJson.isJsonObject()) {
			for (String key : LINK_KINDS) {
				String url = https(SocialLink.string(linkJson.getAsJsonObject(), key));
				if (url != null) {
					links.put(key, url);
				}
			}
		}
		String primaryKind = "open_url";
		String primaryLabel = null;
		String primaryUrl = null;
		JsonElement p = entry.get("primary");
		if (p != null && p.isJsonObject()) {
			JsonObject po = p.getAsJsonObject();
			primaryKind = oneOf(po, "kind", List.of("join_server", "open_url"), "open_url");
			primaryLabel = text(po, "label", 32);
			primaryUrl = https(SocialLink.string(po, "url"));
		}
		return new Partner(id, name, kind, label, summary == null ? "" : summary, bool(entry, "featured"), address, joinable,
			text(entry, "versions", 60), stats, count(entry, "members"), count(entry, "membersOnline"), banner, logo,
			Collections.unmodifiableMap(links), primaryKind, primaryLabel, primaryUrl);
	}

	static Art readArt(JsonObject reply) throws LinkException {
		String type = SocialLink.string(reply, "type");
		String data = SocialLink.string(reply, "data");
		if (data == null || data.length() > MAX_ART_BYTES * 4 / 3 + 8 || !("png".equals(type) || "jpeg".equals(type))) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(data);
		} catch (IllegalArgumentException e) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		boolean png = bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
		boolean jpeg = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
		if (bytes.length > MAX_ART_BYTES || !(png && "png".equals(type) || jpeg && "jpeg".equals(type))) {
			throw new LinkException("Flint sent a picture this game can't read.", false);
		}
		return new Art(png, bytes);
	}

	static Join readJoin(JsonObject reply) throws LinkException {
		String name = text(reply, "name", 60);
		String address = address(SocialLink.string(reply, "address"));
		if (name == null || address == null) {
			throw new LinkException("Flint sent a server this game can't join.", false);
		}
		return new Join(name, address);
	}

	static Community readCommunity(JsonObject reply) {
		String state = oneOf(reply, "state", List.of("ok", "off", "signedOut", "unavailable"), "off");
		String invite = invite(SocialLink.string(reply, "invite"));
		Discord discord = null;
		JsonElement d = reply.get("discord");
		if (d != null && d.isJsonObject()) {
			JsonObject o = d.getAsJsonObject();
			String username = text(o, "username", 40);
			if (username != null) {
				discord = new Discord(username, text(o, "displayName", 40), maybe(o, "member"), maybe(o, "booster"),
					oneOf(o, "guild", List.of("checking", "verified", "pending", "unavailable"), "unavailable"));
			}
		}
		boolean linked = discord != null;
		boolean member = false;
		boolean pending = false;
		JsonElement st = reply.get("standing");
		if (st != null && st.isJsonObject()) {
			JsonObject o = st.getAsJsonObject();
			linked = linked || bool(o, "linked");
			member = bool(o, "member") || bool(o, "booster");
			pending = bool(o, "pending");
		}
		if (discord != null && (Boolean.TRUE.equals(discord.member()) || Boolean.TRUE.equals(discord.booster()))) {
			member = true;
		}
		List<Reward> rewards = new ArrayList<>();
		for (JsonObject entry : objects(reply, "rewards", MAX_REWARDS)) {
			String id = SocialLink.cosmeticId(SocialLink.string(entry, "id"));
			String kind = oneOf(entry, "kind", SocialLink.SLOTS, null);
			String name = text(entry, "name", 120);
			String requirement = SocialLink.string(entry, "requirement");
			if (id == null || kind == null || name == null || requirement == null || !requirement.matches("[a-z_]{1,40}")) {
				continue;
			}
			String label = text(entry, "label", 120);
			rewards.add(new Reward(id, kind, name, requirement, label == null ? "" : label, oneOf(entry, "status", REWARD_STATUSES, "locked"),
				bool(entry, "animated"), Math.max(0, count(entry, "art"))));
		}
		return new Community(state, bool(reply, "discordEnabled"), bool(reply, "canLink"), invite == null ? DEFAULT_INVITE : invite, discord,
			linked, member, pending, List.copyOf(rewards));
	}

	static LinkStart readLinkStart(JsonObject reply) throws LinkException {
		if ("off".equals(SocialLink.string(reply, "state"))) {
			return new LinkStart(true, null);
		}
		String url = SocialLink.string(reply, "url");
		if (!"started".equals(SocialLink.string(reply, "state")) || !discordPage(url)) {
			throw new LinkException("Flint sent a Discord address this game won't open.", false);
		}
		return new LinkStart(false, url);
	}

	static Showcase readShowcase(JsonObject reply) {
		List<Slide> slides = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (JsonObject entry : objects(reply, "slides", MAX_SLIDES)) {
			String id = SocialLink.string(entry, "id");
			int w = (int) Math.min(8192, count(entry, "width"));
			int h = (int) Math.min(8192, count(entry, "height"));
			if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || w < 16 || h < 16 || !seen.add(id)) {
				continue;
			}
			String title = text(entry, "title", 80);
			String alt = text(entry, "alt", 200);
			long ends = count(entry, "endsAt");
			slides.add(new Slide(id, title == null ? "" : title, alt == null ? "" : alt, https(SocialLink.string(entry, "link")), w, h, ends));
		}
		long rotate = count(reply, "rotateMs");
		return new Showcase(List.copyOf(slides), rotate < 0 ? 6000 : Math.clamp(rotate, 3000, 30_000));
	}

	// ---------- small checks ----------

	/** Flint's own Discord server, when the Store names none. */
	public static final String DEFAULT_INVITE = "https://discord.gg/flintclient";

	/** A Discord invite: https on discord.gg/CODE or discord.com/invite/CODE. */
	static @Nullable String invite(@Nullable String url) {
		if (url == null || url.length() > 300) {
			return null;
		}
		return url.matches("https://(discord\\.gg/|(www\\.)?discord\\.com/invite/)[A-Za-z0-9-]{2,64}/?") ? url : null;
	}

	/** Discord's own sign-in page for linking (the launcher checked its every parameter); its local stand-in in development. */
	static boolean discordPage(@Nullable String url) {
		return url != null && url.length() <= 4096 && !url.matches(".*[\\s\\x00-\\x1f\\x7f].*")
			&& (url.startsWith("https://discord.com/oauth2/authorize?") || url.startsWith("https://discord.com/api/oauth2/authorize?")
				|| url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"));
	}

	/** An https link without spaces or control characters, at most 2048 characters; null otherwise. */
	static @Nullable String https(@Nullable String url) {
		if (url == null || url.length() > 2048 || !url.regionMatches(true, 0, "https://", 0, 8) || url.length() <= 8) {
			return null;
		}
		for (int i = 0; i < url.length(); i++) {
			char c = url.charAt(i);
			if (c <= ' ' || c == 0x7F || c > 0x7E) {
				return null;
			}
		}
		String host = url.substring(8).split("[/?#]", 2)[0];
		return host.isEmpty() || host.contains("@") ? null : url;
	}

	/** A server address as Minecraft's server list writes it: {@code host} or {@code host:port}, IPv6 in brackets. */
	static @Nullable String address(@Nullable String address) {
		if (address == null || address.isEmpty() || address.length() > 262) {
			return null;
		}
		return address.matches("([A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?") ? address.toLowerCase(Locale.ROOT) : null;
	}

	static @Nullable String sha(@Nullable String sha) {
		return sha != null && sha.matches("[0-9a-f]{64}") ? sha : null;
	}

	private static @Nullable String text(JsonObject object, String key, int max) {
		String value = SocialLink.string(object, key);
		if (value == null) {
			return null;
		}
		String text = SocialLink.clean(value, max, false).strip();
		return text.isEmpty() ? null : text;
	}

	private static @Nullable String oneOf(JsonObject object, String key, List<String> allowed, @Nullable String otherwise) {
		String value = SocialLink.string(object, key);
		return value != null && allowed.contains(value) ? value : otherwise;
	}

	private static boolean bool(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
	}

	private static @Nullable Boolean maybe(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
	}

	/** A whole number from 0 to 10^13, or -1 when it isn't one. */
	private static long count(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return -1;
		}
		JsonPrimitive p = value.getAsJsonPrimitive();
		try {
			double d = p.getAsDouble();
			return Double.isFinite(d) && d >= 0 && d <= 1e13 ? (long) d : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static List<JsonObject> objects(JsonObject object, String key, int max) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonArray()) {
			return List.of();
		}
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement element : value.getAsJsonArray()) {
			if (out.size() >= max) {
				break;
			}
			if (element.isJsonObject()) {
				out.add(element.getAsJsonObject());
			}
		}
		return out;
	}
}

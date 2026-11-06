package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Field;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.module.social.FriendsClient;
import com.flintclient.client.module.social.FriendsClient.Reach;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.module.social.SocialLink.Friend;
import com.flintclient.client.module.social.SocialLink.Friends;
import com.flintclient.client.module.social.SocialLink.Player;
import com.flintclient.client.module.social.SocialLink.Request;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The Friends window's content ({@link FlintFriendsScreen}), laid out as the app's Friends page: FRIENDS and the player's
 * status across the top; on the left, adding a friend by name and the list (pinned, online, offline) or the requests (received
 * and sent); on the right, the chat with the friend picked ({@link FriendsChat}) or Friends' settings. When there is nothing
 * to show yet (no Flint, Flint closed, another account, signed out, offline at first…) the whole page says why, plainly, and
 * what to do. Sizes in design pixels ({@link MenuStyle#d}).
 */
final class FriendsPage extends Node {
	private static final float PAD = 28;
	private static final float HEAD = 56;
	private static final float GAP = 20;
	private static final float FIELD = 48;
	private static final float TAB = 40;
	private static final long SEARCH_DELAY_MILLIS = 350;
	private static final long NOTE_MILLIS = 4500;

	final FriendsClient client;
	private final @Nullable SocialLink link = SocialLink.get();
	private final Consumer<String> openLink;
	/** Opened from Messages: once the list is there, the friend with the most unread messages is opened. */
	private boolean pickUnread;

	private final Label title = new Label(Type.HEADER_TITLE, "FRIENDS");
	private final Label offline = new Label(Type.CAPTION, "OFFLINE · RETRYING");
	private final StatusChip[] statuses = new StatusChip[SocialLink.MY_STATUSES.size()];
	private final Parts.Square gear;
	private final Field add;
	private final Tab friendsTab;
	private final Tab requestsTab;
	private final Stack list = new Stack(d(4));
	private final ScrollView listView;
	private final FriendsChat chat;
	private final SettingsPanel settings;
	private final StatePanel state;

	private boolean showRequests;
	private boolean showSettings;
	private @Nullable String selected;
	private @Nullable String pendingStatus;
	private @Nullable Boolean pendingAllow;
	private int shownRevision = -1;
	private String shownList = "";
	private String query = "";
	private long queryChangedAt;
	private String searchedFor = "";
	private @Nullable List<Player> results;
	private boolean searching;
	// ---------- a short note at the window's foot ----------
	private final Lines note = new Lines(Type.ROW_NOTE, "");
	private boolean noteBad;
	private long noteAt = Long.MIN_VALUE;
	// ---------- layout ----------
	private float leftX;
	private float leftW;
	private float rightX;
	private float rightW;
	private float bodyY;
	private float bodyH;

	FriendsPage(FriendsClient client, boolean chats, Consumer<String> openLink) {
		this.client = client;
		this.pickUnread = chats;
		this.openLink = openLink;
		for (int i = 0; i < statuses.length; i++) {
			statuses[i] = add(new StatusChip(SocialLink.MY_STATUSES.get(i)));
		}
		gear = add(new Parts.Square(Glyph.GEAR, "Friends settings", this::toggleSettings).active(() -> showSettings));
		add = add(new Field("Add a friend", 16, true));
		add.name("Add a friend by Minecraft name");
		add.onChange(text -> {
			query = text.strip();
			queryChangedAt = Clock.millis();
		});
		add.onSubmit(text -> sendRequest(text.strip()));
		friendsTab = add(new Tab("Friends", () -> !showRequests, () -> showRequests = false));
		requestsTab = add(new Tab("Requests", () -> showRequests, () -> showRequests = true));
		listView = add(new ScrollView(list));
		listView.flatBar(MenuStyle.SCROLL_THUMB, d(10), d(70));
		chat = add(new FriendsChat(this));
		settings = add(new SettingsPanel());
		state = add(new StatePanel());
		client.watch(null);
		client.tick();
	}

	// ---------- each tick ----------

	/** Polls (with the open chat), takes answers, runs a search that waited long enough, and keeps the page current. */
	void step() {
		client.watch(selected);
		client.tick();
		if (link != null && !query.equals(searchedFor) && Clock.millis() - queryChangedAt >= SEARCH_DELAY_MILLIS) {
			search(query);
		}
		syncList();
		chat.step();
	}

	private @Nullable String playing() {
		return client.playing();
	}

	// ---------- actions ----------

	/** Runs an action through Flint; a failure says why at the window's foot. */
	<T> void run(FriendsClient.Work<T> work, Consumer<T> done) {
		client.run(work, done, code -> say(SocialParts.errorText(code), true));
	}

	void say(String text, boolean bad) {
		note.set(text);
		noteBad = bad;
		noteAt = Clock.millis();
	}

	private void search(String text) {
		searchedFor = text;
		String who = playing();
		if (text.isEmpty() || who == null || link == null) {
			results = null;
			searching = false;
			return;
		}
		if (!text.matches("[A-Za-z0-9_]{1,16}")) {
			results = List.of();
			searching = false;
			return;
		}
		searching = true;
		client.run(() -> link.search(who, text), found -> {
			if (text.equals(searchedFor)) {
				results = found;
				searching = false;
			}
		}, code -> {
			searching = false;
			say(SocialParts.errorText(code), true);
		});
	}

	private void sendRequest(String name) {
		String who = playing();
		if (link == null || who == null || name.isEmpty()) {
			return;
		}
		if (!name.matches("[A-Za-z0-9_]{1,16}")) {
			say("Minecraft names have up to 16 letters, numbers and underscores.", true);
			return;
		}
		run(() -> link.request(who, name), result -> {
			say("accepted".equals(result) ? "You and " + name + " are friends now" : "Friend request sent to " + name, false);
			add.setText("");
			query = "";
			searchedFor = "";
			results = null;
		});
	}

	private void respond(Request request, boolean accept) {
		String who = playing();
		if (link != null && who != null) {
			run(() -> {
				link.respond(who, request, accept);
				return accept;
			}, accepted -> say(accepted ? "You and " + request.name() + " are friends now" : "Request from " + request.name() + " declined", false));
		}
	}

	private void cancel(Request request) {
		String who = playing();
		if (link != null && who != null) {
			run(() -> {
				link.cancel(who, request);
				return true;
			}, done -> say("Request to " + request.name() + " cancelled", false));
		}
	}

	private void setStatus(String status) {
		String who = playing();
		if (link == null || who == null) {
			return;
		}
		pendingStatus = status;
		client.run(() -> link.settings(who, status, null), me -> pendingStatus = null, code -> {
			pendingStatus = null;
			say(SocialParts.errorText(code), true);
		});
	}

	private void setAllowRequests(boolean allow) {
		String who = playing();
		if (link == null || who == null) {
			return;
		}
		pendingAllow = allow;
		client.run(() -> link.settings(who, null, allow), me -> pendingAllow = null, code -> {
			pendingAllow = null;
			say(SocialParts.errorText(code), true);
		});
	}

	private void toggleSettings() {
		showSettings = !showSettings;
		invalidate();
	}

	void open(String friend) {
		selected = friend;
		showSettings = false;
		chat.show(friend);
		invalidate();
	}

	void closed(String friend) {
		if (friend.equals(selected)) {
			selected = null;
			chat.show(null);
			invalidate();
		}
	}

	@Nullable SocialLink link() {
		return link;
	}

	@Nullable Friends friends() {
		return client.reach() == Reach.READY ? client.friends() : null;
	}

	void openLink(String url) {
		openLink.accept(url);
	}

	// ---------- the list ----------

	/** Makes the list's rows again when what it shows changed (a new answer, a tab, a search). */
	private void syncList() {
		Friends f = friends();
		String key = listKey(f);
		if (key.equals(shownList) && client.revision() == shownRevision) {
			return;
		}
		shownRevision = client.revision();
		if (key.equals(shownList)) {
			return;
		}
		shownList = key;
		list.clearChildren();
		if (f == null) {
			return;
		}
		if (!query.isEmpty()) {
			buildResults(f);
		} else if (showRequests) {
			buildRequests(f);
		} else {
			buildFriends(f);
		}
		listView.invalidate();
	}

	private String listKey(@Nullable Friends f) {
		if (f == null) {
			return "none";
		}
		StringBuilder key = new StringBuilder();
		if (!query.isEmpty()) {
			key.append("q:").append(query).append(searching).append(results == null ? "-" : results.toString());
			for (Request request : f.incoming()) {
				key.append(request.uuid());
			}
			return key.toString();
		}
		key.append(showRequests ? "r:" : "f:");
		if (showRequests) {
			for (Request request : f.incoming()) {
				key.append(request.key()).append(request.name()).append(';');
			}
			key.append('|');
			for (Request request : f.outgoing()) {
				key.append(request.key()).append(request.name()).append(';');
			}
		} else {
			long minute = System.currentTimeMillis() / 60_000;
			key.append(minute);
			for (Friend friend : f.friends()) {
				key.append(friend.toString());
			}
		}
		return key.toString();
	}

	private void buildFriends(Friends f) {
		List<Friend> pinned = new ArrayList<>();
		List<Friend> online = new ArrayList<>();
		List<Friend> away = new ArrayList<>();
		for (Friend friend : f.friends()) {
			(friend.pinned() ? pinned : friend.online() ? online : away).add(friend);
		}
		Comparator<Friend> byName = Comparator.comparing(friend -> friend.name().toLowerCase(Locale.ROOT));
		Comparator<Friend> byStatus = Comparator.comparingInt(friend -> SocialLink.FRIEND_STATUSES.indexOf(friend.status()));
		pinned.sort(byStatus.thenComparing(byName));
		online.sort(Comparator.comparing((Friend friend) -> !friend.inGame()).thenComparing(byStatus).thenComparing(byName));
		away.sort(Comparator.comparingLong((Friend friend) -> -friend.lastSeen()).thenComparing(byName));
		if (f.friends().isEmpty()) {
			list.add(new NoteRow("No friends yet. Add one by their Minecraft name above: they need to have opened Flint once."));
			return;
		}
		section("PINNED", pinned);
		section("ONLINE", online);
		section("OFFLINE", away);
	}

	private void section(String name, List<Friend> friends) {
		if (friends.isEmpty()) {
			return;
		}
		list.add(new SectionRow(name + " · " + friends.size()));
		long now = System.currentTimeMillis();
		for (Friend friend : friends) {
			list.add(new FriendRow(friend, now));
		}
	}

	private void buildRequests(Friends f) {
		list.add(new SectionRow("RECEIVED · " + f.incoming().size()));
		if (f.incoming().isEmpty()) {
			list.add(new NoteRow("No friend requests right now."));
		}
		for (Request request : f.incoming()) {
			list.add(new RequestRow(request, true));
		}
		if (!f.outgoing().isEmpty()) {
			list.add(new SectionRow("SENT · " + f.outgoing().size()));
			for (Request request : f.outgoing()) {
				list.add(new RequestRow(request, false));
			}
		}
	}

	private void buildResults(Friends f) {
		list.add(new SectionRow("PLAYERS ON FLINT"));
		if (!query.matches("[A-Za-z0-9_]{1,16}")) {
			list.add(new NoteRow("Minecraft names have up to 16 letters, numbers and underscores."));
			return;
		}
		if (results == null || searching && results.isEmpty()) {
			list.add(new NoteRow("Searching…"));
			return;
		}
		if (results.isEmpty()) {
			list.add(new NoteRow("Nobody on Flint goes by \"" + query + "\". Press Enter to send them a request anyway: they need to have opened Flint once."));
			return;
		}
		for (Player player : results) {
			Request incoming = null;
			for (Request request : f.incoming()) {
				if (request.uuid().equals(player.uuid())) {
					incoming = request;
				}
			}
			list.add(new PlayerRow(player, incoming));
		}
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		float pad = d(PAD);
		float left = x + pad;
		float right = x + w - pad;
		float top = y + pad;
		float chip = d(40);
		float gx = right - chip;
		gear.setBounds(gx, top + (d(HEAD) - chip) / 2, chip, chip);
		Canvas m = measure();
		float cx = gx - d(16);
		for (int i = statuses.length - 1; i >= 0; i--) {
			float cw = statuses[i].prefWidth(m);
			cx -= cw;
			statuses[i].setBounds(cx, top + (d(HEAD) - chip) / 2, cw, chip);
			cx -= d(8);
		}
		bodyY = top + d(HEAD) + d(GAP);
		bodyH = Math.max(0, y + h - pad - bodyY);
		leftW = Math.clamp((right - left) * 0.36F, Math.min(d(300), (right - left) / 2), d(460));
		leftX = left;
		rightX = left + leftW + d(GAP);
		rightW = Math.max(0, right - rightX);
		add.setBounds(leftX, bodyY, leftW, d(FIELD));
		float tabY = bodyY + d(FIELD) + d(14);
		float tw = (leftW - d(8)) / 2;
		friendsTab.setBounds(leftX, tabY, tw, d(TAB));
		requestsTab.setBounds(leftX + tw + d(8), tabY, leftW - tw - d(8), d(TAB));
		float listY = tabY + d(TAB) + d(12);
		listView.setBounds(leftX, listY, leftW, Math.max(0, bodyY + bodyH - listY));
		chat.setBounds(rightX, bodyY, rightW, bodyH);
		settings.setBounds(rightX, bodyY, rightW, bodyH);
		state.setBounds(x, y, w, h);
		applyVisibility();
	}

	private void applyVisibility() {
		boolean ready = friends() != null;
		boolean searchMode = !query.isEmpty();
		state.setVisible(!ready);
		for (StatusChip s : statuses) {
			s.setVisible(ready);
		}
		gear.setVisible(ready);
		add.setVisible(ready);
		friendsTab.setVisible(ready && !searchMode);
		requestsTab.setVisible(ready && !searchMode);
		listView.setVisible(ready);
		settings.setVisible(ready && showSettings);
		chat.setVisible(ready && !showSettings);
	}

	// ---------- drawing ----------

	@Override
	protected void paint(Canvas c) {
		Friends f = friends();
		pickUnreadOnce(f);
		if (selected != null && (f == null || f.friend(selected) == null) && f != null) {
			selected = null;
			chat.show(null);
		}
		// Each part comes and goes with the state (setVisible lays the page out again only when one changed).
		applyVisibility();
		requestsTab.count = f == null ? 0 : f.incoming().size();

		if (f == null) {
			return;
		}
		float cy = y + d(PAD) + d(HEAD) / 2;
		c.pushZoom(1.5F, c.px(x + d(PAD)), c.px(cy));
		try {
			MenuStyle.text(c, title, x + d(PAD), cy, MenuStyle.WORDMARK);
		} finally {
			c.popOffset();
		}
		float titleRight = x + d(PAD) + 1.5F * title.width(c) + d(18);
		if (f.offline() || f.stale()) {
			offline.set(f.offline() ? "OFFLINE · RETRYING" : "NOT LIVE YET");
			if (titleRight + offline.width(c) < statuses[0].x() - d(12)) {
				SocialParts.dot(c, titleRight + d(4), cy, d(8), f.offline() ? MenuStyle.WARN_TEXT : MenuStyle.NEUTRAL);
				MenuStyle.text(c, offline, titleRight + d(14), cy, f.offline() ? MenuStyle.WARN_TEXT : MenuStyle.NEUTRAL);
			}
		}
		// The list's panel.
		MenuStyle.framed(c, listView.x() - d(2), listView.y() - d(2), listView.width() + d(4), listView.height() + d(4), MenuStyle.radius(),
			MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
	}

	@Override
	protected void paintOver(Canvas c) {
		long age = Clock.millis() - noteAt;
		if (note.text().isEmpty() || age > NOTE_MILLIS || age < 0) {
			return;
		}
		float k = Kit.reduced() ? 1 : Math.min(1, Math.min(age / 160.0F, (NOTE_MILLIS - age) / 300.0F));
		float maxW = Math.min(w - d(80), d(760));
		float textW = maxW - d(48);
		float lineH = Lines.pitch(c);
		float th = note.height(c, textW);
		float bw = 0;
		for (com.flintclient.client.render.TextLine line : note.lines(c, textW)) {
			bw = Math.max(bw, c.width(line) / (float) c.scale());
		}
		bw += d(48);
		float bh = th + d(24);
		float bx = x + (w - bw) / 2;
		float by = y + h - d(PAD) - bh - d(8) + (1 - k) * d(8);
		float before = c.opacity();
		c.setOpacity(before * k);
		try {
			MenuStyle.box(c, bx, by, bw, bh, MenuStyle.radius(), 0xF2101214);
			MenuStyle.ring(c, bx, by, bw, bh, MenuStyle.radius(), Math.max(1, c.px(d(1))), noteBad ? Palette.withAlpha(MenuStyle.RED, 0.7F)
				: Palette.withAlpha(SocialParts.ONLINE, 0.6F));
			SocialParts.dot(c, bx + d(18), by + d(12) + lineH / 2, d(8), noteBad ? MenuStyle.RED : SocialParts.ONLINE);
			note.paint(c, bx + d(32), by + d(12), textW, MenuStyle.TEXT);
		} finally {
			c.setOpacity(before);
		}
	}

	private void pickUnreadOnce(@Nullable Friends f) {
		if (!pickUnread || f == null) {
			return;
		}
		pickUnread = false;
		Friend best = null;
		for (Friend friend : f.friends()) {
			if (friend.unread() > 0 && (best == null || friend.unread() > best.unread())) {
				best = friend;
			}
		}
		// Nothing unread: the chat most likely wanted, a pinned friend (online first), else one who is online.
		for (int pass = 0; best == null && pass < 3; pass++) {
			for (Friend friend : f.friends()) {
				boolean fits = pass == 0 ? friend.pinned() && friend.online() : pass == 1 ? friend.pinned() : friend.online();
				if (fits) {
					best = friend;
					break;
				}
			}
		}
		if (best != null) {
			open(best.uuid());
		}
	}

	// ---------- rows ----------

	/** Stacks its visible children, each as tall as it wants, {@code gap} apart. */
	static final class Stack extends Node {
		private final float gap;

		Stack(float gap) {
			this.gap = gap;
		}

		@Override
		public float prefHeight(float width) {
			float total = 0;
			int n = 0;
			for (int i = 0; i < childCount(); i++) {
				Node child = child(i);
				if (child.visible()) {
					total += child.prefHeight(width);
					n++;
				}
			}
			return total + Math.max(0, n - 1) * gap;
		}

		@Override
		protected void layout() {
			float at = y;
			for (int i = 0; i < childCount(); i++) {
				Node child = child(i);
				if (child.visible()) {
					float ch = child.prefHeight(w);
					child.setBounds(x, at, w, ch);
					at += ch + gap;
				}
			}
		}
	}

	private static final class SectionRow extends Node {
		private final Label label;

		SectionRow(String text) {
			label = new Label(Type.CAPTION, text);
		}

		@Override
		public float prefHeight(float width) {
			return d(34);
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.text(c, label, x + d(14), y + h / 2 + d(3), MenuStyle.NEUTRAL);
		}
	}

	private static final class NoteRow extends Node {
		private final Lines text;

		NoteRow(String text) {
			this.text = new Lines(Type.ROW_NOTE, text);
		}

		@Override
		public float prefHeight(float width) {
			return text.height(measure(), width - d(28)) + d(24);
		}

		@Override
		protected void paint(Canvas c) {
			text.paint(c, x + d(14), y + d(12), w - d(28), MenuStyle.EMPTY_NOTE);
		}
	}

	/** One friend: face with a status dot, name, status line, pin and unread count; opens the chat. */
	private final class FriendRow extends Pressable {
		private final Friend friend;
		private final Label name;
		private final Label line;
		private final Label count;

		FriendRow(Friend friend, long now) {
			super(null);
			this.friend = friend;
			this.name = new Label(Type.ROW_LABEL, friend.name());
			this.line = new Label(Type.ROW_NOTE, SocialParts.statusLine(friend.status(), friend.inGame(), friend.lastSeen(), now));
			this.count = new Label(Type.COUNT, friend.unread() > 99 ? "99+" : String.valueOf(friend.unread()));
			setAction(() -> open(friend.uuid()));
			name(friend.name() + ", " + line.text() + (friend.unread() > 0 ? ", " + friend.unread() + " unread" : ""));
		}

		@Override
		public float prefHeight(float width) {
			return d(64);
		}

		@Override
		protected void paint(Canvas c) {
			boolean on = friend.uuid().equals(selected);
			float rad = MenuStyle.radius();
			float inset = d(4);
			int fill = on ? Palette.withAlpha(MenuStyle.ACCENT, 0.16F) : Palette.withAlpha(MenuStyle.TEXT, 0.035F * hover);
			MenuStyle.box(c, x + inset, y, w - 2 * inset, h, rad, Palette.lerp(fill, 0x00000000, press * 0.2F));
			if (on) {
				MenuStyle.ring(c, x + inset, y, w - 2 * inset, h, rad, Math.max(1, c.px(d(1))), Palette.withAlpha(MenuStyle.ACCENT, 0.55F));
			}
			float faceSize = d(36);
			float fx = x + d(16);
			float fy = y + (h - faceSize) / 2;
			float dim = friend.online() ? 1 : 0.55F;
			float before = c.opacity();
			c.setOpacity(before * dim);
			SocialParts.face(c, friend.uuid(), fx, fy, faceSize);
			c.setOpacity(before);
			// The status dot sits on the face's corner, ringed in the panel's colour.
			float dot = d(12);
			SocialParts.dot(c, fx + faceSize - d(2), fy + faceSize - d(2), dot + d(4), MenuStyle.SECTION_CARD);
			SocialParts.dot(c, fx + faceSize - d(2), fy + faceSize - d(2), dot, SocialParts.statusColor(friend.status()));
			float tx = fx + faceSize + d(14);
			float right = x + w - d(16);
			if (friend.unread() > 0) {
				float cw = Math.max(d(26), count.width(c) + d(14));
				MenuStyle.box(c, right - cw, y + h / 2 - d(11), cw, d(22), d(11), MenuStyle.ACCENT);
				MenuStyle.centered(c, count, right - cw / 2, y + h / 2, 0xFFFFFFFF, cw);
				right -= cw + d(10);
			}
			if (friend.pinned()) {
				Glyph.PIN.draw(c, right - d(8), y + h / 2, d(14), MenuStyle.NEUTRAL);
				right -= d(24);
			}
			MenuStyle.fitted(c, name, tx, y + h / 2 - d(10), friend.online() ? MenuStyle.TEXT : MenuStyle.NAME_OFF, right - tx);
			int ink = friend.inGame() && friend.online() ? SocialParts.ONLINE : MenuStyle.NEUTRAL;
			MenuStyle.fitted(c, line, tx, y + h / 2 + d(12), ink, right - tx);
			MenuStyle.focusRing(c, x + inset, y, w - 2 * inset, h, rad, focus);
		}
	}

	/** A request received (Accept, Decline) or sent (Cancel), its answers as square buttons so the name keeps its room. */
	private final class RequestRow extends Node {
		private final Request request;
		private final boolean received;
		private final Label name;
		private final Label line;
		private final SocialParts.Icon yes;
		private final SocialParts.@Nullable Icon no;

		RequestRow(Request request, boolean received) {
			this.request = request;
			this.received = received;
			this.name = new Label(Type.ROW_LABEL, request.name());
			this.line = new Label(Type.ROW_NOTE, received ? "Wants to be friends" : "Request sent");
			if (received) {
				yes = add(new SocialParts.Icon(Glyph.CHECK, "Accept " + request.name(), true, () -> respond(request, true)));
				no = add(new SocialParts.Icon(Glyph.CLOSE, "Decline " + request.name(), false, () -> respond(request, false)));
			} else {
				yes = add(new SocialParts.Icon(Glyph.CLOSE, "Cancel the request to " + request.name(), false, () -> cancel(request)));
				no = null;
			}
		}

		@Override
		public float prefHeight(float width) {
			return d(64);
		}

		@Override
		protected void layout() {
			float sq = d(36);
			float right = x + w - d(14);
			if (no != null) {
				no.setBounds(right - sq, y + (h - sq) / 2, sq, sq);
				right -= sq + d(8);
			}
			yes.setBounds(right - sq, y + (h - sq) / 2, sq, sq);
		}

		@Override
		protected void paint(Canvas c) {
			float faceSize = d(36);
			float fx = x + d(16);
			SocialParts.face(c, request.uuid(), fx, y + (h - faceSize) / 2, faceSize);
			float tx = fx + faceSize + d(14);
			float right = yes.x() - d(10);
			MenuStyle.fitted(c, name, tx, y + h / 2 - d(10), MenuStyle.TEXT, right - tx);
			MenuStyle.fitted(c, line, tx, y + h / 2 + d(12), received ? MenuStyle.ACCENT : MenuStyle.NEUTRAL, right - tx);
		}
	}

	/** A player found by name: Add, Accept (they asked), or what they are to you. */
	private final class PlayerRow extends Node {
		private final Player player;
		private final Label name;
		private final Label line;
		private final @Nullable Button action;

		PlayerRow(Player player, @Nullable Request incoming) {
			this.player = player;
			this.name = new Label(Type.ROW_LABEL, player.name());
			String relation = switch (player.relation()) {
				case "friend" -> "Your friend";
				case "outgoing" -> "Pending";
				case "incoming" -> "Wants to be friends";
				case "self" -> "You";
				default -> player.accepts() ? "On Flint" : "Not accepting requests";
			};
			this.line = new Label(Type.ROW_NOTE, relation);
			if ("none".equals(player.relation()) && player.accepts()) {
				action = add(new Button("Add", Glyph.PLUS, PageControls.Look.ACCENT, () -> sendRequest(player.name())));
				action.name("Add " + player.name());
			} else if ("incoming".equals(player.relation()) && incoming != null) {
				action = add(new Button("Accept", Glyph.CHECK, PageControls.Look.ACCENT, () -> respond(incoming, true)));
				action.name("Accept " + player.name());
			} else if ("friend".equals(player.relation())) {
				action = add(new Button("Message", null, PageControls.Look.PLAIN, () -> {
					add.setText("");
					query = "";
					searchedFor = "";
					results = null;
					open(player.uuid());
				}));
			} else {
				action = null;
			}
		}

		@Override
		public float prefHeight(float width) {
			return d(64);
		}

		@Override
		protected void layout() {
			if (action != null) {
				float bh = d(Button.H) - d(6);
				float bw = Math.max(d(84), action.prefWidth());
				action.setBounds(x + w - d(14) - bw, y + (h - bh) / 2, bw, bh);
			}
		}

		@Override
		protected void paint(Canvas c) {
			float faceSize = d(36);
			float fx = x + d(16);
			SocialParts.face(c, player.uuid(), fx, y + (h - faceSize) / 2, faceSize);
			float tx = fx + faceSize + d(14);
			float right = (action != null ? action.x() : x + w - d(14)) - d(10);
			MenuStyle.fitted(c, name, tx, y + h / 2 - d(10), MenuStyle.TEXT, right - tx);
			MenuStyle.fitted(c, line, tx, y + h / 2 + d(12), MenuStyle.NEUTRAL, right - tx);
		}
	}

	// ---------- the header's controls ----------

	/** One of the player's statuses (a dot and its word); the chosen one is lit. */
	private final class StatusChip extends Pressable {
		private final String status;
		private final Label label;
		private float on = -1;

		StatusChip(String status) {
			super(null);
			this.status = status;
			this.label = new Label(Type.CHIP, SocialParts.statusWord(status));
			setAction(() -> setStatus(status));
			name("Your status: " + label.text());
			tooltip("invisible".equals(status) ? "Appear offline to your friends" : "Show your friends you're " + label.text().toLowerCase(Locale.ROOT));
		}

		float prefWidth(Canvas c) {
			return label.width(c) + d(16) + d(10) + d(10) + d(14);
		}

		private boolean chosen() {
			if (pendingStatus != null) {
				return status.equals(pendingStatus);
			}
			Friends f = friends();
			return f != null && f.me() != null && status.equals(f.me().status());
		}

		@Override
		public String narration() {
			return accessibleName() + (chosen() ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, chosen());
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(Palette.lerp(MenuStyle.CHIP, MenuStyle.CHIP_HOVER, hover), Palette.withAlpha(MenuStyle.TEXT, 0.10F), on));
			if (on > 0.01F) {
				MenuStyle.ring(c, x, y, w, h, rad, Math.max(1, c.px(d(1))), Palette.withAlpha(SocialParts.statusColor("invisible".equals(status) ? "offline" : status), 0.8F * on));
			}
			SocialParts.dot(c, x + d(16), y + h / 2, d(10), SocialParts.statusColor("invisible".equals(status) ? "offline" : status));
			int ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover * 0.6F), MenuStyle.TEXT, on);
			MenuStyle.text(c, label, x + d(28), y + h / 2 + d(0.75F), ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** Friends or Requests (with the count of requests waiting). */
	private final class Tab extends Pressable {
		private final Label label;
		private final Label badge = new Label(Type.COUNT, "");
		private final java.util.function.BooleanSupplier chosen;
		private float on = -1;
		int count;

		Tab(String text, java.util.function.BooleanSupplier chosen, Runnable choose) {
			super(null);
			this.label = new Label(Type.CHIP, text);
			this.chosen = chosen;
			setAction(() -> {
				choose.run();
				shownList = "";
			});
			name(text);
		}

		@Override
		public String narration() {
			return accessibleName() + (count > 0 ? ", " + count + " waiting" : "") + (chosen.getAsBoolean() ? ", selected" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, chosen.getAsBoolean());
			float rad = MenuStyle.radius();
			int fill = Palette.lerp(MenuStyle.CHIP, MenuStyle.ACCENT, on);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(fill, 0xFF000000, press * 0.15F));
			MenuStyle.halo(c, x, y, w, h, rad, hover, on >= 0.5F ? Palette.withAlpha(MenuStyle.ACCENT, 0.30F) : Palette.withAlpha(MenuStyle.TEXT, 0.03F));
			int ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover * 0.6F), MenuStyle.TEXT, on);
			float lw = label.width(c);
			float bw = 0;
			if (count > 0) {
				badge.set(count > 99 ? "99+" : String.valueOf(count));
				bw = Math.max(d(22), badge.width(c) + d(12)) + d(8);
			}
			float left = x + (w - lw - bw) / 2;
			MenuStyle.text(c, label, left, y + h / 2 + d(0.75F), ink);
			if (count > 0) {
				float cw = bw - d(8);
				float bx = left + lw + d(8);
				MenuStyle.box(c, bx, y + h / 2 - d(10), cw, d(20), d(10), on >= 0.5F ? 0xFFFFFFFF : MenuStyle.ACCENT);
				MenuStyle.centered(c, badge, bx + cw / 2, y + h / 2, on >= 0.5F ? MenuStyle.ACCENT : 0xFFFFFFFF, cw);
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	// ---------- settings ----------

	/** Friends' settings: taking requests (the app's own setting), and the notices in game (this game's). */
	private final class SettingsPanel extends Node {
		private final Label heading = new Label(Type.SECTION_TITLE, "FRIENDS SETTINGS");
		private final Label allowLabel = new Label(Type.ROW_LABEL, "Allow friend requests");
		private final Lines allowNote = new Lines(Type.ROW_NOTE, "Anyone on Flint can ask to be your friend. The same setting as in the Flint app.");
		private final Label noticeLabel = new Label(Type.ROW_LABEL, "Notifications in game");
		private final Lines noticeNote = new Lines(Type.ROW_NOTE, "A small note in game when a friend messages you, asks to be friends or comes online.");
		private final PageControls.Switch allow;
		private final PageControls.Switch notices;

		SettingsPanel() {
			allow = add(new PageControls.Switch(this::allowing, () -> setAllowRequests(!allowing())));
			allow.name("Allow friend requests");
			notices = add(new PageControls.Switch(SettingsPanel::noticesOn, () -> {
				ControlState.settings().setGame(GameOptions.FRIEND_NOTICES, !noticesOn());
				ControlState.saveAll();
			}));
			notices.name("Notifications in game");
		}

		private boolean allowing() {
			if (pendingAllow != null) {
				return pendingAllow;
			}
			Friends f = friends();
			return f == null || f.me() == null || f.me().allowRequests();
		}

		private static boolean noticesOn() {
			try {
				return ControlState.settings().gameBool(GameOptions.FRIEND_NOTICES);
			} catch (RuntimeException | LinkageError e) {
				return true;
			}
		}

		/** Where the second row starts: under the first row's note, however many lines it takes. */
		private float secondY;

		private float textWidth() {
			return w - d(28) - MenuStyle.r(PageControls.Switch.W) - d(20) - d(28);
		}

		@Override
		protected void layout() {
			float sw = MenuStyle.r(PageControls.Switch.W);
			float sh = MenuStyle.r(PageControls.Switch.H);
			float firstY = y + d(72);
			secondY = firstY + d(26) + allowNote.height(measure(), textWidth()) + d(24);
			allow.setBounds(x + w - d(28) - sw, firstY + d(12) - sh / 2, sw, sh);
			notices.setBounds(x + w - d(28) - sw, secondY + d(12) - sh / 2, sw, sh);
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.framed(c, x, y, w, h, MenuStyle.radius(), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
			float left = x + d(28);
			float textW = textWidth();
			float firstY = y + d(72);
			MenuStyle.text(c, heading, left, y + d(40), MenuStyle.TEXT);
			MenuStyle.text(c, allowLabel, left, firstY + d(12), MenuStyle.TEXT);
			allowNote.paint(c, left, firstY + d(26), textW, MenuStyle.NEUTRAL);
			MenuStyle.text(c, noticeLabel, left, secondY + d(12), MenuStyle.TEXT);
			noticeNote.paint(c, left, secondY + d(26), textW, MenuStyle.NEUTRAL);
		}
	}

	// ---------- why there is nothing to show ----------

	/** The whole page when Friends can't be shown: what happened and what to do, with Retry where it helps. */
	private final class StatePanel extends Node {
		private final Label heading = new Label(Type.EMPTY, "");
		private final Lines body = new Lines(Type.EMPTY_NOTE, "");
		private final Button retry;
		private @Nullable Reach shownReach;

		StatePanel() {
			retry = add(new Button("Try again", Glyph.RESET, PageControls.Look.PLAIN, this::retry));
		}

		private void retry() {
			String who = playing();
			if (client.reach() == Reach.ERROR && link != null && who != null) {
				client.run(() -> {
					link.connect(who);
					return true;
				}, done -> {
				}, code -> say(SocialParts.errorText(code), true));
			}
			client.refreshSoon();
		}

		private void describe(Reach reach) {
			String[] words = switch (reach) {
				case NO_APP -> new String[] {"Open Flint to use Friends in game",
					"Friends in game come from the Flint app. Start Minecraft from Flint to see your friends, requests and chats here."};
				case UNREACHABLE -> new String[] {"Open Flint to use Friends in game",
					"Flint isn't running any more, so this game can't reach Friends. Open the Flint app; this page reconnects by itself."};
				case OLD_APP -> new String[] {"Update Flint to use Friends here",
					"This copy of the Flint app doesn't offer Friends in game yet. Update Flint, then start Minecraft from it again."};
				case ACCOUNT -> new String[] {"Flint is using another account",
					"Friends here shows the account the Flint app uses, and this game plays as another one. Switch accounts (the account card in the main menu, or the Flint app) so both match."};
				case SIGNED_OUT -> new String[] {"Sign in to add friends", client.testIdentity()
					? "The test identity has no Minecraft account. Sign in with Microsoft in the Flint app to use Friends."
					: "Sign in with Microsoft in the Flint app to add friends, see when they're online and chat with them."};
				case NOT_CONFIGURED -> new String[] {"Friends isn't in this build",
					"This copy of Flint was built without Flint's online services, so Friends isn't available in it."};
				case UNAVAILABLE -> new String[] {"Friends isn't available right now",
					"Flint can't reach its Friends service at the moment. This page reconnects by itself."};
				case ERROR -> new String[] {"Friends didn't load", SocialParts.errorText(client.error())};
				default -> new String[] {"Connecting to Friends…", "Your friends, requests and chats come from the Flint app."};
			};
			heading.set(words[0]);
			body.set(words[1]);
			boolean canRetry = reach == Reach.ERROR || reach == Reach.UNREACHABLE || reach == Reach.UNAVAILABLE;
			retry.setVisible(canRetry);
		}

		@Override
		protected void layout() {
			float bw = Math.max(d(150), retry.prefWidth());
			retry.setBounds(x + (w - bw) / 2, y + h / 2 + d(70), bw, d(Button.H));
		}

		@Override
		protected void paint(Canvas c) {
			Reach reach = client.reach();
			if (reach != shownReach || reach == Reach.ERROR) {
				shownReach = reach;
				describe(reach);
			}
			float cx = x + w / 2;
			float cy = y + h / 2 - d(40);
			float disc = d(64);
			boolean waiting = reach == Reach.LOADING || reach == Reach.CONNECTING;
			MenuStyle.box(c, cx - disc / 2, cy - disc / 2 - d(30), disc, disc, disc / 2, Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
			Glyph glyph = waiting ? Glyph.HOURGLASS : reach == Reach.ERROR || reach == Reach.UNAVAILABLE ? Glyph.INFO : Glyph.USERS;
			glyph.draw(c, cx, cy - d(30), d(26), MenuStyle.ACCENT);
			if (waiting && !Kit.reduced()) {
				// Three dots that come and go, so a wait never looks frozen.
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
}

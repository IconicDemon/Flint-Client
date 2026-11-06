package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.SocialParts.Button;
import com.flintclient.client.control.rshift.SocialParts.Field;
import com.flintclient.client.control.rshift.SocialParts.Lines;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.module.social.PrivateText;
import com.flintclient.client.module.social.SocialLink;
import com.flintclient.client.module.social.SocialLink.Chat;
import com.flintclient.client.module.social.SocialLink.Friend;
import com.flintclient.client.module.social.SocialLink.Friends;
import com.flintclient.client.module.social.SocialLink.Message;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The chat with one friend in the Friends window, as the app's: their face, name and status with Pin, Send a gift and Remove
 * at the top; the messages (the latest from Flint, older ones a page at a time, newest at the bottom, staying there unless the
 * player scrolled up); and the line to write in. A message goes out with an id of its own, the same on every retry, so a
 * retry after a lost answer is never stored twice; one that didn't go stays, marked, to retry. Before anything that looks
 * private goes out (a password, an email, a card number… {@link PrivateText}) the chat asks first, as the app does; what was
 * sent anyway shows hidden until clicked. Their messages are marked read while the chat is open.
 */
final class FriendsChat extends Node {
	private static final float HEAD = 72;
	private static final float COMPOSER = 48;
	private static final float PAD = 20;
	/** Messages close together from one person form a group; the time shows once, under the last of them. */
	private static final long GROUP_MILLIS = 5 * 60_000;

	/** A message on its way (or one that didn't go). */
	private static final class Pending {
		final String clientId = UUID.randomUUID().toString();
		final String to;
		final String body;
		final boolean sensitive;
		final long at = System.currentTimeMillis();
		boolean failed;

		Pending(String to, String body, boolean sensitive) {
			this.to = to;
			this.body = body;
			this.sensitive = sensitive;
		}
	}

	private final FriendsPage page;
	private final Label name = new Label(Type.PAGE_TITLE, "");
	private final Label line = new Label(Type.ROW_NOTE, "");
	private final Label confirmText = new Label(Type.ROW_LABEL, "");
	private final Lines confirmNote = new Lines(Type.ROW_NOTE, "You'll stop seeing each other online, and your chat goes away. You can add each other again later.");
	private final Label emptyTitle = new Label(Type.EMPTY, "Pick a friend to chat");
	private final Lines emptyBody = new Lines(Type.EMPTY_NOTE, "Your conversations show up here.");
	private final Label privateTitle = new Label(Type.ROW_LABEL, "This looks private");
	private final Lines privateBody = new Lines(Type.ROW_NOTE,
		"Never send passwords, emails, usernames for other sites or card details, not even to friends. Flint staff will never ask for them.");
	private final Parts.Square pin;
	private final Parts.Square gift;
	private final Parts.Square remove;
	private final Button keep;
	private final Button removeYes;
	private final FriendsPage.Stack column = new FriendsPage.Stack(d(6));
	private final ScrollView view;
	private final Field field;
	private final Button send;
	private final Button dontSend;
	private final Button sendAnyway;

	private @Nullable String friend;
	private boolean confirming;
	private @Nullable Boolean pendingPin;
	/** The message waiting for "Send anyway". */
	private @Nullable String privateDraft;
	// ---------- the conversation as shown ----------
	private final TreeMap<Long, Message> older = new TreeMap<>();
	private final TreeMap<Long, Message> confirmed = new TreeMap<>();
	private final List<Pending> pending = new ArrayList<>();
	private final Set<String> revealed = new HashSet<>();
	private boolean complete;
	private boolean loadingOlder;
	private boolean askedLatest;
	private long readUpTo;
	private String shownKey = "";
	/** After the messages change: scroll to the newest ({@code toBottom}), or keep the distance from the bottom. */
	private boolean toBottom = true;
	private float keepFromBottom = -1;
	private boolean firstScroll = true;
	private boolean laidOutAsking;
	/** The "This looks private" panel's height, from its text at the chat's width. */
	private float warningH;

	FriendsChat(FriendsPage page) {
		this.page = page;
		pin = add(new Parts.Square(Glyph.PIN, "Pin", this::togglePin).active(this::pinned));
		gift = add(new Parts.Square(Glyph.GIFT, "Send a gift", this::sendGift));
		remove = add(new Parts.Square(Glyph.TRASH, "Remove friend", () -> confirming = true));
		keep = add(new Button("Keep", null, PageControls.Look.PLAIN, () -> confirming = false));
		removeYes = add(new Button("Remove friend", null, PageControls.Look.DANGER, this::removeFriend));
		view = add(new ScrollView(column));
		view.flatBar(MenuStyle.SCROLL_THUMB, d(10), d(70));
		field = add(new Field("Message", SocialLink.MAX_BODY, false));
		field.name("Message");
		field.onSubmit(text -> submit());
		send = add(new Button("Send", Glyph.SHARE, PageControls.Look.ACCENT, this::submit));
		dontSend = add(new Button("Don't send", null, PageControls.Look.PLAIN, () -> privateDraft = null));
		sendAnyway = add(new Button("Send anyway", null, PageControls.Look.DANGER, this::sendAnyway));
	}

	/** Shows {@code uuid}'s chat (null: none). */
	void show(@Nullable String uuid) {
		if (java.util.Objects.equals(uuid, friend)) {
			return;
		}
		friend = uuid;
		confirming = false;
		pendingPin = null;
		privateDraft = null;
		older.clear();
		confirmed.clear();
		revealed.clear();
		complete = false;
		loadingOlder = false;
		askedLatest = false;
		readUpTo = 0;
		shownKey = "";
		toBottom = true;
		firstScroll = true;
		field.setText("");
		column.clearChildren();
		invalidate();
	}

	private @Nullable Friend current() {
		Friends f = page.friends();
		return f == null || friend == null ? null : f.friend(friend);
	}

	private @Nullable Chat chat() {
		Friends f = page.friends();
		return f == null || f.chat() == null || !f.chat().with().equals(friend) ? null : f.chat();
	}

	private boolean pinned() {
		Friend f = current();
		return pendingPin != null ? pendingPin : f != null && f.pinned();
	}

	// ---------- each tick ----------

	/** The latest page once (when Flint has none for this chat yet), and their messages marked read. */
	void step() {
		Friend f = current();
		if (f != null) {
			syncMessages(f);
		}
		SocialLink link = page.link();
		String who = page.client.playing();
		if (f == null || link == null || who == null) {
			return;
		}
		Chat chat = chat();
		if (chat != null && !chat.loaded() && !askedLatest) {
			askedLatest = true;
			String with = f.uuid();
			page.client.run(() -> link.history(who, with, 0), got -> {
				if (with.equals(friend)) {
					for (Message m : got.messages()) {
						older.put(m.id(), m);
					}
					complete = got.complete();
				}
			}, code -> askedLatest = false);
		}
		// Read: what they sent, while this chat is open.
		long lastTheirs = 0;
		for (Message m : merged()) {
			if (m.from().equals(f.uuid())) {
				lastTheirs = Math.max(lastTheirs, m.id());
			}
		}
		if (f.unread() > 0 && lastTheirs > readUpTo) {
			readUpTo = lastTheirs;
			long upTo = lastTheirs;
			String with = f.uuid();
			page.client.run(() -> {
				link.read(who, with, upTo);
				return true;
			}, done -> {
			}, code -> {
			});
		}
	}

	/** Every message known for this chat, by id: older pages, Flint's latest, and sends Flint confirmed. */
	private List<Message> merged() {
		TreeMap<Long, Message> all = new TreeMap<>(older);
		Chat chat = chat();
		if (chat != null) {
			for (Message m : chat.messages()) {
				all.put(m.id(), m);
			}
		}
		for (Message m : confirmed.values()) {
			all.putIfAbsent(m.id(), m);
		}
		return new ArrayList<>(all.values());
	}

	// ---------- actions ----------

	private void submit() {
		String body = field.text().strip();
		Friend f = current();
		if (body.isEmpty() || f == null) {
			return;
		}
		if (body.length() > SocialLink.MAX_BODY) {
			page.say(SocialParts.errorText("too_long"), true);
			return;
		}
		if (PrivateText.looksPrivate(body)) {
			privateDraft = body;
			return;
		}
		go(f.uuid(), body, false);
	}

	private void sendAnyway() {
		String body = privateDraft;
		Friend f = current();
		privateDraft = null;
		if (body != null && f != null) {
			go(f.uuid(), body, true);
		}
	}

	private void go(String to, String body, boolean sensitive) {
		field.setText("");
		Pending message = new Pending(to, body, sensitive);
		pending.add(message);
		toBottom = true;
		deliver(message);
	}

	private void deliver(Pending message) {
		SocialLink link = page.link();
		String who = page.client.playing();
		if (link == null || who == null) {
			message.failed = true;
			return;
		}
		message.failed = false;
		page.client.run(() -> link.send(who, message.to, message.body, message.sensitive, message.clientId), sent -> {
			pending.remove(message);
			if (message.to.equals(friend)) {
				confirmed.put(sent.id(), sent);
			}
		}, code -> {
			message.failed = true;
			page.say(SocialParts.errorText(code), true);
		});
	}

	private void loadOlder() {
		SocialLink link = page.link();
		String who = page.client.playing();
		List<Message> all = merged();
		if (link == null || who == null || all.isEmpty() || loadingOlder || friend == null) {
			return;
		}
		loadingOlder = true;
		String with = friend;
		long before = all.get(0).id();
		page.client.run(() -> link.history(who, with, before), got -> {
			loadingOlder = false;
			if (with.equals(friend)) {
				for (Message m : got.messages()) {
					older.put(m.id(), m);
				}
				complete = got.complete() || got.messages().isEmpty();
				keepFromBottom = view.model().maxOffset() - view.model().offset();
			}
		}, code -> {
			loadingOlder = false;
			page.say(SocialParts.errorText(code), true);
		});
	}

	private void togglePin() {
		Friend f = current();
		SocialLink link = page.link();
		String who = page.client.playing();
		if (f == null || link == null || who == null) {
			return;
		}
		boolean next = !pinned();
		pendingPin = next;
		page.client.run(() -> {
			link.pin(who, f.uuid(), next);
			return next;
		}, done -> pendingPin = null, code -> {
			pendingPin = null;
			page.say(SocialParts.errorText(code), true);
		});
	}

	private void removeFriend() {
		Friend f = current();
		SocialLink link = page.link();
		String who = page.client.playing();
		confirming = false;
		if (f == null || link == null || who == null) {
			return;
		}
		page.client.run(() -> {
			link.remove(who, f.uuid());
			return true;
		}, done -> {
			page.say(f.name() + " was removed from your friends", false);
			page.closed(f.uuid());
		}, code -> page.say(SocialParts.errorText(code), true));
	}

	/** The Flint Store's cosmetics, set up as a gift for this friend (Minecraft asks before the browser opens). */
	private void sendGift() {
		Friend f = current();
		if (f != null) {
			page.openLink(StoreEndpoint.DEFAULT_BASE + "/cosmetics/?for=gift&to=" + f.uuid() + "&toName="
				+ URLEncoder.encode(f.name(), StandardCharsets.UTF_8));
		}
	}

	// ---------- the messages ----------

	/** Makes the message rows again when what they show changed. */
	private void syncMessages(Friend f) {
		List<Message> all = merged();
		Chat chat = chat();
		boolean more = !complete && (chat == null || chat.more() || !older.isEmpty() || all.size() >= 30);
		StringBuilder key = new StringBuilder().append(more).append(loadingOlder).append(complete).append(LocalDate.now());
		for (Message m : all) {
			key.append(m.id()).append(m.sensitive() && revealed.contains("m" + m.id()) ? 'r' : '-');
		}
		for (Pending p : pending) {
			if (p.to.equals(f.uuid())) {
				key.append(p.clientId, 0, 8).append(p.failed).append(p.sensitive && revealed.contains(p.clientId));
			}
		}
		String now = key.toString();
		if (now.equals(shownKey)) {
			return;
		}
		boolean stick = toBottom || view.model().maxOffset() - view.model().target() < d(60);
		shownKey = now;
		column.clearChildren();
		if (more) {
			Button older = column.add(new Button(loadingOlder ? "Loading…" : "Load older messages", null, PageControls.Look.PLAIN, this::loadOlder));
			older.setEnabled(!loadingOlder);
		} else if (chat != null && chat.loaded() || complete) {
			column.add(new Caption("This is the start of your chat with " + f.name()));
		}
		String me = page.client.playing();
		for (int i = 0; i < all.size(); i++) {
			Message m = all.get(i);
			Message next = i + 1 < all.size() ? all.get(i + 1) : null;
			boolean last = next == null || !next.from().equals(m.from()) || next.at() - m.at() > GROUP_MILLIS;
			column.add(new Bubble(m.from().equals(me), m.body(), m.sensitive(), "m" + m.id(), last ? time(m.at()) : null, null));
		}
		for (Pending p : pending) {
			if (p.to.equals(f.uuid())) {
				column.add(new Bubble(true, p.body, p.sensitive, p.clientId, p.failed ? "Not sent · click to retry" : "Sending…", p));
			}
		}
		if (all.isEmpty() && pending.stream().noneMatch(p -> p.to.equals(f.uuid()))) {
			column.add(new Caption((chat != null && chat.loaded()) || complete ? "Say hi, or send them a gift." : "Loading the chat…"));
		}
		toBottom = stick && keepFromBottom < 0;
		view.invalidate();
	}

	private static String time(long at) {
		if (at <= 0) {
			return "";
		}
		boolean h24 = true;
		try {
			h24 = ControlState.settings().gameBool(GameOptions.CLOCK_24H);
		} catch (RuntimeException | LinkageError ignored) {
			// Settings not loaded: 24-hour.
		}
		ZonedDateTime when = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
		String clock = when.format(DateTimeFormatter.ofPattern(h24 ? "HH:mm" : "h:mm a", Locale.ENGLISH));
		LocalDate day = when.toLocalDate();
		LocalDate today = LocalDate.now();
		if (day.equals(today)) {
			return clock;
		}
		if (day.equals(today.minusDays(1))) {
			return "Yesterday " + clock;
		}
		return when.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " " + clock;
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		float sq = d(40);
		float top = y + (d(HEAD) - sq) / 2;
		float right = x + w - d(PAD);
		remove.setBounds(right - sq, top, sq, sq);
		gift.setBounds(right - 2 * sq - d(8), top, sq, sq);
		pin.setBounds(right - 3 * sq - d(16), top, sq, sq);
		float bh = d(Button.H);
		float rw = Math.max(d(150), removeYes.prefWidth());
		float kw = Math.max(d(100), keep.prefWidth());
		removeYes.setBounds(right - rw, top, rw, bh);
		keep.setBounds(right - rw - d(8) - kw, top, kw, bh);
		float composerY = y + h - d(PAD) - d(COMPOSER);
		float sw = Math.max(d(110), send.prefWidth());
		send.setBounds(right - sw, composerY, sw, d(COMPOSER));
		field.setBounds(x + d(PAD), composerY, Math.max(0, right - sw - d(10) - x - d(PAD)), d(COMPOSER));
		float aw = Math.max(d(150), sendAnyway.prefWidth());
		float dw = Math.max(d(130), dontSend.prefWidth());
		sendAnyway.setBounds(right - aw, composerY, aw, d(COMPOSER));
		dontSend.setBounds(right - aw - d(10) - dw, composerY, dw, d(COMPOSER));
		float listY = y + d(HEAD) + d(8);
		laidOutAsking = privateDraft != null;
		warningH = d(38) + privateBody.height(measure(), w - 2 * d(PAD) - d(60)) + d(14);
		float warning = privateDraft != null ? warningH + d(12) : 0;
		view.setBounds(x + d(8), listY, w - d(16), Math.max(0, composerY - d(12) - warning - listY));
	}

	// ---------- drawing ----------

	@Override
	protected void paint(Canvas c) {
		MenuStyle.framed(c, x, y, w, h, MenuStyle.radius(), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
		Friend f = current();
		boolean open = f != null;
		boolean asking = privateDraft != null;
		setShown(pin, open && !confirming);
		setShown(gift, open && !confirming);
		setShown(remove, open && !confirming);
		setShown(keep, open && confirming);
		setShown(removeYes, open && confirming);
		setShown(view, open);
		setShown(field, open && !asking);
		setShown(send, open && !asking);
		setShown(dontSend, open && asking);
		setShown(sendAnyway, open && asking);
		if (!open) {
			float cx = x + w / 2;
			float cy = y + h / 2;
			Glyph.USERS.draw(c, cx, cy - d(48), d(30), Palette.withAlpha(MenuStyle.TEXT, 0.25F));
			MenuStyle.centered(c, emptyTitle, cx, cy, MenuStyle.EMPTY_TEXT, w - d(40));
			emptyBody.paintCentered(c, cx, cy + d(18), w - d(80), MenuStyle.EMPTY_NOTE);
			return;
		}
		if (keepFromBottom >= 0) {
			view.model().scrollTo(Math.max(0, view.model().maxOffset() - keepFromBottom), true);
			keepFromBottom = -1;
		} else if (toBottom && view.model().target() < view.model().maxOffset()) {
			view.model().scrollTo(view.model().maxOffset(), firstScroll);
			firstScroll = false;
		}
		if (view.model().maxOffset() - view.model().target() > d(60)) {
			toBottom = false;
		}
		float headCy = y + d(HEAD) / 2;
		float faceSize = d(44);
		float fx = x + d(PAD);
		SocialParts.face(c, f.uuid(), fx, headCy - faceSize / 2, faceSize);
		SocialParts.dot(c, fx + faceSize - d(2), headCy + faceSize / 2 - d(2), d(16), MenuStyle.SECTION_CARD);
		SocialParts.dot(c, fx + faceSize - d(2), headCy + faceSize / 2 - d(2), d(12), SocialParts.statusColor(f.status()));
		float tx = fx + faceSize + d(16);
		if (confirming) {
			confirmText.set("Remove " + f.name() + "?");
			float right = keep.x() - d(16);
			MenuStyle.fitted(c, confirmText, tx, headCy - d(10), MenuStyle.TEXT, right - tx);
			List<com.flintclient.client.render.TextLine> lines = confirmNote.lines(c, right - tx);
			if (!lines.isEmpty()) {
				c.text(lines.get(0), c.px(tx), c.baselineFor(lines.get(0), c.px(headCy + d(12))), MenuStyle.NEUTRAL, Math.max(1, c.px(right - tx)));
			}
		} else {
			name.set(f.name());
			line.set(SocialParts.statusLine(f.status(), f.inGame(), f.lastSeen(), System.currentTimeMillis()));
			float right = pin.x() - d(16);
			MenuStyle.fitted(c, name, tx, headCy - d(10), MenuStyle.TEXT, right - tx);
			MenuStyle.fitted(c, line, tx, headCy + d(14), f.inGame() && f.online() ? SocialParts.ONLINE : MenuStyle.NEUTRAL, right - tx);
		}
		int rule = Math.max(1, c.px(d(1)));
		c.fill(c.px(x + d(PAD)), c.px(y + d(HEAD)), c.px(x + w - d(PAD)), c.px(y + d(HEAD)) + rule, MenuStyle.SECTION_EDGE);
		if (asking) {
			float wy = field.y() - d(12) - warningH;
			float ww = w - 2 * d(PAD);
			MenuStyle.box(c, x + d(PAD), wy, ww, warningH, MenuStyle.radius(), Palette.withAlpha(MenuStyle.RED, 0.10F));
			MenuStyle.ring(c, x + d(PAD), wy, ww, warningH, MenuStyle.radius(), Math.max(1, c.px(d(1))), Palette.withAlpha(MenuStyle.RED, 0.5F));
			Glyph.INFO.draw(c, x + d(PAD) + d(22), wy + d(24), d(18), MenuStyle.DANGER_TEXT);
			MenuStyle.text(c, privateTitle, x + d(PAD) + d(44), wy + d(24), MenuStyle.DANGER_TEXT);
			privateBody.paint(c, x + d(PAD) + d(44), wy + d(38), ww - d(60), MenuStyle.SENTENCE_TEXT);
		}
		if (asking != laidOutAsking) {
			invalidate();
		}
	}

	private static void setShown(Node node, boolean shown) {
		node.setVisible(shown);
	}

	/** A line of quiet text in the chat (its beginning, a hint). */
	private static final class Caption extends Node {
		private final Lines text;

		Caption(String text) {
			this.text = new Lines(Type.ROW_NOTE, text);
		}

		@Override
		public float prefHeight(float width) {
			return text.height(measure(), width - d(40)) + d(20);
		}

		@Override
		protected void paint(Canvas c) {
			text.paintCentered(c, x + w / 2, y + d(10), w - d(40), MenuStyle.NEUTRAL);
		}
	}

	/**
	 * One message: theirs on the left in the window's grey, yours on the right in the accent; its time (or "Sending…", "Not
	 * sent") under the last of a group. One sent as private shows hidden until clicked; one that didn't go retries on a click.
	 */
	private final class Bubble extends Pressable {
		private final boolean mine;
		private final boolean sensitive;
		private final String key;
		private final Lines text;
		private final Lines hidden = new Lines(Type.ROW_NOTE, "Hidden private message · click to show");
		private final @Nullable Label under;
		private final @Nullable Pending pendingMessage;

		Bubble(boolean mine, String body, boolean sensitive, String key, @Nullable String under, @Nullable Pending pendingMessage) {
			super(null);
			this.mine = mine;
			this.sensitive = sensitive;
			this.key = key;
			this.text = new Lines(Type.SENTENCE, body);
			this.under = under == null || under.isEmpty() ? null : new Label(Type.CAPTION, under);
			this.pendingMessage = pendingMessage;
			setAction(this::clicked);
			name(sensitive && !revealed.contains(key) ? "Hidden private message" : body);
		}

		private void clicked() {
			if (pendingMessage != null && pendingMessage.failed) {
				deliver(pendingMessage);
				shownKey = "";
			} else if (sensitive) {
				if (!revealed.remove(key)) {
					revealed.add(key);
				}
				shownKey = "";
			}
		}

		@Override
		public boolean interactive() {
			return sensitive || pendingMessage != null && pendingMessage.failed;
		}

		@Override
		public boolean focusable() {
			return interactive();
		}

		private Lines shown() {
			return sensitive && !revealed.contains(key) ? hidden : text;
		}

		private float maxText() {
			return Math.max(d(80), w * 0.72F - 2 * d(14));
		}

		@Override
		public float prefHeight(float width) {
			Canvas m = measure();
			float max = Math.max(d(80), width * 0.72F - 2 * d(14));
			return shown().height(m, max) + 2 * d(10) + (under != null ? d(22) : 0);
		}

		@Override
		protected void paint(Canvas c) {
			Lines body = shown();
			float max = maxText();
			float textW = 0;
			for (com.flintclient.client.render.TextLine l : body.lines(c, max)) {
				textW = Math.max(textW, c.width(l) / (float) c.scale());
			}
			float bw = Math.min(max, textW) + 2 * d(14);
			float bh = body.height(c, max) + 2 * d(10);
			float bx = mine ? x + w - d(12) - bw : x + d(12);
			float rad = Math.min(MenuStyle.radius() * 1.5F, bh / 2);
			boolean failed = pendingMessage != null && pendingMessage.failed;
			boolean sending = pendingMessage != null && !failed;
			int fill = mine ? Palette.withAlpha(MenuStyle.ACCENT, sending ? 0.12F : 0.22F) : Palette.withAlpha(MenuStyle.TEXT, 0.07F);
			if (failed) {
				fill = Palette.withAlpha(MenuStyle.RED, 0.16F);
			}
			MenuStyle.box(c, bx, y, bw, bh, rad, Palette.lerp(fill, Palette.withAlpha(MenuStyle.TEXT, 0.12F), interactive() ? hover * 0.5F : 0));
			boolean veiled = body == hidden;
			body.paint(c, bx + d(14), y + d(10), max, veiled ? MenuStyle.NEUTRAL : sending ? Palette.withAlpha(MenuStyle.TEXT, 0.7F) : MenuStyle.TEXT);
			if (sensitive && !veiled) {
				MenuStyle.ring(c, bx, y, bw, bh, rad, Math.max(1, c.px(d(1))), Palette.withAlpha(MenuStyle.RED, 0.45F));
			}
			if (under != null) {
				float uy = y + bh + d(11);
				int ink = failed ? MenuStyle.DANGER_TEXT : MenuStyle.LABEL;
				if (mine) {
					MenuStyle.text(c, under, x + w - d(14) - under.width(c), uy, ink);
				} else {
					MenuStyle.text(c, under, x + d(14), uy, ink);
				}
			}
			MenuStyle.focusRing(c, bx, y, bw, bh, rad, focus);
		}
	}
}

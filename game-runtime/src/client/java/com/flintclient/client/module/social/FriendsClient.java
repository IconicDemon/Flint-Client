package com.flintclient.client.module.social;

import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.SocialLink.Friend;
import com.flintclient.client.module.social.SocialLink.Friends;
import com.flintclient.client.module.social.SocialLink.Request;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Friends as the game shows them: the Flint app's Friends state, asked for over the game link ({@link SocialLink}) every
 * {@link #OPEN_POLL_MILLIS} while the Friends screen is open (with the chat it shows) and every {@link #IDLE_POLL_MILLIS}
 * while something only needs the count (the main menu's Friends badge, the in-game notices), never when nothing needs it.
 * Every call runs on one worker thread of its own; answers are taken on the render thread in {@link #tick}, so the screens
 * read plain fields. What Flint says is kept as the last good answer; why there is none (no Flint, Flint closed, another
 * account, signed out…) is {@link #reach}.
 *
 * <p>Successive answers are compared ({@link #notice}): a new friend request, a request accepted, a friend coming online and
 * new messages become notices for the game's toasts, never on the first answer (what was already waiting isn't news).
 * Platform-free; render thread except the worker's calls.
 */
public final class FriendsClient {
	public static final long OPEN_POLL_MILLIS = 4000;
	public static final long IDLE_POLL_MILLIS = 15_000;
	/** Asking about Friends is wanted for this long after the last {@link #want}. */
	private static final long INTEREST_MILLIS = 2500;
	private static final int MAX_NOTICES = 8;

	/** Why there is (or isn't) a Friends state to show. */
	public enum Reach {
		/** This game wasn't started by Flint (no game link). */
		NO_APP,
		/** Not asked yet. */
		LOADING,
		/** Flint isn't running any more (or doesn't answer). */
		UNREACHABLE,
		/** A Flint from before Friends in game: it doesn't know the question. */
		OLD_APP,
		/** Flint's active account is another one than the game plays as. */
		ACCOUNT,
		SIGNED_OUT,
		NOT_CONFIGURED,
		UNAVAILABLE,
		CONNECTING,
		ERROR,
		READY
	}

	/** Something worth a toast: {@code text} for the player, about {@code friend} (a UUID, or null). */
	public record Notice(Kind kind, String text, @Nullable String friend) {
		public enum Kind {
			REQUEST, ACCEPTED, ONLINE, MESSAGE
		}
	}

	/** The question each poll asks (the game link, or a test's stand-in). */
	public interface Source {
		Friends friends(String playing, @Nullable String chat) throws LinkException;
	}

	/** Work for the worker (an action): its answer, or a {@link LinkException} with Flint's code. */
	public interface Work<T> {
		T run() throws LinkException;
	}

	private static volatile @Nullable FriendsClient shared;

	private final @Nullable Source source;
	private final Executor worker;
	private final LongSupplier clock;
	private final Supplier<@Nullable String> playing;
	private final ConcurrentLinkedQueue<Runnable> answers = new ConcurrentLinkedQueue<>();
	private final ArrayList<Notice> notices = new ArrayList<>();

	private Reach reach;
	private @Nullable Friends friends;
	private @Nullable String error;
	private boolean testIdentity;
	private @Nullable String account;
	private int revision;
	private boolean polling;
	private long nextPoll;
	private long openUntil = Long.MIN_VALUE;
	private long idleUntil = Long.MIN_VALUE;
	private @Nullable String chat;
	/** The answer notices compare with (the last ready one for this account). */
	private @Nullable Friends compared;
	private int pollGeneration;

	public FriendsClient(@Nullable Source source, Executor worker, LongSupplier clock, Supplier<@Nullable String> playing) {
		this.source = source;
		this.worker = worker;
		this.clock = clock;
		this.playing = playing;
		this.reach = source == null ? Reach.NO_APP : Reach.LOADING;
	}

	/** The game's own client: the game link, one worker thread, the account the game plays as from {@code playing}. */
	public static FriendsClient get(Supplier<@Nullable String> playing) {
		FriendsClient client = shared;
		if (client == null) {
			synchronized (FriendsClient.class) {
				client = shared;
				if (client == null) {
					SocialLink link = SocialLink.get();
					ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
						Thread thread = new Thread(task, "Flint friends");
						thread.setDaemon(true);
						return thread;
					});
					worker.allowCoreThreadTimeOut(true);
					client = new FriendsClient(link == null ? null : link::friends, worker, System::currentTimeMillis, playing);
					shared = client;
				}
			}
		}
		return client;
	}

	/** The shared client if one was made (nothing is started by asking). */
	public static @Nullable FriendsClient existing() {
		return shared;
	}

	// ---------- what is known ----------

	public Reach reach() {
		return reach;
	}

	/** The last good answer for the account the game plays as (shown while a newer one is on its way). */
	public @Nullable Friends friends() {
		return friends;
	}

	/** Flint's code for the last failure (when {@link #reach} is {@link Reach#ERROR}, or an otherwise ready state's). */
	public @Nullable String error() {
		return error;
	}

	/** Signed out because Flint's active account is its test identity. */
	public boolean testIdentity() {
		return testIdentity;
	}

	/** Changes whenever what is shown changes (a new answer or reach). */
	public int revision() {
		return revision;
	}

	/** Friend requests waiting and unread messages: the Friends button's count. */
	public int badge() {
		Friends f = friends;
		return reach == Reach.READY && f != null ? f.badge() : 0;
	}

	public boolean linked() {
		return source != null;
	}

	/** The account the game plays as now (32 lowercase hex), or null when it can't be told. */
	public @Nullable String playing() {
		return SocialLink.canonical(playing.get());
	}

	// ---------- what is wanted ----------

	/** The Friends screen is open, showing {@code chatWith}'s chat (or none): polled every 4 s, the chat with it. */
	public void watch(@Nullable String chatWith) {
		long now = clock.getAsLong();
		boolean opened = openUntil < now;
		openUntil = now + INTEREST_MILLIS;
		if (opened || !java.util.Objects.equals(chat, chatWith)) {
			chat = chatWith;
			nextPoll = Math.min(nextPoll, now);
		}
	}

	/** Only the count is wanted (the main menu, the in-game notices): polled every 15 s. */
	public void want() {
		idleUntil = clock.getAsLong() + INTEREST_MILLIS;
	}

	/** Asks again at the next tick (after an action). */
	public void refreshSoon() {
		nextPoll = Long.MIN_VALUE;
	}

	/** The notices since the last call, oldest first (they are dropped after {@link #MAX_NOTICES}). */
	public List<Notice> takeNotices() {
		if (notices.isEmpty()) {
			return List.of();
		}
		List<Notice> out = List.copyOf(notices);
		notices.clear();
		return out;
	}

	// ---------- the render thread's step ----------

	/** Takes the worker's answers and starts the next poll when one is due and wanted. Render thread, every frame or tick. */
	public void tick() {
		for (Runnable answer; (answer = answers.poll()) != null;) {
			answer.run();
		}
		Source asked = source;
		if (asked == null || polling) {
			return;
		}
		long now = clock.getAsLong();
		boolean open = openUntil >= now;
		if (!open && idleUntil < now) {
			return;
		}
		if (now < nextPoll) {
			return;
		}
		String who = playing();
		if (who == null) {
			return;
		}
		if (!who.equals(account)) {
			// The game switched accounts: nothing of the last one is shown, and nothing about it is news.
			account = who;
			friends = null;
			compared = null;
			reach = Reach.LOADING;
			revision++;
		}
		polling = true;
		int generation = ++pollGeneration;
		String askChat = open ? chat : null;
		nextPoll = now + (open ? OPEN_POLL_MILLIS : IDLE_POLL_MILLIS);
		try {
			worker.execute(() -> {
				Friends answer = null;
				LinkException failure = null;
				try {
					answer = asked.friends(who, askChat);
				} catch (LinkException e) {
					failure = e;
				} catch (RuntimeException e) {
					failure = new LinkException("invalid", false);
				}
				Friends got = answer;
				LinkException failed = failure;
				answers.add(() -> {
					polling = false;
					if (generation == pollGeneration && who.equals(account)) {
						if (got != null) {
							took(got);
						} else if (failed != null) {
							failed(failed);
						}
					}
				});
			});
		} catch (RuntimeException e) {
			polling = false;
		}
	}

	/**
	 * Runs {@code work} on the worker; {@code done} or {@code failed} (with Flint's code, or "unreachable") gets its end on
	 * the render thread, at the next {@link #tick}. A Friends state is asked for right after.
	 */
	public <T> void run(Work<T> work, Consumer<T> done, Consumer<String> failed) {
		try {
			worker.execute(() -> {
				T result = null;
				String error = null;
				try {
					result = work.run();
				} catch (LinkException e) {
					error = e.unreachable() ? "unreachable" : e.getMessage() == null ? "invalid" : e.getMessage();
				} catch (RuntimeException e) {
					error = "invalid";
				}
				T got = result;
				String code = error;
				answers.add(() -> {
					refreshSoon();
					if (code == null) {
						done.accept(got);
					} else {
						failed.accept(code);
					}
				});
			});
		} catch (RuntimeException e) {
			failed.accept("invalid");
		}
	}

	private void took(Friends answer) {
		testIdentity = answer.testIdentity();
		Reach next;
		if (!answer.configured()) {
			next = Reach.NOT_CONFIGURED;
		} else {
			next = switch (answer.phase()) {
				case "signed_out" -> Reach.SIGNED_OUT;
				case "unavailable" -> Reach.UNAVAILABLE;
				case "connecting" -> Reach.CONNECTING;
				case "ready" -> Reach.READY;
				default -> Reach.ERROR;
			};
		}
		error = answer.error();
		if (next == Reach.READY || answer.stale() && !answer.friends().isEmpty()) {
			if (next == Reach.READY) {
				notice(compared, answer);
				compared = answer;
			}
			friends = answer;
			reach = Reach.READY;
		} else {
			friends = next == Reach.CONNECTING || next == Reach.ERROR ? friends : null;
			reach = next == Reach.CONNECTING && friends != null ? Reach.READY : next;
		}
		revision++;
	}

	private void failed(LinkException failure) {
		String message = failure.getMessage() == null ? "" : failure.getMessage();
		Reach next;
		if (failure.unreachable()) {
			next = Reach.UNREACHABLE;
		} else if ("account".equals(message)) {
			next = Reach.ACCOUNT;
		} else if ("bad request".equals(message) || "unsupported version".equals(message)) {
			next = Reach.OLD_APP;
		} else {
			next = Reach.ERROR;
			error = SocialLink.code(message);
		}
		if (next != Reach.ERROR) {
			friends = null;
			compared = null;
		}
		reach = next;
		revision++;
	}

	// ---------- notices ----------

	/** What changed from {@code before} to {@code after} that the player would want to hear of. */
	static List<Notice> changes(@Nullable Friends before, Friends after) {
		if (before == null) {
			return List.of();
		}
		List<Notice> out = new ArrayList<>();
		Set<String> knownRequests = new HashSet<>();
		for (Request request : before.incoming()) {
			knownRequests.add(request.key());
		}
		for (Request request : after.incoming()) {
			if (!knownRequests.contains(request.key())) {
				out.add(new Notice(Notice.Kind.REQUEST, request.name() + " wants to be friends", request.uuid()));
			}
		}
		Map<String, Friend> was = new HashMap<>();
		for (Friend friend : before.friends()) {
			was.put(friend.uuid(), friend);
		}
		Set<String> stillOut = new HashSet<>();
		for (Request request : after.outgoing()) {
			stillOut.add(request.key());
		}
		for (Request request : before.outgoing()) {
			if (!stillOut.contains(request.key()) && !was.containsKey(request.uuid()) && after.friend(request.uuid()) != null) {
				out.add(new Notice(Notice.Kind.ACCEPTED, request.name() + " accepted your friend request", request.uuid()));
			}
		}
		for (Friend friend : after.friends()) {
			Friend earlier = was.get(friend.uuid());
			if (earlier == null) {
				continue;
			}
			if (!earlier.online() && friend.online()) {
				out.add(new Notice(Notice.Kind.ONLINE, friend.name() + " is online", friend.uuid()));
			}
			int fresh = friend.unread() - earlier.unread();
			if (fresh > 0) {
				out.add(new Notice(Notice.Kind.MESSAGE, fresh == 1 ? friend.name() + " sent you a message" : friend.name() + " sent you " + fresh + " messages",
					friend.uuid()));
			}
		}
		return out;
	}

	private void notice(@Nullable Friends before, Friends after) {
		for (Notice notice : changes(before, after)) {
			if (notices.size() >= MAX_NOTICES) {
				notices.remove(0);
			}
			notices.add(notice);
		}
	}
}

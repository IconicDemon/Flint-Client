package com.flintclient.client.module.social;

import com.flintclient.client.FlintLog;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.MenuLink.Community;
import com.flintclient.client.module.social.MenuLink.Partners;
import com.flintclient.client.module.social.MenuLink.Showcase;
import com.flintclient.client.module.social.MenuLink.Slide;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What the main menu shows from the Flint app, kept for the game session: the partner listings (Discover and its count), the
 * showcase slides and the community rewards (the "Join our Discord" card, Friends settings' linked Discord). Each is asked for
 * only while something wants it ({@link #want}), at most as often as it changes (partners every 2 minutes, showcase and
 * community every minute; the launcher keeps the Store's own caching on top), on one worker thread; answers are taken on the
 * render thread at {@link #tick}. A Flint app without the operation hides the feature for the session; a Flint that isn't
 * running is asked again later. Nothing is shown before an answer: the menu never shows an empty card. Platform-free.
 */
public final class MenuFeeds {
	public enum Feed {
		PARTNERS(120_000),
		SHOWCASE(60_000),
		COMMUNITY(60_000);

		final long every;

		Feed(long every) {
			this.every = every;
		}
	}

	/** After Flint couldn't be reached, or answered an error, a feed is asked again after this long. */
	private static final long RETRY_MILLIS = 30_000;
	/** While a Discord link is waiting for the browser, the community view is read this often (for up to 10 minutes). */
	private static final long LINKING_EVERY = 4000;
	private static final long LINKING_FOR = 10 * 60_000;

	private static @Nullable MenuFeeds shared;

	private final @Nullable MenuLink link;
	private final Executor worker;
	private final Queue<Runnable> answers = new ConcurrentLinkedQueue<>();
	private final long[] nextAsk = new long[Feed.values().length];
	private final boolean[] busy = new boolean[Feed.values().length];
	private final boolean[] absent = new boolean[Feed.values().length];
	private Partners partners = Partners.NONE;
	private boolean partnersKnown;
	private Showcase showcase = Showcase.NONE;
	private @Nullable Community community;
	private @Nullable String communityFor;
	private long linkingUntil = Long.MIN_VALUE;
	private int revision;
	private String version = "";

	MenuFeeds(@Nullable MenuLink link, Executor worker) {
		this.link = link;
		this.worker = worker;
	}

	/** The game's own: the game link and one worker thread. */
	public static MenuFeeds get() {
		MenuFeeds feeds = shared;
		if (feeds == null) {
			synchronized (MenuFeeds.class) {
				feeds = shared;
				if (feeds == null) {
					ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
						Thread thread = new Thread(task, "Flint menu feeds");
						thread.setDaemon(true);
						return thread;
					});
					worker.allowCoreThreadTimeOut(true);
					feeds = new MenuFeeds(MenuLink.get(), worker);
					shared = feeds;
				}
			}
		}
		return feeds;
	}

	/** The link to the Flint app, or null when Flint didn't start this game. */
	public @Nullable MenuLink link() {
		return link;
	}

	/** This game's Minecraft version (the launcher says which partner servers take it). */
	public void version(String minecraft) {
		version = minecraft;
	}

	/** Changes each time an answer changes what is shown. */
	public int revision() {
		return revision;
	}

	public Partners partners() {
		return partners;
	}

	/** Whether the partner listings have been answered once this session (Discover shows a wait until then). */
	public boolean partnersKnown() {
		return partnersKnown;
	}

	/** The Flint app doesn't have Discover (an older app), or Flint didn't start this game. */
	public boolean partnersAbsent() {
		return link == null || absent[Feed.PARTNERS.ordinal()];
	}

	/** The slides running at {@code now} (one that ended since the last answer is left out). */
	public List<Slide> slides(long now) {
		List<Slide> out = new ArrayList<>();
		for (Slide slide : showcase.slides()) {
			if (slide.endsAt() < 0 || now < slide.endsAt()) {
				out.add(slide);
			}
		}
		return out;
	}

	public long rotateMillis() {
		return showcase.rotateMillis();
	}

	/** The community view for {@code playing}, or null before an answer (or for another account). */
	public @Nullable Community community(@Nullable String playing) {
		return playing != null && playing.equals(communityFor) ? community : null;
	}

	/** A Discord link was started: the community view is read often until it shows the link (or 10 minutes pass). */
	public void linking(long now) {
		linkingUntil = now + LINKING_FOR;
		nextAsk[Feed.COMMUNITY.ordinal()] = now;
	}

	/** Asks for {@code feed} if it is due ({@code playing}: the account, for the community view; {@code now}: the wall clock, ms). */
	public void want(Feed feed, @Nullable String playing, long now) {
		int i = feed.ordinal();
		MenuLink l = link;
		if (l == null || absent[i] || busy[i]) {
			return;
		}
		if (feed == Feed.COMMUNITY) {
			if (playing == null) {
				return;
			}
			if (!playing.equals(communityFor)) {
				// Another account: what was shown was the other one's.
				communityFor = playing;
				community = null;
				nextAsk[i] = now;
			}
			if (now < linkingUntil) {
				nextAsk[i] = Math.min(nextAsk[i], Math.max(now, lastAsked[i] + LINKING_EVERY));
			}
		}
		if (now < nextAsk[i]) {
			return;
		}
		busy[i] = true;
		lastAsked[i] = now;
		String who = playing;
		String game = version;
		try {
			worker.execute(() -> {
				Object answer;
				try {
					answer = switch (feed) {
						case PARTNERS -> l.partners(game);
						case SHOWCASE -> l.showcase();
						case COMMUNITY -> l.community(who == null ? "" : who);
					};
				} catch (LinkException e) {
					answer = e;
				} catch (RuntimeException e) {
					answer = new LinkException("invalid", false);
				}
				Object got = answer;
				answers.add(() -> took(feed, who, got, now));
			});
		} catch (RuntimeException e) {
			busy[i] = false;
		}
	}

	private final long[] lastAsked = new long[Feed.values().length];

	/** Takes the answers that came back (render thread). */
	public void tick() {
		for (Runnable answer; (answer = answers.poll()) != null;) {
			answer.run();
		}
	}

	private void took(Feed feed, @Nullable String playing, Object answer, long askedAt) {
		int i = feed.ordinal();
		busy[i] = false;
		if (answer instanceof LinkException e) {
			if (MenuLink.missing(e)) {
				absent[i] = true;
				FlintLog.LOGGER.info("Flint: this Flint app doesn't offer the main menu's {} yet", feed.name().toLowerCase(java.util.Locale.ROOT));
			} else if ("account".equals(e.getMessage()) && feed == Feed.COMMUNITY) {
				community = null;
			}
			nextAsk[i] = askedAt + RETRY_MILLIS;
			if (feed == Feed.PARTNERS) {
				partnersKnown = true;
			}
			revision++;
			return;
		}
		nextAsk[i] = askedAt + feed.every;
		switch (feed) {
			case PARTNERS -> {
				partners = (Partners) answer;
				partnersKnown = true;
			}
			case SHOWCASE -> showcase = (Showcase) answer;
			case COMMUNITY -> {
				if (playing != null && playing.equals(communityFor)) {
					Community next = (Community) answer;
					// "unavailable": the Store couldn't answer now; what was shown stays.
					if (!"unavailable".equals(next.state()) || community == null) {
						community = next;
					}
					if (next.linked()) {
						linkingUntil = Long.MIN_VALUE;
					}
				}
			}
		}
		revision++;
	}

	/**
	 * Runs {@code work} on the worker; {@code done} or {@code failed} (the launcher's words, or "unreachable") on the render
	 * thread at the next {@link #tick}.
	 */
	public <T> void run(Work<T> work, Consumer<T> done, Consumer<String> failed) {
		MenuLink l = link;
		if (l == null) {
			failed.accept("unreachable");
			return;
		}
		try {
			worker.execute(() -> {
				T result = null;
				String error = null;
				try {
					result = work.run(l);
				} catch (LinkException e) {
					error = e.unreachable() ? "unreachable" : e.getMessage() == null ? "invalid" : e.getMessage();
				} catch (RuntimeException e) {
					error = "invalid";
				}
				T got = result;
				String why = error;
				answers.add(() -> {
					if (why == null) {
						done.accept(got);
					} else {
						failed.accept(why);
					}
				});
			});
		} catch (RuntimeException e) {
			failed.accept("invalid");
		}
	}

	/** Work for the worker, with the link. */
	public interface Work<T> {
		T run(MenuLink link) throws LinkException;
	}
}

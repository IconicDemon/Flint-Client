package com.flintclient.client.module.account;

import com.flintclient.client.FlintClient;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What the main menu's account card shows and does: Flint's accounts (through {@link LauncherLink}), switching the game to
 * one ({@link SessionSwitch}, then Flint's active account follows), adding one with Flint's own sign-in and signing one out.
 * The state lives here, not in the screen, so leaving and coming back to the menu (or resizing it) keeps a switch, a
 * sign-in or a removal going.
 *
 * <p>Link calls run one at a time on a background thread; the screen reads the state each frame and rebuilds its rows when
 * {@link #revision} changes. Every method is for the render thread.
 */
public final class AccountSwitcher {
	/** How the card can talk to Flint. */
	public enum Reach {
		/** Asking Flint for the first time. */
		LOADING,
		/** Flint answered: its accounts are listed. */
		READY,
		/** No link (the game wasn't started by Flint) or Flint isn't running any more. */
		NO_APP
	}

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Flint account switcher");
		thread.setDaemon(true);
		return thread;
	});
	private static final int POLL_TICKS = 15;
	/** How long "Copied" shows after Copy link. */
	private static final long COPIED_MILLIS = 1800;
	/** Microsoft's sign-in pages: the only addresses the game opens for Flint. */
	private static final List<String> SIGN_IN_HOSTS = List.of("https://login.microsoftonline.com/", "https://login.live.com/");

	private static Reach reach = Reach.LOADING;
	private static List<LauncherLink.Account> accounts = List.of();
	private static @Nullable String switching;
	private static boolean adding;
	private static @Nullable String signInUrl;
	private static long copiedAt;
	private static @Nullable String confirming;
	private static @Nullable String removing;
	private static @Nullable String error;
	private static int revision;
	private static int pollTicks;
	private static boolean asking;

	private AccountSwitcher() {
	}

	public static Reach reach() {
		return reach;
	}

	/** Flint's accounts, the game's own first; empty until Flint answered. */
	public static List<LauncherLink.Account> accounts() {
		return accounts;
	}

	/** The account a switch is under way to, or null. */
	public static @Nullable String switching() {
		return switching;
	}

	public static boolean adding() {
		return adding;
	}

	/** Microsoft's page for the sign-in waiting now (to open again or copy), or null. */
	public static @Nullable String signInUrl() {
		return adding ? signInUrl : null;
	}

	/** True for a moment after Copy link. */
	public static boolean linkCopied() {
		return copiedAt != 0 && System.currentTimeMillis() - copiedAt < COPIED_MILLIS;
	}

	/** The account whose row asks "Remove?" now, or null. */
	public static @Nullable String confirming() {
		return confirming;
	}

	/** The account being signed out now, or null. */
	public static @Nullable String removing() {
		return removing;
	}

	/** What went wrong last (for the player), or null. */
	public static @Nullable String error() {
		return error;
	}

	public static void clearError() {
		if (error != null) {
			error = null;
			revision++;
		}
	}

	/** Changes whenever what the card shows changes. */
	public static int revision() {
		return revision;
	}

	public static boolean busy() {
		return switching != null || adding || removing != null;
	}

	/** The account the game plays as now, in Flint's form (32 hex digits). */
	public static String currentId() {
		return Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
	}

	/** Asks Flint for its accounts again (the card was opened, or a sign-in moved on). */
	public static void refresh() {
		LauncherLink link = LauncherLink.get();
		if (link == null) {
			set(Reach.NO_APP, List.of());
			return;
		}
		if (asking) {
			return;
		}
		asking = true;
		WORKER.execute(() -> {
			LauncherLink.Accounts answer = null;
			boolean unreachable = false;
			try {
				answer = link.accounts();
			} catch (LauncherLink.LinkException e) {
				unreachable = true;
			}
			LauncherLink.Accounts result = answer;
			boolean gone = unreachable;
			Minecraft.getInstance().execute(() -> {
				asking = false;
				if (gone || result == null) {
					set(Reach.NO_APP, List.of());
					adding = false;
					return;
				}
				set(Reach.READY, ordered(result.list()));
				if (adding) {
					follow(result.signIn());
				}
			});
		});
	}

	/** The game's account first, then Flint's order. */
	private static List<LauncherLink.Account> ordered(List<LauncherLink.Account> list) {
		String current = currentId();
		return list.stream().sorted((a, b) -> Boolean.compare(!a.uuid().equals(current), !b.uuid().equals(current))).toList();
	}

	private static void set(Reach next, List<LauncherLink.Account> list) {
		if (next != reach || !list.equals(accounts)) {
			reach = next;
			accounts = list;
			if (confirming != null && list.stream().noneMatch(a -> a.uuid().equals(confirming))) {
				confirming = null;
			}
			revision++;
		}
	}

	/** Polls Flint while a sign-in runs (from the screen's tick). */
	public static void tick() {
		if (adding && ++pollTicks >= POLL_TICKS) {
			pollTicks = 0;
			refresh();
		}
	}

	/** Switches the game to {@code uuid}: a fresh session from Flint, then Minecraft's user swapped here, then Flint told. */
	public static void switchTo(String uuid) {
		LauncherLink link = LauncherLink.get();
		if (link == null || busy() || uuid.equals(currentId())) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null) {
			fail("Leave the world first, then switch accounts.");
			return;
		}
		switching = uuid;
		confirming = null;
		error = null;
		revision++;
		WORKER.execute(() -> {
			LauncherLink.Session session;
			try {
				session = link.session(uuid);
			} catch (LauncherLink.LinkException e) {
				String message = e.unreachable() ? "Flint isn't running. Open the Flint app, then try again." : e.getMessage();
				minecraft.execute(() -> {
					switching = null;
					fail(message);
					if (e.unreachable()) {
						set(Reach.NO_APP, List.of());
					}
				});
				return;
			}
			minecraft.execute(() -> {
				try {
					SessionSwitch.apply(session);
				} catch (SessionSwitch.SwitchException e) {
					FlintClient.LOGGER.warn("Flint: couldn't switch accounts", e.getCause() == null ? e : e.getCause());
					switching = null;
					fail(e.getMessage());
					return;
				} catch (RuntimeException | LinkageError e) {
					FlintClient.LOGGER.error("Flint: couldn't switch accounts", e);
					switching = null;
					fail("Minecraft couldn't switch to this account. Restart the game from Flint to play as it.");
					return;
				}
				switching = null;
				revision++;
				accounts = ordered(accounts);
				WORKER.execute(() -> {
					try {
						link.activate(uuid);
					} catch (LauncherLink.LinkException e) {
						FlintClient.LOGGER.warn("Flint: the app didn't take the switch: {}", e.getMessage());
					}
				});
			});
		});
	}

	/**
	 * Starts Flint's Microsoft sign-in: Flint gets it ready and hands back Microsoft's page, which the game opens in the
	 * browser (the game is in front, so the browser comes forward; Flint, minimised, couldn't bring it there). The new account
	 * appears, and the game switches to it, when Flint has it.
	 */
	public static void add() {
		LauncherLink link = LauncherLink.get();
		if (link == null || busy()) {
			return;
		}
		adding = true;
		signInUrl = null;
		copiedAt = 0;
		confirming = null;
		error = null;
		pollTicks = 0;
		revision++;
		WORKER.execute(() -> {
			try {
				String url = link.addAccount();
				Minecraft.getInstance().execute(() -> {
					if (adding && url != null) {
						signInUrl = url;
						revision++;
						openSignInPage();
					}
				});
			} catch (LauncherLink.LinkException e) {
				String message = e.unreachable() ? "Flint isn't running. Open the Flint app, then try again." : e.getMessage();
				Minecraft.getInstance().execute(() -> {
					adding = false;
					fail(message);
				});
			}
		});
	}

	/** Opens the waiting sign-in's page in the browser (again). */
	public static void openSignInPage() {
		String url = signInUrl();
		if (!signInPage(url)) {
			return;
		}
		try {
			com.flintclient.client.compat.McCompat.openUri(url);
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: the browser didn't open for the sign-in", e);
			fail("Your browser didn't open. Copy the link and open it there.");
		}
	}

	/** Whether {@code url} is Microsoft's sign-in page over HTTPS: the only kind of address the game opens for Flint. */
	static boolean signInPage(@Nullable String url) {
		return url != null && url.length() < 4096 && url.indexOf(' ') < 0 && SIGN_IN_HOSTS.stream().anyMatch(url::startsWith);
	}

	/** Puts the waiting sign-in's page on the clipboard (when the browser didn't open, or is on another screen). */
	public static void copySignInLink() {
		String url = signInUrl();
		if (url != null) {
			Minecraft.getInstance().keyboardHandler.setClipboard(url);
			copiedAt = System.currentTimeMillis();
		}
	}

	/** Stops waiting for the sign-in (and closes Flint's). */
	public static void cancelAdd() {
		LauncherLink link = LauncherLink.get();
		if (!adding || link == null) {
			return;
		}
		adding = false;
		signInUrl = null;
		revision++;
		WORKER.execute(() -> {
			try {
				link.cancelAdd();
			} catch (LauncherLink.LinkException ignored) {
				// Flint's own sign-in times out by itself.
			}
		});
	}

	/** Asks "Remove?" on {@code uuid}'s row (another account than the game's). */
	public static void confirmRemove(String uuid) {
		// The card reads this every frame (the row's line and its chips change in place, nothing is rebuilt).
		if (!uuid.equals(currentId()) && !busy()) {
			confirming = uuid;
		}
	}

	/** Keeps the account: the row goes back to how it was. */
	public static void keep() {
		confirming = null;
	}

	/**
	 * Signs {@code uuid} out of Flint (as the app's account menu does: its saved sign-in goes, and the app's account menu
	 * updates). Never the account the game plays as: Flint refuses it too.
	 */
	public static void remove(String uuid) {
		LauncherLink link = LauncherLink.get();
		String playing = currentId();
		if (link == null || busy() || uuid.equals(playing)) {
			return;
		}
		removing = uuid;
		error = null;
		revision++;
		WORKER.execute(() -> {
			LauncherLink.LinkException failure = null;
			try {
				link.removeAccount(uuid, playing);
			} catch (LauncherLink.LinkException e) {
				failure = e;
			}
			LauncherLink.LinkException failed = failure;
			Minecraft.getInstance().execute(() -> {
				removing = null;
				confirming = null;
				if (failed != null) {
					fail(failed.unreachable() ? "Flint isn't running. Open the Flint app, then try again." : failed.getMessage());
					if (failed.unreachable()) {
						set(Reach.NO_APP, List.of());
					}
					return;
				}
				set(reach, accounts.stream().filter(a -> !a.uuid().equals(uuid)).toList());
				revision++;
				refresh();
			});
		});
	}

	/** The card closed: an open "Remove?" goes back. */
	public static void cardClosed() {
		keep();
	}

	private static void follow(LauncherLink.SignIn signIn) {
		if (signIn.done() && signIn.uuid() != null) {
			adding = false;
			signInUrl = null;
			revision++;
			if (Minecraft.getInstance().level == null) {
				switchTo(signIn.uuid());
			}
		} else if (signIn.failed()) {
			adding = false;
			signInUrl = null;
			fail(signIn.message() == null ? "The sign-in didn't finish." : signIn.message());
		} else if (!signIn.running()) {
			// Flint forgot the sign-in (it restarted): nothing to wait for.
			adding = false;
			signInUrl = null;
			revision++;
		} else if (signIn.url() != null && !signIn.url().equals(signInUrl)) {
			signInUrl = signIn.url();
			revision++;
		}
	}

	private static void fail(String message) {
		error = message;
		revision++;
	}
}

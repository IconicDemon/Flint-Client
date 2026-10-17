package com.flintclient.client.network.platform;

import com.flintclient.client.network.ConnectionOutcome;
import com.flintclient.client.network.DisconnectReasons;
import com.flintclient.client.network.NetworkCore;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

/**
 * Where Flint's network mixins land: turns Minecraft 26.3's objects into the tracker's plain events
 * (the connection as an identity token, reasons as vanilla translation keys) and keeps a hook that throws
 * from reaching Minecraft. Each method says which thread calls it. None of them changes, delays or sends
 * anything.
 */
public final class NetworkHooks {
	// The tracked socket connect until it completes, for a failure screen that beats its listener.
	private static final AtomicReference<@Nullable Opening> OPENING = new AtomicReference<>();

	// Which connection the future opens, so a screen only ever reads the running attempt's own future.
	private record Opening(Connection connection, ChannelFuture future) {
	}

	private NetworkHooks() {
	}

	/** Main thread: the connect screen is starting {@code connector}, the thread that resolves and connects. */
	public static void attemptStarted(Thread connector) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().attemptStarted(connector.threadId());
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Any thread that looks up a server address (connector threads, server-list and Flint menu pings). */
	public static void resolving() {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().resolving(Thread.currentThread().threadId());
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Same threads: the lookup returned; {@code found} is false for an unknown or blocked host. */
	public static void resolved(boolean found) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().resolved(Thread.currentThread().threadId(), found);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/**
	 * Any thread opening a remote connection. For the tracked attempt's connector thread, listens to the
	 * connect future: the listener runs on the Netty thread that completes it and only reads the result.
	 */
	public static void socketOpening(Connection connection, ChannelFuture future) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			if (NetworkCore.tracker().socketOpening(Thread.currentThread().threadId(), connection)) {
				Opening opening = new Opening(connection, future);
				OPENING.set(opening);
				future.addListener((ChannelFutureListener) done -> socketDone(opening));
			}
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	private static void socketDone(Opening opening) {
		OPENING.compareAndSet(opening, null);
		if (!NetworkCore.active()) {
			return;
		}
		try {
			if (opening.future().isSuccess()) {
				NetworkCore.tracker().socketOpened(opening.connection());
			} else {
				NetworkCore.tracker().socketFailed(opening.connection(), failure(opening.future()));
			}
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Netty: the server's login hello; {@code authenticate} when it wants a session check (online mode). */
	public static void hello(Connection connection, boolean authenticate) {
		if (!authenticate || !NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().authenticating(connection);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Netty, or the I/O pool after a session check: encryption starts, so authentication is over. */
	public static void encrypting(Connection connection) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().authenticated(connection);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Netty: login finished. */
	public static void loginFinished(Connection connection) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().loginFinished(connection);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Main thread: the server started reconfiguring a session in play. */
	public static void reconfiguring(Connection connection) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().reconfiguring(connection);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Main thread: play started (also after each reconfiguration). */
	public static void playStarted(Connection connection) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().connected(connection);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Netty: the server sent a disconnect (login or later). */
	public static void kicked(Connection connection, @Nullable Component reason) {
		if (!NetworkCore.active() || !NetworkCore.tracker().tracks(connection)) {
			return;
		}
		try {
			NetworkCore.tracker().kicked(connection, reasonKey(reason));
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/**
	 * Any thread, for every connection: {@code Connection.disconnect} is starting. Only the first one while
	 * the channel is open matters (vanilla stores that one), and only for the tracked connection.
	 */
	public static void disconnecting(Connection connection, DisconnectionDetails details) {
		if (!NetworkCore.active() || !NetworkCore.tracker().tracks(connection)) {
			return;
		}
		try {
			if (connection.isConnected()) {
				NetworkCore.tracker().disconnecting(connection, reasonKey(details.reason()));
			}
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Main thread: Minecraft handles the disconnect (a login, configuration or play listener's onDisconnect). */
	public static void disconnected(Connection connection, DisconnectionDetails details) {
		if (!NetworkCore.active() || !NetworkCore.tracker().tracks(connection)) {
			return;
		}
		try {
			NetworkCore.tracker().disconnected(connection, reasonKey(details.reason()));
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Main thread: the play listener is closed (quitting goes only through here). */
	public static void closed(Connection connection) {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().closed(connection);
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Main thread: a disconnected screen was made; it ends an attempt that failed before login began. */
	public static void disconnectedScreen(DisconnectionDetails details) {
		if (!NetworkCore.active() || !NetworkCore.tracker().current().running()) {
			return;
		}
		try {
			Opening opening = OPENING.get();
			ChannelFuture future = opening == null || !NetworkCore.tracker().tracks(opening.connection()) ? null : opening.future();
			ConnectionOutcome cause = future != null && future.isDone() && !future.isSuccess() ? failure(future) : null;
			NetworkCore.tracker().failedWithoutConnection(cause, reasonKey(details.reason()));
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	/** Main thread: the connect screen was replaced. */
	public static void connectScreenClosed() {
		if (!NetworkCore.active()) {
			return;
		}
		try {
			NetworkCore.tracker().connectScreenClosed();
		} catch (RuntimeException e) {
			NetworkCore.fail(e);
		}
	}

	private static ConnectionOutcome failure(ChannelFuture future) {
		return future.isCancelled() ? ConnectionOutcome.CANCELLED : DisconnectReasons.outcome(future.cause());
	}

	/**
	 * The reason's vanilla translation key, or null. For "Connection Lost: %s"-style wrappers, the wrapped
	 * key when that's a vanilla one too ("unknown host", "invalid session"). Never the text itself.
	 */
	static @Nullable String reasonKey(@Nullable Component reason) {
		if (reason == null || !(reason.getContents() instanceof TranslatableContents translatable)) {
			return null;
		}
		String key = translatable.getKey();
		if (DisconnectReasons.wrapsReason(key)) {
			Object[] args = translatable.getArgs();
			if (args.length > 0 && args[0] instanceof Component inner && inner.getContents() instanceof TranslatableContents wrapped) {
				String innerKey = DisconnectReasons.vanillaKey(wrapped.getKey());
				if (innerKey != null) {
					return innerKey;
				}
			}
		}
		return DisconnectReasons.vanillaKey(key);
	}
}

package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.PresenceClient;
import com.flintclient.client.control.store.RewardsClient;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Daily rewards and the Merit balance, as the main menu's card and the Daily Rewards window
 * show them: read from Flint's servers ({@link RewardsClient}) once the account is proven ({@link FlintPresence}), again
 * every {@link #REFRESH_MILLIS} while something shows them and as soon as the next reward is due; {@link #claim} claims it.
 * Times come from the server and are kept against this computer's clock (the difference is measured at each answer), so a
 * wrong clock can't bring a reward early or late.
 *
 * <p>Game thread: {@link #update} picks up answers (they arrive on request threads) and asks again when due. A claim's
 * success bumps {@link #claims}, so a view can play its moment once. Pure Java.
 */
public final class DailyRewards {
	static final long REFRESH_MILLIS = 5 * 60_000L;
	static final long RETRY_MILLIS = 30_000L;
	/** After a claim the balance counts up to its new total: starting this long after the answer (as the Merits arrive), over COUNT_MILLIS. */
	public static final long COUNT_DELAY_MILLIS = 450;
	public static final long COUNT_MILLIS = 1100;
	/** A claim refused for a reason the server didn't say lasts all day: not offered again for this long. */
	static final long REFUSED_MILLIS = 10 * 60_000L;
	private static final long DAY_MILLIS = 86_400_000L;

	/** What can be shown. */
	public enum State {
		/** This Flint was built without Flint's servers. */
		UNAVAILABLE,
		/** Proving the account or reading the rewards. */
		LOADING,
		/** Mojang won't confirm this account (an offline account): it can't collect rewards. */
		NO_ACCOUNT,
		/** Flint's servers can't be reached right now (tried again by itself). */
		OFFLINE,
		/** Flint's servers have daily rewards switched off (or closed for this account); read again by itself. */
		PAUSED,
		/** The account hasn't signed in to the Flint Store yet, where its Merit is kept. */
		STORE_ACCOUNT,
		/** {@link #rewards} is there. */
		READY
	}

	private final RewardsClient client;
	private final FlintPresence presence;
	// ---------- game thread ----------
	private RewardsClient.@Nullable Rewards rewards;
	private @Nullable UUID rewardsFor;
	/** Server time minus this computer's, at the last answer. */
	private long offset;
	private long nextAsk;
	private long lastAsk = Long.MIN_VALUE / 2;
	private boolean asking;
	private boolean claiming;
	private boolean offline;
	private int claims;
	private int failures;
	private long balanceBefore;
	private int claimedDay;
	private int claimedReward;
	/** When the last claim's answer came (this computer's epoch milliseconds), for the count up; 0 before any. */
	private long claimedAt;
	/** Why the server refused the last claim (its code), and until when (server time) it isn't offered again. */
	private @Nullable String refusal;
	private long refusedUntil = Long.MIN_VALUE;
	// ---------- answers, from the request threads ----------
	private volatile RewardsClient.@Nullable Answer statusAnswer;
	private volatile RewardsClient.@Nullable Answer claimAnswer;

	public DailyRewards(RewardsClient client, FlintPresence presence) {
		this.client = client;
		this.presence = presence;
	}

	private static volatile @Nullable DailyRewards shared;

	public static DailyRewards get() {
		DailyRewards rewards = shared;
		if (rewards == null) {
			rewards = new DailyRewards(new RewardsClient(PresenceClient.get()), FlintPresence.get());
			shared = rewards;
		}
		return rewards;
	}

	/** Picks up answers and asks again when due; {@code now} is this computer's time in epoch milliseconds. */
	public void update(long now) {
		if (!client.configured()) {
			return;
		}
		take(now);
		FlintPresence.Account account = presence.account();
		String token = presence.token();
		if (account == null || token == null) {
			return;
		}
		if (!account.id().equals(rewardsFor)) {
			// Another account: its own rewards, read now.
			rewards = null;
			rewardsFor = account.id();
			refusal = null;
			refusedUntil = Long.MIN_VALUE;
			nextAsk = now;
		}
		boolean due = now - nextAsk >= 0 || rewards != null && !rewards.canClaim() && rewards.nextClaimAt() >= 0
			&& serverNow(now) >= rewards.nextClaimAt() && now - lastAsk >= 5_000;
		if (!asking && !claiming && due) {
			asking = true;
			lastAsk = now;
			nextAsk = now + REFRESH_MILLIS;
			client.status(account.id(), token).thenAccept(a -> statusAnswer = a);
		}
	}

	/** Claims today's reward (nothing while a claim is on its way or there is nothing to claim). */
	public void claim(long now) {
		FlintPresence.Account account = presence.account();
		String token = presence.token();
		if (claiming || account == null || token == null || !claimable(now)) {
			return;
		}
		claiming = true;
		balanceBefore = rewards == null ? 0 : rewards.balance();
		client.claim(account.id(), token).thenAccept(a -> claimAnswer = a);
	}

	private void take(long now) {
		RewardsClient.Answer s = statusAnswer;
		if (s != null) {
			statusAnswer = null;
			asking = false;
			accept(s, now);
		}
		RewardsClient.Answer c = claimAnswer;
		if (c != null) {
			claimAnswer = null;
			claiming = false;
			boolean was = rewards != null && rewards.canClaim();
			if (c.refusal() != null) {
				// The server answered and said no (this network's daily limit, today's total, rewards switched off…): the button
				// isn't offered again until that can have changed, the reason is shown, and the rewards are read again now.
				refusal = c.refusal();
				long server = serverNow(now);
				boolean allDay = "ip_daily_limit".equals(refusal) || "daily_cap_reached".equals(refusal);
				refusedUntil = allDay ? (Math.floorDiv(server, DAY_MILLIS) + 1) * DAY_MILLIS : server + REFUSED_MILLIS;
				nextAsk = now;
				return;
			}
			accept(c, now);
			if (c.rewards() != null && c.rewards().claimedReward() > 0) {
				claimedDay = c.rewards().claimedDay();
				claimedReward = c.rewards().claimedReward();
				claimedAt = now;
				claims++;
			} else if (c.status() != PresenceClient.Status.OK && was) {
				// Not claimed (no answer): the button comes back and the rewards are read again soon.
				nextAsk = now + RETRY_MILLIS;
			}
		}
	}

	private void accept(RewardsClient.Answer answer, long now) {
		switch (answer.status()) {
			case OK -> {
				rewards = answer.rewards();
				offline = false;
				failures = 0;
				if (rewards != null && rewards.serverTime() > 0) {
					offset = rewards.serverTime() - now;
				}
			}
			case EXPIRED -> {
				presence.tokenExpired();
				nextAsk = now;
			}
			default -> {
				offline = true;
				failures++;
				nextAsk = now + Math.min(RETRY_MILLIS << Math.min(failures - 1, 4), REFRESH_MILLIS);
			}
		}
	}

	public State state() {
		if (!client.configured()) {
			return State.UNAVAILABLE;
		}
		if (rewards != null && presence.account() != null && presence.account().id().equals(rewardsFor)) {
			String closed = rewards.unavailable();
			return closed == null ? State.READY : "store_account_required".equals(closed) ? State.STORE_ACCOUNT : State.PAUSED;
		}
		return switch (presence.proof()) {
			case NOT_CONFIGURED -> State.UNAVAILABLE;
			case REFUSED -> State.NO_ACCOUNT;
			case OFFLINE -> State.OFFLINE;
			default -> offline ? State.OFFLINE : State.LOADING;
		};
	}

	/** The rewards last read (null before the first answer, or for another account). */
	public RewardsClient.@Nullable Rewards rewards() {
		return state() == State.READY ? rewards : null;
	}

	/** Today's reward can be claimed now (not while a refusal still holds). */
	public boolean claimable(long now) {
		RewardsClient.Rewards r = rewards();
		return r != null && serverNow(now) >= refusedUntil && (r.canClaim() || r.nextClaimAt() >= 0 && serverNow(now) >= r.nextClaimAt());
	}

	/** Why the last claim was refused, while that still holds ({@code "ip_daily_limit"}, {@code "daily_cap_reached"}…). */
	public @Nullable String refusal(long now) {
		return refusal != null && serverNow(now) < refusedUntil ? refusal : null;
	}

	/** Why the server closed rewards for this account ({@code "disabled"}, {@code "locked"}, {@code "account_too_new"}…), or null. */
	public @Nullable String closedReason() {
		RewardsClient.Rewards r = rewards;
		return r == null ? null : r.unavailable();
	}

	/** How long until the next reward, in milliseconds (0 when it is there). */
	public long untilNext(long now) {
		RewardsClient.Rewards r = rewards();
		if (r == null || claimable(now) || r.nextClaimAt() < 0) {
			return 0;
		}
		return Math.max(0, r.nextClaimAt() - serverNow(now));
	}

	public boolean claiming() {
		return claiming;
	}

	/** How many claims succeeded this session: a view plays its moment when this changes. */
	public int claims() {
		return claims;
	}

	/** The streak day the last claim was for. */
	public int claimedDay() {
		return claimedDay;
	}

	/** The Merits the last claim gave. */
	public int claimedReward() {
		return claimedReward;
	}

	/**
	 * The balance to show at {@code now}: the balance, except just after a claim, when it counts up eased from the old total
	 * to the new one, the same on every view.
	 */
	public long shownBalance(long now) {
		RewardsClient.Rewards r = rewards();
		if (r == null) {
			return -1;
		}
		long since = now - claimedAt - COUNT_DELAY_MILLIS;
		if (claimedAt == 0 || since >= COUNT_MILLIS) {
			return r.balance();
		}
		float t = Math.clamp(since / (float) COUNT_MILLIS, 0, 1);
		float eased = 1 - (1 - t) * (1 - t) * (1 - t);
		return balanceBefore + Math.round((r.balance() - balanceBefore) * eased);
	}

	/** The balance before the last claim, for counting up to the new one. */
	public long balanceBefore() {
		return balanceBefore;
	}

	/** The server's time now. */
	private long serverNow(long now) {
		return now + offset;
	}
}

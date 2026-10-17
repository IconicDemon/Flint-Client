package com.flintclient.client.control.model;

/**
 * When first-time setup appears by itself. Only for a genuinely new player: setup not finished or skipped,
 * and no layout file at all (a damaged one counts, since it means the player had layouts). Anyone with
 * layouts is never interrupted; they can run setup again from Settings. What finishing and skipping do is
 * in {@code store.Onboarding}.
 */
public final class OnboardingDecision {
	private OnboardingDecision() {
	}

	public static boolean shouldShow(boolean completed, boolean anyLayoutFileExists) {
		return !completed && !anyLayoutFileExists;
	}
}

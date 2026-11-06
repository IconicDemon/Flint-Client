package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.ItemPainter;

/** The Item Counter and Cooldowns lists. */
final class ItemViews {
	private ItemViews() {
	}

	static final class CounterView extends ItemListView {
		private static final boolean[] ON = new boolean[HudData.GROUPS];

		CounterView(int surface) {
			super(HudMods.ItemCounter.ID, surface);
		}

		@Override
		boolean vertical(ModuleState state) {
			return state.option(HudMods.ItemCounter.LAYOUT) == 1;
		}

		@Override
		int collect(ModuleState state, int mode, long nowNanos) {
			ON[HudData.ARROWS] = state.bool(HudMods.ItemCounter.ARROWS);
			ON[HudData.GOLDEN_APPLES] = state.bool(HudMods.ItemCounter.GOLDEN_APPLES);
			ON[HudData.PEARLS] = state.bool(HudMods.ItemCounter.PEARLS);
			ON[HudData.TOTEMS] = state.bool(HudMods.ItemCounter.TOTEMS);
			ON[HudData.FIREWORKS] = state.bool(HudMods.ItemCounter.FIREWORKS);
			ON[HudData.BLOCKS] = state.bool(HudMods.ItemCounter.BLOCKS);
			boolean sample = mode == SAMPLE;
			boolean skip = state.bool(HudMods.ItemCounter.SKIP_ZERO) && !sample;
			if (!sample && !HudData.inWorld()) {
				return 0;
			}
			int n = 0;
			for (int g = 0; g < HudData.GROUPS && n < MAX; g++) {
				if (!ON[g]) {
					continue;
				}
				Object icon = HudData.groupIcon(g);
				if (icon == null) {
					// A group this game doesn't have (1.8.9 has no totems).
					continue;
				}
				int count = sample ? 64 - 9 * g : HudData.groupCount(g);
				if (count == 0 && skip) {
					continue;
				}
				stacks[n] = icon;
				text().integer(count);
				setText(n++);
			}
			int extras = HudData.setExtraItems(state.text(HudMods.ItemCounter.EXTRA));
			for (int i = 0; i < extras && n < MAX; i++) {
				int count = sample ? 8 : HudData.extraCount(i);
				if (count == 0 && skip) {
					continue;
				}
				stacks[n] = HudData.extraIcon(i);
				text().integer(count);
				setText(n++);
			}
			return n;
		}

		@Override
		String emptyReason() {
			return "None of the tracked items in your inventory";
		}
	}

	static final class CooldownView extends ItemListView {
		private static final Object[] SAMPLE_STACKS = new Object[2];
		private final Object[] found = new Object[MAX];
		private final float[] left = new float[MAX];
		private final int[] ticks = new int[MAX];

		CooldownView(int surface) {
			super(HudMods.Cooldowns.ID, surface);
		}

		@Override
		boolean vertical(ModuleState state) {
			return state.option(HudMods.Cooldowns.LAYOUT) == 0;
		}

		@Override
		int collect(ModuleState state, int mode, long nowNanos) {
			boolean bar = state.option(HudMods.Cooldowns.FORMAT) == 1;
			int n;
			if (mode == SAMPLE) {
				if (SAMPLE_STACKS[0] == null) {
					SAMPLE_STACKS[0] = ItemPainter.sample("ender_pearl", 1);
					SAMPLE_STACKS[1] = ItemPainter.sample("shield", 1);
				}
				found[0] = SAMPLE_STACKS[0];
				found[1] = SAMPLE_STACKS[1];
				left[0] = 0.4F;
				left[1] = 0.7F;
				ticks[0] = 8;
				ticks[1] = 70;
				n = 2;
			} else {
				n = HudData.cooldowns(found, left, ticks, MAX, 0);
			}
			for (int i = 0; i < n; i++) {
				stacks[i] = found[i];
				share[i] = bar ? left[i] : 1;
				if (bar) {
					text();
				} else if (ticks[i] >= 0) {
					text().fixed(ticks[i] / 20.0, 1).ch('s');
				} else {
					text().integer(Math.round(left[i] * 100)).ch('%');
				}
				setText(i);
			}
			return n;
		}

		@Override
		String emptyReason() {
			return "No item is cooling down";
		}
	}
}

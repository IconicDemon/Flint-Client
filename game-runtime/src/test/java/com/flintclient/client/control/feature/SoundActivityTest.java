package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreMods.Subtitles;
import org.junit.jupiter.api.Test;

/** Subtitles' Nearby Sound Activity: its rows, how repeats are grouped, how rows go, and which way the arrows point. */
class SoundActivityTest {
	private static final long S = 1_000_000_000L;
	private static final long LIFE = 3 * S;

	private static SoundActivity list() {
		return new SoundActivity();
	}

	private static void hear(SoundActivity a, String text, double x, double z, long now, int grouping, int max) {
		a.heard(text, SoundActivity.BLOCKS, SoundActivity.KIND_BLOCKS, true, x, 64, z, now, grouping, max, LIFE);
	}

	@Test
	void newSoundsStackUpwardOldestAtTheBottom() {
		SoundActivity a = list();
		hear(a, "Chest opens", 0, 0, 0, SoundActivity.SAME_PLACE, 8);
		hear(a, "Footsteps", 5, 0, S / 10, SoundActivity.SAME_PLACE, 8);
		hear(a, "Shulker box closes", -5, 0, S / 5, SoundActivity.SAME_PLACE, 8);
		assertEquals(3, a.count());
		assertEquals("Chest opens", a.row(0).text(), "the bottom row is the oldest");
		assertEquals("Shulker box closes", a.row(2).text(), "the newest comes on top");
	}

	@Test
	void aRepeatWhereItWasRefreshesItsRowAndKeepsItsPlace() {
		SoundActivity a = list();
		hear(a, "Footsteps", 0, 0, 0, SoundActivity.SAME_PLACE, 8);
		hear(a, "Chest opens", 3, 3, S / 10, SoundActivity.SAME_PLACE, 8);
		for (int i = 1; i <= 50; i++) {
			hear(a, "Footsteps", i * 0.1, 0, S / 10 + i * S / 20, SoundActivity.SAME_PLACE, 8);
		}
		assertEquals(2, a.count(), "fifty footsteps are one row");
		SoundActivity.Entry steps = a.row(0);
		assertEquals("Footsteps", steps.text(), "and it stays where it was");
		assertEquals(51, steps.count());
		assertEquals(0.0, steps.x(), 1e-9, "its arrow stays on where the row began");
		assertEquals(S / 10 + 50 * S / 20, steps.last());
	}

	@Test
	void aRingOfTheSameSoundNeverChainsIntoOneRowAndEachRowsArrowStaysPut() {
		// Thirty cows on a ring of 10 blocks around you, two blocks apart, each calling in turn, lap after lap: each repeat is
		// near the one before, so rows that followed their latest sound would chain all the way round into one.
		SoundActivity a = list();
		int cows = 30;
		long now = 0;
		double[] firstX = null;
		double[] firstZ = null;
		int[] firstWay = null;
		for (int lap = 0; lap < 5; lap++) {
			for (int i = 0; i < cows; i++) {
				double angle = 2 * Math.PI * i / cows;
				now += S / 600;
				a.heard("Cow moos", SoundActivity.ENTITIES, SoundActivity.KIND_NEUTRAL, true, 10 * Math.cos(angle), 64, 10 * Math.sin(angle), now,
					SoundActivity.SAME_PLACE, SoundActivity.CAPACITY, LIFE);
			}
			if (lap == 0) {
				firstX = new double[a.count()];
				firstZ = new double[a.count()];
				firstWay = new int[a.count()];
				for (int r = 0; r < a.count(); r++) {
					firstX[r] = a.row(r).x();
					firstZ[r] = a.row(r).z();
					firstWay[r] = SoundActivity.direction(0, 0, 0, firstX[r], firstZ[r], SoundActivity.NONE);
				}
			}
		}
		int rows = a.count();
		assertTrue(rows >= 6, "the ring is several rows, not one: " + rows);
		assertEquals(firstX.length, rows, "no row was added or lost after the first lap");
		long counted = 0;
		for (int r = 0; r < rows; r++) {
			SoundActivity.Entry e = a.row(r);
			counted += e.count();
			assertEquals(firstX[r], e.x(), 1e-9, "row " + r + " points where it began");
			assertEquals(firstZ[r], e.z(), 1e-9);
			assertEquals(firstWay[r], SoundActivity.direction(0, 0, 0, e.x(), e.z(), firstWay[r]), "row " + r + "'s arrow stays put");
			for (int q = 0; q < r; q++) {
				double apart = Math.hypot(e.x() - a.row(q).x(), e.z() - a.row(q).z());
				assertTrue(apart > SoundActivity.PLACE_BLOCKS, "rows " + q + " and " + r + " are separate places, " + apart + " apart");
			}
		}
		assertEquals(5L * cows, counted, "every call was counted, each in one row");
	}

	@Test
	void theShownCountStopsAt99Plus() {
		assertEquals("", SoundActivity.countLabel(0));
		assertEquals("", SoundActivity.countLabel(1), "a sound heard once shows no count");
		assertEquals("×2", SoundActivity.countLabel(2));
		assertEquals("×99", SoundActivity.countLabel(99));
		assertEquals("×99+", SoundActivity.countLabel(100));
		assertEquals("×99+", SoundActivity.countLabel(9060));
		assertSame(SoundActivity.countLabel(57), SoundActivity.countLabel(57), "made once, never per frame");
	}

	@Test
	void theSameSoundSomewhereElseGetsItsOwnRow() {
		SoundActivity a = list();
		hear(a, "Footsteps", 0, 0, 0, SoundActivity.SAME_PLACE, 8);
		hear(a, "Footsteps", 20, 0, S / 10, SoundActivity.SAME_PLACE, 8);
		assertEquals(2, a.count(), "two players walking apart are two rows");
		hear(a, "Footsteps", 19, 1, S / 5, SoundActivity.SAME_PLACE, 8);
		assertEquals(2, a.count(), "a repeat goes to the nearer row");
		assertEquals(2, a.row(1).count());
		assertEquals(1, a.row(0).count());
	}

	@Test
	void sameCaptionGroupsWhereverItIsAndOffGroupsNothing() {
		SoundActivity byCaption = list();
		hear(byCaption, "Footsteps", 0, 0, 0, SoundActivity.SAME_CAPTION, 8);
		hear(byCaption, "Footsteps", 40, 0, 1, SoundActivity.SAME_CAPTION, 8);
		assertEquals(1, byCaption.count());
		assertEquals(40, byCaption.row(0).x(), 1e-9);
		SoundActivity off = list();
		hear(off, "Footsteps", 0, 0, 0, SoundActivity.NO_GROUPING, 8);
		hear(off, "Footsteps", 0, 0, 1, SoundActivity.NO_GROUPING, 8);
		assertEquals(2, off.count());
	}

	@Test
	void distinctSoundsCloseTogetherAreNeverMerged() {
		SoundActivity a = list();
		hear(a, "TNT fizzes", 1, 1, 0, SoundActivity.SAME_PLACE, 8);
		hear(a, "Explosion", 1, 1, 1, SoundActivity.SAME_PLACE, 8);
		hear(a, "Bottle thrown", 1, 1, 2, SoundActivity.SAME_PLACE, 8);
		hear(a, "Bottle smashes", 1, 1, 3, SoundActivity.SAME_PLACE, 8);
		assertEquals(4, a.count());
	}

	@Test
	void aFullListLetsTheSoundHeardLongestAgoGo() {
		SoundActivity a = list();
		hear(a, "Footsteps", 0, 0, 0, SoundActivity.SAME_PLACE, 3);
		hear(a, "Chest opens", 0, 0, 1, SoundActivity.SAME_PLACE, 3);
		hear(a, "Door creaks", 0, 0, 2, SoundActivity.SAME_PLACE, 3);
		hear(a, "Footsteps", 0, 0, 3, SoundActivity.SAME_PLACE, 3);
		hear(a, "Explosion", 0, 0, 4, SoundActivity.SAME_PLACE, 3);
		assertEquals(3, a.count());
		assertEquals("Footsteps", a.row(0).text(), "still heard, so it stays");
		assertEquals("Door creaks", a.row(1).text());
		assertEquals("Explosion", a.row(2).text());
		a.trim(1);
		assertEquals(1, a.count());
		assertEquals("Explosion", a.row(0).text(), "lowering the maximum keeps the freshest");
	}

	@Test
	void rowsGoOnceTheirSoundStopsForTheirLifetime() {
		SoundActivity a = list();
		hear(a, "Chest opens", 0, 0, 0, SoundActivity.SAME_PLACE, 8);
		hear(a, "Footsteps", 4, 4, 2 * S, SoundActivity.SAME_PLACE, 8);
		a.expire(3 * S, LIFE);
		assertEquals(2, a.count(), "three seconds: still showing");
		a.expire(3 * S + 1, LIFE);
		assertEquals(1, a.count());
		assertEquals("Footsteps", a.row(0).text(), "the row above moves down into its place");
		a.expire(10 * S, LIFE);
		assertEquals(0, a.count());
	}

	@Test
	void hundredsOfSoundsKeepTheListBoundedAndAllocationFree() {
		SoundActivity a = list();
		SoundActivity.Entry[] pool = new SoundActivity.Entry[SoundActivity.CAPACITY];
		for (int i = 0; i < SoundActivity.CAPACITY; i++) {
			hear(a, "Sound " + i, i * 10, 0, i, SoundActivity.NO_GROUPING, SoundActivity.CAPACITY);
			pool[i] = a.row(i);
		}
		for (int i = 0; i < 100_000; i++) {
			hear(a, (i % 3 == 0 ? "Footsteps" : "Sound " + (i % 40)), i % 70, i % 13, SoundActivity.CAPACITY + i * 1000L,
				i % 2 == 0 ? SoundActivity.SAME_PLACE : SoundActivity.NO_GROUPING, SoundActivity.CAPACITY);
		}
		assertEquals(SoundActivity.CAPACITY, a.count());
		java.util.Set<SoundActivity.Entry> rows = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (int i = 0; i < a.count(); i++) {
			rows.add(a.row(i));
		}
		assertEquals(java.util.Set.of(pool).size(), rows.size());
		for (SoundActivity.Entry e : pool) {
			assertTrue(rows.contains(e), "the rows are the same pooled objects");
		}
		a.clear();
		assertEquals(0, a.count());
	}

	@Test
	void arrowsPointTheWayAsYouFace() {
		int none = SoundActivity.NONE;
		// Facing south (+z): east (+x) is on your left, west on your right.
		assertEquals(SoundActivity.LEFT, SoundActivity.direction(0, 0, 0, 5, 0, none));
		assertEquals(SoundActivity.RIGHT, SoundActivity.direction(0, 0, 0, -5, 0, none));
		assertEquals(SoundActivity.FRONT, SoundActivity.direction(0, 0, 0, 0, 5, none));
		assertEquals(SoundActivity.BEHIND, SoundActivity.direction(0, 0, 0, 0, -5, none));
		// Facing west (yaw 90): north (-z) is on your right; facing north (180), east is.
		assertEquals(SoundActivity.FRONT, SoundActivity.direction(0, 0, 90, -5, 0, none));
		assertEquals(SoundActivity.RIGHT, SoundActivity.direction(0, 0, 90, 0, -5, none));
		assertEquals(SoundActivity.RIGHT, SoundActivity.direction(0, 0, 180, 5, 0, none));
		assertEquals(SoundActivity.LEFT, SoundActivity.direction(0, 0, -90, 0, -5, none), "facing east, north is on the left");
		// Turning: the same sound changes side as you turn, whatever the yaw's winding.
		assertEquals(SoundActivity.direction(10, 10, 45, 13, 2, none), SoundActivity.direction(10, 10, 45 + 720, 13, 2, none));
		// Behind and to the left is still on the left; right where you stand, no arrow.
		assertEquals(SoundActivity.LEFT, SoundActivity.direction(0, 0, 0, 5, -3, none));
		assertEquals(SoundActivity.NEAR, SoundActivity.direction(3, 3, 0, 3.5, 3.5, none));
	}

	@Test
	void anArrowOnAnEdgeDoesntFlicker() {
		// 35 degrees right of straight ahead: past the front's 32 when coming from the side, inside its 40 once in front.
		double x = -Math.sin(Math.toRadians(35)) * 10;
		double z = Math.cos(Math.toRadians(35)) * 10;
		assertEquals(SoundActivity.RIGHT, SoundActivity.direction(0, 0, 0, x, z, SoundActivity.RIGHT));
		assertEquals(SoundActivity.FRONT, SoundActivity.direction(0, 0, 0, x, z, SoundActivity.FRONT));
		assertEquals(SoundActivity.RIGHT, SoundActivity.direction(0, 0, 0, x, z, SoundActivity.NONE));
		// Near: 1.5 blocks away stays near once near, and isn't near otherwise.
		assertEquals(SoundActivity.NEAR, SoundActivity.direction(0, 0, 0, 1.5, 0, SoundActivity.NEAR));
		assertEquals(SoundActivity.LEFT, SoundActivity.direction(0, 0, 0, 1.5, 0, SoundActivity.LEFT));
	}

	@Test
	void combatSoundsAreKnownByTheirIds() {
		for (String id : new String[] {"entity.generic.explode", "entity.tnt.primed", "entity.arrow.shoot", "entity.arrow.hit_player",
			"entity.ender_pearl.throw", "entity.potion.throw", "entity.splash_potion.break", "entity.player.attack.crit", "entity.player.hurt",
			"entity.player.hurt_on_fire", "entity.player.death", "item.shield.block", "item.crossbow.shoot", "item.totem.use",
			"entity.wind_charge.wind_burst", "entity.creeper.primed", "entity.ghast.shoot", "entity.end_crystal.explode"}) {
			assertTrue(SoundActivity.combat(id), id);
		}
		for (String id : new String[] {"block.chest.open", "block.stone.step", "entity.zombie.ambient", "entity.generic.eat",
			"block.shulker_box.close", "weather.rain", "block.note_block.harp", "entity.player.levelup"}) {
			assertFalse(SoundActivity.combat(id), id);
		}
	}

	@Test
	void theSettingsDecideWhichSoundsShow() {
		ModuleState s = new ModuleState(ModuleRegistry.get(Subtitles.ID));
		assertTrue(SoundActivity.wanted(s, SoundActivity.ENTITIES, 10, 16, false), "every kind shows by default");
		assertTrue(SoundActivity.wanted(s, SoundActivity.PLAYERS, 0.2, 16, true), "your own sounds too");
		assertTrue(SoundActivity.wanted(s, SoundActivity.OTHER, Double.NaN, Double.POSITIVE_INFINITY, false), "a sound with no place");
		assertFalse(SoundActivity.wanted(s, SoundActivity.BLOCKS, 17, 16, false), "beyond where Minecraft lets it be heard");
		assertTrue(SoundActivity.wanted(s, SoundActivity.COMBAT, 40, 64, false), "a loud explosion farther away");
		assertFalse(SoundActivity.wanted(s, SoundActivity.COMBAT, 50, 64, false), "beyond the 48 blocks of Maximum distance");
		s.set(Subtitles.INCLUDE_ENTITIES, false);
		s.set(Subtitles.OWN_SOUNDS, false);
		assertFalse(SoundActivity.wanted(s, SoundActivity.ENTITIES, 10, 16, false));
		assertFalse(SoundActivity.wanted(s, SoundActivity.PLAYERS, 0.2, 16, true));
		assertTrue(SoundActivity.wanted(s, SoundActivity.PLAYERS, 6, 16, false), "another player's sounds still show");
		assertTrue(SoundActivity.wanted(s, SoundActivity.OTHER, Double.NaN, 16, false), "no switch leaves out the others");
		assertEquals(Math.round(3.6 * S), SoundActivity.lifeNanos(s), "three seconds shown, 0.6 fading");
	}

	@Test
	void theSettingsSaveUnderStableIdsWithTheDefaultsTheOverlayNeeds() {
		ModuleState s = new ModuleState(ModuleRegistry.get(Subtitles.ID));
		assertTrue(s.bool(Subtitles.ACTIVITY));
		assertTrue(s.bool(Subtitles.ARROWS));
		assertEquals(8, s.integer(Subtitles.MAX_ENTRIES));
		assertEquals(SoundActivity.SAME_PLACE, s.option(Subtitles.GROUPING));
		assertEquals(0xFFFFFF, s.rgb(Subtitles.TEXT_COLOR));
		assertEquals("nearbySoundActivity", Subtitles.ACTIVITY.id());
		assertSame(Subtitles.ACTIVITY, ModuleRegistry.get(Subtitles.ID).setting("nearbySoundActivity"));
		// The colours Subtitles always had are kept as they were.
		assertTrue(s.bool(Subtitles.COLORS));
		assertEquals(13, s.option(Subtitles.HOSTILE));
		assertSame(Subtitles.COLORS, ModuleRegistry.get(Subtitles.ID).setting("subtitleColorsEnabled"));
	}

	@Test
	void settingsThatChangeNothingSaySo() {
		ModuleState s = new ModuleState(ModuleRegistry.get(Subtitles.ID));
		assertNull(Subtitles.inactiveReason(s, Subtitles.SCALE));
		s.set(Subtitles.BACKGROUND, false);
		assertEquals("Turn on Subtitle background first", Subtitles.inactiveReason(s, Subtitles.BACKGROUND_OPACITY));
		s.set(Subtitles.ACTIVITY, false);
		assertEquals("Turn on Nearby Sound Activity first", Subtitles.inactiveReason(s, Subtitles.ARROWS));
		assertNull(Subtitles.inactiveReason(s, Subtitles.ACTIVITY));
		assertNull(Subtitles.inactiveReason(s, Subtitles.HOSTILE), "Colors is on");
		s.set(Subtitles.COLORS, false);
		assertEquals("Turn on Colors first", Subtitles.inactiveReason(s, Subtitles.HOSTILE));
		s.set(Subtitles.ACTIVITY, true);
		s.set(Subtitles.KIND_COLORS, true);
		assertNull(Subtitles.inactiveReason(s, Subtitles.HOSTILE), "the list colours its captions with them");
	}
}

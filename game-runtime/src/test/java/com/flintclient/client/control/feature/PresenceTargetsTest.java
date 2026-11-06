package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.store.PresenceClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PresenceTargetsTest {
	private final UUID account = UUID.randomUUID();
	private final UUID near = UUID.randomUUID();
	private final UUID far = UUID.randomUUID();
	private final UUID listed = UUID.randomUUID();
	private final UUID unlisted = UUID.randomUUID();
	/** A name-based UUID, as an offline-mode server gives its players (version 3). */
	private final UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Pebble".getBytes(StandardCharsets.UTF_8));

	@Test
	void playersInTheWorldComeFirstNearestFirstThenTheListThenTheUnlisted() {
		PresenceTargets targets = new PresenceTargets();
		targets.begin(false, account, account);
		// The world's players nearest first, then the player list (which repeats some), then everyone the server knows.
		for (UUID id : List.of(near, far, listed, near, unlisted, listed, far)) {
			targets.add(id);
		}
		assertEquals(List.of(near, far, listed, unlisted), targets.players());
	}

	@Test
	void youAreNeverAskedAboutUnderEitherUuid() {
		PresenceTargets targets = new PresenceTargets();
		UUID you = UUID.randomUUID();
		targets.begin(true, account, you);
		targets.add(you);
		targets.add(account);
		targets.add(near);
		targets.add(null);
		assertEquals(List.of(near), targets.players(), "an offline-mode server gives you another UUID than your account's");
	}

	@Test
	void onlyMojangAccountsAreAskedAboutUnlessTheServicesAreOnThisComputer() {
		PresenceTargets targets = new PresenceTargets();
		targets.begin(false, account, null);
		targets.add(offline);
		targets.add(near);
		assertEquals(List.of(near), targets.players(), "Flint's servers can't know an offline-mode player");
		targets.begin(true, account, null);
		targets.add(offline);
		targets.add(near);
		assertEquals(List.of(offline, near), targets.players(), "QA's local services are asked about anyone");
	}

	@Test
	void atMostTwoHundredAndTheWorldsPlayersKeepTheirPlaces() {
		PresenceTargets targets = new PresenceTargets();
		targets.begin(false, account, null);
		List<UUID> world = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			world.add(UUID.randomUUID());
		}
		world.forEach(targets::add);
		assertFalse(targets.full());
		List<UUID> list = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			list.add(UUID.randomUUID());
		}
		list.forEach(targets::add);
		assertTrue(targets.full());
		assertEquals(PresenceClient.MAX_LOOKUP, targets.players().size());
		assertEquals(world, targets.players().subList(0, 150), "everyone drawn around you is asked about");
		assertEquals(list.subList(0, 50), targets.players().subList(150, 200));
	}

	@Test
	void aNewGatheringStartsOver() {
		PresenceTargets targets = new PresenceTargets();
		targets.begin(false, account, null);
		targets.add(near);
		targets.begin(false, account, null);
		assertTrue(targets.players().isEmpty());
		targets.add(near);
		assertEquals(List.of(near), targets.players(), "asked about again after a new start");
	}
}

package com.flintclient.client.control.feature;

import static com.flintclient.client.control.feature.ArmorPieces.AXE;
import static com.flintclient.client.control.feature.ArmorPieces.BOW;
import static com.flintclient.client.control.feature.ArmorPieces.MACE;
import static com.flintclient.client.control.feature.ArmorPieces.NONE;
import static com.flintclient.client.control.feature.ArmorPieces.OFFHAND;
import static com.flintclient.client.control.feature.ArmorPieces.PICKAXE;
import static com.flintclient.client.control.feature.ArmorPieces.SHIELD;
import static com.flintclient.client.control.feature.ArmorPieces.SWORD;
import static com.flintclient.client.control.feature.ArmorPieces.TRIDENT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings.Armor;
import com.flintclient.client.control.model.ModuleState;
import org.junit.jupiter.api.Test;

class ArmorPiecesTest {
	/** The hotbar (slots 0 to 8) and the offhand, each holding items of the given kinds (bits). */
	private static int[] places() {
		return new int[ArmorPieces.PLACES];
	}

	private static int bit(int kind) {
		return 1 << kind;
	}

	private static int[] pick(int[] kinds, int selected, int wanted) {
		int[] out = new int[ArmorPieces.KINDS];
		ArmorPieces.pick(kinds, selected, wanted, out);
		return out;
	}

	@Test
	void aToolSlotShowsTheHeldOneElseTheFirstInTheHotbar() {
		int[] kinds = places();
		kinds[2] = bit(SWORD);
		kinds[5] = bit(SWORD);
		kinds[7] = bit(PICKAXE);
		assertEquals(2, pick(kinds, 0, bit(SWORD))[SWORD], "holding nothing: the first sword from the left");
		assertEquals(5, pick(kinds, 5, bit(SWORD))[SWORD], "the held sword comes first");
		assertEquals(2, pick(kinds, 7, bit(SWORD))[SWORD], "holding a pickaxe: the first sword");
		assertEquals(7, pick(kinds, 7, bit(SWORD) | bit(PICKAXE))[PICKAXE]);
		assertEquals(NONE, pick(kinds, 0, bit(AXE))[AXE], "no axe anywhere");
		// The offhand isn't searched for a tool: only for a shield.
		kinds[OFFHAND] = bit(AXE);
		assertEquals(NONE, pick(kinds, 0, bit(AXE))[AXE]);
	}

	@Test
	void aShieldIsTheOffhandsFirstThenTheHeldOneThenTheHotbars() {
		int[] kinds = places();
		kinds[1] = bit(SHIELD);
		kinds[4] = bit(SHIELD);
		assertEquals(1, pick(kinds, 0, bit(SHIELD))[SHIELD], "none in hand: the first in the hotbar");
		assertEquals(4, pick(kinds, 4, bit(SHIELD))[SHIELD], "the held one before the hotbar's");
		kinds[OFFHAND] = bit(SHIELD);
		assertEquals(OFFHAND, pick(kinds, 4, bit(SHIELD))[SHIELD], "the offhand's before any other");
	}

	@Test
	void anItemNeverShowsInTwoSlots() {
		// One item that counts as two kinds (a server's custom sword-axe) fills the first slot that wants it only.
		int[] kinds = places();
		kinds[3] = bit(SWORD) | bit(AXE);
		int[] out = pick(kinds, 3, bit(SWORD) | bit(AXE));
		assertEquals(3, out[SWORD]);
		assertEquals(NONE, out[AXE], "the held sword-axe is already the sword");
		// With a second axe, the axe slot shows that one.
		kinds[6] = bit(AXE);
		out = pick(kinds, 3, bit(SWORD) | bit(AXE));
		assertEquals(3, out[SWORD]);
		assertEquals(6, out[AXE]);
		// Every kind wanted, one item of each: each in its own place, none twice.
		kinds = places();
		int[] order = {SWORD, PICKAXE, AXE, MACE, TRIDENT, BOW};
		for (int i = 0; i < order.length; i++) {
			kinds[i] = bit(order[i]);
		}
		kinds[OFFHAND] = bit(SHIELD);
		out = pick(kinds, 0, ArmorPieces.ALL);
		assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, OFFHAND}, out);
	}

	@Test
	void onlyTheWantedKindsArePicked() {
		int[] kinds = places();
		kinds[0] = bit(SWORD);
		kinds[1] = bit(BOW);
		int[] out = pick(kinds, 0, bit(BOW));
		assertEquals(NONE, out[SWORD], "the sword slot is off");
		assertEquals(1, out[BOW]);
		assertArrayEquals(new int[] {NONE, NONE, NONE, NONE, NONE, NONE, NONE}, pick(kinds, 0, 0));
		// A held slot out of range (no hotbar selection yet) falls back to the hotbar search.
		assertEquals(0, pick(kinds, -1, bit(SWORD))[SWORD]);
		assertEquals(0, pick(kinds, 12, bit(SWORD))[SWORD]);
	}

	@Test
	void theSwitchesAreReadInTheStripsOrderLessWhatTheGameLacks() {
		ModuleState armor = new Layout("x", "X").module(ModuleId.ARMOR);
		assertEquals(0, ArmorPieces.wanted(armor, ArmorPieces.ALL), "every item slot is off by default: layouts look as before");
		for (int kind = 0; kind < ArmorPieces.KINDS; kind++) {
			assertFalse(Armor.ITEMS.get(kind).defaultValue(), Armor.ITEMS.get(kind).id());
		}
		armor.set(Armor.SWORD, true);
		armor.set(Armor.SHIELD, true);
		armor.set(Armor.MACE, true);
		assertEquals(bit(SWORD) | bit(SHIELD) | bit(MACE), ArmorPieces.wanted(armor, ArmorPieces.ALL));
		assertEquals(bit(SWORD), ArmorPieces.wanted(armor, ArmorPieces.ALL_1_8_9), "1.8.9 has no shields or maces");
		assertSame(Armor.SWORD, Armor.ITEMS.get(SWORD));
		assertSame(Armor.PICKAXE, Armor.ITEMS.get(PICKAXE));
		assertSame(Armor.AXE, Armor.ITEMS.get(AXE));
		assertSame(Armor.MACE, Armor.ITEMS.get(MACE));
		assertSame(Armor.TRIDENT, Armor.ITEMS.get(TRIDENT));
		assertSame(Armor.BOW, Armor.ITEMS.get(BOW));
		assertSame(Armor.SHIELD, Armor.ITEMS.get(SHIELD));
		assertEquals(ArmorPieces.KINDS, Armor.ITEMS.size());
	}

	@Test
	void durabilityAsTextAndTheWarningRule() {
		assertEquals("250", ArmorPieces.durabilityText(ArmorPieces.NUMERIC, 0, 250));
		assertEquals("1", ArmorPieces.durabilityText(ArmorPieces.NUMERIC, 249, 250));
		assertEquals("", ArmorPieces.durabilityText(ArmorPieces.PERCENTAGE, 0, 250), "undamaged: nothing");
		assertEquals("99%", ArmorPieces.durabilityText(ArmorPieces.PERCENTAGE, 1, 250));
		assertEquals("50%", ArmorPieces.durabilityText(ArmorPieces.PERCENTAGE, 125, 250));
		assertEquals("0%", ArmorPieces.durabilityText(ArmorPieces.PERCENTAGE, 249, 250));
		// uku's defaults: 10 % or 20 uses left.
		assertFalse(ArmorPieces.endangered(0, 1561, 0.10, 20));
		assertTrue(ArmorPieces.endangered(1541, 1561, 0.10, 20), "20 uses left");
		assertFalse(ArmorPieces.endangered(200, 250, 0.10, 0), "a fifth left");
		assertTrue(ArmorPieces.endangered(225, 250, 0.10, 0), "10 % left");
		assertFalse(ArmorPieces.endangered(0, 0, 0.10, 20), "nothing to wear out");
	}

	@Test
	void theRoomKeptForTextIsTheWidestTheItemCanShow() {
		assertEquals("000", ArmorPieces.widest(ArmorPieces.NUMERIC, 250, false));
		assertEquals("0000", ArmorPieces.widest(ArmorPieces.NUMERIC, 2031, false));
		assertEquals("0", ArmorPieces.widest(ArmorPieces.NUMERIC, 0, false));
		assertEquals("00000", ArmorPieces.widest(ArmorPieces.NUMERIC, 2_000_000, false), "capped");
		assertEquals("00%", ArmorPieces.widest(ArmorPieces.PERCENTAGE, 2031, false), "100 % never shows (undamaged shows nothing)");
		assertEquals("§l000", ArmorPieces.widest(ArmorPieces.NUMERIC, 250, true), "bold with Minecraft's formatting code");
		assertEquals("§l00%", ArmorPieces.widest(ArmorPieces.PERCENTAGE, 250, true));
	}

	@Test
	void textsAreMadeAgainOnlyWhenTheyChange() {
		ArmorPieces.Texts texts = new ArmorPieces.Texts();
		String first = texts.get(0, ArmorPieces.NUMERIC, 10, 250, false);
		assertEquals("240", first);
		assertSame(first, texts.get(0, ArmorPieces.NUMERIC, 10, 250, false), "the same text: no new string");
		assertEquals("239", texts.get(0, ArmorPieces.NUMERIC, 11, 250, false));
		assertEquals("§l239", texts.get(0, ArmorPieces.NUMERIC, 11, 250, true));
		assertEquals("95%", texts.get(0, ArmorPieces.PERCENTAGE, 11, 250, false));
		assertEquals("", texts.get(0, ArmorPieces.PERCENTAGE, 0, 250, true), "nothing to show is never bold-only");
		// Each slot (and the hand texts) keeps its own.
		assertEquals("1", texts.get(ArmorPieces.MAIN_HAND_TEXT, ArmorPieces.NUMERIC, 249, 250, false));
		assertEquals("2", texts.get(ArmorPieces.OFFHAND_TEXT, ArmorPieces.NUMERIC, 248, 250, false));
		assertEquals("95%", texts.get(0, ArmorPieces.PERCENTAGE, 11, 250, false));
	}
}

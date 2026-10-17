package com.flintclient.client.control.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class MappingListTest {
	/** Stands in for a KeyMapping: compared by identity, like the real thing. */
	private record Key(String name) {
		@Override
		public boolean equals(Object other) {
			return this == other;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this);
		}
	}

	private final Key forward = new Key("forward");
	private final Key jump = new Key("jump");
	private final Key open = new Key("flint.open");
	private final Key editor = new Key("flint.editor");
	private final Key[] flint = {open, editor};

	@Test
	void appendsTheMissingOnesAtTheEndInOrder() {
		Key[] vanilla = {forward, jump};
		Key[] merged = MappingList.appendMissing(vanilla, flint);
		assertArrayEquals(new Key[] {forward, jump, open, editor}, merged);
		assertArrayEquals(new Key[] {forward, jump}, vanilla, "the original array is left alone");
		assertEquals(Key[].class, merged.getClass(), "keeps the array type Minecraft expects");
	}

	@Test
	void runningAgainChangesNothing() {
		Key[] once = MappingList.appendMissing(new Key[] {forward}, flint);
		assertSame(once, MappingList.appendMissing(once, flint), "already there: the same array comes back");
	}

	@Test
	void keepsOtherModsEntriesWhicheverRunsFirst() {
		Key other = new Key("othermod.zoom");
		// Fabric ran first: its entry is already on the end.
		assertArrayEquals(new Key[] {forward, other, open, editor}, MappingList.appendMissing(new Key[] {forward, other}, flint));
		// Flint ran first, then Fabric appended its own after ours: nothing to add, nothing lost.
		Key[] afterFabric = {forward, open, editor, other};
		assertSame(afterFabric, MappingList.appendMissing(afterFabric, flint));
	}

	@Test
	void addsOnlyWhatIsMissing() {
		Key[] partial = {forward, editor};
		assertArrayEquals(new Key[] {forward, editor, open}, MappingList.appendMissing(partial, flint));
	}

	@Test
	void comparesByIdentityNotByName() {
		Key lookalike = new Key("flint.open");
		assertArrayEquals(new Key[] {lookalike, open, editor}, MappingList.appendMissing(new Key[] {lookalike}, flint));
	}

	@Test
	void handlesEmptyLists() {
		assertArrayEquals(flint, MappingList.appendMissing(new Key[0], flint));
		Key[] vanilla = {forward};
		assertSame(vanilla, MappingList.appendMissing(vanilla, new Key[0]));
	}
}

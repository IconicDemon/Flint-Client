package com.flintclient.client.control.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.sound.ToneSynth;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The link between a sound instance and its samples: the engine asks for the file Minecraft derives from the
 * sound's id, and only those two files get the generated samples. Nothing here touches OpenAL.
 */
class UiSoundsTest {
	private static final Identifier OPEN_FILE = Identifier.fromNamespaceAndPath("flintclient", "sounds/ui/open.ogg");
	private static final Identifier CLOSE_FILE = Identifier.fromNamespaceAndPath("flintclient", "sounds/ui/close.ogg");

	@Test
	void theEngineAsksForTheFilesTheMixinAnswers() {
		assertEquals(OPEN_FILE, Sound.SOUND_LISTER.idToFile(UiSounds.OPEN));
		assertEquals(CLOSE_FILE, Sound.SOUND_LISTER.idToFile(UiSounds.CLOSE));
		assertEquals(OPEN_FILE, UiSounds.OPEN_FILE);
		assertEquals(CLOSE_FILE, UiSounds.CLOSE_FILE);
	}

	@Test
	void eachFileGetsItsSoundsSamplesInNativeOrder() {
		assertSamples(ToneSynth.open(), UiSounds.samples(OPEN_FILE));
		assertSamples(ToneSynth.close(), UiSounds.samples(CLOSE_FILE));
	}

	@Test
	void everyRequestGetsItsOwnBuffer() {
		// OpenAL takes a buffer's samples once and the buffer then drops them, so one can't be handed out twice.
		ByteBuffer first = UiSounds.samples(OPEN_FILE);
		ByteBuffer second = UiSounds.samples(OPEN_FILE);
		assertNotNull(first);
		assertNotSame(first, second);
		assertTrue(first.isDirect(), "OpenAL reads native memory");
	}

	@Test
	void aReadyBufferForFlintsFiles() {
		assertTrue(UiSounds.buffer(OPEN_FILE).isDone());
		assertNotNull(UiSounds.buffer(CLOSE_FILE).join());
	}

	@Test
	void otherFilesAreLeftToMinecraft() {
		assertNull(UiSounds.buffer(Identifier.fromNamespaceAndPath("flintclient", "sounds/ui/other.ogg")));
		assertNull(UiSounds.buffer(Identifier.fromNamespaceAndPath("minecraft", "sounds/ui/open.ogg")), "same path, other namespace");
		assertNull(UiSounds.buffer(UiSounds.OPEN), "the sound's id, not its file");
		assertNull(UiSounds.samples(Identifier.fromNamespaceAndPath("minecraft", "sounds/random/click.ogg")));
	}

	private static void assertSamples(short[] expected, ByteBuffer actual) {
		assertNotNull(actual);
		assertEquals(0, actual.position());
		assertEquals(expected.length * 2, actual.remaining(), "two bytes a sample, mono");
		assertEquals(ByteOrder.nativeOrder(), actual.order());
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual.getShort(i * 2), "sample " + i);
		}
	}
}

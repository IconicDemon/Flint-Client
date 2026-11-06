package com.flintclient.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The credits page names every licence file Flint ships, and only those. */
class FlintCreditsTest {
	@Test
	void everyShippedLicenceIsCreditedExactlyOnce() throws Exception {
		java.net.URL folder = FlintCreditsTest.class.getClassLoader().getResource("META-INF/licenses");
		assertTrue(folder != null, "META-INF/licenses on the classpath");
		Set<String> shipped = new TreeSet<>();
		try (Stream<Path> files = Files.list(Path.of(folder.toURI()))) {
			files.forEach(f -> shipped.add(f.getFileName().toString()));
		}
		Set<String> credited = new TreeSet<>();
		for (FlintCredits.Section section : FlintCredits.SECTIONS) {
			for (FlintCredits.Entry entry : section.entries()) {
				if (entry.licenseFile() != null) {
					assertTrue(credited.add(entry.licenseFile()), "credited twice: " + entry.licenseFile());
				}
			}
		}
		assertEquals(shipped, credited);
	}

	@Test
	void theNoticeIsMojangsWording() {
		assertEquals("Flint Client is not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
			FlintCredits.NOT_OFFICIAL);
	}
}

package com.flintclient.qa;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Cape switching frame by frame, for the dev tour: {@code call com.flintclient.qa.QaCapeSwitch.begin}, then the switches (the
 * mock Store's {@code --switch}), then {@code call com.flintclient.qa.QaCapeSwitch.end NAME}. Every rendered frame (from the head
 * of {@code runTick}) it reads the cape your skin carries as the cape layer gets it (Flint's Store cape through
 * {@code PlayerInfoMixin}, else Minecraft's, else none), and {@code end} writes {@code cape-switch-NAME.csv} (one row per change:
 * when, which texture) and answers how many times the cape changed and how many frames showed no Flint cape between two Flint
 * capes (a flash of Minecraft's cape, or of none). Dev only.
 */
public final class QaCapeSwitch {
	private static volatile boolean recording;
	private static final List<String> CHANGES = new ArrayList<>();
	private static String last = "";
	private static long start;
	private static long frames;
	/** Frames without a Flint cape since the last Flint one (counted as a gap once a Flint cape follows). */
	private static long pendingGap;
	private static long gapFrames;
	private static int gaps;
	private static boolean seenFlint;

	private QaCapeSwitch() {
	}

	public static String begin() {
		CHANGES.clear();
		last = "";
		frames = 0;
		pendingGap = 0;
		gapFrames = 0;
		gaps = 0;
		seenFlint = false;
		start = System.nanoTime();
		recording = true;
		return "recording";
	}

	/** From the head of every frame (QaFrames.frame). */
	public static void frame() {
		if (!recording) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		frames++;
		String id = cape(player.getSkin());
		boolean flint = id.contains("store-capes/");
		if (flint) {
			if (seenFlint && pendingGap > 0) {
				gaps++;
				gapFrames += pendingGap;
			}
			pendingGap = 0;
			seenFlint = true;
		} else if (seenFlint) {
			pendingGap++;
		}
		if (!id.equals(last)) {
			CHANGES.add(String.format(java.util.Locale.ROOT, "%.3f,%s", (System.nanoTime() - start) / 1e9, id));
			last = id;
		}
	}

	/** The skin's cape as text (26.x: `cape()`, a texture asset naming its path; older lines: `capeTexture()`), "none" for none. */
	private static String cape(Object skin) {
		for (String name : new String[] {"cape", "capeTexture"}) {
			try {
				Object cape = skin.getClass().getMethod(name).invoke(skin);
				return cape == null ? "none" : cape.toString();
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Not this line's name.
			}
		}
		return "unknown";
	}

	public static String end(String name) throws Exception {
		recording = false;
		Path out = Path.of(System.getProperty("flintqa.out", "."), "cape-switch-" + name + ".csv");
		Files.writeString(out, "seconds,cape\n" + String.join("\n", CHANGES) + "\n", StandardCharsets.UTF_8);
		long flintChanges = CHANGES.stream().filter(c -> c.contains("store-capes/")).count();
		return "frames=" + frames + " changes=" + CHANGES.size() + " flintCapes=" + flintChanges + " gapsBetweenFlintCapes=" + gaps
			+ " gapFrames=" + gapFrames + " csv=" + out;
	}
}

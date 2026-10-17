package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.hud.Snapper;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.control.store.LayoutStore;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the HUD and the Control Center call every frame must not allocate: reading settings, the layout's
 * revision, availability, placement clamping, snapping and the open animation.
 */
class PerFrameAllocationTest {
	@TempDir
	Path config;

	@Test
	void perFrameCallsAllocateNothing() {
		java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		assumeTrue(threads instanceof com.sun.management.ThreadMXBean, "allocation counting needs HotSpot's thread bean");
		com.sun.management.ThreadMXBean hotspot = (com.sun.management.ThreadMXBean) threads;
		assumeTrue(hotspot.isThreadAllocatedMemorySupported() && hotspot.isThreadAllocatedMemoryEnabled());

		ControlSettings settings = new ControlSettings(config);
		LayoutStore store = new LayoutStore(config, settings);
		store.load();
		Preset.PVP.applyTo(store.active(), HudThemeId.FLINT);
		Frame frame = new Frame(store, settings);
		long sink = frame.run(2_000);
		long before = hotspot.getCurrentThreadAllocatedBytes();
		sink += frame.run(50_000);
		long allocated = hotspot.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink != 0);
		// 50,000 frames: anything per frame would be megabytes. Allow a little for the measurement itself.
		assertTrue(allocated < 4096, "allocated " + allocated + " bytes");
	}

	/** One HUD frame's worth of calls into the pure packages. */
	private static final class Frame {
		private final LayoutStore store;
		private final ControlSettings settings;
		private final ModuleDescriptor ping = ModuleRegistry.get(ModuleId.PING);
		private final HudPlacement corner = HudPlacement.of(Anchor.TOP_RIGHT, 4, 4);
		private final Snapper snapper = new Snapper();
		private final OpenState open = new OpenState();

		Frame(LayoutStore store, ControlSettings settings) {
			this.store = store;
			this.settings = settings;
			snapper.begin(640, 360);
			for (int i = 0; i < 8; i++) {
				snapper.addModule(i * 20, i * 10, 40, 11);
			}
			snapper.addArea(229, 338, 182, 22);
		}

		long run(int frames) {
			long sink = 0;
			for (int i = 0; i < frames; i++) {
				Layout layout = store.active();
				sink += store.switchCount() + layout.revision() + layout.enabledCount();
				// By index, as renderers walk it: an iterator would allocate.
				for (int m = 0; m < layout.modules().size(); m++) {
					ModuleState module = layout.modules().get(m);
					sink += module.enabled() ? module.revision() : 1;
				}
				ModuleState keystrokes = layout.modules().get(3);
				sink += keystrokes.integer(ModuleSettings.Keystrokes.KEY_SIZE);
				sink += keystrokes.choice(ModuleSettings.Keystrokes.LAYOUT).mouse() ? 1 : 0;
				sink += keystrokes.integer(ModuleSettings.Keystrokes.SPACING) + keystrokes.integer(ModuleSettings.Keystrokes.CORNERS);
				sink += layout.modules().get(0).bool(ModuleSettings.Fps.REVERSED) ? 1 : 0;
				sink += layout.modules().get(0).choice(ModuleSettings.Fps.DISPLAY_MODE).ordinal();
				sink += ModuleSettings.inactiveReason(layout.modules().get(0), ModuleSettings.Fps.WIDTH) == null ? 1 : 0;
				sink += layout.modules().get(0).choice(ModuleSettings.Fps.WINDOW).refreshes();
				sink += ModuleSettings.inactiveReason(keystrokes, ModuleSettings.Keystrokes.PRESSED_COLOR) == null ? 1 : 0;
				sink += ModuleSettings.labelSetting(keystrokes.descriptor()) == null ? 1 : 0;
				sink += layout.modules().get(4).choice(ModuleSettings.Coordinates.DECIMALS).ordinal();
				sink += settings.showHudWithDebugScreen() ? 1 : 0;
				sink += (long) (settings.soundVolume() * 100);
				sink += ping.availability(i % 2 == 0, true, i % 3 == 0, true).ordinal();
				sink += (long) corner.clampedLeft(640, 92) + (long) corner.clampedTop(360, 40);
				snapper.snap(i % 600, i % 300, 40, 11, false);
				sink += (long) snapper.left() + (long) snapper.top();
				if (i % 40 == 0) {
					open.open(false);
				} else if (i % 40 == 20) {
					open.close(false);
				}
				sink += open.advance(0.004F) ? 1 : 0;
				sink += (long) (open.alpha() * 10 + open.offsetY());
			}
			return sink;
		}
	}
}

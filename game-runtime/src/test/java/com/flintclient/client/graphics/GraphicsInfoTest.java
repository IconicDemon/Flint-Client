package com.flintclient.client.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flintclient.client.perf.Compat;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphicsInfoTest {
	/** sodium, iris, lithium, ferritecore, immediatelyfast, entityculling, dynamic_fps, moreculling, modernfix, c2me. */
	private static Compat compat(boolean sodium, boolean iris, boolean lithium, boolean immediatelyfast, boolean dynamicFps) {
		return new Compat(sodium, iris, lithium, false, immediatelyfast, false, dynamicFps, false, false, false);
	}

	@Test
	void listsOnlyTheRenderingOptimizers() {
		GraphicsInfo info = GraphicsInfo.of(new GraphicsPlatform.Device("OpenGL", "Apple M2", "Apple", "4.1 Metal - 91.7", 16384),
			compat(true, false, true, true, false));
		assertEquals(List.of("sodium", "immediatelyfast"), info.optimizers(), "lithium isn't a rendering mod");
		assertEquals("backend OpenGL (4.1 Metal - 91.7), device Apple M2 (Apple), max texture 16384, optimizers sodium, immediatelyfast",
			info.describe());
		assertEquals(List.of("sodium", "immediatelyfast", "iris", "dynamic_fps"),
			GraphicsInfo.of(new GraphicsPlatform.Device("Vulkan", "d", "v", "", 0), compat(true, true, true, true, true)).optimizers());
	}

	@Test
	void leavesOutWhatThePlatformDoesNotKnow() {
		GraphicsInfo info = GraphicsInfo.of(new GraphicsPlatform.Device("Vulkan", "Apple M2", "", " ", 0), compat(false, false, false, false, false));
		assertEquals("backend Vulkan, device Apple M2, optimizers none", info.describe());
	}
}

package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flintclient.client.control.editor.EditorMath;
import org.junit.jupiter.api.Test;

/** The HUD editor's keyboard selection ({@link EditorMath#nextShown}) over a scene the renderer filled in. */
class SceneCycleTest {
	private static HudScene scene(int... shown) {
		HudScene scene = new HudScene();
		for (int i = 0; i < scene.size(); i++) {
			scene.off(i);
		}
		for (int i : shown) {
			scene.set(i, i % 2 == 0 ? HudScene.SHOWN : HudScene.OUTLINE, 0, 0, 10, 10, null);
		}
		return scene;
	}

	@Test
	void enterVisitsEveryShownModuleAndWraps() {
		HudScene scene = scene(1, 4, 6);
		assertEquals(1, EditorMath.nextShown(scene, -1, 1), "from nothing: the first");
		assertEquals(4, EditorMath.nextShown(scene, 1, 1));
		assertEquals(6, EditorMath.nextShown(scene, 4, 1), "an outlined module (nothing to show here) counts too");
		assertEquals(1, EditorMath.nextShown(scene, 6, 1), "wraps round");
	}

	@Test
	void shiftGoesBack() {
		HudScene scene = scene(0, 3, scene().size() - 1);
		int last = scene.size() - 1;
		assertEquals(last, EditorMath.nextShown(scene, -1, -1), "from nothing: the last");
		assertEquals(3, EditorMath.nextShown(scene, last, -1));
		assertEquals(0, EditorMath.nextShown(scene, 3, -1));
		assertEquals(last, EditorMath.nextShown(scene, 0, -1), "wraps round");
	}

	@Test
	void aloneOrNoneAtAll() {
		assertEquals(5, EditorMath.nextShown(scene(5), 5, 1), "the only one stays selected");
		assertEquals(-1, EditorMath.nextShown(scene(), -1, 1));
		assertEquals(-1, EditorMath.nextShown(scene(), 2, -1));
	}
}

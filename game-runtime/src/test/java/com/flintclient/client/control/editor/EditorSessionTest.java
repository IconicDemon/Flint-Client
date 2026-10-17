package com.flintclient.client.control.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Preset;
import org.junit.jupiter.api.Test;

class EditorSessionTest {
	private static Layout layout() {
		return Preset.PVP.create("pvp", "PvP", HudThemeId.FLINT);
	}

	@Test
	void aDragIsOneUndoStepHoweverManyMovesItMakes() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState fps = layout.module(ModuleId.FPS);
		HudPlacement start = fps.placement();
		session.begin();
		for (int i = 1; i <= 30; i++) {
			fps.setPlacement(start.moved(i, i));
		}
		session.end();
		assertTrue(session.canUndo());
		assertTrue(session.undo());
		assertEquals(start, fps.placement());
		assertFalse(session.canUndo(), "the whole drag was one step");
		assertTrue(session.redo());
		assertEquals(start.moved(30, 30), fps.placement());
	}

	@Test
	void aGestureThatChangesNothingAddsNoStep() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState fps = layout.module(ModuleId.FPS);
		HudPlacement start = fps.placement();
		session.begin();
		fps.setPlacement(start.moved(5, 0));
		fps.setPlacement(start);
		session.end();
		assertFalse(session.canUndo(), "a click that moved nothing, or a slider let go where it started");
		session.end();
		assertFalse(session.canUndo(), "ending twice is harmless");
	}

	@Test
	void unsavedChangesAreJudgedAgainstTheEditorsOwnCopy() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		assertFalse(session.dirty());
		ModuleState armor = layout.module(ModuleId.ARMOR);
		session.edit(() -> armor.setAppearance(Appearance.INHERIT.withTheme(HudThemeId.CLASSIC)));
		assertTrue(session.dirty());
		session.undo();
		assertFalse(session.dirty(), "undone back to how it opened: nothing to discard");
		session.redo();
		assertTrue(session.dirty());
	}

	@Test
	void afterAcceptDiscardGoesBackOnlyToWhatWasAccepted() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState fps = layout.module(ModuleId.FPS);
		HudPlacement start = fps.placement();
		session.edit(() -> fps.setPlacement(start.moved(5, 0)));
		session.accept();
		assertFalse(session.dirty(), "what was saved is the new starting point");
		assertFalse(session.canUndo(), "the history starts again");
		session.edit(() -> fps.setPlacement(start.moved(9, 0)));
		session.discard();
		assertEquals(start.moved(5, 0), fps.placement());
	}

	@Test
	void discardPutsEverythingBackInPlace() {
		Layout layout = layout();
		Layout opened = layout.copy();
		ModuleState effects = layout.module(ModuleId.EFFECTS);
		EditorSession session = new EditorSession(layout);
		session.edit(() -> effects.setEnabled(false));
		session.begin();
		layout.module(ModuleId.CPS).setPlacement(HudPlacement.of(Anchor.CENTER, 10, -20));
		session.discard();
		assertEquals(opened, layout);
		assertFalse(session.editing());
		assertFalse(session.canUndo());
		assertTrue(layout.module(ModuleId.EFFECTS) == effects, "the same module objects, restored in place");
	}

	@Test
	void aNewChangeAfterUndoDropsTheRedoBranch() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState fps = layout.module(ModuleId.FPS);
		session.edit(() -> fps.setPlacement(fps.placement().moved(10, 0)));
		session.undo();
		assertTrue(session.canRedo());
		session.edit(() -> fps.setPlacement(fps.placement().withScale(2.0F)));
		assertFalse(session.canRedo());
	}

	@Test
	void resetPositionsIsOneStep() {
		Layout layout = layout();
		layout.module(ModuleId.FPS).setPlacement(HudPlacement.of(Anchor.BOTTOM_RIGHT, 12, 30).withScale(1.5F));
		layout.module(ModuleId.ARMOR).setPlacement(HudPlacement.of(Anchor.LEFT, 4, 0));
		EditorSession session = new EditorSession(layout);
		Layout before = layout.copy();
		session.resetPositions();
		assertNotEquals(before, layout);
		for (ModuleState module : layout.modules()) {
			assertEquals(module.descriptor().defaultPlacement(), module.placement());
		}
		session.undo();
		assertEquals(before, layout);
	}

	@Test
	void aBurstOfKeyPressesIsOneStep() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState fps = layout.module(ModuleId.FPS);
		HudPlacement start = fps.placement();
		long t = 1_000_000_000L;
		for (int i = 1; i <= 40; i++) {
			// A held arrow key repeats about every 50 ms.
			session.continueRun(t);
			fps.setPlacement(start.moved(i, 0));
			t += 50_000_000L;
			assertFalse(session.endIdleRun(t), "still coming");
		}
		assertTrue(session.inRun());
		assertTrue(session.endIdleRun(t + EditorSession.RUN_IDLE_NANOS), "ends once the presses stop");
		assertFalse(session.editing());
		assertTrue(session.undo());
		assertEquals(start, fps.placement(), "forty presses undone at once");
		assertFalse(session.canUndo());
	}

	@Test
	void anythingElseEndsARunAsItsOwnStep() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState fps = layout.module(ModuleId.FPS);
		HudPlacement start = fps.placement();
		session.continueRun(0);
		fps.setPlacement(start.moved(3, 0));
		// A drag (or an inspector change) starts while the run is still open.
		session.begin();
		assertFalse(session.inRun());
		fps.setPlacement(start.moved(3, 20));
		session.end();
		assertTrue(session.undo());
		assertEquals(start.moved(3, 0), fps.placement(), "the drag was its own step");
		assertTrue(session.undo());
		assertEquals(start, fps.placement(), "then the run");
		session.continueRun(0);
		fps.setPlacement(start.moved(0, 1));
		session.continueRun(10_000_000_000L);
		assertTrue(session.inRun(), "a press after a pause continues the run until the idle check ends it");
		assertTrue(session.undo(), "undo ends the run first");
		assertEquals(start, fps.placement());
		assertFalse(session.inRun());
	}

	@Test
	void undoInTheMiddleOfAGestureEndsItFirst() {
		Layout layout = layout();
		EditorSession session = new EditorSession(layout);
		ModuleState cps = layout.module(ModuleId.CPS);
		HudPlacement start = cps.placement();
		session.begin();
		cps.setPlacement(start.moved(0, 12));
		assertTrue(session.undo(), "the open gesture became a step and was undone");
		assertEquals(start, cps.placement());
		assertFalse(session.editing());
	}
}

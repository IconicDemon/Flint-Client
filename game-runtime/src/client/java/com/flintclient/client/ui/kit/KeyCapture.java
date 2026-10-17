package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A key binding control: the bound key as a key chip ({@link KeyChip}). Activating it waits for a key (the chip
 * says "Press a key…" in ember): the next key or mouse button is bound, Backspace or Delete unbinds, Escape
 * cancels. The host does the binding ({@link Binder}) and reports keys shared with other actions
 * ({@link Conflicts}), shown beside the chip as "Also used by: …" in the warning colour; the binding still applies
 * (the user decides). Right-aligned in its bounds.
 */
public class KeyCapture extends Pressable {
	/** Applies a new binding ({@link InputConstants#UNKNOWN} to unbind). */
	@FunctionalInterface
	public interface Binder {
		void bind(InputConstants.Key key);
	}

	/** The names of other actions bound to {@code key} (empty when none). */
	@FunctionalInterface
	public interface Conflicts {
		List<String> namesFor(InputConstants.Key key);
	}

	private static final float MIN_CHIP = 34;
	private static final float NOTE_MAX = 120;
	private static final float NOTE_GAP = 6;
	private static final float ICON = 7;

	private final Binder binder;
	private final @Nullable Conflicts conflicts;
	private final TextLine keyName = new TextLine(Type.META, "");
	private final TextLine waiting = new TextLine(Type.META, "Press a key…");
	private final TextLine note = new TextLine(Type.META, "");
	private InputConstants.Key key;
	private boolean listening;
	private boolean conflicted;

	public KeyCapture(InputConstants.Key key, Binder binder, @Nullable Conflicts conflicts) {
		super(null);
		this.binder = binder;
		this.conflicts = conflicts;
		this.h = KeyChip.HEIGHT;
		this.key = key;
		setKey(key);
	}

	/** Shows {@code key} (after the host changed or reset the binding) and refreshes its conflicts. */
	public void setKey(InputConstants.Key key) {
		this.key = key;
		keyName.set(key.getDisplayName().getString());
		List<String> names = conflicts == null || key.equals(InputConstants.UNKNOWN) ? List.of() : conflicts.namesFor(key);
		conflicted = !names.isEmpty();
		if (conflicted) {
			String text = "Also used by: " + String.join(", ", names);
			note.set(text);
			tooltip(text);
		} else {
			note.set("");
			tooltip(null);
		}
		invalidate();
	}

	public InputConstants.Key key() {
		return key;
	}

	public boolean listening() {
		return listening;
	}

	/** Stops waiting for a key without changing anything. */
	public void cancel() {
		listening = false;
	}

	@Override
	public boolean capturesKeys() {
		return listening;
	}

	@Override
	public void activate() {
		if (!enabled()) {
			return;
		}
		press = 1.0F;
		listening = true;
		if (screen != null) {
			screen.playClick();
			screen.announce(this);
		}
	}

	private void bind(InputConstants.Key next) {
		listening = false;
		binder.bind(next);
		setKey(next);
		if (screen != null && focused()) {
			screen.announce(this);
		}
	}

	@Override
	public boolean keyDown(KeyEvent event) {
		if (!listening) {
			return super.keyDown(event);
		}
		int code = event.key();
		if (event.isEscape()) {
			listening = false;
		} else if (code == InputConstants.KEY_BACKSPACE || code == InputConstants.KEY_DELETE) {
			bind(InputConstants.UNKNOWN);
		} else {
			bind(InputConstants.getKey(event));
		}
		return true;
	}

	@Override
	public boolean captureMouseButton(int button) {
		if (!listening) {
			return false;
		}
		bind(InputConstants.Type.MOUSE.getOrCreate(button));
		return true;
	}

	@Override
	protected void focusChanged(boolean focused) {
		if (!focused) {
			listening = false;
		}
	}

	@Override
	public String narration() {
		String name = accessibleName().isEmpty() ? "Key" : accessibleName();
		String state = listening ? "press a key" : keyName.text();
		return withReason(name + ", " + state + (conflicted ? ", " + note.text() : ""));
	}

	private float noteWidth() {
		if (!conflicted) {
			return 0;
		}
		Canvas c = measure();
		return Math.min(NOTE_MAX, ICON + 3 + (float) c.width(note) / c.scale()) + NOTE_GAP;
	}

	/** The chip's width: the key's name (or "Press a key…" while waiting), at least {@link #MIN_CHIP}. */
	private float chipWidth() {
		Canvas c = measure();
		return Math.max(MIN_CHIP, KeyChip.width(c, listening ? waiting : keyName));
	}

	@Override
	public float prefWidth() {
		return Math.max(chipWidth(), KeyChip.width(measure(), waiting)) + noteWidth();
	}

	@Override
	public float prefHeight(float width) {
		return KeyChip.HEIGHT;
	}

	/** The pointer only presses the chip itself, not the note beside it. */
	@Override
	public boolean mouseDown(float mx, float my, int button) {
		return button == Kit.LEFT_BUTTON && mx >= x + w - chipWidth();
	}

	@Override
	protected void paint(Canvas c) {
		float chipW = chipWidth();
		float bx = x + w - chipW;
		float by = y + (h - KeyChip.HEIGHT) / 2;
		TextLine shown = listening ? waiting : keyName;
		int ink = listening ? Tokens2.EMBER_TEXT : conflicted ? Tokens2.WARN : Palette.lerp(Tokens2.TEXT_DIM, Tokens2.TEXT, hover);
		int outline = listening ? Palette.withAlpha(Tokens2.EMBER, 0.7F)
			: conflicted ? Palette.withAlpha(Tokens2.WARN, 0.5F) : Palette.lerp(Tokens2.hairlineStrong(), 0x30FFFFFF, hover);
		if (!enabled()) {
			ink = Palette.withAlpha(Tokens2.muted(), 0.5F);
		}
		int fill = Palette.withAlpha(Tokens2.PRESS, Math.max(hover, press));
		KeyChip.paintChip(c, shown, bx, by, chipW, ink, outline, fill);
		if (conflicted && !listening) {
			int center = c.px(by + KeyChip.HEIGHT / 2);
			int noteRight = c.px(bx - NOTE_GAP);
			int noteLeft = px0(c);
			int icon = c.px(ICON);
			int textMax = Math.max(0, noteRight - noteLeft - icon - c.px(3));
			int textW = c.width(note, textMax);
			int iconX = noteRight - textW - c.px(3) - icon;
			c.icon(Icon.WARNING, iconX, center - icon / 2, icon, Tokens2.WARN);
			c.textRight(note, noteRight, c.baselineFor(note, center), Tokens2.WARN, textMax);
		}
		Kit.focusRing(c, bx, by, chipW, KeyChip.HEIGHT, KeyChip.HEIGHT / 2, focus);
	}
}

package com.flintclient.client.ui.kit;

/**
 * The editable text of a field: the text, the caret and the selection (between the caret and an
 * anchor), with the editing operations a text field offers. Kept free of Minecraft so the rules are
 * tested: a maximum length in characters, word jumps as Minecraft's own edit box does them (skip spaces,
 * then a word), and surrogate pairs never split.
 */
public final class TextEditState {
	private final StringBuilder text = new StringBuilder();
	private int caret;
	private int anchor;
	private int maxLength;
	/** Bumped by every change of text, caret or selection, so views rebuild only when it moves. */
	private int version;

	public TextEditState(int maxLength) {
		this.maxLength = Math.max(0, maxLength);
	}

	public String text() {
		return text.toString();
	}

	public CharSequence chars() {
		return text;
	}

	public int length() {
		return text.length();
	}

	public boolean isEmpty() {
		return text.isEmpty();
	}

	public int caret() {
		return caret;
	}

	public int anchor() {
		return anchor;
	}

	public int selectionStart() {
		return Math.min(caret, anchor);
	}

	public int selectionEnd() {
		return Math.max(caret, anchor);
	}

	public boolean hasSelection() {
		return caret != anchor;
	}

	public String selected() {
		return text.substring(selectionStart(), selectionEnd());
	}

	public int maxLength() {
		return maxLength;
	}

	public int version() {
		return version;
	}

	/** Changes the limit; text over it is cut (at a character boundary). */
	public void setMaxLength(int maxLength) {
		this.maxLength = Math.max(0, maxLength);
		if (text.length() > this.maxLength) {
			text.setLength(boundaryAtOrBefore(text, this.maxLength));
			caret = Math.min(caret, text.length());
			anchor = Math.min(anchor, text.length());
			version++;
		}
	}

	/** Replaces everything; the caret goes to the end. */
	public void set(String value) {
		text.setLength(0);
		text.append(value, 0, boundaryAtOrBefore(value, Math.min(value.length(), maxLength)));
		caret = anchor = text.length();
		version++;
	}

	/** Types or pastes {@code value} over the selection, cut short to the room left under the limit. */
	public void insert(String value) {
		int start = selectionStart();
		int end = selectionEnd();
		int room = maxLength - (text.length() - (end - start));
		String fitted = value.substring(0, boundaryAtOrBefore(value, Math.max(0, Math.min(room, value.length()))));
		if (fitted.isEmpty() && start == end) {
			// Nothing to replace and no room (or nothing typed): no change to report.
			return;
		}
		text.replace(start, end, fitted);
		caret = anchor = start + fitted.length();
		version++;
	}

	public void selectAll() {
		anchor = 0;
		caret = text.length();
		version++;
	}

	/** Removes and returns the selection (empty when there is none). */
	public String cut() {
		if (!hasSelection()) {
			return "";
		}
		String cut = selected();
		deleteSelection();
		return cut;
	}

	private void deleteSelection() {
		int start = selectionStart();
		text.delete(start, selectionEnd());
		caret = anchor = start;
		version++;
	}

	/** Backspace: the selection, else one character (or a word) before the caret. */
	public void backspace(boolean word) {
		if (hasSelection()) {
			deleteSelection();
			return;
		}
		int to = word ? wordLeft(text, caret) : previous(text, caret);
		if (to < caret) {
			text.delete(to, caret);
			caret = anchor = to;
			version++;
		}
	}

	/** Delete: the selection, else one character (or a word) after the caret. */
	public void delete(boolean word) {
		if (hasSelection()) {
			deleteSelection();
			return;
		}
		int to = word ? wordRight(text, caret) : next(text, caret);
		if (to > caret) {
			text.delete(caret, to);
			version++;
		}
	}

	public void left(boolean select, boolean word) {
		if (!select && hasSelection() && !word) {
			moveTo(selectionStart(), false);
			return;
		}
		moveTo(word ? wordLeft(text, caret) : previous(text, caret), select);
	}

	public void right(boolean select, boolean word) {
		if (!select && hasSelection() && !word) {
			moveTo(selectionEnd(), false);
			return;
		}
		moveTo(word ? wordRight(text, caret) : next(text, caret), select);
	}

	public void home(boolean select) {
		moveTo(0, select);
	}

	public void end(boolean select) {
		moveTo(text.length(), select);
	}

	/** Puts the caret at {@code index} (a click); {@code select} extends the selection from the anchor. */
	public void moveTo(int index, boolean select) {
		int to = Math.clamp(index, 0, text.length());
		if (to > 0 && to < text.length() && Character.isLowSurrogate(text.charAt(to)) && Character.isHighSurrogate(text.charAt(to - 1))) {
			to--;
		}
		if (to != caret || (!select && anchor != to)) {
			caret = to;
			if (!select) {
				anchor = to;
			}
			version++;
		}
	}

	static int previous(CharSequence s, int index) {
		if (index <= 0) {
			return 0;
		}
		int i = index - 1;
		if (i > 0 && Character.isLowSurrogate(s.charAt(i)) && Character.isHighSurrogate(s.charAt(i - 1))) {
			i--;
		}
		return i;
	}

	static int next(CharSequence s, int index) {
		if (index >= s.length()) {
			return s.length();
		}
		int i = index + 1;
		if (i < s.length() && Character.isLowSurrogate(s.charAt(i)) && Character.isHighSurrogate(s.charAt(i - 1))) {
			i++;
		}
		return i;
	}

	/** The start of the word before {@code index}: spaces first, then the word (as Minecraft's edit box). */
	static int wordLeft(CharSequence s, int index) {
		int i = Math.min(index, s.length());
		while (i > 0 && s.charAt(i - 1) == ' ') {
			i--;
		}
		while (i > 0 && s.charAt(i - 1) != ' ') {
			i--;
		}
		return i;
	}

	/** The end of the word at {@code index} and the spaces after it (as Minecraft's edit box). */
	static int wordRight(CharSequence s, int index) {
		int i = Math.max(0, index);
		while (i < s.length() && s.charAt(i) != ' ') {
			i++;
		}
		while (i < s.length() && s.charAt(i) == ' ') {
			i++;
		}
		return i;
	}

	/** {@code limit}, or one less if that would split a surrogate pair. */
	static int boundaryAtOrBefore(CharSequence s, int limit) {
		if (limit > 0 && limit < s.length() && Character.isLowSurrogate(s.charAt(limit)) && Character.isHighSurrogate(s.charAt(limit - 1))) {
			return limit - 1;
		}
		return Math.min(limit, s.length());
	}
}

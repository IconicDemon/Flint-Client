package com.flintclient.client.control.store;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of a layout action: the layout it concerns, or a sentence saying why it didn't happen (shown
 * under the field or in a toast).
 */
public record LayoutResult(@Nullable String id, @Nullable String error) {
	public static LayoutResult ok(String id) {
		return new LayoutResult(id, null);
	}

	public static LayoutResult failed(String error) {
		return new LayoutResult(null, error);
	}

	public boolean succeeded() {
		return error == null;
	}
}

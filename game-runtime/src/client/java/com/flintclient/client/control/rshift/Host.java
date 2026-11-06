package com.flintclient.client.control.rshift;

import com.flintclient.client.ui.kit.Node;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** What a page asks of the window around it: the shared pickers, and going to another mod's page. */
interface Host {
	default void openColorPicker(Node anchor, String name, int defaultRgb, IntSupplier get, IntConsumer set) {
		openColorPicker(anchor, name, defaultRgb, get, set, null);
	}

	/** The colour picker; with {@code clear}, the colour can be see-through and Transparent is offered. */
	void openColorPicker(Node anchor, String name, int defaultRgb, IntSupplier get, IntConsumer set, ColorPicker.@Nullable Clear clear);

	/** The list picker as a chooser (one sound, one item, one long option). */
	void openChooser(Node anchor, String name, List<ListPicker.Item> items, boolean allowNone, Supplier<String> current, Consumer<String> choose,
		@Nullable Consumer<String> preview);

	/** The list picker as a list of per-entry values (or ticks). */
	void openValues(Node anchor, String name, List<ListPicker.Item> items, ListPicker.Values model, @Nullable Consumer<String> preview);

	/** The page of the mod that holds Flint module {@code moduleId}. */
	void openModule(String moduleId);
}

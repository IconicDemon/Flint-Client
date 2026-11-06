package com.flintclient.client.control.keys;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Keybind Search's list (Flint's own implementation of the behaviour of the Keybind Search mod): every key mapping, grouped
 * under the mod that added it (the second word of its name, "minecraft" for Minecraft's own) and sorted by mod then name,
 * filtered as you type by name or category, by the key it's bound to, or by mod. Each row shows the action, "[mod] category",
 * a Rebind button and the key; while rebinding, the next key pressed is bound (Escape or Backspace unbinds it) and the
 * options are saved. Done returns to the Key Binds screen.
 */
public final class KeyBindsSearchScreen extends Screen {
	private static final int PAD = 8;
	private static final int ROW_HEIGHT = 22;
	private static final int KEY_BOX_WIDTH = 108;
	private static final int KEY_BOX_HEIGHT = 18;
	private static final int REBIND_WIDTH = 78;
	private static final int GAP = 10;
	private static final String[] MODES = {"Name", "Key", "Mod"};

	private final @Nullable Screen parent;
	private EditBox search;
	private Button mode;
	private int modeIndex;
	private Results results;
	private @Nullable KeyMapping rebinding;

	public KeyBindsSearchScreen(@Nullable Screen parent) {
		super(Component.literal("Search Keybinds"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int boxWidth = Math.min(360, width - 2 * PAD - 210);
		String query = search == null ? "" : search.getValue();
		search = new EditBox(font, PAD, PAD, boxWidth, 20, Component.literal("search"));
		search.setMaxLength(200);
		search.setValue(query);
		search.setResponder(text -> refresh());
		addRenderableWidget(search);
		mode = addRenderableWidget(Button.builder(modeLabel(), button -> {
			modeIndex = (modeIndex + 1) % MODES.length;
			button.setMessage(modeLabel());
			refresh();
		}).bounds(PAD + boxWidth + 6, PAD, 110, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
			search.setValue("");
			refresh();
		}).bounds(mode.getX() + mode.getWidth() + 6, PAD, 68, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).bounds(width - 80 - PAD, height - 28, 80, 20).build());
		int top = 32;
		results = addRenderableWidget(new Results(minecraft, width, height - top - 36 - PAD, top, ROW_HEIGHT));
		refresh();
		setInitialFocus(search);
	}

	/** Opens Minecraft's Key Binds screen over the current one, as Options › Controls › Key Binds does (for QA tours). */
	public static void showKeyBinds() {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(mc.gui.screen(), mc.options));
	}

	private Component modeLabel() {
		return Component.literal("Search by: " + MODES[modeIndex]);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.fill(0, 30, width, 31, 0x33000000);
		if (rebinding != null) {
			graphics.centeredText(font, Component.literal("Press a key for: " + I18n.get(rebinding.getName()) + " (Esc=Clear, Backspace=Unbind)"), width / 2,
				6, 0xFFFFFF55);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		KeyMapping target = rebinding;
		if (target == null) {
			return super.keyPressed(event);
		}
		boolean unbind = event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_BACKSPACE;
		target.setKey(unbind ? InputConstants.UNKNOWN : InputConstants.getKey(event));
		KeyMapping.resetMapping();
		minecraft.options.save();
		rebinding = null;
		results.fill();
		return true;
	}

	private void refresh() {
		if (results != null) {
			results.fill();
		}
	}

	private static String normalize(@Nullable String text) {
		return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
	}

	/** The mod a key mapping came from: the second word of its name ("key.flintclient.control_center"), or "minecraft". */
	static String modOf(KeyMapping key) {
		String name = key.getName() == null ? "" : key.getName().strip();
		int first = name.indexOf('.');
		int second = first < 0 ? -1 : name.indexOf('.', first + 1);
		String mod = second < 0 ? "" : name.substring(first + 1, second);
		return mod.isEmpty() || mod.equalsIgnoreCase("categories") ? "minecraft" : mod.toLowerCase(Locale.ROOT);
	}

	private boolean matches(KeyMapping key, String query) {
		if (query.isEmpty()) {
			return true;
		}
		return switch (modeIndex) {
			case 1 -> normalize(key.getTranslatedKeyMessage().getString()).contains(query);
			case 2 -> modOf(key).contains(query);
			default -> normalize(I18n.get(key.getName())).contains(query) || normalize(key.getCategory().label().getString()).contains(query);
		};
	}

	/** {@code text} cut to {@code maxWidth}, ending in "..." when it had to be cut. */
	private static String fit(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}

	/** The results: a heading per mod, then its key mappings. */
	private final class Results extends ObjectSelectionList<Results.Line> {
		Results(Minecraft minecraft, int width, int height, int top, int rowHeight) {
			super(minecraft, width, height, top, rowHeight);
		}

		void fill() {
			clearEntries();
			String query = normalize(search.getValue());
			List<KeyMapping> found = new ArrayList<>();
			for (KeyMapping key : minecraft.options.keyMappings) {
				if (matches(key, query)) {
					found.add(key);
				}
			}
			found.sort(Comparator.comparing(KeyBindsSearchScreen::modOf, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(key -> I18n.get(key.getName()), String.CASE_INSENSITIVE_ORDER));
			String mod = null;
			for (KeyMapping key : found) {
				String own = modOf(key);
				if (!own.equals(mod)) {
					mod = own;
					addEntry(new Line(own, null));
				}
				addEntry(new Line(own, key));
			}
		}

		@Override
		public int getRowLeft() {
			return PAD;
		}

		@Override
		public int getRowWidth() {
			return width - 2 * PAD - 6;
		}

		/** One line: a mod's heading ({@code key} null) or one of its key mappings with its Rebind button. */
		final class Line extends ObjectSelectionList.Entry<Line> {
			private final String mod;
			private final @Nullable KeyMapping key;
			private final @Nullable Button rebind;

			Line(String mod, @Nullable KeyMapping key) {
				this.mod = mod;
				this.key = key;
				this.rebind = key == null ? null : Button.builder(Component.literal("Rebind"), button -> rebinding = key).bounds(0, 0, REBIND_WIDTH, 20).build();
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int left = getX();
				int top = getY();
				int lineWidth = getWidth();
				int lineHeight = getHeight();
				Font font = minecraft.font;
				if (key == null) {
					graphics.fill(left, top, left + lineWidth, top + lineHeight, 0x22000000);
					graphics.text(font, Component.literal(mod), left + 2, top + (lineHeight - 9) / 2, 0xFFB0B0B0, false);
					graphics.fill(left, top + lineHeight - 1, left + lineWidth, top + lineHeight, 0x33000000);
					return;
				}
				if (hovered) {
					graphics.fill(left, top, left + lineWidth, top + lineHeight, 0x1FFFFFFF);
				}
				if (this == getSelected()) {
					graphics.fill(left, top, left + lineWidth, top + lineHeight, 0x33FFFFFF);
				}
				int boxRight = left + lineWidth - PAD;
				int boxLeft = boxRight - KEY_BOX_WIDTH;
				int boxTop = top + (lineHeight - KEY_BOX_HEIGHT) / 2;
				int rebindRight = boxLeft - GAP;
				int rebindLeft = rebindRight - REBIND_WIDTH;
				rebind.setX(rebindLeft);
				rebind.setY(top + (lineHeight - 20) / 2);
				rebind.extractRenderState(graphics, mouseX, mouseY, partialTick);
				int inner = KEY_BOX_WIDTH - 8;
				String bound = fit(font, key.getTranslatedKeyMessage().getString(), inner);
				graphics.text(font, bound, boxLeft + 4 + Math.max(0, (inner - font.width(bound)) / 2), boxTop + (KEY_BOX_HEIGHT - 9) / 2, 0xFF5BF15B, true);
				int textLeft = left + PAD;
				int available = Math.max(0, rebindLeft - GAP - textLeft);
				int metaWidth = Math.min(200, available / 2);
				int nameWidth = Math.max(0, available - metaWidth - GAP);
				int textY = top + 5;
				graphics.text(font, fit(font, I18n.get(key.getName()), nameWidth), textLeft, textY, 0xFFE0E0E0, true);
				String meta = "[" + mod + "] " + key.getCategory().label().getString();
				graphics.text(font, fit(font, meta, metaWidth), textLeft + nameWidth + GAP, textY, 0xFF9AA0A6, true);
				graphics.fill(left, top + lineHeight - 1, left + lineWidth, top + lineHeight, 0x22000000);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (rebind != null && rebind.mouseClicked(event, doubleClick)) {
					return true;
				}
				return key != null && super.mouseClicked(event, doubleClick);
			}

			@Override
			public Component getNarration() {
				return Component.literal(key == null ? "Group " + mod : "Keybind " + I18n.get(key.getName()));
			}
		}
	}
}

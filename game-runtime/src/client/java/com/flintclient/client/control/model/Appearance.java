package com.flintclient.client.control.model;

import org.jspecify.annotations.Nullable;

/**
 * How a HUD module looks. Each module holds one where null means "same as the layout"; the layout holds its
 * shared style, where the background and its opacity may also be null, meaning "whatever the style does" (see
 * {@link HudThemeId#defaultBackground()}), so a Classic module gets Classic's lighter panel and a Minimal one no
 * panel without either being written down. {@link #effective} combines them for drawing. Records are immutable,
 * so a change makes a new one: that happens on player actions, never per frame.
 *
 * <p>The accent colour tints labels and units ("FPS", "ms", "X") and is what a pressed key fills with unless the
 * keystrokes module picks its own; {@link HudColor#DEFAULT} means the style's own (Flint's ember, Classic's grey,
 * Minimal's dimmed text). Modules whose label is set by their own setting (see
 * {@link ModuleSettings#labelSetting}) ignore {@link #showLabel}; it stays for the others.
 *
 * <p>The panel's colour, its border (on, colour, thickness in half units) and the text shadow are null until chosen, and
 * then replace the style's own ({@link #styleDefault}); a layout file only names them once set.
 */
public record Appearance(@Nullable HudThemeId theme, @Nullable HudColor textColor, @Nullable Boolean background,
	@Nullable Integer backgroundOpacity, @Nullable Integer padding, @Nullable Boolean showLabel, @Nullable HudColor accentColor,
	@Nullable Integer backgroundColor, @Nullable Boolean border, @Nullable Integer borderColor, @Nullable Integer borderThickness,
	@Nullable Boolean textShadow) {

	public static final ChoiceSetting<HudThemeId> THEME = new ChoiceSetting<>("theme", "Style", null, HudThemeId.class, HudThemeId.FLINT);
	public static final ColorSetting TEXT_COLOR = new ColorSetting("textColor", "Text color", null, HudColor.DEFAULT);
	/** Labels, units and the default pressed-key colour; Default is the style's own. */
	public static final ColorSetting ACCENT_COLOR = new ColorSetting("accentColor", "Accent color",
		"Labels and units, and pressed keys; Default is the style's own", HudColor.DEFAULT);
	/** Its default is Flint's; the effective default depends on the style ({@link HudThemeId#defaultBackground()}). */
	public static final BoolSetting BACKGROUND = new BoolSetting("background", "Background", true);
	/** Its default is Flint's; the effective default depends on the style ({@link HudThemeId#defaultBackgroundOpacity()}). */
	public static final IntSetting BACKGROUND_OPACITY = new IntSetting("backgroundOpacity", "Background opacity", null, 70, 0, 100, 5,
		"%");
	public static final IntSetting PADDING = new IntSetting("padding", "Padding", null, 3, 0, 8, 1, "");
	public static final BoolSetting SHOW_LABEL = new BoolSetting("showLabel", "Show label", true);
	/** The panel's colour (its opacity is {@link #BACKGROUND_OPACITY}); the default is Flint's panel. */
	public static final RgbSetting BACKGROUND_COLOR = new RgbSetting("backgroundColor", "Background color", null, 0x0F0E0D);
	public static final BoolSetting BORDER = new BoolSetting("border", "Border", "A line around the panel", true);
	public static final RgbSetting BORDER_COLOR = new RgbSetting("borderColor", "Border color", null, 0xFFFFFF);
	public static final IntSetting BORDER_THICKNESS = new IntSetting("borderThickness", "Border thickness", "In half units: 1 is a hairline", 1, 1,
		4, 1, "");
	public static final BoolSetting TEXT_SHADOW = new BoolSetting("textShadow", "Text shadow", "A shadow under the text", false);

	/** Every field inherited. */
	public static final Appearance INHERIT = new Appearance(null, null, null, null, null, null, null);
	/**
	 * A new layout's shared style: Flint, the style's text colour, padding 3, labels on; the panel and the accent
	 * colour follow the style until one is chosen (so a layout file only names them once set).
	 */
	public static final Appearance DEFAULTS = new Appearance(HudThemeId.FLINT, HudColor.DEFAULT, null, null, 3, true, null);

	public Appearance {
		backgroundOpacity = backgroundOpacity == null ? null : BACKGROUND_OPACITY.clamp(backgroundOpacity);
		padding = padding == null ? null : PADDING.clamp(padding);
		backgroundColor = backgroundColor == null ? null : backgroundColor & 0xFFFFFF;
		borderColor = borderColor == null ? null : borderColor & 0xFFFFFF;
		borderThickness = borderThickness == null ? null : BORDER_THICKNESS.clamp(borderThickness);
	}

	/** With the style's own panel colour, border and shadow. */
	public Appearance(@Nullable HudThemeId theme, @Nullable HudColor textColor, @Nullable Boolean background,
		@Nullable Integer backgroundOpacity, @Nullable Integer padding, @Nullable Boolean showLabel, @Nullable HudColor accentColor) {
		this(theme, textColor, background, backgroundOpacity, padding, showLabel, accentColor, null, null, null, null, null);
	}

	/** Without an accent colour (it follows the layout, or the style's own). */
	public Appearance(@Nullable HudThemeId theme, @Nullable HudColor textColor, @Nullable Boolean background,
		@Nullable Integer backgroundOpacity, @Nullable Integer padding, @Nullable Boolean showLabel) {
		this(theme, textColor, background, backgroundOpacity, padding, showLabel, null);
	}

	/** The fields a look row can name, in the order a settings page lists them. */
	public static final SettingDescriptor<?>[] FIELDS = {THEME, TEXT_COLOR, ACCENT_COLOR, TEXT_SHADOW, BACKGROUND, BACKGROUND_COLOR,
		BACKGROUND_OPACITY, BORDER, BORDER_COLOR, BORDER_THICKNESS, PADDING, SHOW_LABEL};

	/** The field {@code id} names, or null. */
	public static @Nullable SettingDescriptor<?> field(String id) {
		for (SettingDescriptor<?> f : FIELDS) {
			if (f.id().equals(id)) {
				return f;
			}
		}
		return null;
	}

	/** The value of {@code field} here (null: not set at this level). */
	public @Nullable Object get(SettingDescriptor<?> field) {
		if (field == THEME) {
			return theme;
		} else if (field == TEXT_COLOR) {
			return textColor;
		} else if (field == ACCENT_COLOR) {
			return accentColor;
		} else if (field == TEXT_SHADOW) {
			return textShadow;
		} else if (field == BACKGROUND) {
			return background;
		} else if (field == BACKGROUND_COLOR) {
			return backgroundColor;
		} else if (field == BACKGROUND_OPACITY) {
			return backgroundOpacity;
		} else if (field == BORDER) {
			return border;
		} else if (field == BORDER_COLOR) {
			return borderColor;
		} else if (field == BORDER_THICKNESS) {
			return borderThickness;
		} else if (field == PADDING) {
			return padding;
		} else if (field == SHOW_LABEL) {
			return showLabel;
		}
		throw new IllegalArgumentException("not an appearance field: " + field.id());
	}

	/** This appearance with {@code field} set to {@code value} (null: back to inheriting it). */
	public Appearance with(SettingDescriptor<?> field, @Nullable Object value) {
		return new Appearance(
			field == THEME ? (HudThemeId) value : theme,
			field == TEXT_COLOR ? (HudColor) value : textColor,
			field == BACKGROUND ? (Boolean) value : background,
			field == BACKGROUND_OPACITY ? (Integer) value : backgroundOpacity,
			field == PADDING ? (Integer) value : padding,
			field == SHOW_LABEL ? (Boolean) value : showLabel,
			field == ACCENT_COLOR ? (HudColor) value : accentColor,
			field == BACKGROUND_COLOR ? (Integer) value : backgroundColor,
			field == BORDER ? (Boolean) value : border,
			field == BORDER_COLOR ? (Integer) value : borderColor,
			field == BORDER_THICKNESS ? (Integer) value : borderThickness,
			field == TEXT_SHADOW ? (Boolean) value : textShadow);
	}

	/**
	 * What {@code field} is when nothing sets it, in {@code style}: the style's own background, opacity, panel colour,
	 * border and shadow (as the renderer draws them), else the field's default.
	 */
	public static Object styleDefault(SettingDescriptor<?> field, HudThemeId style) {
		if (field == BACKGROUND) {
			return style.defaultBackground();
		} else if (field == BACKGROUND_OPACITY) {
			return style.defaultBackgroundOpacity();
		} else if (field == BACKGROUND_COLOR) {
			return style == HudThemeId.CLASSIC ? 0x000000 : BACKGROUND_COLOR.defaultValue();
		} else if (field == BORDER) {
			return style == HudThemeId.FLINT;
		} else if (field == TEXT_SHADOW) {
			return style != HudThemeId.FLINT;
		}
		return field.defaultValue();
	}

	/** This appearance with every null field taken from {@code base}. */
	public Appearance over(Appearance base) {
		return new Appearance(
			theme != null ? theme : base.theme,
			textColor != null ? textColor : base.textColor,
			background != null ? background : base.background,
			backgroundOpacity != null ? backgroundOpacity : base.backgroundOpacity,
			padding != null ? padding : base.padding,
			showLabel != null ? showLabel : base.showLabel,
			accentColor != null ? accentColor : base.accentColor,
			backgroundColor != null ? backgroundColor : base.backgroundColor,
			border != null ? border : base.border,
			borderColor != null ? borderColor : base.borderColor,
			borderThickness != null ? borderThickness : base.borderThickness,
			textShadow != null ? textShadow : base.textShadow);
	}

	/**
	 * What a module draws with: its overrides, then the layout's style, then {@link #DEFAULTS}, and for the panel
	 * whatever the resulting style does. Only the panel colour, border and shadow may stay null (the style's own).
	 */
	public static Appearance effective(Appearance module, Appearance layout) {
		Appearance merged = module.over(layout.over(DEFAULTS));
		HudThemeId style = merged.theme;
		if (merged.background != null && merged.backgroundOpacity != null && merged.accentColor != null) {
			return merged;
		}
		return new Appearance(style, merged.textColor, merged.background != null ? merged.background : style.defaultBackground(),
			merged.backgroundOpacity != null ? merged.backgroundOpacity : style.defaultBackgroundOpacity(), merged.padding, merged.showLabel,
			merged.accentColor != null ? merged.accentColor : HudColor.DEFAULT, merged.backgroundColor, merged.border, merged.borderColor,
			merged.borderThickness, merged.textShadow);
	}

	/** Whether nothing is overridden. */
	public boolean isInherit() {
		return theme == null && textColor == null && background == null && backgroundOpacity == null && padding == null && showLabel == null
			&& accentColor == null && backgroundColor == null && border == null && borderColor == null && borderThickness == null
			&& textShadow == null;
	}

	public Appearance withTheme(@Nullable HudThemeId value) {
		return with(THEME, value);
	}

	public Appearance withTextColor(@Nullable HudColor value) {
		return with(TEXT_COLOR, value);
	}

	public Appearance withBackground(@Nullable Boolean value) {
		return with(BACKGROUND, value);
	}

	public Appearance withBackgroundOpacity(@Nullable Integer value) {
		return with(BACKGROUND_OPACITY, value);
	}

	public Appearance withPadding(@Nullable Integer value) {
		return with(PADDING, value);
	}

	public Appearance withShowLabel(@Nullable Boolean value) {
		return with(SHOW_LABEL, value);
	}

	public Appearance withAccentColor(@Nullable HudColor value) {
		return with(ACCENT_COLOR, value);
	}
}

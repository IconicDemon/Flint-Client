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
 */
public record Appearance(@Nullable HudThemeId theme, @Nullable HudColor textColor, @Nullable Boolean background,
	@Nullable Integer backgroundOpacity, @Nullable Integer padding, @Nullable Boolean showLabel, @Nullable HudColor accentColor) {

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
	}

	/** Without an accent colour (it follows the layout, or the style's own). */
	public Appearance(@Nullable HudThemeId theme, @Nullable HudColor textColor, @Nullable Boolean background,
		@Nullable Integer backgroundOpacity, @Nullable Integer padding, @Nullable Boolean showLabel) {
		this(theme, textColor, background, backgroundOpacity, padding, showLabel, null);
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
			accentColor != null ? accentColor : base.accentColor);
	}

	/**
	 * What a module draws with: its overrides, then the layout's style, then {@link #DEFAULTS}, and for the panel
	 * whatever the resulting style does. No field of the result is null.
	 */
	public static Appearance effective(Appearance module, Appearance layout) {
		Appearance merged = module.over(layout.over(DEFAULTS));
		HudThemeId style = merged.theme;
		if (merged.background != null && merged.backgroundOpacity != null && merged.accentColor != null) {
			return merged;
		}
		return new Appearance(style, merged.textColor, merged.background != null ? merged.background : style.defaultBackground(),
			merged.backgroundOpacity != null ? merged.backgroundOpacity : style.defaultBackgroundOpacity(), merged.padding, merged.showLabel,
			merged.accentColor != null ? merged.accentColor : HudColor.DEFAULT);
	}

	/** Whether nothing is overridden. */
	public boolean isInherit() {
		return theme == null && textColor == null && background == null && backgroundOpacity == null && padding == null && showLabel == null
			&& accentColor == null;
	}

	public Appearance withTheme(@Nullable HudThemeId value) {
		return new Appearance(value, textColor, background, backgroundOpacity, padding, showLabel, accentColor);
	}

	public Appearance withTextColor(@Nullable HudColor value) {
		return new Appearance(theme, value, background, backgroundOpacity, padding, showLabel, accentColor);
	}

	public Appearance withBackground(@Nullable Boolean value) {
		return new Appearance(theme, textColor, value, backgroundOpacity, padding, showLabel, accentColor);
	}

	public Appearance withBackgroundOpacity(@Nullable Integer value) {
		return new Appearance(theme, textColor, background, value, padding, showLabel, accentColor);
	}

	public Appearance withPadding(@Nullable Integer value) {
		return new Appearance(theme, textColor, background, backgroundOpacity, value, showLabel, accentColor);
	}

	public Appearance withShowLabel(@Nullable Boolean value) {
		return new Appearance(theme, textColor, background, backgroundOpacity, padding, value, accentColor);
	}

	public Appearance withAccentColor(@Nullable HudColor value) {
		return new Appearance(theme, textColor, background, backgroundOpacity, padding, showLabel, value);
	}
}

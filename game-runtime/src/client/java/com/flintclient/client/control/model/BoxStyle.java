package com.flintclient.client.control.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The look shared by Flint's one-line counters (FPS, CPS, Ping, Playtime, Memory, Reach...): a display mode (in a box,
 * between brackets, or just text), the box (fill, Static or Dynamic width, height, border, corners, alignment) and the
 * text (colour, shadow). Each module makes its own instance, as a setting belongs to exactly one module; the ids are
 * the same in every module, so files read alike. What the settings page shows is exactly what the HUD draws
 * ({@code BoxedText} in the renderer uses these and nothing else).
 */
public final class BoxStyle {
	public final ChoiceSetting<FpsDisplayMode> displayMode;
	public final BoolSetting background;
	public final ChoiceSetting<FpsSizing> backgroundType;
	public final IntSetting width;
	public final IntSetting height;
	public final BoolSetting border;
	public final RgbSetting textColor;
	public final BoolSetting textShadow;
	public final ChoiceSetting<TextAlign> align;
	public final RgbSetting backgroundColor;
	public final IntSetting backgroundOpacity;
	public final RgbSetting borderColor;
	public final IntSetting borderThickness;
	public final IntSetting cornerRadius;

	/** A counter that starts as a dynamic box {@code height} units tall, {@code width} wide when made static. */
	public BoxStyle(int width, int height) {
		this(width, height, FpsSizing.DYNAMIC, 0xFFFFFF, true);
	}

	public BoxStyle(int defaultWidth, int defaultHeight, FpsSizing sizing, int textRgb, boolean shadow) {
		this(defaultWidth, 200, defaultHeight, sizing, textRgb, shadow);
	}

	public BoxStyle(int defaultWidth, int maxWidth, int defaultHeight, FpsSizing sizing, int textRgb, boolean shadow) {
		displayMode = new ChoiceSetting<>("displayMode", "Display mode", "How the counter is framed", FpsDisplayMode.class,
			FpsDisplayMode.BACKGROUND);
		background = new BoolSetting("background", "Background", "Fills the box behind the text", true);
		backgroundType = new ChoiceSetting<>("backgroundType", "Background type", "Dynamic fits the box to the text", FpsSizing.class, sizing);
		width = new IntSetting("width", "Background width", "At 1× size; it never gets narrower than the text", defaultWidth, 24, maxWidth, 1, "");
		height = new IntSetting("height", "Background height", "At 1× size; it never gets shorter than the text", defaultHeight, 9, 40, 1, "");
		border = new BoolSetting("border", "Border", "A thin line around the box", false);
		textColor = new RgbSetting("textColor", "Text color", null, textRgb);
		textShadow = new BoolSetting("textShadow", "Text shadow", "A soft shadow under the text, as Minecraft draws it", shadow);
		align = new ChoiceSetting<>("align", "Text alignment", "Where the text sits in a box wider than it", TextAlign.class, TextAlign.CENTER);
		backgroundColor = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		backgroundOpacity = new IntSetting("backgroundOpacity", "Background opacity", null, 45, 0, 100, 5, "%");
		borderColor = new RgbSetting("borderColor", "Border color", null, 0x6B6157);
		borderThickness = new IntSetting("borderThickness", "Border thickness", "In half units: 1 is a hairline", 1, 1, 4, 1, "");
		cornerRadius = new IntSetting("cornerRadius", "Corner radius", "At 1× size", 3, 0, 8, 1, "");
	}

	/** The "Style" section's settings, in the reference order. */
	public SettingDescriptor<?>[] main() {
		return new SettingDescriptor<?>[] {displayMode, background, backgroundType, width, height, border, textColor, textShadow};
	}

	/** The box details, for a folded section. */
	public SettingDescriptor<?>[] details() {
		return new SettingDescriptor<?>[] {align, backgroundColor, backgroundOpacity, borderColor, borderThickness, cornerRadius};
	}

	public SettingSection section(String title, SettingDescriptor<?>... extra) {
		SettingDescriptor<?>[] main = main();
		SettingDescriptor<?>[] all = new SettingDescriptor<?>[main.length + extra.length];
		System.arraycopy(main, 0, all, 0, main.length);
		System.arraycopy(extra, 0, all, main.length, extra.length);
		return new SettingSection(title, List.of(all), false);
	}

	public SettingSection detailSection() {
		return SettingSection.advanced("Background and border", details());
	}

	/** Whether {@code setting} is one of this style's. */
	public boolean owns(SettingDescriptor<?> setting) {
		return setting == displayMode || setting == background || setting == backgroundType || setting == width || setting == height
			|| setting == border || setting == textColor || setting == textShadow || boxOnly(setting);
	}

	private boolean boxOnly(SettingDescriptor<?> setting) {
		return setting == background || setting == backgroundType || setting == width || setting == height || setting == border
			|| setting == align || setting == backgroundColor || setting == backgroundOpacity || setting == borderColor
			|| setting == borderThickness || setting == cornerRadius;
	}

	/** Why {@code setting} (one of this style's) changes nothing as {@code module} is set now, or null. Allocation-free. */
	public @Nullable String inactiveReason(ModuleState module, SettingDescriptor<?> setting) {
		if (boxOnly(setting) && module.choice(displayMode) != FpsDisplayMode.BACKGROUND) {
			return "Only in the Background display mode";
		}
		boolean dynamic = module.choice(backgroundType) == FpsSizing.DYNAMIC;
		if (setting == width && dynamic) {
			return "Automatic in Dynamic: the box fits the text";
		}
		if (setting == align && dynamic) {
			return "In Dynamic the box fits the text exactly";
		}
		if ((setting == backgroundColor || setting == backgroundOpacity) && !module.bool(background)) {
			return "Turn the background on first";
		}
		if ((setting == borderColor || setting == borderThickness) && !module.bool(border)) {
			return "Turn the border on first";
		}
		if (setting == cornerRadius && !module.bool(background) && !module.bool(border)) {
			return "Needs the background or the border";
		}
		return null;
	}
}

package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.TagShape;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

/** Minecraft 26.3's nametag text parts as {@link Mc263Nametags#look} says: a shaped background as standalone backgrounds first. */
public final class NametagParts {
	private NametagParts() {
	}

	/** One nametag text part, as Minecraft would make it ({@code vanilla}), restyled; a shaped background goes to {@code before}. */
	public static TextFeatureRenderer.Submit part(Matrix4f pose, float x, float y, FormattedCharSequence name, int light, int color, int background,
		Font.DisplayMode mode, Factory vanilla, Consumer<TextFeatureRenderer.Submit> before) {
		Mc263Nametags.Look look = Mc263Nametags.look(color, background, mode);
		if (look == null) {
			return vanilla.make(pose, x, y, name, light, color, background, mode);
		}
		if (look.skip) {
			return new TextFeatureRenderer.Submit(pose, mode, light, new TextFeatureRenderer.Content.Text(x, y, FormattedCharSequence.EMPTY, false, 0, 0, 0));
		}
		if (look.shaped()) {
			float width = Minecraft.getInstance().font.width(name);
			shape(pose, mode, light, x + look.left, y + look.top, x + width + look.right, y + look.bottom, look, before);
		}
		return new TextFeatureRenderer.Submit(pose, mode, light, new TextFeatureRenderer.Content.Text(x, y, name, look.shadow, look.text, look.background, 0));
	}

	/** A background box with rounded corners or a border, as rectangles that don't overlap, before the text. */
	private static void shape(Matrix4f pose, Font.DisplayMode mode, int light, float left, float top, float right, float bottom, Mc263Nametags.Look look,
		Consumer<TextFeatureRenderer.Submit> before) {
		List<float[]> fillRects = new ArrayList<>(12);
		List<float[]> ringRects = new ArrayList<>(look.thickness > 0 ? 24 : 0);
		TagShape.build(left, top, right, bottom, look.radius, look.ring != 0 ? look.thickness : 0, fillRects, ringRects);
		if (ARGB.alpha(look.fill) > 0) {
			for (float[] r : fillRects) {
				before.accept(new TextFeatureRenderer.Submit(pose, mode, light, new TextFeatureRenderer.Content.StandaloneBackground(r[0], r[1], r[2], r[3], look.fill)));
			}
		}
		if (ARGB.alpha(look.ring) > 0) {
			for (float[] r : ringRects) {
				before.accept(new TextFeatureRenderer.Submit(pose, mode, light, new TextFeatureRenderer.Content.StandaloneBackground(r[0], r[1], r[2], r[3], look.ring)));
			}
		}
	}

	/** Minecraft's own nametag part maker. */
	@FunctionalInterface
	public interface Factory {
		TextFeatureRenderer.Submit make(Matrix4f pose, float x, float y, FormattedCharSequence name, int light, int color, int background,
			Font.DisplayMode mode);
	}
}

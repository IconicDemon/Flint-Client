package com.flintclient.legacy.ui;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.legacy.Flint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * Flint's menu blur on 1.8.9, which has none of its own (26.x blurs what is behind its menus; Flint's menus there follow
 * Minecraft's blur and General › Menu blur strength): what is drawn so far is blurred where Flint's menu asks for it
 * ({@code Frame.blurBehind}: once a frame), the whole screen or only the rectangles of a frosted window. A separable gaussian in
 * two passes (across, then down) through one texture the window's size, kept between frames. The strength is the menu's
 * ({@link ControlState#menuBlurOverride}, as 26.x's blur reads it), else a soft default. If a step fails, the blur is off for the
 * session (the menu shows as before, sharp behind its scrim). Render thread.
 */
public final class MenuBlur189 {
	private static final HookGuard GUARD = new HookGuard("menu blur",
		(path, error) -> Flint.LOG.warn("Flint: the {} failed; menus aren't blurred this session", path, error));
	private static final int DEFAULT_STRENGTH = 5;
	private static final String VERTEX = "#version 120\nvarying vec2 uv;\nvoid main() {\n  uv = gl_MultiTexCoord0.xy;\n"
		+ "  gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n}\n";
	private static final String FRAGMENT = "#version 120\nuniform sampler2D tex;\nuniform vec2 step;\nvarying vec2 uv;\n"
		+ "void main() {\n  vec3 sum = vec3(0.0);\n  float total = 0.0;\n  for (int i = -12; i <= 12; i++) {\n"
		+ "    float w = exp(-float(i * i) / 72.0);\n    sum += texture2D(tex, uv + step * float(i)).rgb * w;\n    total += w;\n  }\n"
		+ "  gl_FragColor = vec4(sum / total, 1.0);\n}\n";
	private static int program;
	private static int uStep;
	private static int texture;
	private static int texWidth;
	private static int texHeight;
	private static long blurredFrame = -1;
	private static long frames;
	/** For QA and the log: the last blur (rectangles, -1 the whole screen; its strength) and how many ran. */
	private static int lastRects = -2;
	private static int lastStrength = -1;
	private static long runs;
	private static long claims;

	private MenuBlur189() {
	}

	/** Whether the blur can run (it hasn't failed this session). */
	public static boolean on() {
		return GUARD.on();
	}

	/** After each frame was presented. */
	public static void endFrame() {
		frames++;
		// A blur asked for and never run (a clip that named no rectangle) ends with the frame: the backdrop and the panels are
		// drawn in separate passes of the same frame, so it can't end with a pass.
		com.flintclient.client.platform.Frame.clipBlurOff();
	}

	/** This frame's one blur is taken (it runs a moment later, Frame.blurBehind). */
	public static void claim() {
		claims++;
		blurredFrame = frames;
	}

	/** Whether this frame was blurred already (one blur a frame, as on 26.x). */
	public static boolean blurredThisFrame() {
		return blurredFrame == frames;
	}

	/**
	 * Blurs what is drawn so far in the window rectangles {@code rects} (x0, y0, x1, y1 in window pixels from the top left;
	 * {@code n} of them, or the whole window for -1). The frame's pixel projection is current (Frame.beginPixels). True once blurred.
	 */
	public static boolean blur(int[] rects, int n) {
		if (!GUARD.on()) {
			return false;
		}
		blurredFrame = frames;
		try {
			Minecraft mc = Minecraft.getInstance();
			int w = mc.width;
			int h = mc.height;
			if (w < 2 || h < 2) {
				return false;
			}
			if (program == 0) {
				program = link();
				uStep = GL20.glGetUniformLocation(program, "step");
			}
			if (texture == 0 || texWidth != w || texHeight != h) {
				if (texture != 0) {
					GL11.glDeleteTextures(texture);
				}
				texture = GL11.glGenTextures();
				GlStateManager.bindTexture(texture);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
				GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
				texWidth = w;
				texHeight = h;
			}
			int override = ControlState.menuBlurOverride();
			int strength = override >= 0 ? override : DEFAULT_STRENGTH;
			lastRects = n;
			lastStrength = strength;
			runs++;
			if (strength <= 0) {
				return true;
			}
			// One tap a pixel at strength 1 (25 taps), wider steps for more: up to 2.5 pixels a tap at 10.
			float spread = 0.25F * strength * Math.max(1, Math.min(w, h) / 720F);
			GlStateManager.activeTexture(GL13.GL_TEXTURE0);
			GlStateManager.disableBlend();
			GlStateManager.disableAlphaTest();
			GlStateManager.color4f(1, 1, 1, 1);
			GL20.glUseProgram(program);
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "tex"), 0);
			// Across, then down: each pass reads the window as it is now into the texture and draws the blurred rectangles back.
			for (int pass = 0; pass < 2; pass++) {
				GlStateManager.bindTexture(texture);
				GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
				GL20.glUniform2f(uStep, pass == 0 ? spread / w : 0, pass == 0 ? 0 : spread / h);
				if (n < 0) {
					quad(0, 0, w, h, w, h);
				} else {
					for (int i = 0; i < n; i++) {
						quad(rects[4 * i], rects[4 * i + 1], rects[4 * i + 2], rects[4 * i + 3], w, h);
					}
				}
			}
			GL20.glUseProgram(0);
			GlStateManager.enableBlend();
			return true;
		} catch (RuntimeException | LinkageError e) {
			GL20.glUseProgram(0);
			GUARD.fail(e);
			return false;
		}
	}

	/** QA: the blur's last run. */
	public static String describe() {
		return "menu blur: on=" + GUARD.on() + " claims=" + claims + " runs=" + runs + " last rects=" + lastRects + " strength=" + lastStrength;
	}

	/** The window rectangle (x0, y0)-(x1, y1), pixels from the top left, textured from the same place of the copy. */
	private static void quad(int x0, int y0, int x1, int y1, int w, int h) {
		x0 = Math.max(0, x0);
		y0 = Math.max(0, y0);
		x1 = Math.min(w, x1);
		y1 = Math.min(h, y1);
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		// The copy's rows run from the bottom of the window.
		float u0 = x0 / (float) w;
		float u1 = x1 / (float) w;
		float v0 = 1 - y0 / (float) h;
		float v1 = 1 - y1 / (float) h;
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(u0, v1);
		GL11.glVertex3f(x0, y1, 0);
		GL11.glTexCoord2f(u1, v1);
		GL11.glVertex3f(x1, y1, 0);
		GL11.glTexCoord2f(u1, v0);
		GL11.glVertex3f(x1, y0, 0);
		GL11.glTexCoord2f(u0, v0);
		GL11.glVertex3f(x0, y0, 0);
		GL11.glEnd();
	}

	private static int link() {
		int vs = shader(GL20.GL_VERTEX_SHADER, VERTEX);
		int fs = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, vs);
		GL20.glAttachShader(p, fs);
		GL20.glLinkProgram(p);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
			throw new IllegalStateException("blur program: " + GL20.glGetProgramInfoLog(p, 2000));
		}
		GL20.glDeleteShader(vs);
		GL20.glDeleteShader(fs);
		return p;
	}

	private static int shader(int type, String source) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, source);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
			throw new IllegalStateException("blur shader: " + GL20.glGetShaderInfoLog(s, 2000));
		}
		return s;
	}
}

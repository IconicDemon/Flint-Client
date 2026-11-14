package com.flintclient.qa.mixin;

import com.flintclient.qa.QaStamp;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QaStamp on 1.21.x: the frame number painted into the window's own framebuffer at the last moment, right before
 * glfwSwapBuffers (after everything that draws there, a shader pack's final pass included), by scissored clears with the GL
 * state put back; the swap timed. Dev only.
 */
@Mixin(RenderSystem.class)
abstract class FrameStampMixin {
	@Inject(method = "flipFrame(J)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
	private static void flintqa$beforePresent(long window, CallbackInfo ci) {
		if (!QaStamp.active()) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
			boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
			IntBuffer box = stack.mallocInt(4);
			GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
			FloatBuffer clear = stack.mallocFloat(4);
			GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
			ByteBuffer mask = stack.malloc(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
			GL11.glColorMask(true, true, true, true);
			GL11.glEnable(GL11.GL_SCISSOR_TEST);
			for (int i = 0; i < QaStamp.BLOCKS; i++) {
				float v = QaStamp.white(i) ? 1 : 0;
				GL11.glScissor(i * QaStamp.BLOCK, 0, QaStamp.BLOCK, QaStamp.BLOCK);
				GL11.glClearColor(v, v, v, 1);
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			}
			GL11.glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
			GL11.glScissor(box.get(0), box.get(1), box.get(2), box.get(3));
			if (!scissor) {
				GL11.glDisable(GL11.GL_SCISSOR_TEST);
			}
			GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
		}
		QaStamp.beforePresent();
	}

	@Inject(method = "flipFrame(J)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", shift = At.Shift.AFTER, remap = false))
	private static void flintqa$afterPresent(long window, CallbackInfo ci) {
		QaStamp.afterPresent();
	}
}

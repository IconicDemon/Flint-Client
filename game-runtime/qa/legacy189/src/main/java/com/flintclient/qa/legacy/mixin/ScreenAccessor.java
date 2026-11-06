package com.flintclient.qa.legacy.mixin;

import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A screen's input methods, called by QA steps as Minecraft would, and its buttons. */
@Mixin(Screen.class)
public interface ScreenAccessor {
	@Invoker("keyPressed")
	void flintqa$keyPressed(char chr, int key);

	@Invoker("mouseClicked")
	void flintqa$mouseClicked(int mouseX, int mouseY, int button);

	@Invoker("mouseReleased")
	void flintqa$mouseReleased(int mouseX, int mouseY, int button);

	@Invoker("mouseDragged")
	void flintqa$mouseDragged(int mouseX, int mouseY, int button, long duration);

	@Accessor("buttons")
	List<ButtonWidget> flintqa$buttons();
}

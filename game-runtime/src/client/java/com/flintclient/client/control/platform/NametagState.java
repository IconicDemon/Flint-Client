package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.ChatBubbles;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Room on a living thing's render state for Nametags' chat bubbles: the lines to show and where its nametag sits. */
public interface NametagState {
	List<ChatBubbles.Line> flint$bubbles();

	@Nullable Vec3 flint$bubbleAt();

	void flint$bubbles(List<ChatBubbles.Line> lines, @Nullable Vec3 at);
}

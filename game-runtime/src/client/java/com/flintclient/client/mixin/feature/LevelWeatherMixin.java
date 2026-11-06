package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Weather;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Weather Changer: the rain and thunder levels of the client's level, which its sky, fog, rain, snow, splashes and sounds
 * all read. The singleplayer server's level shares this class and is left alone.
 */
@Mixin(Level.class)
abstract class LevelWeatherMixin {
	@ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
	private float flint$rain(float level) {
		return (Object) this instanceof ClientLevel ? Mc263Weather.rain(level) : level;
	}

	@ModifyReturnValue(method = "getThunderLevel", at = @At("RETURN"))
	private float flint$thunder(float level) {
		return (Object) this instanceof ClientLevel ? Mc263Weather.thunder(level) : level;
	}
}

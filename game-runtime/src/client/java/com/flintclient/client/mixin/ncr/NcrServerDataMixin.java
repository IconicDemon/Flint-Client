package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports: whether a saved server prevents chat reports, kept in servers.dat as the mod keeps it.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerData.class)
abstract class NcrServerDataMixin implements Mc263ChatSafety.ServerFlag {
	@Unique
	private boolean flint$prevents;

	@Override
	public boolean flint$preventsReports() {
		return flint$prevents;
	}

	@Override
	public void flint$setPreventsReports(boolean prevents) {
		flint$prevents = prevents;
	}

	@Inject(method = "write", at = @At("RETURN"))
	private void flint$write(CallbackInfoReturnable<CompoundTag> callback) {
		callback.getReturnValue().putBoolean("preventsChatReports", flint$prevents);
	}

	@Inject(method = "read", at = @At("RETURN"))
	private static void flint$read(CompoundTag tag, CallbackInfoReturnable<ServerData> callback) {
		if (callback.getReturnValue() instanceof Mc263ChatSafety.ServerFlag flag) {
			flag.flint$setPreventsReports(tag.getBooleanOr("preventsChatReports", false));
		}
	}
}

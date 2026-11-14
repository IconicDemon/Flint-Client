package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import java.lang.reflect.Type;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports: a server status's "preventsChatReports" field, read as its JSON is turned into a status (the status drops it).
 * Minecraft 1.19.2 has no LenientJsonParser: its status packet reads the JSON with ServerStatus' own deserializer, on the same
 * thread that then answers the ping (NcrStatusListenerMixin). Gson calls the deserializer through its interface method (the
 * bridge to the typed one), whose descriptor names no Minecraft class and so needs no remapping.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerStatus.Serializer.class)
abstract class NcrLenientJsonParserMixin {
	@Inject(method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;Lcom/google/gson/JsonDeserializationContext;)Ljava/lang/Object;",
		at = @At("HEAD"), remap = false)
	private void flint$status(JsonElement json, Type type, JsonDeserializationContext context, CallbackInfoReturnable<Object> callback) {
		Mc263ChatSafety.parsedJson(json);
	}
}

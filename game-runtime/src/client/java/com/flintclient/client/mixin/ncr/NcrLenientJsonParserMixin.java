package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.google.gson.JsonElement;
import net.minecraft.util.LenientJsonParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports: a server status's "preventsChatReports" field, read as its JSON is parsed (the record drops it).
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(LenientJsonParser.class)
abstract class NcrLenientJsonParserMixin {
	@Inject(method = "parse(Ljava/lang/String;)Lcom/google/gson/JsonElement;", at = @At("RETURN"))
	private static void flint$status(String json, CallbackInfoReturnable<JsonElement> callback) {
		Mc263ChatSafety.parsedJson(callback.getReturnValue());
	}
}

package com.flintclient.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What Minecraft builds from the signed-in player when it starts, so the main menu's account card can switch accounts without
 * a restart (module.account.SessionSwitch): the user, the profile and properties it fetched, the user API service and what
 * was made from it (friends and blocking, telemetry, chat signing keys, reporting, Realms). Every Minecraft line Flint runs on
 * has these fields under these names.
 */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {
	@Mutable
	@Accessor("user")
	void flint$setUser(User user);

	@Mutable
	@Accessor("profileFuture")
	void flint$setProfileFuture(CompletableFuture<?> future);

	@Mutable
	@Accessor("userApiService")
	void flint$setUserApiService(UserApiService service);

	@Mutable
	@Accessor("userPropertiesFuture")
	void flint$setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

	@Mutable
	@Accessor("playerSocialManager")
	void flint$setPlayerSocialManager(PlayerSocialManager manager);

	@Accessor("telemetryManager")
	ClientTelemetryManager flint$telemetryManager();

	@Mutable
	@Accessor("telemetryManager")
	void flint$setTelemetryManager(ClientTelemetryManager manager);

	@Mutable
	@Accessor("profileKeyPairManager")
	void flint$setProfileKeyPairManager(ProfileKeyPairManager manager);

	@Accessor("reportingContext")
	void flint$setReportingContext(ReportingContext context);

	@Mutable
	@Accessor("realmsDataFetcher")
	void flint$setRealmsDataFetcher(RealmsDataFetcher fetcher);
}

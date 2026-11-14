package com.flintclient.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What Minecraft 1.19.2 builds from the signed-in player when it starts, so the main menu's account card can switch accounts
 * without a restart (module.account.SessionSwitch): the user, its profile's properties, the user API service and what was made
 * from it (blocking, chat signing keys, reporting, Realms). 1.19.2 has no profile or user-properties lookup to replace: it looks
 * the profile's properties up the first time they're asked for (emptied, they're looked up again for the new user), and its user
 * API service fetches its own properties as it's made. It makes telemetry for each connection from the user and the service, so
 * there is none to replace either.
 */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {
	@Mutable
	@Accessor("user")
	void flint$setUser(User user);

	@Accessor("profileProperties")
	PropertyMap flint$profileProperties();

	@Mutable
	@Accessor("userApiService")
	void flint$setUserApiService(UserApiService service);

	@Mutable
	@Accessor("playerSocialManager")
	void flint$setPlayerSocialManager(PlayerSocialManager manager);

	@Mutable
	@Accessor("profileKeyPairManager")
	void flint$setProfileKeyPairManager(ProfileKeyPairManager manager);

	@Accessor("reportingContext")
	void flint$setReportingContext(ReportingContext context);

	@Mutable
	@Accessor("realmsDataFetcher")
	void flint$setRealmsDataFetcher(RealmsDataFetcher fetcher);
}

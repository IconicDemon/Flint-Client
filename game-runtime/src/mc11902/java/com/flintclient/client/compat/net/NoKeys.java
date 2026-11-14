package com.flintclient.client.compat.net;

import com.mojang.authlib.minecraft.TelemetrySession;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.report.AbuseReportLimits;
import com.mojang.authlib.yggdrasil.request.AbuseReportRequest;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.Util;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.util.Signer;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.jspecify.annotations.Nullable;

/**
 * A key manager with no keys: 1.19.3's {@code ProfileKeyPairManager.EMPTY_KEY_MANAGER} on Minecraft 1.19.2, whose manager is a
 * class with no keyless twin. No Chat Reports hands it out while your client keeps its keys to itself (NcrMinecraftMixin), and an
 * account switch to an offline account installs it (SessionSwitch). 1.19.2's manager starts reading and fetching its keys as it is
 * made: this one is made over a key service that has none and says so at once, before any file is read or written (so nothing is
 * logged either), and it answers every question with "no key".
 */
public final class NoKeys extends ProfileKeyPairManager {
	/** Fails a key request at once, with an error ProfileKeyPairManager doesn't catch: its own fetch just ends, quietly. */
	private static final UserApiService NO_KEY_SERVICE = new UserApiService() {
		@Override
		public UserProperties properties() {
			return UserApiService.OFFLINE.properties();
		}

		@Override
		public boolean isBlockedPlayer(UUID id) {
			return false;
		}

		@Override
		public void refreshBlockList() {
		}

		@Override
		public TelemetrySession newTelemetrySession(Executor executor) {
			return TelemetrySession.DISABLED;
		}

		@Override
		public KeyPairResponse getKeyPair() {
			throw new IllegalStateException("Flint: no chat signing keys here");
		}

		@Override
		public void reportAbuse(AbuseReportRequest request) {
		}

		@Override
		public boolean canSendReports() {
			return false;
		}

		@Override
		public AbuseReportLimits getAbuseReportLimits() {
			return UserApiService.OFFLINE.getAbuseReportLimits();
		}
	};

	public static final ProfileKeyPairManager MANAGER = new NoKeys();

	private NoKeys() {
		// A key file that never exists ("flint-no-signing-keys/profilekeys/<nil>.json"): the manager finds none and asks the service.
		super(NO_KEY_SERVICE, Util.NIL_UUID, Path.of("flint-no-signing-keys"));
	}

	@Override
	public CompletableFuture<Optional<ProfilePublicKey.Data>> preparePublicKey() {
		return CompletableFuture.completedFuture(Optional.empty());
	}

	@Override
	public @Nullable Signer signer() {
		return null;
	}

	@Override
	public Optional<ProfilePublicKey> profilePublicKey() {
		return Optional.empty();
	}
}

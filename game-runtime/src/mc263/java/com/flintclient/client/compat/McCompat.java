package com.flintclient.client.compat;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.friends.FriendsOverlayScreen;
import net.minecraft.client.gui.screens.options.OnlineOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Minecraft 26.3's answers to what the shared code needs where versions differ: the shared sources call these, and each
 * Minecraft version Flint is built for has its own McCompat (src/mc&lt;version&gt;) with the same methods. Keep them small
 * and alike: one call each where the version's API allows.
 */
public final class McCompat {
	/** The Minecraft line this build is for. */
	public static final String MINECRAFT = "26.3";
	/** Whether a nametag can have a shaped background (rounded corners, a border). */
	public static final boolean SHAPED_NAMETAGS = true;
	/** Whether Minecraft has a friends list. */
	public static final boolean FRIENDS = true;
	/** The keyboard key type (26.3 keys keyboard keys by SDL scancode). */
	public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;
	/** The GPU pipeline type's internal name, for mixin descriptors (annotation values may use this constant). */
	public static final String RENDER_PIPELINE = "com/mojang/renderpearl/api/pipeline/RenderPipeline";
	/** The HUD's class: its internal name (for descriptors) and its name (for a mixin's targets). */
	public static final String HUD = "net/minecraft/client/gui/Hud";
	public static final String HUD_CLASS = "net.minecraft.client.gui.Hud";
	/** The class whose setScreen every screen passes through. */
	public static final String SCREENS_CLASS = "net.minecraft.client.gui.Gui";
	/** The class and method that extract the world for a frame. */
	public static final String LEVEL_EXTRACTOR_CLASS = "net.minecraft.client.renderer.extract.LevelExtractor";
	public static final String EXTRACT_LEVEL = "extract";
	/** LevelRenderer's method that draws the world and fetches the entity outline effect. */
	public static final String LEVEL_RENDER = "render";
	/** The pause menu's Disconnect button action. */
	public static final String PAUSE_DISCONNECT = "lambda$createPauseMenu$10";
	/** The call whose answer decides whether the player list shows heads. */
	public static final String TAB_HEADS = "Lnet/minecraft/client/multiplayer/ClientPacketListener;onlineMode()Z";
	/** LivingEntity's swing-duration method, for Animations' mixin. */
	public static final String SWING_DURATION = "getModifiedSwingDuration";
	/** Glint's tooltips: the call an item tooltip is set up with (26.3 adds a flag), the screens' tooltip method and the background's. */
	public static final String TOOLTIP_CALL = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;Z)V";
	public static final String CONTAINER_TOOLTIP = "extractTooltip";
	public static final String TOOLTIP_BACKGROUND = "extractTooltipBackground";
	/** Whether this Minecraft builds generated item models with gaps (1.21.10; 1.21.11 rebuilt them): Model Gap Fix's work. */
	public static final boolean ITEM_MODEL_GAPS = false;
	/** Whether this Minecraft has an offhand (1.9 and later), and the items Armor's slots look for that 1.8.9 lacks: shields, tridents, maces. */
	public static final boolean OFFHAND = true;
	/** Whether Armor's empty slots can show the outline of what goes there (Minecraft's container/slot sprites, 1.21.2 and later). */
	public static final boolean SLOT_OUTLINES = true;
	/** Chat Heads: ChatListener's delayed show of a disguised chat message, glyph text's sprite draw, a head's log text, the suggestions' draw. */
	public static final String DISGUISED_SHOW = "lambda$handleDisguisedChatMessage$0";
	public static final String TEXT_SPRITE = "Lnet/minecraft/client/gui/font/PlainTextRenderable;renderSprite(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;IFFFI)V";
	public static final String SPRITE_FALLBACK = "defaultFallback";
	public static final String SUGGESTIONS_DRAW = "extractRenderState";

	/** Whether ChatListener.handleSystemMessage's flag means the server sent it (26.x) rather than an action-bar message (1.21.x). */
	public static boolean systemFromServer(boolean flag) {
		return flag;
	}
	/** Zoom's relative view bobbing: where the camera reads the walking bob (26.x: the camera's render state). */
	public static final String ZOOM_BOB_CLASS = "net.minecraft.client.Camera";
	public static final String ZOOM_BOB_METHOD = "extractRenderState";
	/** Hurt Cam: the call that makes each of bobHurt's turns (26.3 turns the pose stack itself), and its angle argument. */
	public static final String HURT_TURN = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V";
	public static final int HURT_TURN_ANGLE = 1;
	/** WeatherEffectRenderer's method that lays out the rain and snow columns, for Weather Changer's mixin. */
	public static final String WEATHER_INSTANCES = "prepareInstances";
	/** F3's post-effect line (its id spelled out on a newer Minecraft without the constant, so the rest of this class still loads). */
	public static final Identifier DEBUG_POST_EFFECTS = debugPostEffects();
	private static @Nullable ByteBuffer keyboardState;

	private McCompat() {
	}

	private static Identifier debugPostEffects() {
		try {
			return DebugScreenEntries.POST_EFFECTS;
		} catch (LinkageError e) {
			return Identifier.withDefaultNamespace("post_effects");
		}
	}

	/** Minecraft's HUD. */
	public static Hud hud() {
		return Minecraft.getInstance().gui.hud;
	}

	public static Camera mainCamera() {
		return Minecraft.getInstance().gameRenderer.mainCamera();
	}

	/** The camera as this frame's level rendering sees it: projection, view rotation and position. */
	public static CameraRenderState cameraState() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
	}

	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	/** A team's colour as RGB, or {@code fallback} when it has none. */
	public static int teamRgb(PlayerTeam team, int fallback) {
		return team.getColor().map(TeamColor::rgb).orElse(fallback);
	}

	/** The sidebar slot a team's colour shows, or null when the team has no colour. */
	public static @Nullable DisplaySlot teamSidebar(PlayerTeam team) {
		return team.getColor().map(TeamColor::displaySlot).orElse(null);
	}

	public static Identifier mobEffectSprite(Holder<MobEffect> effect) {
		return Hud.getMobEffectSprite(effect);
	}

	public static EntityType<?> playerType() {
		return EntityTypes.PLAYER;
	}

	public static EntityType<?> zombieType() {
		return EntityTypes.ZOMBIE;
	}

	/** A nametag-style text at {@code at} ({@code distanceSq} is for versions that order tags by it). */
	public static void submitNameTag(SubmitNodeCollector collector, PoseStack pose, Vec3 at, int y, net.minecraft.network.chat.Component text,
		boolean seeThrough, int light, double distanceSq, CameraRenderState camera) {
		collector.submitNameTag(pose, at, y, text, seeThrough, light, camera);
	}

	/** Uploads rows [top, bottom) of {@code pixels} to {@code texture}: whole rows sit together in memory, so it's one write. */
	public static void writeRows(GpuTexture texture, NativeImage pixels, int top, int bottom) {
		int rowBytes = pixels.getWidth() * pixels.format().components();
		ByteBuffer rows = pixels.getPixelBytes().slice(top * rowBytes, (bottom - top) * rowBytes);
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, rows, 0, 0, 0, top, pixels.getWidth(), bottom - top);
	}

	/**
	 * Uploads all of {@code image} to ({@code x}, {@code y}) of {@code texture}: one write of that rectangle only (an animated
	 * cape's frame). Between frames, never inside a render pass.
	 */
	public static void writeImage(net.minecraft.client.renderer.texture.DynamicTexture texture, NativeImage image, int x, int y) {
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), image, 0, 0, x, y);
	}

	/** Opens Minecraft's friends list over {@code parent} (after its online-options check). */
	public static void openFriends(Minecraft minecraft, Screen parent) {
		OnlineOptionsScreen.confirmFriendsListEnabled(minecraft, () -> minecraft.gui.setScreen(new FriendsOverlayScreen(parent)), parent);
	}

	/** How many friend requests are waiting (0 while the friends list is off). */
	public static int friendRequests(Minecraft minecraft) {
		var social = minecraft.getPlayerSocialManager();
		return social.isFriendListEnabled() ? social.getIncomingRequests().size() : 0;
	}

	/**
	 * Whether the keyboard key with this code is held, as {@code InputConstants.isKeyDown} answers. That method asks SDL
	 * for its key state array on every call, and LWJGL wraps the answer in new buffer objects each time; SDL's array is a
	 * fixed internal one that lives as long as the program, so this asks once and keeps the view, and per-frame reads
	 * allocate nothing.
	 */
	public static boolean keyDown(int scancode) {
		ByteBuffer state = keyboardState;
		if (state == null) {
			state = SDLKeyboard.SDL_GetKeyboardState();
			if (state == null) {
				return false;
			}
			keyboardState = state;
		}
		return scancode >= 0 && scancode < state.capacity() && state.get(scancode) != 0;
	}

	/** The character a key types as a shortcut (layout-aware): 'Z' for Ctrl+Z. */
	public static int shortcutKey(KeyEvent event) {
		return event.shortcutKey();
	}

	/** Opens a file or folder the way the system does (its file browser, its image viewer). */
	public static void openPath(Path path) {
		Blaze3D.openPath(path);
	}

	/** Opens a web address in the system's browser, from the game (which is in front, so the browser comes forward). */
	public static void openUri(String uri) {
		Blaze3D.openUri(java.net.URI.create(uri));
	}

	/** Minecraft's Options screen, returning to {@code parent}. */
	public static Screen optionsScreen(Screen parent, Options options) {
		return new OptionsScreen(parent, options);
	}

	/** A linear, clamp-to-edge sampler, for a texture's {@code sampler} field (its type is the version's own). */
	@SuppressWarnings("unchecked")
	public static <T> T linearClampSampler() {
		return (T) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	/** A linear, repeating sampler, for a texture's {@code sampler} field. */
	@SuppressWarnings("unchecked")
	public static <T> T linearRepeatSampler() {
		return (T) RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
	}

	public static void rotate(PoseStack pose, Axis axis, float radians) {
		pose.rotate(axis, radians);
	}

	public static void rotateDegrees(PoseStack pose, Axis axis, float degrees) {
		pose.rotateDegrees(axis, degrees);
	}

	public static void rotate(PoseStack pose, Quaternionfc rotation) {
		pose.rotate(rotation);
	}

	/** A bundle's items, as copies. */
	public static Stream<ItemStack> bundleItems(BundleContents bundle) {
		return bundle.itemCopies();
	}

	/** A container's slots, as copies (empty slots included). */
	public static Stream<ItemStack> containerItems(ItemContainerContents contents) {
		return contents.itemCopies();
	}

	/** A pack's pack.png, read, or null when it has none. */
	public static @Nullable NativeImage packIcon(Pack pack) throws Exception {
		try (PackMetadataResources resources = pack.openMetadata()) {
			IoSupplier<InputStream> png = resources.getRootResource("pack.png");
			if (png == null) {
				return null;
			}
			try (InputStream in = png.get()) {
				return NativeImage.read(in);
			}
		}
	}

	public static AdvancementTree advancementTree(ClientAdvancements advancements) {
		return advancements.tree();
	}

	public static Map<AdvancementHolder, AdvancementProgress> advancementProgress(ClientAdvancements advancements) {
		return advancements.progress();
	}

	public static net.minecraft.network.chat.Component title(DisplayInfo display) {
		return display.title();
	}

	public static net.minecraft.network.chat.Component description(DisplayInfo display) {
		return display.description();
	}

	public static ItemStackTemplate icon(DisplayInfo display) {
		return display.icon();
	}

	public static boolean hidden(DisplayInfo display) {
		return display.hidden();
	}

	public static AdvancementType type(DisplayInfo display) {
		return display.type();
	}

	/**
	 * The maps: the texture a block shows from above (its model's top face, else any face, else its particle texture), as the
	 * sprite's name; {@code tinted[0]} says whether that face takes a tint (grass, leaves, water). Null when it has none.
	 */
	public static @Nullable Identifier mapSprite(net.minecraft.world.level.block.state.BlockState state, boolean[] tinted) {
		var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new java.util.ArrayList<>();
		model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
		net.minecraft.client.resources.model.geometry.BakedQuad any = null;
		for (var part : parts) {
			for (var quad : part.getQuads(net.minecraft.core.Direction.UP)) {
				tinted[0] = quad.materialInfo().isTinted();
				return quad.materialInfo().sprite().contents().name();
			}
			for (var quad : part.getQuads(null)) {
				if (quad.direction() == net.minecraft.core.Direction.UP) {
					tinted[0] = quad.materialInfo().isTinted();
					return quad.materialInfo().sprite().contents().name();
				}
				if (any == null) {
					any = quad;
				}
			}
		}
		if (any != null) {
			tinted[0] = any.materialInfo().isTinted();
			return any.materialInfo().sprite().contents().name();
		}
		tinted[0] = false;
		var particle = model.particleMaterial();
		return particle == null ? null : particle.sprite().contents().name();
	}

	/** The maps: the tint Minecraft gives a block where it stands (grass, foliage and water by biome), or -1 for none. */
	public static int mapTint(net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.multiplayer.ClientLevel level,
		net.minecraft.core.BlockPos pos) {
		var source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
		return source == null ? -1 : source.colorInWorld(state, level, pos);
	}

	/** A model drawn again in one tint (no outline). */
	public static <S> void submitTinted(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack pose, RenderType type, int light,
		int overlay, int tint) {
		collector.submitModel(model, state, pose, type, light, overlay, tint, null, 0);
	}

	/**
	 * The cape-derived elytra (control/platform/Mc263CapeElytra): the elytra model with the cape texture in a back-face-culled
	 * cut-out pass (entityCutoutCull), then the glint pass when the elytra is enchanted (trimmedArmorGlint), as the elytra layer orders its own.
	 */
	public static <S> void submitCapeWings(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack pose, Identifier texture,
		int light, int outline, boolean foil) {
		collector.order(0).submitModel(model, state, pose, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutCull(texture), light,
			net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, -1, null, outline);
		if (foil) {
			collector.order(1).submitModel(model, state, pose, net.minecraft.client.renderer.rendertype.RenderTypes.trimmedArmorGlint(), light,
				net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, -1, null, 0);
		}
	}

	// ---------- Custom F3 (BetterF3): what its lines read that each version keeps elsewhere ----------

	/** "C: rendered/total (s) D: view distance, …", as the debug screen's chunk line gives it (null without a world). */
	public static @Nullable String f3SectionStats() {
		return Minecraft.getInstance().levelExtractor.sectionStatistics();
	}

	/** "E: rendered/total, SD: …", as the debug screen's entity line gives it (null without a world). */
	public static @Nullable String f3EntityStats() {
		return Minecraft.getInstance().levelExtractor.entityStatistics();
	}

	/** Chunk sections waiting to be compiled, waiting to go to the GPU, and free buffers; null without a world. */
	public static int @Nullable [] f3SectionQueues() {
		net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher = Minecraft.getInstance().levelRenderer.sectionRenderDispatcher();
		if (dispatcher == null) {
			return null;
		}
		return new int[] {dispatcher.getCompileQueueSize(), dispatcher.getCompileQueueSize(), dispatcher.getFreeBufferCount()};
	}

	/** The sound engine's "Sounds: playing/max + playing/max" line. */
	public static String f3SoundStats() {
		return Minecraft.getInstance().getSoundManager().getChannelDebugString();
	}

	/** The post effects (shaders) applied now, comma separated, or "". */
	public static String f3PostEffects() {
		java.util.List<Identifier> effects = Minecraft.getInstance().gameRenderer.getAppliedPostEffects();
		return effects.stream().map(Identifier::toString).collect(java.util.stream.Collectors.joining(", "));
	}

	/** The graphics preset, named as BetterF3 names it ("FANCY", "CUSTOM"). */
	public static String f3Graphics() {
		return org.apache.commons.lang3.StringUtils.capitalize(Minecraft.getInstance().options.graphicsPreset().get().toString());
	}

	/** Minecraft's graphics option (the preset: Fast, Fancy, Fabulous, Custom), for the menu's Graphics tab. */
	public static net.minecraft.client.OptionInstance<?> graphicsOption() {
		return Minecraft.getInstance().options.graphicsPreset();
	}

	/** The texture filtering options as one number (a change needs a texture reload, as mipmaps do). */
	public static int textureFiltering() {
		net.minecraft.client.Options options = Minecraft.getInstance().options;
		return options.maxAnisotropyBit().get() * 31 + options.textureFiltering().get().ordinal();
	}

	/** The overworld clock: day ticks and days played come from it. */
	public static long f3Clock(net.minecraft.world.level.Level level) {
		return level.getOverworldClockTime();
	}

	/** How bright the moon is over {@code pos} (local difficulty grows with it). */
	public static float f3MoonBrightness(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		net.minecraft.world.level.MoonPhase phase = level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.MOON_PHASE, pos);
		return net.minecraft.world.level.dimension.DimensionType.MOON_BRIGHTNESS_PER_PHASE[phase.index()];
	}

	/** The graphics card: name, vendor, graphics API and driver. */
	public static String[] f3Gpu() {
		com.mojang.renderpearl.api.device.DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
		return new String[] {info.name(), info.vendorName(), info.backendName(), info.driverInfo()};
	}

	public static String f3Cpu() {
		return net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs.getCpuInfo();
	}

	/** The width the debug screen lays its columns out in (its own GUI scale may be smaller than the game's). */
	public static int f3Width(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
		int standard = window.getGuiScale();
		int scale = minecraft.options.debugGuiScale().get();
		if (scale == -1) {
			scale = standard;
		} else if (scale == 0) {
			scale = window.calculateScale(0, minecraft.isEnforceUnicode()) / 2;
		} else {
			scale = window.calculateScale(scale, minecraft.isEnforceUnicode());
		}
		return scale < standard && scale > 0 ? window.getWidth() / scale : graphics.guiWidth();
	}

	// ---------- Saturation, No Chat Reports ----------

	/** Natural health regeneration in a world you host (its game rule). */
	public static boolean naturalRegeneration(net.minecraft.server.level.ServerLevel level) {
		return level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION);
	}

	/** Whether the server you're on runs in online mode (its login packet says). */
	public static boolean onlineMode(net.minecraft.client.multiplayer.ClientPacketListener listener) {
		return listener.onlineMode();
	}

	/** A chat packet's signature when it has none (an empty Optional here). */
	public static @Nullable Object noSignature() {
		return java.util.Optional.empty();
	}

	/** A player chat packet's unsigned (server-decorated) content, or null. */
	public static net.minecraft.network.chat.@Nullable Component unsignedContent(net.minecraft.network.protocol.game.ClientboundPlayerChatPacket packet) {
		return packet.unsignedContent().orElse(null);
	}

	/** authlib's user API service, whose telemetry sessions No Chat Reports switches off. */
	public static final String USER_API_SERVICE_CLASS = "com.mojang.authlib.services.MinecraftServicesUserApiService";

	// ---------- Animations (Not Enough Animations) ----------

	/** Whether {@code entity} is swinging an arm. */
	public static boolean swinging(net.minecraft.world.entity.LivingEntity entity) {
		return entity.isSwinging();
	}

	/** The hand {@code entity} swings (the main hand when it isn't swinging). */
	public static net.minecraft.world.InteractionHand swingHand(net.minecraft.world.entity.LivingEntity entity) {
		net.minecraft.world.entity.LivingEntity.SwingDescription swing = entity.getCurrentSwing();
		return swing == null ? net.minecraft.world.InteractionHand.MAIN_HAND : swing.hand();
	}

	/** How far through its attack swing a player's render state is (0 to 1). */
	public static float attackTime(net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		return state.swingAnimation;
	}

	/** Whether an item is used as a spear (it then takes the spear pose). */
	public static boolean spearUse(net.minecraft.world.item.ItemUseAnimation use) {
		return use == net.minecraft.world.item.ItemUseAnimation.SPEAR;
	}

	/** The spear pose for a spear in use, else null. */
	public static net.minecraft.client.model.HumanoidModel.@Nullable ArmPose spearPose(net.minecraft.world.item.ItemUseAnimation use) {
		return use == net.minecraft.world.item.ItemUseAnimation.SPEAR ? net.minecraft.client.model.HumanoidModel.ArmPose.SPEAR : null;
	}

	/** Whether an arm pose is the spear's. */
	public static boolean spearArmPose(net.minecraft.client.model.HumanoidModel.ArmPose pose) {
		return pose == net.minecraft.client.model.HumanoidModel.ArmPose.SPEAR;
	}

	/** Submits a block's model at the pose (a held lantern). */
	public static void submitBlock(PoseStack pose, SubmitNodeCollector collector, net.minecraft.world.level.block.state.BlockState state, int light) {
		Minecraft minecraft = Minecraft.getInstance();
		if (blockModels == null || blockModelsFor != minecraft.getModelManager()) {
			blockModelsFor = minecraft.getModelManager();
			blockModels = new net.minecraft.client.renderer.block.BlockModelResolver(blockModelsFor);
		}
		net.minecraft.client.renderer.block.BlockModelRenderState render = new net.minecraft.client.renderer.block.BlockModelRenderState();
		blockModels.update(render, state, net.minecraft.client.renderer.block.model.BlockDisplayContext.create());
		render.submit(pose, collector, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
	}

	private static net.minecraft.client.renderer.block.@Nullable BlockModelResolver blockModels;
	private static net.minecraft.client.resources.model.@Nullable ModelManager blockModelsFor;

	// ---------- switching accounts (module.account.SessionSwitch) ----------

	/** Minecraft's signed-in user for a session from Flint. */
	public static net.minecraft.client.User newUser(String name, java.util.UUID id, String token, java.util.Optional<String> xuid,
		java.util.Optional<String> clientId) {
		return new net.minecraft.client.User(name, id, token, xuid, clientId);
	}

	/** Minecraft's services, as it finds them at start-up (online, or offline for an account without a sign-in). */
	private static com.mojang.authlib.services.MinecraftServicesDiscoveryService discovery(Minecraft minecraft, boolean offline) {
		return com.mojang.authlib.services.MinecraftServicesDiscoveryService.create(minecraft.getProxy(), !offline);
	}

	/** The user API service for a token, as Minecraft makes it at start-up. */
	public static com.mojang.authlib.minecraft.UserApiService userApiService(Minecraft minecraft, String token) throws Exception {
		return discovery(minecraft, false).createUserApiService(token);
	}

	/** The account's profile (name, skin), fetched as Minecraft does at start-up; null when the services don't know it. */
	public static com.mojang.authlib.@Nullable GameProfile fetchProfile(Minecraft minecraft, java.util.UUID id) {
		var result = minecraft.services().sessionService().fetchProfile(id, true);
		return result == null ? null : result.profile();
	}

	/** Minecraft's profile lookup for the signed-in user, as it starts it at start-up. */
	public static java.util.concurrent.CompletableFuture<?> profileFuture(Minecraft minecraft, java.util.UUID id, boolean offline) {
		return offline ? java.util.concurrent.CompletableFuture.completedFuture(null)
			: java.util.concurrent.CompletableFuture.supplyAsync(() -> minecraft.services().sessionService().fetchProfile(id, true),
				net.minecraft.util.Util.nonCriticalIoPool());
	}

	/**
	 * The friends list and social manager for the new user: the old account goes offline for its friends and its friend list
	 * updates stop; the new ones start as Minecraft starts them.
	 */
	public static void replaceSocial(Minecraft minecraft, com.mojang.authlib.minecraft.UserApiService api, String token, boolean offline) {
		var accessor = (com.flintclient.client.mixin.bridge.MinecraftFriendsAccessor) minecraft;
		try {
			minecraft.getPlayerSocialManager().getPresenceHandler().sendOfflinePresence();
		} catch (RuntimeException ignored) {
			// The old account's presence just times out on Mojang's side.
		}
		accessor.flint$friendListUpdates().close();
		var friends = discovery(minecraft, offline).createFriendsService(token);
		var updates = new net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler(friends, minecraft);
		accessor.flint$setFriendListUpdates(updates);
		var social = new net.minecraft.client.gui.screens.social.PlayerSocialManager(minecraft, api, friends, updates);
		((com.flintclient.client.mixin.MinecraftSessionAccessor) minecraft).flint$setPlayerSocialManager(social);
		if (social.isFriendListEnabled()) {
			updates.start();
		}
	}

	/** A Realms client for the signed-in user (Minecraft keeps one per run; the old user's is dropped first). */
	public static com.mojang.realmsclient.client.RealmsClient realmsClient(Minecraft minecraft) {
		com.flintclient.client.module.account.SessionSwitch.forgetRealmsClient();
		return com.mojang.realmsclient.client.RealmsClient.getOrCreate(minecraft);
	}
}

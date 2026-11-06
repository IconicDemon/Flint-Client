package com.flintclient.client.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.InputStream;
import java.lang.reflect.Field;
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
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.1's answers to what the shared code needs where versions differ (see the 26.3 McCompat for each). Like
 * 26.2, 26.1 keys keyboard keys by GLFW key code, keeps its GPU types in com.mojang.blaze3d, opens files through Util's
 * platform, rotates poses with mulPose, and names a few accessors the older way (getTree, getIcon, isHidden, getType).
 * On top of that, 26.1's {@code Minecraft.gui} is the HUD itself (26.2 split it into {@code Gui} for screens and
 * {@code Gui.hud}): the screen methods and isHidden come from {@link GuiBridge}, and {@link #hud} is {@code gui}.
 */
public final class McCompat {
	public static final String MINECRAFT = "26.1";
	/** Whether a nametag can have a shaped background (rounded corners, a border): 26.1 draws only its plain one. */
	public static final boolean SHAPED_NAMETAGS = false;
	/** Whether Minecraft has a friends list (26.2 added it). */
	public static final boolean FRIENDS = false;
	public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;
	/** The GPU pipeline type's internal name, for mixin descriptors (annotation values may use this constant). */
	public static final String RENDER_PIPELINE = "com/mojang/blaze3d/pipeline/RenderPipeline";
	/** The HUD's class: its internal name (for descriptors) and its name (for a mixin's targets); 26.1's is Gui. */
	public static final String HUD = "net/minecraft/client/gui/Gui";
	public static final String HUD_CLASS = "net.minecraft.client.gui.Gui";
	/** The class whose setScreen every screen passes through (26.1: Minecraft itself). */
	public static final String SCREENS_CLASS = "net.minecraft.client.Minecraft";
	/** The class and method that extract the world for a frame (26.1: LevelRenderer.extractLevel). */
	public static final String LEVEL_EXTRACTOR_CLASS = "net.minecraft.client.renderer.LevelRenderer";
	public static final String EXTRACT_LEVEL = "extractLevel";
	/** LevelRenderer's method that draws the world and fetches the entity outline effect. */
	public static final String LEVEL_RENDER = "renderLevel";
	/** The pause menu's Disconnect button action. */
	public static final String PAUSE_DISCONNECT = "lambda$createPauseMenu$6";
	/** The call whose answer decides whether the player list shows heads (26.1 also shows them in a local world). */
	public static final String TAB_HEADS = "Lnet/minecraft/network/Connection;isEncrypted()Z";
	public static final String SWING_DURATION = "getCurrentSwingDuration";
	/** Glint's tooltips: the call an item tooltip is set up with, the screens' tooltip method and the background's. */
	public static final String TOOLTIP_CALL = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V";
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
	/** Hurt Cam: the call that makes each of bobHurt's turns (Axis.rotationDegrees here), and its angle argument. */
	public static final String HURT_TURN = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;";
	public static final int HURT_TURN_ANGLE = 0;
	public static final String WEATHER_INSTANCES = "renderInstances";
	public static final Identifier DEBUG_POST_EFFECTS = DebugScreenEntries.POST_EFFECT;
	/** ClientAdvancements' progress map: 26.1 has no getter for it (26.3 added one), and its names aren't obfuscated. */
	private static @Nullable Field progress;

	private McCompat() {
	}

	/** The HUD (26.1: {@code Minecraft.gui} itself). */
	public static Gui hud() {
		return Minecraft.getInstance().gui;
	}

	public static Camera mainCamera() {
		return Minecraft.getInstance().gameRenderer.getMainCamera();
	}

	/** The camera as this frame's level rendering sees it: projection, view rotation and position. */
	public static CameraRenderState cameraState() {
		return Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
	}

	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().getMainRenderTarget();
	}

	/** A team's colour as RGB, or {@code fallback} when it has none (26.1 colours teams with a ChatFormatting). */
	public static int teamRgb(PlayerTeam team, int fallback) {
		Integer rgb = team.getColor().getColor();
		return rgb != null ? rgb : fallback;
	}

	/** The sidebar slot a team's colour shows, or null when the team has no colour. */
	public static @Nullable DisplaySlot teamSidebar(PlayerTeam team) {
		return DisplaySlot.teamColorToSlot(team.getColor());
	}

	public static Identifier mobEffectSprite(Holder<MobEffect> effect) {
		return Gui.getMobEffectSprite(effect);
	}

	public static EntityType<?> playerType() {
		return EntityType.PLAYER;
	}

	public static EntityType<?> zombieType() {
		return EntityType.ZOMBIE;
	}

	/** A nametag-style text at {@code at} (26.1 also takes the distance, which orders the tags). */
	public static void submitNameTag(SubmitNodeCollector collector, PoseStack pose, Vec3 at, int y, net.minecraft.network.chat.Component text,
		boolean seeThrough, int light, double distanceSq, CameraRenderState camera) {
		collector.submitNameTag(pose, at, y, text, seeThrough, light, distanceSq, camera);
	}

	/** Uploads rows [top, bottom) of {@code pixels} to {@code texture}: one write, read straight from the image. */
	public static void writeRows(GpuTexture texture, NativeImage pixels, int top, int bottom) {
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, top, pixels.getWidth(), bottom - top, 0, top);
	}

	/**
	 * Uploads all of {@code image} to ({@code x}, {@code y}) of {@code texture}: one write of that rectangle only (an animated
	 * cape's frame). Between frames, never inside a render pass.
	 */
	public static void writeImage(net.minecraft.client.renderer.texture.DynamicTexture texture, NativeImage image, int x, int y) {
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.getTexture(), image, 0, 0, x, y, image.getWidth(),
			image.getHeight(), 0, 0);
	}

	/** Opens Minecraft's friends list: 26.1 has none (the button is off, {@link #FRIENDS}). */
	public static void openFriends(Minecraft minecraft, Screen parent) {
	}

	public static int friendRequests(Minecraft minecraft) {
		return 0;
	}

	/** Whether the keyboard key with this GLFW code is held (GLFW keeps the state; reading it allocates nothing). */
	public static boolean keyDown(int key) {
		return key >= 0 && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key);
	}

	/** The character a key types as a shortcut: GLFW's codes for letters and brackets are their ASCII ('Z', '['). */
	public static int shortcutKey(KeyEvent event) {
		return event.key();
	}

	public static void openPath(Path path) {
		Util.getPlatform().openPath(path);
	}

	/** Opens a web address in the system's browser, from the game (which is in front, so the browser comes forward). */
	public static void openUri(String uri) {
		Util.getPlatform().openUri(java.net.URI.create(uri));
	}

	public static Screen optionsScreen(Screen parent, Options options) {
		return new OptionsScreen(parent, options, false);
	}

	@SuppressWarnings("unchecked")
	public static <T> T linearClampSampler() {
		return (T) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	@SuppressWarnings("unchecked")
	public static <T> T linearRepeatSampler() {
		return (T) RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
	}

	public static void rotate(PoseStack pose, Axis axis, float radians) {
		pose.mulPose(axis.rotation(radians));
	}

	public static void rotateDegrees(PoseStack pose, Axis axis, float degrees) {
		pose.mulPose(axis.rotationDegrees(degrees));
	}

	public static void rotate(PoseStack pose, Quaternionfc rotation) {
		pose.mulPose(rotation);
	}

	public static Stream<ItemStack> bundleItems(BundleContents bundle) {
		return bundle.itemCopyStream();
	}

	public static Stream<ItemStack> containerItems(ItemContainerContents contents) {
		return contents.allItemsCopyStream();
	}

	public static @Nullable NativeImage packIcon(Pack pack) throws Exception {
		try (PackResources resources = pack.open()) {
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
		return advancements.getTree();
	}

	@SuppressWarnings("unchecked")
	public static Map<AdvancementHolder, AdvancementProgress> advancementProgress(ClientAdvancements advancements) {
		try {
			Field field = progress;
			if (field == null) {
				field = ClientAdvancements.class.getDeclaredField("progress");
				field.setAccessible(true);
				progress = field;
			}
			return (Map<AdvancementHolder, AdvancementProgress>) field.get(advancements);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("ClientAdvancements.progress", e);
		}
	}

	public static net.minecraft.network.chat.Component title(DisplayInfo display) {
		return display.getTitle();
	}

	public static net.minecraft.network.chat.Component description(DisplayInfo display) {
		return display.getDescription();
	}

	public static ItemStackTemplate icon(DisplayInfo display) {
		return display.getIcon();
	}

	public static boolean hidden(DisplayInfo display) {
		return display.isHidden();
	}

	public static AdvancementType type(DisplayInfo display) {
		return display.getType();
	}

	public static <S> void submitTinted(SubmitNodeCollector collector, Model<? super S> model, S state, PoseStack pose, RenderType type, int light,
		int overlay, int tint) {
		collector.submitModel(model, state, pose, type, light, overlay, tint, null, 0, null);
	}

	// ---------- Custom F3 (BetterF3): what its lines read that each version keeps elsewhere ----------

	/** "C: rendered/total (s) D: view distance, …", as the debug screen's chunk line gives it (null without a world). */
	public static @Nullable String f3SectionStats() {
		return Minecraft.getInstance().levelRenderer.getSectionStatistics();
	}

	/** "E: rendered/total, SD: …", as the debug screen's entity line gives it (null without a world). */
	public static @Nullable String f3EntityStats() {
		return Minecraft.getInstance().levelRenderer.getEntityStatistics();
	}

	/** Chunk sections waiting to be compiled, waiting to go to the GPU, and free buffers; null without a world. */
	public static int @Nullable [] f3SectionQueues() {
		net.minecraft.client.renderer.chunk.SectionRenderDispatcher dispatcher = Minecraft.getInstance().levelRenderer.getSectionRenderDispatcher();
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
		Identifier effect = Minecraft.getInstance().gameRenderer.currentPostEffect();
		return effect == null ? "" : effect.toString();
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
		com.mojang.blaze3d.systems.GpuDevice device = RenderSystem.getDevice();
		return new String[] {device.getRenderer(), device.getVendor(), device.getBackendName(), device.getVersion()};
	}

	public static String f3Cpu() {
		return com.mojang.blaze3d.platform.GLX._getCpuInfo();
	}

	/** The width the debug screen lays its columns out in. */
	public static int f3Width(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
		return graphics.guiWidth();
	}

	/**
	 * The maps: the texture a block shows from above (its model's top face, else any face, else its particle texture), as the
	 * sprite's name; {@code tinted[0]} says whether that face takes a tint (grass, leaves, water). Null when it has none.
	 */
	public static net.minecraft.resources.@org.jspecify.annotations.Nullable Identifier mapSprite(net.minecraft.world.level.block.state.BlockState state,
		boolean[] tinted) {
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

	// ---------- Saturation, No Chat Reports ----------

	/** Natural health regeneration in a world you host (its game rule). */
	public static boolean naturalRegeneration(net.minecraft.server.level.ServerLevel level) {
		return level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION);
	}

	/** Whether the server you're on runs in online mode (only online servers encrypt the connection). */
	public static boolean onlineMode(net.minecraft.client.multiplayer.ClientPacketListener listener) {
		return listener.getConnection().isEncrypted();
	}

	/** A chat packet's signature when it has none (null here). */
	public static @Nullable Object noSignature() {
		return null;
	}

	/** A player chat packet's unsigned (server-decorated) content, or null. */
	public static net.minecraft.network.chat.@Nullable Component unsignedContent(net.minecraft.network.protocol.game.ClientboundPlayerChatPacket packet) {
		return packet.unsignedContent();
	}

	/** authlib's user API service, whose telemetry sessions No Chat Reports switches off. */
	public static final String USER_API_SERVICE_CLASS = "com.mojang.authlib.yggdrasil.YggdrasilUserApiService";

	// ---------- Animations (Not Enough Animations) ----------

	/** Whether {@code entity} is swinging an arm. */
	public static boolean swinging(net.minecraft.world.entity.LivingEntity entity) {
		return entity.swinging;
	}

	/** The hand {@code entity} swings (the main hand when it isn't swinging). */
	public static net.minecraft.world.InteractionHand swingHand(net.minecraft.world.entity.LivingEntity entity) {
		return entity.swingingArm == null ? net.minecraft.world.InteractionHand.MAIN_HAND : entity.swingingArm;
	}

	/** How far through its attack swing a player's render state is (0 to 1). */
	public static float attackTime(net.minecraft.client.renderer.entity.state.AvatarRenderState state) {
		return state.attackTime;
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

	/** The user API service for a token, as Minecraft makes it at start-up. */
	public static com.mojang.authlib.minecraft.UserApiService userApiService(Minecraft minecraft, String token) throws Exception {
		return new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(minecraft.getProxy()).createUserApiService(token);
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

	/** The social manager (blocking, hiding) for the new user's API service. */
	public static void replaceSocial(Minecraft minecraft, com.mojang.authlib.minecraft.UserApiService api, String token, boolean offline) {
		((com.flintclient.client.mixin.MinecraftSessionAccessor) minecraft)
			.flint$setPlayerSocialManager(new net.minecraft.client.gui.screens.social.PlayerSocialManager(minecraft, api));
	}

	/** A Realms client for the signed-in user (Minecraft keeps one per run; the old user's is dropped first). */
	public static com.mojang.realmsclient.client.RealmsClient realmsClient(Minecraft minecraft) {
		com.flintclient.client.module.account.SessionSwitch.forgetRealmsClient();
		return com.mojang.realmsclient.client.RealmsClient.getOrCreate(minecraft);
	}
}

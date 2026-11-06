package com.flintclient.client.compat;

import com.flintclient.client.compat.input.KeyEvent;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerTeam;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * Minecraft 1.21.1's answers to what the shared code needs where versions differ (see the 26.3 McCompat for each). It
 * follows the 1.21.10 and 1.21.11 lines (Gui is the HUD; private members through accessor mixins in {@code mixin.bridge};
 * the shared sources compiled from an adapted copy, {@code src/mc12101/shared-sources.rules}), with what 1.21.1 lacks
 * bridged: GuiGraphics ({@link GraphicsBridge}), input events ({@code compat.input}), GPU textures (plain GL textures here).
 * Features that can't exist on 1.21.1 are switched off with the reason on their page ({@link #unavailable}).
 */
public final class McCompat {
	public static final String MINECRAFT = "1.21.1";
	/** Whether a nametag can have a shaped background (rounded corners, a border): 1.21.1 draws only its plain one. */
	public static final boolean SHAPED_NAMETAGS = false;
	/** Whether Minecraft has a friends list (26.2 added it). */
	public static final boolean FRIENDS = false;
	public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;
	/** 26.x's GPU pipeline type's internal name, for mixin descriptors (1.21.1 has none; kept for the shared constants). */
	public static final String RENDER_PIPELINE = "com/mojang/blaze3d/pipeline/RenderPipeline";
	/** The HUD's class: its internal name (for descriptors) and its name (for a mixin's targets); 1.21.1's is Gui. */
	public static final String HUD = "net/minecraft/client/gui/Gui";
	public static final String HUD_CLASS = "net.minecraft.client.gui.Gui";
	/** The class whose setScreen every screen passes through (1.21.1: Minecraft itself). */
	public static final String SCREENS_CLASS = "net.minecraft.client.Minecraft";
	/** LevelRenderer's method that draws the world and fetches the entity outline effect. */
	public static final String LEVEL_RENDER = "renderLevel";
	/** The pause menu's Disconnect button action (1.21.1: the lambda that offers a report draft first). */
	public static final String PAUSE_DISCONNECT = "method_19836";
	/** The call whose answer decides whether the player list shows heads. */
	public static final String TAB_HEADS = "Lnet/minecraft/network/Connection;isEncrypted()Z";
	public static final String SWING_DURATION = "getCurrentSwingDuration";
	/** Whether this Minecraft builds generated item models with gaps (1.21.10 and before; 1.21.11 rebuilt them). */
	public static final boolean ITEM_MODEL_GAPS = true;
	/** Whether this Minecraft has an offhand (1.9 and later), and the items Armor's slots look for that 1.8.9 lacks: shields, tridents, maces. */
	public static final boolean OFFHAND = true;
	/** Whether Armor's empty slots can show the outline of what goes there (1.21.1 keeps those outlines as item textures, not GUI sprites). */
	public static final boolean SLOT_OUTLINES = false;
	/** Chat Heads: ChatListener's delayed show of a disguised chat message, the command suggestions' draw. */
	public static final String DISGUISED_SHOW = "method_45745";
	public static final String SUGGESTIONS_DRAW = "render";

	/** Whether ChatListener.handleSystemMessage's flag means the server sent it (26.x) rather than an action-bar message (1.21.x). */
	public static boolean systemFromServer(boolean flag) {
		return !flag;
	}

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
	public static float attackTime(com.flintclient.client.compat.state.AvatarRenderState state) {
		return state.attackTime;
	}

	/** Whether an item is used as a spear: 1.21.1 has no spear (its SPEAR use is the trident's). */
	public static boolean spearUse(net.minecraft.world.item.UseAnim use) {
		return false;
	}

	/** The spear pose for a spear in use: none on 1.21.1. */
	public static net.minecraft.client.model.HumanoidModel.@Nullable ArmPose spearPose(net.minecraft.world.item.UseAnim use) {
		return null;
	}

	/** Whether an arm pose is the spear's: none on 1.21.1. */
	public static boolean spearArmPose(net.minecraft.client.model.HumanoidModel.ArmPose pose) {
		return false;
	}

	/** Zoom's relative view bobbing: where the walking bob is read (1.21.x: GameRenderer.bobView itself). */
	public static final String ZOOM_BOB_CLASS = "net.minecraft.client.renderer.GameRenderer";
	public static final String ZOOM_BOB_METHOD = "bobView";
	public static final String WEATHER_INSTANCES = "renderSnowAndRain";

	private McCompat() {
	}

	// ---------- what 1.21.1 can't have ----------

	private static final String NO_RENDER_STATES = "Minecraft 1.21.1 draws entities straight from the game, without the render states this mod works on (they came in 1.21.2)";
	/** Why the module {@code id} can't work on this Minecraft, or null when it can ({@link ModuleAvailability}). */
	public static @Nullable String unavailable(String id) {
		return ModuleAvailability.unavailable(id);
	}

	/**
	 * Why {@code setting} can't apply on 1.21.1, or null when it does: its page shows the row off with this reason
	 * (ParityReasons). The settings whose feature Minecraft 1.21.1 lacks, while the rest of their module works.
	 */
	public static @Nullable String settingUnavailable(com.flintclient.client.control.model.SettingDescriptor<?> setting) {
		if (setting == com.flintclient.client.control.model.mods.UtilityMods.Chat.HEAD_POSITION) {
			return "Minecraft 1.21.1 can't put a head inside chat text (1.21.9 added that), so heads go before the line";
		}
		if (setting == com.flintclient.client.control.model.mods.VisualMods.FallingLeaves.VANILLA_PARTICLES
			|| setting == com.flintclient.client.control.model.mods.VisualMods.FallingLeaves.VANILLA_TEXTURES) {
			return "Minecraft 1.21.1 has no falling leaves of its own (1.21.5 added them), so Flint's are used";
		}
		if (setting == com.flintclient.client.control.model.mods.ToolMods.Animations.MAP
			|| setting == com.flintclient.client.control.model.mods.ToolMods.Animations.LANTERNS
			|| setting == com.flintclient.client.control.model.mods.ToolMods.Animations.OFFHAND_HIDING
			|| setting == com.flintclient.client.control.model.mods.ToolMods.Animations.NO_SLEEP_ITEMS) {
			return "Minecraft 1.21.1 draws held items without the render states these need (1.21.2 added them)";
		}
		if (setting == com.flintclient.client.control.model.ModuleSettings.Armor.ICONS) {
			return "Minecraft 1.21.1 has no outlines for empty slots in its GUI (1.21.2 added them)";
		}
		return null;
	}

	// ---------- fog, chat, packs ----------

	/** The fog colour Minecraft is setting up right now (FogRendererMixin sets this). */
	public static Vector4f fogColor = new Vector4f();

	public static Vector4f fogColor() {
		return fogColor;
	}

	/** Whether a chat message came from a player (26.x tags its source; 1.21.1 marks the client's and server's own as system). */
	public static boolean fromPlayer(net.minecraft.client.GuiMessage message) {
		net.minecraft.client.GuiMessageTag tag = message.tag();
		return tag != net.minecraft.client.GuiMessageTag.system() && tag != net.minecraft.client.GuiMessageTag.systemSinglePlayer();
	}

	/** The HUD (1.21.1: {@code Minecraft.gui} itself). */
	public static Gui hud() {
		return Minecraft.getInstance().gui;
	}

	public static Camera mainCamera() {
		return Minecraft.getInstance().gameRenderer.getMainCamera();
	}

	// ---------- the world's projection ----------

	private static final Matrix4f WORLD_PROJECTION = new Matrix4f();
	private static final Matrix4f WORLD_VIEW = new Matrix4f();
	private static Vec3 worldCamera = Vec3.ZERO;

	/** This frame's world projection and view rotation, as GameRenderer hands them to the level renderer (GameRendererFeatureMixin). */
	public static void worldMatrices(Matrix4f projection, Matrix4f view, Vec3 camera) {
		WORLD_PROJECTION.set(projection);
		WORLD_VIEW.set(view);
		worldCamera = camera;
	}

	/** Waypoints: the world's projection this frame into {@code out}; returns the camera position it is relative to. */
	public static Vec3 worldProjection(Matrix4f out) {
		out.set(WORLD_PROJECTION).mul(WORLD_VIEW);
		return worldCamera;
	}

	/** A flat gizmo quad seen from both sides (1.21.10's debug drawing culls a quad's back face). */
	public static void rectBothSides(Vec3 a, Vec3 b, Vec3 c, Vec3 d, com.flintclient.client.compat.gizmos.GizmoStyle style) {
		com.flintclient.client.compat.gizmos.Gizmos.rect(a, b, c, d, style);
		if (style.hasFill()) {
			com.flintclient.client.compat.gizmos.Gizmos.rect(a, d, c, b, com.flintclient.client.compat.gizmos.GizmoStyle.fill(style.fill()));
		}
	}

	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().getMainRenderTarget();
	}

	/** A screenshot of {@code target}, handed to {@code then} (1.21.1 reads it back at once). */
	public static void takeScreenshot(RenderTarget target, Consumer<NativeImage> then) {
		then.accept(Screenshot.takeScreenshot(target));
	}

	/** Leaves the world as the pause menu's Disconnect does on 1.21.1. */
	public static void leaveWorld(Minecraft mc) {
		boolean local = mc.isLocalServer();
		ServerData server = mc.getCurrentServer();
		if (mc.level != null) {
			mc.level.disconnect();
		}
		if (local) {
			mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
		} else {
			mc.disconnect();
		}
		TitleScreen title = new TitleScreen();
		if (local) {
			mc.setScreen(title);
		} else if (server != null && server.isRealm()) {
			mc.setScreen(new com.mojang.realmsclient.RealmsMainScreen(title));
		} else {
			mc.setScreen(new JoinMultiplayerScreen(title));
		}
	}

	// ---------- teams, effects, entities ----------

	/** A team's colour as RGB, or {@code fallback} when it has none. */
	public static int teamRgb(PlayerTeam team, int fallback) {
		Integer rgb = team.getColor().getColor();
		return rgb != null ? rgb : fallback;
	}

	/** The sidebar slot a team's colour shows, or null when the team has no colour. */
	public static @Nullable DisplaySlot teamSidebar(PlayerTeam team) {
		return DisplaySlot.teamColorToSlot(team.getColor());
	}

	/**
	 * An effect's icon as 26.x names it, {@code mob_effect/<effect>} (1.21.1 keeps the icons in their own atlas:
	 * {@link #effectSprite} finds it there, and GraphicsBridge draws such a name from it).
	 */
	public static ResourceLocation mobEffectSprite(Holder<MobEffect> effect) {
		ResourceLocation key = effect.unwrapKey().map(k -> k.location()).orElse(ResourceLocation.withDefaultNamespace("missing"));
		return ResourceLocation.fromNamespaceAndPath(key.getNamespace(), "mob_effect/" + key.getPath());
	}

	private static final ResourceLocation MOB_EFFECTS_ATLAS = ResourceLocation.withDefaultNamespace("textures/atlas/mob_effects.png");

	/** The sprite of an effect icon named as {@link #mobEffectSprite} names it, or null for another name. */
	public static @Nullable TextureAtlasSprite effectSprite(ResourceLocation sprite) {
		String path = sprite.getPath();
		if (!path.startsWith("mob_effect/")) {
			return null;
		}
		if (Minecraft.getInstance().getTextureManager().getTexture(MOB_EFFECTS_ATLAS, null) instanceof TextureAtlas atlas) {
			return atlas.getSprite(ResourceLocation.fromNamespaceAndPath(sprite.getNamespace(), path.substring("mob_effect/".length())));
		}
		return null;
	}

	public static EntityType<?> playerType() {
		return EntityType.PLAYER;
	}

	public static EntityType<?> zombieType() {
		return EntityType.ZOMBIE;
	}

	/** A player's face, tinted by {@code argb} (1.21.1 draws faces untinted: the shader colour does it). */
	public static void drawFace(GuiGraphics graphics, ResourceLocation skin, int x, int y, int size, boolean hat, boolean upsideDown, int argb) {
		boolean tint = argb != -1;
		if (tint) {
			RenderSystem.setShaderColor((argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F);
			RenderSystem.enableBlend();
		}
		PlayerFaceRenderer.draw(graphics, skin, x, y, size, hat, upsideDown);
		if (tint) {
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			RenderSystem.disableBlend();
		}
	}

	/** A nametag-style text at {@code at} (relative to the pose), drawn at once (compat.state.SubmitNodeCollector). */
	public static void submitNameTag(com.flintclient.client.compat.state.SubmitNodeCollector collector, PoseStack pose, Vec3 at, int y,
		Component text, boolean seeThrough, int light, double distanceSq, com.flintclient.client.compat.state.CameraRenderState camera) {
		collector.submitNameTag(pose, at, y, text, seeThrough, light, distanceSq, camera);
	}

	// ---------- textures ----------

	/** Uploads rows [top, bottom) of {@code pixels} to {@code texture}: one write, read straight from the image. */
	public static void writeRows(DynamicTexture texture, NativeImage pixels, int top, int bottom) {
		texture.bind();
		pixels.upload(0, 0, top, 0, top, pixels.getWidth(), bottom - top, false, false, false, false);
	}

	/** Uploads all of {@code image} to ({@code x}, {@code y}) of {@code texture}: one sub-image upload (an animated cape's frame). */
	public static void writeImage(DynamicTexture texture, NativeImage image, int x, int y) {
		texture.bind();
		image.upload(0, x, y, 0, 0, image.getWidth(), image.getHeight(), false, false, false, false);
	}

	/** A texture sampled with linear filtering, clamped at its edges. */
	public static void linearClamp(AbstractTexture texture) {
		texture.setFilter(true, false);
		wrap(texture, 0x812F);
	}

	/** A texture sampled with linear filtering, repeating past its edges. */
	public static void linearRepeat(AbstractTexture texture) {
		texture.setFilter(true, false);
		wrap(texture, GL11.GL_REPEAT);
	}

	private static void wrap(AbstractTexture texture, int mode) {
		RenderSystem.assertOnRenderThreadOrInit();
		texture.bind();
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, mode);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, mode);
	}

	/** Opens Minecraft's friends list: 1.21.1 has none (the button is off, {@link #FRIENDS}). */
	public static void openFriends(Minecraft minecraft, Screen parent) {
	}

	public static int friendRequests(Minecraft minecraft) {
		return 0;
	}

	/** Whether the keyboard key with this GLFW code is held (GLFW keeps the state; reading it allocates nothing). */
	public static boolean keyDown(int key) {
		return key >= 0 && InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key);
	}

	/** The character a key types as a shortcut: GLFW's codes for letters and brackets are their ASCII ('Z', '['). */
	public static int shortcutKey(KeyEvent event) {
		return event.key();
	}

	public static void openPath(Path path) {
		Util.getPlatform().openFile(path.toFile());
	}

	/** Opens a web address in the system's browser, from the game (which is in front, so the browser comes forward). */
	public static void openUri(String uri) {
		Util.getPlatform().openUri(java.net.URI.create(uri));
	}

	public static Screen optionsScreen(Screen parent, Options options) {
		return new OptionsScreen(parent, options);
	}

	public static void rotate(PoseStack pose, Axis axis, float radians) {
		pose.mulPose(axis.rotation(radians));
	}

	public static void rotateDegrees(PoseStack pose, Axis axis, float degrees) {
		pose.mulPose(axis.rotationDegrees(degrees));
	}

	public static void rotate(PoseStack pose, Quaternionfc rotation) {
		pose.mulPose(new Quaternionf(rotation));
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

	// ---------- advancements ----------

	public static AdvancementTree advancementTree(ClientAdvancements advancements) {
		return advancements.getTree();
	}

	public static Map<AdvancementHolder, AdvancementProgress> advancementProgress(ClientAdvancements advancements) {
		return ((com.flintclient.client.mixin.bridge.ClientAdvancementsAccessor) advancements).flint$progress();
	}

	public static Component title(DisplayInfo display) {
		return display.getTitle();
	}

	public static Component description(DisplayInfo display) {
		return display.getDescription();
	}

	/** An advancement's icon, used as 26.x's ItemStackTemplate is: {@code icon(display).create()} makes the stack. */
	public static Icon icon(DisplayInfo display) {
		return new Icon(display.getIcon());
	}

	/** 1.21.1 keeps an advancement's icon as a stack; 26.x as a template whose create() makes one. */
	public record Icon(ItemStack stack) {
		public ItemStack create() {
			return stack.copy();
		}
	}

	public static boolean hidden(DisplayInfo display) {
		return display.isHidden();
	}

	public static AdvancementType type(DisplayInfo display) {
		return display.getType();
	}

	// ---------- Custom F3 (BetterF3): what its lines read ----------

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
		return new int[] {dispatcher.getToBatchCount(), dispatcher.getToUpload(), dispatcher.getFreeBufferCount()};
	}

	/** The sound engine's "Sounds: playing/max + playing/max" line. */
	public static String f3SoundStats() {
		return Minecraft.getInstance().getSoundManager().getDebugString();
	}

	/** The post effect applied now, or "" (1.21.1 names it by its shader file). */
	public static String f3PostEffects() {
		net.minecraft.client.renderer.PostChain effect = Minecraft.getInstance().gameRenderer.currentEffect();
		return effect == null ? "" : effect.getName();
	}

	/** The graphics preset, named as BetterF3 names it ("FANCY", "CUSTOM"). */
	public static String f3Graphics() {
		return org.apache.commons.lang3.StringUtils.capitalize(Minecraft.getInstance().options.graphicsMode().get().toString());
	}

	/** Minecraft's graphics option (Fast, Fancy, Fabulous), for the menu's Graphics tab. */
	public static net.minecraft.client.OptionInstance<?> graphicsOption() {
		return Minecraft.getInstance().options.graphicsMode();
	}

	/** 1.21.1 has no texture filtering options (mipmaps alone need a texture reload). */
	public static int textureFiltering() {
		return 0;
	}

	/** The overworld clock: day ticks and days played come from it. */
	public static long f3Clock(net.minecraft.world.level.Level level) {
		return level.getDayTime();
	}

	/** How bright the moon is over {@code pos} (local difficulty grows with it). */
	public static float f3MoonBrightness(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		return level.getMoonBrightness();
	}

	/** The graphics card: name, vendor, graphics API and driver. */
	public static String[] f3Gpu() {
		return new String[] {com.mojang.blaze3d.platform.GlUtil.getRenderer(), com.mojang.blaze3d.platform.GlUtil.getVendor(), "OpenGL",
			com.mojang.blaze3d.platform.GlUtil.getOpenGLVersion()};
	}

	public static String f3Cpu() {
		return com.mojang.blaze3d.platform.GlUtil.getCpuInfo();
	}

	/** The width the debug screen lays its columns out in. */
	public static int f3Width(GuiGraphics graphics) {
		return graphics.guiWidth();
	}

	// ---------- maps ----------

	/**
	 * The maps: the texture a block shows from above (its model's top face, else any face, else its particle texture), as the
	 * sprite's name; {@code tinted[0]} says whether that face takes a tint (grass, leaves, water). Null when it has none.
	 */
	public static @Nullable ResourceLocation mapSprite(net.minecraft.world.level.block.state.BlockState state, boolean[] tinted) {
		var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
		net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
		for (var quad : model.getQuads(state, net.minecraft.core.Direction.UP, random)) {
			tinted[0] = quad.isTinted();
			return quad.getSprite().contents().name();
		}
		net.minecraft.client.renderer.block.model.BakedQuad any = null;
		random.setSeed(42L);
		for (var quad : model.getQuads(state, null, random)) {
			if (quad.getDirection() == net.minecraft.core.Direction.UP) {
				tinted[0] = quad.isTinted();
				return quad.getSprite().contents().name();
			}
			if (any == null) {
				any = quad;
			}
		}
		if (any != null) {
			tinted[0] = any.isTinted();
			return any.getSprite().contents().name();
		}
		tinted[0] = false;
		TextureAtlasSprite particle = model.getParticleIcon();
		return particle == null ? null : particle.contents().name();
	}

	/** The maps: the tint Minecraft gives a block where it stands (grass, foliage and water by biome), or -1 for none. */
	public static int mapTint(net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.multiplayer.ClientLevel level,
		net.minecraft.core.BlockPos pos) {
		return Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
	}

	// ---------- Saturation, No Chat Reports ----------

	/** Natural health regeneration in a world you host (its game rule). */
	public static boolean naturalRegeneration(net.minecraft.server.level.ServerLevel level) {
		return level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION);
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
	public static @Nullable Component unsignedContent(net.minecraft.network.protocol.game.ClientboundPlayerChatPacket packet) {
		return packet.unsignedContent();
	}

	/** authlib's user API service, whose telemetry sessions No Chat Reports switches off. */
	public static final String USER_API_SERVICE_CLASS = "com.mojang.authlib.yggdrasil.YggdrasilUserApiService";

	// ---------- switching accounts (module.account.SessionSwitch) ----------

	/** Minecraft's signed-in user for a session from Flint (1.21.1's users name their type: Flint's are Microsoft ones). */
	public static net.minecraft.client.User newUser(String name, java.util.UUID id, String token, java.util.Optional<String> xuid,
		java.util.Optional<String> clientId) {
		return new net.minecraft.client.User(name, id, token, xuid, clientId, net.minecraft.client.User.Type.MSA);
	}

	/** The user API service for a token, as Minecraft makes it at start-up. */
	public static com.mojang.authlib.minecraft.UserApiService userApiService(Minecraft minecraft, String token) throws Exception {
		return new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(minecraft.getProxy()).createUserApiService(token);
	}

	/** The account's profile (name, skin), fetched as Minecraft does at start-up; null when the services don't know it. */
	public static com.mojang.authlib.GameProfile fetchProfile(Minecraft minecraft, java.util.UUID id) {
		var result = minecraft.getMinecraftSessionService().fetchProfile(id, true);
		return result == null ? null : result.profile();
	}

	/** Minecraft's profile lookup for the signed-in user, as it starts it at start-up. */
	public static java.util.concurrent.CompletableFuture<?> profileFuture(Minecraft minecraft, java.util.UUID id, boolean offline) {
		return offline ? java.util.concurrent.CompletableFuture.completedFuture(null)
			: java.util.concurrent.CompletableFuture.supplyAsync(() -> minecraft.getMinecraftSessionService().fetchProfile(id, true),
				net.minecraft.Util.nonCriticalIoPool());
	}

	/** The social manager (blocking, hiding) for the new user's API service. */
	public static void replaceSocial(Minecraft minecraft, com.mojang.authlib.minecraft.UserApiService api, String token, boolean offline) {
		((com.flintclient.client.mixin.MinecraftSessionAccessor) minecraft)
			.flint$setPlayerSocialManager(new net.minecraft.client.gui.screens.social.PlayerSocialManager(minecraft, api));
	}

	/** A Realms client for the signed-in user (1.21.1 makes a new one for each use). */
	public static com.mojang.realmsclient.client.RealmsClient realmsClient(Minecraft minecraft) {
		return com.mojang.realmsclient.client.RealmsClient.create(minecraft);
	}
}

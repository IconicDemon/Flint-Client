package com.flintclient.client.compat;

import com.flintclient.client.compat.input.KeyEvent;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * Minecraft 1.19.2's answers to what the shared code needs where versions differ (see the 26.3 McCompat for each), as the
 * 1.21.1 overlay's (this file replaces it) with what 1.19.2 also lacks: GuiGraphics (Flint's own, {@link GuiGraphics}), JOML
 * in Minecraft's own APIs ({@link MatrixBridge}), data components (an item's NBT), the built-in registries (Registry.*), the
 * 1.20.2 advancement tree ({@link AdvancementTree}). Features that can't exist on 1.19.2 are switched off with the reason on
 * their page ({@link #unavailable}, {@link #settingUnavailable}).
 */
public final class McCompat {
	public static final String MINECRAFT = "1.19.2";
	/** Whether a nametag can have a shaped background (rounded corners, a border): 1.19.2 draws only its plain one. */
	public static final boolean SHAPED_NAMETAGS = false;
	/** Whether Minecraft has a friends list (26.2 added it). */
	public static final boolean FRIENDS = false;
	/**
	 * Whether the Right Shift menu shows the game and the HUD live around its panel (only the panel frosted, HUD mods drawn with
	 * sample values where they have nothing to show), as on 26.3.
	 */
	public static final boolean MENU_PREVIEW = true;

	/** The effects the HUD preview shows while the player has none (made once; never applied to anyone). */
	private static Object @Nullable [] sampleEffects;

	/** Representative effects for the Effects preview into {@code out} (fixed times, never ticking); returns how many. */
	public static int sampleEffects(Object[] out) {
		Object[] samples = sampleEffects;
		if (samples == null) {
			samples = new Object[] {new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 95 * 20, 1),
				new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST, 48 * 20, 0),
				new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 170 * 20, 0),
				new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 26 * 20, 0)};
			sampleEffects = samples;
		}
		int n = Math.min(out.length, samples.length);
		System.arraycopy(samples, 0, out, 0, n);
		return n;
	}
	public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;
	/** 26.x's GPU pipeline type's internal name, for mixin descriptors (1.19.2 has none; kept for the shared constants). */
	public static final String RENDER_PIPELINE = "com/mojang/blaze3d/pipeline/RenderPipeline";
	/** The HUD's class: its internal name (for descriptors) and its name (for a mixin's targets); 1.19.2's is Gui. */
	public static final String HUD = "net/minecraft/client/gui/Gui";
	public static final String HUD_CLASS = "net.minecraft.client.gui.Gui";
	/** The class whose setScreen every screen passes through (1.19.2: Minecraft itself). */
	public static final String SCREENS_CLASS = "net.minecraft.client.Minecraft";
	/** LevelRenderer's method that draws the world and fetches the entity outline effect. */
	public static final String LEVEL_RENDER = "renderLevel";
	/** The pause menu's Disconnect button action (1.19.2: the lambda that offers a report draft first). */
	public static final String PAUSE_DISCONNECT = "method_19836";
	/** The call whose answer decides whether the player list shows heads. */
	public static final String TAB_HEADS = "Lnet/minecraft/network/Connection;isEncrypted()Z";
	public static final String SWING_DURATION = "getCurrentSwingDuration";
	/** Whether this Minecraft builds generated item models with gaps (1.21.10 and before; 1.21.11 rebuilt them). */
	public static final boolean ITEM_MODEL_GAPS = true;
	/** Whether this Minecraft has an offhand (1.9 and later), and the items Armor's slots look for that 1.8.9 lacks. */
	public static final boolean OFFHAND = true;
	/** Whether Armor's empty slots can show the outline of what goes there (1.19.2 keeps those outlines as item textures). */
	public static final boolean SLOT_OUTLINES = false;
	/** Chat Heads: ChatListener's delayed show of a disguised chat message, the command suggestions' draw. */
	public static final String DISGUISED_SHOW = "method_45745";
	public static final String SUGGESTIONS_DRAW = "render";

	/** Whether ChatListener.handleSystemMessage's flag means the server sent it (26.x) rather than an action-bar message (1.19.2). */
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

	/** Whether an item is used as a spear: 1.19.2 has no spear (its SPEAR use is the trident's). */
	public static boolean spearUse(net.minecraft.world.item.UseAnim use) {
		return false;
	}

	/** The spear pose for a spear in use: none on 1.19.2. */
	public static net.minecraft.client.model.HumanoidModel.@Nullable ArmPose spearPose(net.minecraft.world.item.UseAnim use) {
		return null;
	}

	/** Whether an arm pose is the spear's: none on 1.19.2. */
	public static boolean spearArmPose(net.minecraft.client.model.HumanoidModel.ArmPose pose) {
		return false;
	}

	/** Zoom's relative view bobbing: where the walking bob is read (GameRenderer.bobView itself). */
	public static final String ZOOM_BOB_CLASS = "net.minecraft.client.renderer.GameRenderer";
	public static final String ZOOM_BOB_METHOD = "bobView";
	public static final String WEATHER_INSTANCES = "renderSnowAndRain";

	private McCompat() {
	}

	// ---------- what 1.19.2 can't have ----------

	/** Why the module {@code id} can't work on this Minecraft, or null when it can ({@link ModuleAvailability}). */
	public static @Nullable String unavailable(String id) {
		return ModuleAvailability.unavailable(id);
	}

	/**
	 * Why {@code setting} can't apply on 1.19.2, or null when it does: its page shows the row off with this reason
	 * (ParityReasons). The settings whose feature Minecraft 1.19.2 lacks, while the rest of their module works.
	 */
	public static @Nullable String settingUnavailable(com.flintclient.client.control.model.SettingDescriptor<?> setting) {
		return ModuleAvailability.settingUnavailable(setting);
	}

	// ---------- fog, chat, packs ----------

	/** The fog colour Minecraft is setting up right now (FogRendererMixin sets this). */
	public static Vector4f fogColor = new Vector4f();

	public static Vector4f fogColor() {
		return fogColor;
	}

	/** Whether a chat message came from a player (1.19.2 marks the client's and server's own as system). */
	public static boolean fromPlayer(net.minecraft.client.GuiMessage message) {
		net.minecraft.client.GuiMessageTag tag = message.tag();
		return tag == null || !"System".equals(tag.logTag());
	}

	/** The HUD (1.19.2: {@code Minecraft.gui} itself). */
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

	/** A flat gizmo quad seen from both sides (the debug drawing culls a quad's back face). */
	public static void rectBothSides(Vec3 a, Vec3 b, Vec3 c, Vec3 d, com.flintclient.client.compat.gizmos.GizmoStyle style) {
		com.flintclient.client.compat.gizmos.Gizmos.rect(a, b, c, d, style);
		if (style.hasFill()) {
			com.flintclient.client.compat.gizmos.Gizmos.rect(a, d, c, b, com.flintclient.client.compat.gizmos.GizmoStyle.fill(style.fill()));
		}
	}

	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().getMainRenderTarget();
	}

	/** A screenshot of {@code target}, handed to {@code then} (read back at once). */
	public static void takeScreenshot(RenderTarget target, Consumer<NativeImage> then) {
		then.accept(Screenshot.takeScreenshot(target));
	}

	/** Leaves the world as the pause menu's Disconnect does on 1.19.2. */
	public static void leaveWorld(Minecraft mc) {
		boolean local = mc.isLocalServer();
		ServerData server = mc.getCurrentServer();
		if (mc.level != null) {
			mc.level.disconnect();
		}
		if (local) {
			mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
		} else {
			mc.clearLevel();
		}
		TitleScreen title = new TitleScreen();
		if (local) {
			mc.setScreen(title);
		} else if (server != null && server.isLan()) {
			mc.setScreen(new JoinMultiplayerScreen(title));
		} else if (mc.isConnectedToRealms()) {
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

	/** The sidebar slot a team's colour shows (1.19.2 numbers them: 3 + the colour's id), or -1 when the team has no colour. */
	public static int teamSidebar(PlayerTeam team) {
		int color = team.getColor().getId();
		return color >= 0 ? 3 + color : -1;
	}

	/**
	 * An effect's icon as 26.x names it, {@code mob_effect/<effect>} (1.19.2 keeps the icons in their own atlas:
	 * {@link #effectSprite} finds it there, and GraphicsBridge draws such a name from it).
	 */
	public static ResourceLocation mobEffectSprite(MobEffect effect) {
		ResourceLocation key = Registry.MOB_EFFECT.getKey(effect);
		if (key == null) {
			key = new ResourceLocation("missing");
		}
		return new ResourceLocation(key.getNamespace(), "mob_effect/" + key.getPath());
	}

	/** The sprite of an effect icon named as {@link #mobEffectSprite} names it, or null for another name. */
	public static @Nullable TextureAtlasSprite effectSprite(ResourceLocation sprite) {
		String path = sprite.getPath();
		if (!path.startsWith("mob_effect/")) {
			return null;
		}
		MobEffect effect = Registry.MOB_EFFECT.get(new ResourceLocation(sprite.getNamespace(), path.substring("mob_effect/".length())));
		return effect == null ? null : Minecraft.getInstance().getMobEffectTextures().get(effect);
	}

	public static EntityType<?> playerType() {
		return EntityType.PLAYER;
	}

	public static EntityType<?> zombieType() {
		return EntityType.ZOMBIE;
	}

	/** A player's face, tinted by {@code argb} (1.19.2 draws faces untinted: the shader colour does it). */
	public static void drawFace(GuiGraphics graphics, ResourceLocation skin, int x, int y, int size, boolean hat, boolean upsideDown, int argb) {
		boolean tint = argb != -1;
		RenderSystem.setShaderColor((argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F);
		if (tint) {
			RenderSystem.enableBlend();
		}
		RenderSystem.setShaderTexture(0, skin);
		PlayerFaceRenderer.draw(graphics.pose(), x, y, size, hat, upsideDown);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		if (tint) {
			RenderSystem.disableBlend();
		}
	}

	/** A player's face from their skin, as 1.20.2's PlayerFaceRenderer.draw(graphics, skin, x, y, size) draws it (hat on). */
	public static void face(GuiGraphics graphics, PlayerSkin skin, int x, int y, int size) {
		RenderSystem.setShaderTexture(0, skin.texture());
		PlayerFaceRenderer.draw(graphics.pose(), x, y, size);
	}

	/** A player's face from a skin texture, hat and upside-down as asked (1.20.2's other draw). */
	public static void face(GuiGraphics graphics, ResourceLocation skin, int x, int y, int size, boolean hat, boolean upsideDown) {
		RenderSystem.setShaderTexture(0, skin);
		PlayerFaceRenderer.draw(graphics.pose(), x, y, size, hat, upsideDown);
	}

	/** Sends a command as the player (1.19.3 moved sending to ClientPacketListener; 1.19.2's player signs it). */
	public static void sendCommand(net.minecraft.client.multiplayer.@Nullable ClientPacketListener connection, String command) {
		net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.commandSigned(command, null);
		}
	}

	/** Sends a chat message as the player (as {@link #sendCommand}). */
	public static void sendChat(net.minecraft.client.multiplayer.@Nullable ClientPacketListener connection, String message) {
		net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.chatSigned(message, null);
		}
	}

	/** The squared distance from {@code point} to the nearest point of {@code box} (0 inside it), as 1.20's AABB.distanceToSqr. */
	public static double distanceToSqr(net.minecraft.world.phys.AABB box, Vec3 point) {
		double dx = Math.max(Math.max(box.minX - point.x, point.x - box.maxX), 0.0);
		double dy = Math.max(Math.max(box.minY - point.y, point.y - box.maxY), 0.0);
		double dz = Math.max(Math.max(box.minZ - point.z, point.z - box.maxZ), 0.0);
		return dx * dx + dy * dy + dz * dz;
	}

	/** How far a player reaches entities: 1.19.2 has no interaction-range attribute (3 blocks, 6 in creative, as it picks). */
	public static double entityReach(net.minecraft.world.entity.player.Player player) {
		return player.getAbilities().instabuild ? 6.0 : 3.0;
	}

	/**
	 * An entity drawn in a box as 1.20.2's InventoryScreen.renderEntityInInventoryFollowsMouse draws it (centred, its middle at
	 * the box's centre, turned towards the pointer, cut to the box), with 1.19.2's renderEntityInInventory, which stands the
	 * entity's feet at the given point.
	 */
	public static void entityInInventory(GuiGraphics graphics, int x0, int y0, int x1, int y1, int size, float yOffset, float mouseX, float mouseY,
		net.minecraft.world.entity.LivingEntity entity) {
		float cx = (x0 + x1) / 2.0F;
		float cy = (y0 + y1) / 2.0F;
		int feetY = Math.round(cy + size * (entity.getBbHeight() / 2.0F + yOffset));
		graphics.enableScissor(x0, y0, x1, y1);
		try {
			graphics.withPose(() -> net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventory(Math.round(cx), feetY, size,
				cx - mouseX, cy - mouseY, entity));
		} finally {
			graphics.disableScissor();
		}
	}

	/**
	 * The menu background blur amount: 1.19 has no menu blur option (1.20.5 added it), so only the Right Shift menu's own strength
	 * while it shows (General › Menu blur strength, control/fx/PanelBlur draws it); 0 otherwise.
	 */
	public static float menuBlur() {
		return Math.max(0, com.flintclient.client.control.ControlState.menuBlurOverride());
	}

	/** Whether a bound chat type is {@code type} (1.19.2 binds the type itself; its key comes from the connection's registry). */
	public static boolean chatTypeIs(net.minecraft.network.chat.ChatType.Bound bound, net.minecraft.resources.ResourceKey<net.minecraft.network.chat.ChatType> type) {
		net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return false;
		}
		return connection.registryAccess().registryOrThrow(net.minecraft.core.Registry.CHAT_TYPE_REGISTRY).getResourceKey(bound.chatType())
			.map(type::equals).orElse(false);
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

	/** Opens Minecraft's friends list: 1.19.2 has none (the button is off, {@link #FRIENDS}). */
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

	/** Asks before opening a web address, as 1.20's ConfirmLinkScreen.confirmLinkNow does (back to {@code parent} after). */
	public static void confirmLink(Screen parent, java.net.URI uri) {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(open -> {
			if (open) {
				Util.getPlatform().openUri(uri);
			}
			mc.setScreen(parent);
		}, uri.toString(), true));
	}

	/** A saved server's icon as PNG bytes, or null (1.19.2 keeps it as base64 text). */
	public static byte @Nullable [] iconBytes(ServerData data) {
		String icon = data.getIconB64();
		if (icon == null) {
			return null;
		}
		try {
			return java.util.Base64.getDecoder().decode(icon.replace("\n", ""));
		} catch (IllegalArgumentException e) {
			return null;
		}
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

	/** A JOML rotation on 1.19.2's pose (which turns by its own quaternions). */
	public static void rotate(PoseStack pose, Quaternionfc rotation) {
		pose.mulPose(new com.mojang.math.Quaternion(rotation.x(), rotation.y(), rotation.z(), rotation.w()));
	}

	/** A bundle's items (compat.item.BundleContents reads them from its NBT's "Items" list). */
	public static Stream<ItemStack> bundleItems(com.flintclient.client.compat.item.BundleContents bundle) {
		return bundle.itemCopyStream();
	}

	/**
	 * A container item's items (a shulker box's), each in its slot's place as 1.20.5 lists them (compat.item.ItemContainerContents
	 * reads them from its block entity's NBT).
	 */
	public static Stream<ItemStack> containerItems(com.flintclient.client.compat.item.ItemContainerContents contents) {
		return contents.allItemsCopyStream();
	}

	public static @Nullable NativeImage packIcon(Pack pack) throws Exception {
		try (PackResources resources = pack.open()) {
			try (InputStream in = resources.getRootResource("pack.png")) {
				return in == null ? null : NativeImage.read(in);
			} catch (java.io.FileNotFoundException e) {
				return null;
			}
		}
	}

	// ---------- advancements ----------

	private static @Nullable AdvancementTree lastTree;
	private static @Nullable Object lastTreeList;
	private static long lastTreeAt;

	/**
	 * 1.20.2's tree of the advancements the server sent (made from 1.19.2's list; the same tree for calls in quick succession, so
	 * its nodes can be told apart by identity, as the screen does).
	 */
	public static AdvancementTree advancementTree(ClientAdvancements advancements) {
		long now = System.nanoTime();
		AdvancementTree tree = lastTree;
		if (tree == null || lastTreeList != advancements.getAdvancements() || now - lastTreeAt > 50_000_000L) {
			tree = new AdvancementTree(advancements.getAdvancements());
			lastTree = tree;
			lastTreeList = advancements.getAdvancements();
			lastTreeAt = now;
		}
		return tree;
	}

	public static Map<Advancement, AdvancementProgress> advancementProgress(ClientAdvancements advancements) {
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

	/** 1.19.2 keeps an advancement's icon as a stack; 26.x as a template whose create() makes one. */
	public record Icon(ItemStack stack) {
		public ItemStack create() {
			return stack.copy();
		}
	}

	public static boolean hidden(DisplayInfo display) {
		return display.isHidden();
	}

	public static FrameType type(DisplayInfo display) {
		return display.getFrame();
	}

	// ---------- Custom F3 (BetterF3): what its lines read ----------

	/** "C: rendered/total (s) D: view distance, …", as the debug screen's chunk line gives it (null without a world). */
	public static @Nullable String f3SectionStats() {
		return Minecraft.getInstance().levelRenderer.getChunkStatistics();
	}

	/** "E: rendered/total, SD: …", as the debug screen's entity line gives it (null without a world). */
	public static @Nullable String f3EntityStats() {
		return Minecraft.getInstance().levelRenderer.getEntityStatistics();
	}

	/** Chunk sections waiting to be compiled, waiting to go to the GPU, and free buffers; null without a world. */
	public static int @Nullable [] f3SectionQueues() {
		return null;
	}

	/** The sound engine's "Sounds: playing/max + playing/max" line. */
	public static String f3SoundStats() {
		return Minecraft.getInstance().getSoundManager().getDebugString();
	}

	/** The post effect applied now, or "" (1.19.2 names it by its shader file). */
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

	/** 1.19.2 has no texture filtering options (mipmaps alone need a texture reload). */
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
			return quad.getSprite().getName();
		}
		net.minecraft.client.renderer.block.model.BakedQuad any = null;
		random.setSeed(42L);
		for (var quad : model.getQuads(state, null, random)) {
			if (quad.getDirection() == net.minecraft.core.Direction.UP) {
				tinted[0] = quad.isTinted();
				return quad.getSprite().getName();
			}
			if (any == null) {
				any = quad;
			}
		}
		if (any != null) {
			tinted[0] = any.isTinted();
			return any.getSprite().getName();
		}
		tinted[0] = false;
		TextureAtlasSprite particle = model.getParticleIcon();
		return particle == null ? null : particle.getName();
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
		return packet.message().unsignedContent().orElse(null);
	}

	/** authlib's user API service, whose telemetry sessions No Chat Reports switches off. */
	public static final String USER_API_SERVICE_CLASS = "com.mojang.authlib.yggdrasil.YggdrasilUserApiService";

	// ---------- switching accounts (module.account.SessionSwitch) ----------

	/** Minecraft's signed-in user for a session from Flint (1.19.2's users name their type and take the id undashed). */
	public static net.minecraft.client.User newUser(String name, java.util.UUID id, String token, java.util.Optional<String> xuid,
		java.util.Optional<String> clientId) {
		return new net.minecraft.client.User(name, com.mojang.util.UUIDTypeAdapter.fromUUID(id), token, xuid, clientId,
			net.minecraft.client.User.Type.MSA);
	}

	/** The user API service for a token, as Minecraft makes it at start-up. */
	public static com.mojang.authlib.minecraft.UserApiService userApiService(Minecraft minecraft, String token) throws Exception {
		return new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(minecraft.getProxy()).createUserApiService(token);
	}

	/** The account's profile (name, skin), fetched as Minecraft does; null when the services don't know it. */
	public static com.mojang.authlib.@Nullable GameProfile fetchProfile(Minecraft minecraft, java.util.UUID id) {
		com.mojang.authlib.GameProfile profile = minecraft.getMinecraftSessionService().fillProfileProperties(new com.mojang.authlib.GameProfile(id, null),
			true);
		return profile == null || profile.getName() == null ? null : profile;
	}

	/** The social manager (blocking, hiding) for the new user's API service. */
	public static void replaceSocial(Minecraft minecraft, com.mojang.authlib.minecraft.UserApiService api, String token, boolean offline) {
		((com.flintclient.client.mixin.MinecraftSessionAccessor) minecraft)
			.flint$setPlayerSocialManager(new net.minecraft.client.gui.screens.social.PlayerSocialManager(minecraft, api));
	}

	/** A Realms client for the signed-in user (1.19.2 makes a new one for each use). */
	public static com.mojang.realmsclient.client.RealmsClient realmsClient(Minecraft minecraft) {
		return com.mojang.realmsclient.client.RealmsClient.create(minecraft);
	}

	// ---------- Flint's main menu: other mods' title-screen buttons, the Discord card's player ----------

	/** Lays {@code screen} out at {@code width} × {@code height} GUI units without showing it (its init, and other mods' hooks on it). */
	public static void initScreen(Screen screen, int width, int height) {
		screen.init(Minecraft.getInstance(), width, height);
	}

	/** A left click in the middle of {@code widget} (another mod's title-screen button, pressed from Flint's menu); true when it took it. */
	public static boolean clickWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
		double x = widget.x + widget.getWidth() / 2.0;
		double y = widget.y + widget.getHeight() / 2.0;
		boolean clicked = widget.mouseClicked(x, y, 0);
		widget.mouseReleased(x, y, 0);
		return clicked;
	}

	/**
	 * Draws the player the game plays as (their own skin) wearing {@code cape} as a whole 3D player in the pixel box ({@code x},
	 * {@code y}, {@code w}, {@code h}) of {@code frame}'s canvas, turned {@code yaw} degrees from facing the viewer, its cape
	 * leaning {@code capeLean} degrees (the Discord card's). False where the box is too small to draw in.
	 */
	public static boolean previewPlayer(com.flintclient.client.platform.Frame frame, com.flintclient.client.platform.Texture cape, int x, int y,
		int w, int h, float yaw, float capeLean) {
		return previewAvatar(frame, null, false, cape, x, y, w, h, yaw, capeLean);
	}

	/**
	 * The wardrobe's player ({@code control/rshift/WardrobeScreen}): {@code skin} (null: the account's own) on the slim or classic
	 * model, wearing {@code cape} (null: none) and, while the wardrobe draws it, the 3D cosmetics it tries on, as a whole 3D
	 * player in the pixel box of {@code frame}'s canvas. 1.19 draws players only from an entity: compat/PreviewAvatar draws the
	 * model itself, as 26.3's GuiGraphics.entity places a player render state. False where the box is too small to draw in.
	 */
	public static boolean previewAvatar(com.flintclient.client.platform.Frame frame, com.flintclient.client.platform.@org.jspecify.annotations.Nullable Texture skin,
		boolean slim, com.flintclient.client.platform.@org.jspecify.annotations.Nullable Texture cape, int x, int y, int w, int h, float yaw, float capeLean) {
		int gui = com.flintclient.client.platform.Frame.windowGuiScale();
		int x0 = Math.round(x / (float) gui);
		int y0 = Math.round(y / (float) gui);
		int x1 = Math.round((x + w) / (float) gui);
		int y1 = Math.round((y + h) / (float) gui);
		if (x1 - x0 < 4 || y1 - y0 < 8) {
			return false;
		}
		PlayerSkin own = com.flintclient.client.module.account.AccountSkin.current();
		ResourceLocation body = skin == null ? own.texture() : skin.id();
		boolean slimArms = skin == null ? own.model() == PlayerSkin.Model.SLIM : slim;
		// Back from the canvas's pixels to GUI units, where the player is drawn.
		frame.pushPlace(0, 0, gui);
		try {
			PreviewAvatar.draw(frame.graphics(), body, slimArms, cape == null ? null : cape.id(), x0, y0, x1, y1, yaw, capeLean);
		} finally {
			frame.pop();
		}
		return true;
	}

	/**
	 * The wardrobe's Apply: {@code skin} (a 64 × 64 texture) on the player's own model at once, first and third person, until
	 * the next join ({@link OwnSkin}). True when it is on.
	 */
	public static boolean wearOwnSkin(com.flintclient.client.platform.Texture skin, boolean slim) {
		java.util.UUID me = Minecraft.getInstance().getUser().getProfileId();
		if (me == null) {
			return false;
		}
		OwnSkin.wear(skin.id(), slim, me);
		return true;
	}
}

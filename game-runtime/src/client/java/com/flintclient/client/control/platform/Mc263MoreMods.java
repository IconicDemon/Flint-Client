package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreMods;
import com.flintclient.client.control.model.mods.MoreMods.AutoText;
import com.flintclient.client.control.model.mods.MoreMods.Backups;
import com.flintclient.client.control.model.mods.MoreMods.CullLogs;
import com.flintclient.client.control.model.mods.MoreMods.Discord;
import com.flintclient.client.control.model.mods.MoreMods.Elytras;
import com.flintclient.client.control.model.mods.MoreMods.Hypixel;
import com.flintclient.client.control.model.mods.MoreMods.ItemDespawn;
import com.flintclient.client.control.model.mods.MoreMods.Subtitles;
import com.flintclient.client.control.model.mods.MoreMods.Totem;
import com.flintclient.client.control.model.mods.MoreMods.UhcOverlay;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of the utility mods added with the menu's full set ({@link MoreMods}): Auto Text's macros, Cull
 * Logs, Backups, Item Despawn's flashing and UHC Overlay's sizes, Elytras, Totem, coloured Subtitles, the Hypixel helpers
 * and Discord's details. The tick work runs from {@code Mc263Features.tick}; the rest from mixins. Fault-isolated: a
 * failure turns these off for the session and the game carries on.
 */
public final class Mc263MoreMods {
	private static final int AUTO_TEXT = Mods.index(AutoText.ID);
	private static final int CULL_LOGS = Mods.index(CullLogs.ID);
	private static final int BACKUPS = Mods.index(Backups.ID);
	private static final int DESPAWN = Mods.index(ItemDespawn.ID);
	private static final int UHC = Mods.index(UhcOverlay.ID);
	private static final int ELYTRAS = Mods.index(Elytras.ID);
	private static final int TOTEM = Mods.index(Totem.ID);
	private static final int SUBTITLES = Mods.index(Subtitles.ID);
	private static final int HYPIXEL = Mods.index(Hypixel.ID);
	private static final int DISCORD = Mods.index(Discord.ID);
	private static final HookGuard GUARD = new HookGuard("utility mods",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final DateTimeFormatter BACKUP_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

	// Auto Text.
	private static final boolean[] MACRO_DOWN = new boolean[AutoText.SLOTS];
	private static final long[] MACRO_SENT = new long[AutoText.SLOTS];
	// Cull Logs: once a session, when it is (or turns) on.
	private static boolean logsCulled;
	// Backups.
	private static boolean backupKeyDown;
	private static @Nullable Path worldDir;
	private static @Nullable String worldName;
	private static boolean backupOnLeave;
	private static long nextScheduleCheck;
	private static final AtomicBoolean BACKING_UP = new AtomicBoolean();
	// Totem.
	private static final Map<UUID, Integer> POPS = new HashMap<>();
	private static boolean resetKeyDown;
	// Hypixel.
	private static long nextTip;
	private static int ggInTicks = -1;
	private static long lastGg;
	private static final com.flintclient.client.control.feature.HypixelChat HYPIXEL_CHAT = new com.flintclient.client.control.feature.HypixelChat();
	private static final Pattern GG_LINE = Pattern.compile("^(?:\\[[^\\]]+\\] )?\\w{1,16}: (?:gg|good game)!*$", Pattern.CASE_INSENSITIVE);
	private static final String[] GAME_OVER = {"Reward Summary", "1st Killer - ", "Winner: ", "WINNER!", "Winners: ", "1st Place - "};
	// Discord.
	private static long nextPresence;
	private static String lastPresence = "";
	/** Simple Discord Rich Presence's state: the last screen or dimension message ("Starting Minecraft" until there is one). */
	private static String presenceStatus = "Starting Minecraft";
	private static @org.jspecify.annotations.Nullable Object presenceScreen;
	private static @org.jspecify.annotations.Nullable Object presenceLevel;
	private static @org.jspecify.annotations.Nullable Object presenceDimension;

	private Mc263MoreMods() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- the client tick ----------

	/** From the start of every client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = mc();
			tickAutoText(mc);
			tickCullLogs(mc);
			tickBackups(mc);
			tickTotem(mc);
			tickHypixel(mc);
			tickPresenceEvents(mc);
			tickDiscord(mc);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void tickAutoText(Minecraft mc) {
		boolean on = Mods.on(AUTO_TEXT) && Mc263Features.gameplay() && mc.getConnection() != null;
		ModuleState state = Mods.state(AUTO_TEXT);
		long now = net.minecraft.util.Util.getMillis();
		for (int i = 0; i < AutoText.SLOTS; i++) {
			boolean down = on && Mc263Features.keyHeld(state.text(AutoText.KEYS[i]));
			if (down && !MACRO_DOWN[i] && now - MACRO_SENT[i] >= 1000) {
				String text = state.text(AutoText.TEXTS[i]).strip();
				if (!text.isEmpty()) {
					send(mc.getConnection(), text);
					MACRO_SENT[i] = now;
				}
			}
			MACRO_DOWN[i] = down;
		}
	}

	/** A chat message, or a command when it starts with "/". */
	private static void send(ClientPacketListener connection, String text) {
		if (text.startsWith("/")) {
			connection.sendCommand(text.substring(1));
		} else {
			connection.sendChat(text);
		}
	}

	private static void tickCullLogs(Minecraft mc) {
		if (!Mods.on(CULL_LOGS)) {
			return;
		}
		if (logsCulled) {
			return;
		}
		logsCulled = true;
		ModuleState state = Mods.state(CULL_LOGS);
		int keep = state.integer(CullLogs.MIN_LOGS);
		long olderThan = MoreMods.delayMillis(state.option(CullLogs.UNIT), state.integer(CullLogs.AMOUNT));
		boolean notify = state.bool(CullLogs.NOTIFY);
		Path logs = mc.gameDirectory.toPath().resolve("logs");
		net.minecraft.util.Util.ioPool().execute(() -> {
			int removed = cull(logs, p -> {
				String name = p.getFileName().toString();
				return (name.endsWith(".log.gz") || name.endsWith(".log")) && !name.equals("latest.log") && !name.equals("debug.log");
			}, keep, olderThan);
			if (removed > 0 && notify) {
				Mc263NoticeToast.show("Removed " + removed + (removed == 1 ? " old log" : " old logs"));
			}
		});
	}

	/**
	 * Removes the files in {@code dir} that {@code match}, older than {@code olderThanMillis}, keeping at least the {@code keep}
	 * newest of them. Returns how many it removed.
	 */
	static int cull(Path dir, java.util.function.Predicate<Path> match, int keep, long olderThanMillis) {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		List<Path> files = new ArrayList<>();
		try (Stream<Path> list = Files.list(dir)) {
			list.filter(Files::isRegularFile).filter(match).forEach(files::add);
		} catch (IOException e) {
			return 0;
		}
		files.sort(Comparator.comparingLong(Mc263MoreMods::modified).reversed());
		long cutoff = System.currentTimeMillis() - olderThanMillis;
		int removed = 0;
		for (int i = keep; i < files.size(); i++) {
			Path file = files.get(i);
			if (modified(file) < cutoff) {
				try {
					Files.deleteIfExists(file);
					removed++;
				} catch (IOException ignored) {
					// In use or locked: left for next time.
				}
			}
		}
		return removed;
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static void tickBackups(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		boolean on = Mods.on(BACKUPS);
		ModuleState state = Mods.state(BACKUPS);
		if (server != null && mc.level != null) {
			worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			Path name = worldDir.getFileName();
			worldName = name == null ? "world" : name.toString();
			backupOnLeave = on && state.bool(Backups.ON_LEAVE);
			boolean down = on && Mc263Features.gameplay() && Mc263Features.keyHeld(state.text(Backups.SAVE_KEY));
			if (down && !backupKeyDown) {
				backUp(mc, server, state, "Backed up ");
			}
			backupKeyDown = down;
			long now = System.currentTimeMillis();
			if (on && state.bool(Backups.SCHEDULED) && now >= nextScheduleCheck) {
				nextScheduleCheck = now + 60_000;
				long every = MoreMods.delayMillis(state.option(Backups.SCHEDULE_UNIT), state.integer(Backups.SCHEDULE_AMOUNT));
				if (now - newestBackup(mc, worldName) >= every) {
					backUp(mc, server, state, "Scheduled backup of ");
				}
			}
			return;
		}
		// Left a singleplayer world: once its server has saved and stopped, its folder is whole; back it up then.
		if (worldDir != null && server == null && mc.level == null) {
			Path dir = worldDir;
			String name = worldName;
			worldDir = null;
			if (backupOnLeave && on) {
				zipLater(mc, dir, name, state, "Backed up ");
			}
		}
	}

	/** Saves the world on its server's thread, then zips it on a worker. */
	private static void backUp(Minecraft mc, IntegratedServer server, ModuleState state, String said) {
		if (BACKING_UP.get() || worldDir == null) {
			return;
		}
		Path dir = worldDir;
		String name = worldName;
		server.execute(() -> {
			server.saveEverything(true, true, true);
			zipLater(mc, dir, name, state, said);
		});
	}

	private static void zipLater(Minecraft mc, Path dir, String name, ModuleState state, String said) {
		if (!BACKING_UP.compareAndSet(false, true)) {
			return;
		}
		boolean cull = state.bool(Backups.CULL);
		int keep = state.integer(Backups.MIN_BACKUPS);
		long olderThan = MoreMods.delayMillis(state.option(Backups.CULL_UNIT), state.integer(Backups.CULL_AMOUNT));
		Path backups = mc.gameDirectory.toPath().resolve("backups");
		net.minecraft.util.Util.ioPool().execute(() -> {
			try {
				Files.createDirectories(backups);
				Path out = backups.resolve(BACKUP_NAME.format(LocalDateTime.now()) + "_" + name + ".zip");
				Path partial = backups.resolve(out.getFileName() + ".part");
				zip(dir, partial, name);
				Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING);
				if (cull) {
					String suffix = "_" + name + ".zip";
					cull(backups, p -> p.getFileName().toString().endsWith(suffix), keep, olderThan);
				}
				Mc263NoticeToast.show(said + name);
			} catch (IOException | RuntimeException e) {
				FlintClient.LOGGER.warn("Flint: couldn't back up {}", name, e);
				Mc263NoticeToast.show("Couldn't back up " + name);
			} finally {
				BACKING_UP.set(false);
			}
		});
	}

	/** {@code dir} as a zip with the world folder at its root (as Minecraft's own backups), leaving out the session lock. */
	static void zip(Path dir, Path out, String root) throws IOException {
		try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file); Stream<Path> walk = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				if (!Files.isRegularFile(p) || p.getFileName().toString().equals("session.lock")) {
					continue;
				}
				String entry = root + "/" + dir.relativize(p).toString().replace('\\', '/');
				zip.putNextEntry(new ZipEntry(entry));
				Files.copy(p, zip);
				zip.closeEntry();
			}
		}
	}

	/** When this world's newest backup was made (0 for none). */
	private static long newestBackup(Minecraft mc, @Nullable String name) {
		Path backups = mc.gameDirectory.toPath().resolve("backups");
		if (name == null || !Files.isDirectory(backups)) {
			return 0;
		}
		String suffix = "_" + name + ".zip";
		try (Stream<Path> list = Files.list(backups)) {
			return list.filter(p -> p.getFileName().toString().endsWith(suffix)).mapToLong(Mc263MoreMods::modified).max().orElse(0);
		} catch (IOException e) {
			return 0;
		}
	}

	private static void tickTotem(Minecraft mc) {
		boolean down = Mods.on(TOTEM) && Mc263Features.gameplay() && Mc263Features.keyHeld(Mods.state(TOTEM).text(Totem.RESET_KEY));
		if (down && !resetKeyDown) {
			POPS.clear();
		}
		resetKeyDown = down;
		if (mc.level == null) {
			POPS.clear();
		}
	}

	// ---------- dropped items ----------

	/** As a dropped item is extracted: its UHC size and whether Item Despawn hides it this frame. */
	public static void extractDropped(ItemEntity entity, ItemEntityRenderState state) {
		// The size starts where Item Physics left it (Mc263Items.extract runs first and sets it each frame).
		DroppedItemState extra = (DroppedItemState) state;
		extra.flint$hidden(false);
		if (!GUARD.on()) {
			return;
		}
		try {
			if (Mods.on(UHC)) {
				extra.flint$scale(extra.flint$scale() * uhcScale(entity.getItem(), Mods.state(UHC)));
			}
			if (Mods.on(DESPAWN)) {
				ModuleState s = Mods.state(DESPAWN);
				extra.flint$hidden(despawnHidden(entity.getAge(), s.integer(ItemDespawn.LIFETIME), s.integer(ItemDespawn.FLASH_FROM), s.integer(ItemDespawn.RATE),
					s.bool(ItemDespawn.DYNAMIC), s.integer(ItemDespawn.MIN_RATE)));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/**
	 * Whether an item {@code ageTicks} old is in a hidden half of its flash: from {@code flashFromSeconds} before the end of
	 * its {@code lifetimeSeconds}, every {@code rateTicks} (or, dynamic, faster towards {@code minRateTicks} as it nears the end).
	 */
	public static boolean despawnHidden(int ageTicks, int lifetimeSeconds, int flashFromSeconds, int rateTicks, boolean dynamic, int minRateTicks) {
		int left = lifetimeSeconds * 20 - ageTicks;
		int window = flashFromSeconds * 20;
		if (left > window || left <= 0) {
			return false;
		}
		int rate = rateTicks;
		if (dynamic) {
			float k = left / (float) window;
			rate = Math.max(1, Math.round(minRateTicks + (rateTicks - minRateTicks) * k));
		}
		return ageTicks / Math.max(1, rate) % 2 == 1;
	}

	private static float uhcScale(ItemStack stack, ModuleState s) {
		// An item the player added ("Your items") has its own size, over the sizes below.
		java.util.Map<String, Integer> added = s.values(UhcOverlay.ITEMS);
		if (!added.isEmpty()) {
			Integer hundredths = added.get(Mc263Items.id(stack.getItem()));
			if (hundredths != null) {
				return hundredths / 100F;
			}
		}
		if (stack.is(Items.GOLDEN_APPLE)) {
			return s.decimal(UhcOverlay.GOLDEN_APPLE);
		}
		if (stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
			return s.decimal(UhcOverlay.GOD_APPLE);
		}
		if (stack.is(Items.GOLD_ORE) || stack.is(Items.DEEPSLATE_GOLD_ORE) || stack.is(Items.NETHER_GOLD_ORE) || stack.is(Items.RAW_GOLD)) {
			return s.decimal(UhcOverlay.GOLD_ORE);
		}
		if (stack.is(Items.GOLD_INGOT)) {
			return s.decimal(UhcOverlay.GOLD_INGOT);
		}
		if (stack.is(Items.GOLD_NUGGET)) {
			return s.decimal(UhcOverlay.GOLD_NUGGET);
		}
		if (stack.is(Items.PLAYER_HEAD) || stack.is(Items.SKELETON_SKULL) || stack.is(Items.WITHER_SKELETON_SKULL) || stack.is(Items.ZOMBIE_HEAD)
			|| stack.is(Items.CREEPER_HEAD) || stack.is(Items.PIGLIN_HEAD) || stack.is(Items.DRAGON_HEAD)) {
			return s.decimal(UhcOverlay.SKULL);
		}
		if (stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE) != null && !stack.is(Items.ELYTRA) && !stack.is(Items.CARVED_PUMPKIN)) {
			return s.decimal(UhcOverlay.ARMOR);
		}
		if (stack.is(Items.EXPERIENCE_BOTTLE)) {
			return s.decimal(UhcOverlay.XP_BOTTLE);
		}
		if (stack.is(Items.ENDER_PEARL)) {
			return s.decimal(UhcOverlay.PEARL);
		}
		if (stack.is(Items.COBWEB)) {
			return s.decimal(UhcOverlay.COBWEB);
		}
		if (stack.is(Items.TOTEM_OF_UNDYING)) {
			return s.decimal(UhcOverlay.TOTEM);
		}
		if (stack.is(Items.MACE)) {
			return s.decimal(UhcOverlay.MACE);
		}
		return 1;
	}

	/** Item Despawn: whether this frame skips the item. */
	public static boolean droppedHidden(ItemEntityRenderState state) {
		return ((DroppedItemState) state).flint$hidden();
	}

	/** UHC Overlay: the item drawn bigger about where it stands. */
	public static void droppedScale(PoseStack pose, ItemEntityRenderState state) {
		float scale = ((DroppedItemState) state).flint$scale();
		// Flint's own poses scale about the item's centre themselves (Mc263Items.turn).
		if (scale != 1 && !Mc263Items.scaledByFlint(state)) {
			pose.scale(scale, scale, scale);
		}
	}

	// ---------- elytras ----------

	/** Elytras: an elytra's tooltip says how much flight it has left (one durability point a second, as Minecraft wears it). */
	public static void elytraTooltip(ItemStack stack, Consumer<Component> builder) {
		if (!GUARD.on() || !stack.is(Items.ELYTRA) || !Mods.on(ELYTRAS) || !Mods.state(ELYTRAS).bool(Elytras.TOOLTIP)) {
			return;
		}
		int left = Math.max(0, stack.getMaxDamage() - stack.getDamageValue() - 1);
		builder.accept(Component.literal("Flight left: " + flightTime(left)).withStyle(ChatFormatting.GRAY));
	}

	/** "7m 12s" for {@code seconds}. */
	public static String flightTime(int seconds) {
		int m = seconds / 60;
		int s = seconds % 60;
		return m > 0 ? m + "m " + s + "s" : s + "s";
	}

	/** Elytras: whether the wings sway as you move. */
	public static boolean elytraPhysics() {
		return GUARD.on() && Mods.on(ELYTRAS) && Mods.state(ELYTRAS).bool(Elytras.PHYSICS);
	}

	// ---------- totem ----------

	/** A player used a totem (entity event 35): the pop counter. */
	public static void totemPopped(@Nullable Entity entity) {
		if (GUARD.on() && entity instanceof Player player && Mods.on(TOTEM)) {
			POPS.merge(player.getUUID(), 1, Integer::sum);
		}
	}

	/** The pop counter after a player's name, when it's on and they have used any. */
	public static Component totemTag(Entity entity, Component name) {
		if (!GUARD.on() || !(entity instanceof Player) || !Mods.on(TOTEM) || !Mods.state(TOTEM).bool(Totem.COUNTER)) {
			return name;
		}
		Integer pops = POPS.get(entity.getUUID());
		if (pops == null) {
			return name;
		}
		return name.copy().append(Component.literal(" ⬢" + pops).withStyle(ChatFormatting.GOLD));
	}

	/** Whether a totem's particles burst from {@code entity} (your own can be left out). */
	public static boolean totemParticles(Entity entity) {
		return !GUARD.on() || !Mods.on(TOTEM) || entity != mc().player || Mods.state(TOTEM).bool(Totem.SHOW_OWN);
	}

	/** Whether the big totem shows on screen when you use one. */
	public static boolean popAnimation() {
		return !GUARD.on() || !Mods.on(TOTEM) || Mods.state(TOTEM).bool(Totem.POP_ANIMATION);
	}

	/** Before the totem animation is drawn: its size and offset (call {@link #popEnd} after when this returns true). */
	public static boolean popStart(PoseStack pose) {
		if (!GUARD.on() || !Mods.on(TOTEM)) {
			return false;
		}
		ModuleState s = Mods.state(TOTEM);
		float scale = s.decimal(Totem.POP_SCALE);
		float x = s.decimal(Totem.POP_X);
		float y = s.decimal(Totem.POP_Y);
		if (scale == 1 && x == 0 && y == 0) {
			return false;
		}
		pose.pushPose();
		pose.translate(x, y, 0);
		pose.scale(scale, scale, scale);
		return true;
	}

	public static void popEnd(PoseStack pose) {
		pose.popPose();
	}

	/** A held totem's size in first person (1: Minecraft's). */
	public static float handScale(ItemStack stack) {
		if (!GUARD.on() || !stack.is(Items.TOTEM_OF_UNDYING) || !Mods.on(TOTEM)) {
			return 1;
		}
		return Mods.state(TOTEM).decimal(Totem.HAND_SCALE);
	}

	/** A totem particle as it is made: its size, life and colours ({@code apply} sets them on the particle). */
	public static void totemParticle(TotemParticleTuner apply) {
		if (!GUARD.on() || !Mods.on(TOTEM)) {
			return;
		}
		ModuleState s = Mods.state(TOTEM);
		apply.size(s.decimal(Totem.PARTICLE_SCALE));
		apply.life(s.decimal(Totem.PARTICLE_LIFE));
		if (s.bool(Totem.CUSTOM_COLOR)) {
			int a = s.rgb(Totem.COLOR_1);
			int b = s.rgb(Totem.COLOR_2);
			float t = (float) Math.random();
			apply.color(com.flintclient.client.render.Palette.lerp(0xFF000000 | a, 0xFF000000 | b, t) & 0xFFFFFF);
		}
	}

	/** What a totem particle lets Totem change. */
	public interface TotemParticleTuner {
		void size(float factor);

		void life(float factor);

		void color(int rgb);
	}

	// ---------- subtitles ----------

	/** A subtitle in its kind of sound's colour. */
	public static Component subtitle(Component text, SoundInstance sound) {
		if (!GUARD.on() || !Mods.on(SUBTITLES)) {
			return text;
		}
		try {
			ModuleState s = Mods.state(SUBTITLES);
			if (!s.bool(Subtitles.COLORS)) {
				return text;
			}
			int option = switch (sound.getSource()) {
				case MUSIC -> s.option(Subtitles.MUSIC);
				case RECORDS -> s.option(Subtitles.RECORDS);
				case WEATHER -> s.option(Subtitles.WEATHER);
				case BLOCKS -> s.option(Subtitles.BLOCKS);
				case HOSTILE -> s.option(Subtitles.HOSTILE);
				case NEUTRAL -> s.option(Subtitles.NEUTRAL);
				case PLAYERS -> s.option(Subtitles.PLAYERS);
				case AMBIENT -> s.option(Subtitles.AMBIENT);
				case VOICE -> s.option(Subtitles.VOICE);
				default -> 0;
			};
			ChatFormatting color = chatColor(option);
			return color == null ? text : text.copy().withStyle(color);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return text;
		}
	}

	/** Option index (None, then Minecraft's sixteen colours in their usual order) to its formatting; null for None. */
	static @Nullable ChatFormatting chatColor(int option) {
		ChatFormatting[] colors = {ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED,
			ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.GRAY, ChatFormatting.DARK_GRAY, ChatFormatting.BLUE, ChatFormatting.GREEN,
			ChatFormatting.AQUA, ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW, ChatFormatting.WHITE};
		return option <= 0 || option > colors.length ? null : colors[option - 1];
	}

	// ---------- hypixel ----------

	/** Whether you are on Hypixel. */
	static boolean onHypixel() {
		ServerData server = mc().getCurrentServer();
		return server != null && com.flintclient.client.control.feature.HypixelChat.isHypixel(server.ip);
	}

	private static void tickHypixel(Minecraft mc) {
		if (!Mods.on(HYPIXEL) || mc.getConnection() == null || !onHypixel()) {
			ggInTicks = -1;
			return;
		}
		ModuleState s = Mods.state(HYPIXEL);
		long now = System.currentTimeMillis();
		if (s.bool(Hypixel.AUTO_TIP)) {
			if (nextTip == 0) {
				// The first tip a minute after you arrive.
				nextTip = now + 60_000;
			} else if (now >= nextTip) {
				mc.getConnection().sendCommand("tip all");
				nextTip = now + s.integer(Hypixel.TIP_INTERVAL) * 1000L;
			}
		}
		if (ggInTicks > 0 && --ggInTicks == 0) {
			String text = s.text(Hypixel.GG_TEXT).strip();
			if (!text.isEmpty()) {
				send(mc.getConnection(), text);
			}
			ggInTicks = -1;
		}
	}

	/**
	 * A message on its way into the chat: whether to hide it (Anti GG, Anti Lobby Join, friend messages), and what it starts
	 * (Auto GG at a game's end, Auto Friend on a request, the oof on your kill).
	 */
	public static boolean hideIncoming(Component message) {
		if (!GUARD.on() || !Mods.on(HYPIXEL) || !onHypixel()) {
			return false;
		}
		try {
			ModuleState s = Mods.state(HYPIXEL);
			String text = ChatFormatting.stripFormatting(message.getString()).strip();
			if (s.bool(Hypixel.ANTI_LOBBY_JOIN) && (text.endsWith("joined the lobby!") || text.contains("spooked into the lobby") || text.endsWith(
				"slid into the lobby!"))) {
				return true;
			}
			if (s.bool(Hypixel.ANTI_GG) && GG_LINE.matcher(text).matches()) {
				return true;
			}
			if (!s.bool(Hypixel.FRIEND_MESSAGES) && text.startsWith("Friend > ") && (text.endsWith(" joined.") || text.endsWith(" left."))) {
				return true;
			}
			if (s.bool(Hypixel.AUTO_GG) && ggInTicks < 0 && System.currentTimeMillis() - lastGg > 10_000) {
				for (String marker : GAME_OVER) {
					if (text.contains(marker)) {
						ggInTicks = Math.max(1, s.integer(Hypixel.GG_DELAY));
						lastGg = System.currentTimeMillis();
						break;
					}
				}
			}
			if (s.bool(Hypixel.AUTO_FRIEND)) {
				// Only Hypixel's own request line counts (a player's chat line can't start one), at most one accept every 2 s.
				String sender = com.flintclient.client.control.feature.HypixelChat.friendRequestSender(text);
				if (sender != null && mc().getConnection() != null && HYPIXEL_CHAT.mayAccept(System.currentTimeMillis())) {
					mc().getConnection().sendCommand("friend accept " + sender);
				}
			}
			if (s.bool(Hypixel.OOF) && mc().player != null) {
				String me = mc().player.getGameProfile().name();
				if (text.contains(" by " + me) || text.endsWith(" " + me + ".")) {
					Mc263Features.playSound("minecraft:entity.player.hurt", s.integer(Hypixel.OOF_VOLUME) / 30.0F);
				}
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
		return false;
	}

	// ---------- discord ----------

	/** Every few seconds while on: what you are doing, for Flint's app to show on Discord (only when it changes). */
	private static void tickDiscord(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now < nextPresence) {
			return;
		}
		nextPresence = now + 5_000;
		Path file = mc.gameDirectory.toPath().resolve("flint-presence.json");
		if (!Mods.on(DISCORD)) {
			if (!lastPresence.isEmpty()) {
				lastPresence = "";
				try {
					Files.deleteIfExists(file);
				} catch (IOException ignored) {
					// The app keeps the plain activity.
				}
			}
			return;
		}
		ModuleState s = Mods.state(DISCORD);
		JsonObject out = new JsonObject();
		if (s.bool(Discord.VERSION)) {
			out.addProperty("version", SharedConstants.getCurrentVersion().name());
		}
		IntegratedServer local = mc.getSingleplayerServer();
		ServerData server = mc.getCurrentServer();
		if (mc.level == null) {
			out.addProperty("state", "In the menus");
		} else if (local != null) {
			out.addProperty("state", "Singleplayer");
			if (s.bool(Discord.WORLD)) {
				out.addProperty("world", local.getWorldData().getLevelName());
			}
		} else if (server != null) {
			out.addProperty("state", "Multiplayer");
			// Never the address: a private server's IP is the owner's. Only a well-known public server is named.
			String name = s.bool(Discord.SERVER) ? publicServerName(server.ip) : null;
			if (name != null) {
				out.addProperty("server", name);
			} else if (s.bool(Discord.HYPIXEL_MODE) && onHypixel()) {
				out.addProperty("server", "Hypixel");
			}
		}
		if (s.bool(Discord.DIMENSION_PRESENCE) || s.bool(Discord.SCREEN_PRESENCE)) {
			out.addProperty("status", presenceStatus);
		}
		String label1 = s.text(Discord.BUTTON1_LABEL).strip();
		String label2 = s.text(Discord.BUTTON2_LABEL).strip();
		com.google.gson.JsonArray buttons = new com.google.gson.JsonArray();
		button(buttons, label1, s.text(Discord.BUTTON1_URL));
		button(buttons, label2, s.text(Discord.BUTTON2_URL));
		if (!buttons.isEmpty()) {
			out.add("buttons", buttons);
		}
		if (mc.level != null && mc.player != null) {
			// With Dimension presence the status already says where you are.
			if (s.bool(Discord.DIMENSION) && !s.bool(Discord.DIMENSION_PRESENCE)) {
				String id = mc.level.dimension().identifier().getPath().replace('_', ' ');
				out.addProperty("dimension", id.equals("overworld") ? "Overworld" : id.equals("the nether") ? "Nether" : id.equals("the end") ? "The End" : id);
			}
			if (s.bool(Discord.BIOME)) {
				mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
					.ifPresent(key -> out.addProperty("biome", words(key.identifier().getPath())));
			}
			if (s.bool(Discord.RIDING) && mc.player.getVehicle() != null) {
				out.addProperty("riding", mc.player.getVehicle().getType().getDescription().getString());
			}
		}
		String json = out.toString();
		if (json.equals(lastPresence)) {
			return;
		}
		lastPresence = json;
		if (s.bool(Discord.LOG_STATE)) {
			FlintClient.LOGGER.info("Sent state to discord: {}", json);
		}
		net.minecraft.util.Util.ioPool().execute(() -> {
			try {
				Path partial = file.resolveSibling("flint-presence.json.part");
				Files.writeString(partial, json, StandardCharsets.UTF_8);
				Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException ignored) {
				// Next change tries again.
			}
		});
	}

	/** Well-known public servers by their domain, the only servers Discord names. */
	private static final Map<String, String> PUBLIC_SERVERS = Map.ofEntries(Map.entry("hypixel.net", "Hypixel"),
		Map.entry("minemen.club", "Minemen Club"), Map.entry("cubecraft.net", "CubeCraft"), Map.entry("hivemc.com", "The Hive"),
		Map.entry("playhive.com", "The Hive"), Map.entry("wynncraft.com", "Wynncraft"), Map.entry("2b2t.org", "2b2t"),
		Map.entry("manacube.com", "ManaCube"), Map.entry("mc-complex.com", "Complex Gaming"), Map.entry("jartexnetwork.com", "JartexNetwork"),
		Map.entry("pika-network.net", "PikaNetwork"), Map.entry("lunar.gg", "Lunar Network"), Map.entry("pvp.land", "PvP Land"),
		Map.entry("mccentral.org", "MCCentral"), Map.entry("bedwarspractice.club", "Bedwars Practice"), Map.entry("blocksmc.com", "BlocksMC"),
		Map.entry("mineplex.com", "Mineplex"), Map.entry("purpleprison.org", "Purple Prison"), Map.entry("opblocks.com", "OPBlocks"),
		Map.entry("herobrine.org", "Herobrine.org"), Map.entry("minehut.com", "Minehut"), Map.entry("ranked.gg", "Ranked"));

	/** The public name of a well-known server at {@code address} (any subdomain, any port); null for every other server. */
	static @Nullable String publicServerName(@Nullable String address) {
		if (address == null) {
			return null;
		}
		String host = address.strip().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon >= 0 && host.indexOf(':') == colon) {
			host = host.substring(0, colon);
		}
		while (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		for (Map.Entry<String, String> server : PUBLIC_SERVERS.entrySet()) {
			if (host.equals(server.getKey()) || host.endsWith("." + server.getKey())) {
				return server.getValue();
			}
		}
		return null;
	}

	/** A profile button: a label and an http(s) address, as Discord takes them; nothing when either is missing. */
	private static void button(com.google.gson.JsonArray buttons, String label, String url) {
		String address = url.strip();
		if (label.isEmpty() || !(address.startsWith("https://") || address.startsWith("http://"))) {
			return;
		}
		JsonObject button = new JsonObject();
		button.addProperty("label", label);
		button.addProperty("url", address);
		buttons.add(button);
	}

	/**
	 * Simple Discord Rich Presence's events, each client tick: one of its screens opening says you're on the main menu; joining a
	 * world or changing dimension says which dimension you're in. The status stays until the next one.
	 */
	private static void tickPresenceEvents(Minecraft mc) {
		if (!Mods.on(DISCORD)) {
			return;
		}
		ModuleState s = Mods.state(DISCORD);
		net.minecraft.client.gui.screens.Screen screen = mc.gui.screen();
		if (screen != presenceScreen) {
			presenceScreen = screen;
			if (screen != null && s.bool(Discord.SCREEN_PRESENCE) && presenceScreen(screen, s.text(Discord.SCREENS))) {
				presenceStatus = s.text(Discord.SCREEN_MESSAGE);
			}
		}
		Object level = mc.level;
		Object dimension = mc.level == null ? null : mc.level.dimension();
		if (level != presenceLevel || dimension != presenceDimension) {
			presenceLevel = level;
			presenceDimension = dimension;
			if (mc.level != null && mc.player != null && s.bool(Discord.DIMENSION_PRESENCE)) {
				presenceStatus = dimensionMessage(s, mc.level.dimension().identifier(), mc.player);
			}
		}
	}

	/** Whether {@code screen} is one the list names (by class; a subclass counts, so Flint's main menu is the title screen). */
	static boolean presenceScreen(net.minecraft.client.gui.screens.Screen screen, String classes) {
		for (String name : classes.split(",")) {
			String trimmed = name.strip();
			for (Class<?> type = screen.getClass(); type != null && !trimmed.isEmpty(); type = type.getSuperclass()) {
				if (type.getName().equals(trimmed)) {
					return true;
				}
			}
		}
		return false;
	}

	/** The dimension message with its variables filled in, "In " in front when asked. */
	static String dimensionMessage(ModuleState s, net.minecraft.resources.Identifier dimension, net.minecraft.world.entity.player.Player player) {
		String key = dimension.toLanguageKey("dimension");
		String name = net.minecraft.client.resources.language.I18n.get(key);
		if (name.equals(key)) {
			name = words(dimension.getPath());
		}
		String message = s.text(Discord.DIMENSION_MESSAGE).replace("{{dimension.name}}", name)
			.replace("{{dimension.identifier}}", dimension.toString()).replace("{{dimension.namespace}}", dimension.getNamespace())
			.replace("{{dimension.path}}", dimension.getPath()).replace("{{player.uuid}}", player.getUUID().toString())
			.replace("{{player.name}}", player.getName().getString());
		return s.bool(Discord.DIMENSION_PREFIX) ? "In " + message : message;
	}

	/** "dark_forest" as "Dark Forest". */
	static String words(String id) {
		StringBuilder out = new StringBuilder();
		for (String w : id.split("_")) {
			if (!w.isEmpty()) {
				out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return out.toString();
	}

	/** Whether {@code source} is a sound category Subtitles colours (for tests). */
	static boolean coloured(SoundSource source) {
		return source != SoundSource.MASTER && source != SoundSource.UI;
	}
}

package com.flintclient.client.hotload;

import com.flintclient.client.FlintLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Puts a Fabric mod into the running game, no restart: the Library calls {@link #load(Path)} after a mod's jar landed in the
 * profile's mods folder, and uses Instant Swap when the answer is false. Only mods {@link HotLoadRule} accepts go in (no
 * mixins, no access widener, no content); the next launch loads the same jar the usual way.
 *
 * <p>What a load does:
 * <ol>
 * <li>off the render thread: reads the jar, checks the rule and the mod's dependencies against the loaded mods' versions
 * (Fabric's own matcher), parses its metadata with Fabric's parser, adds the jar to Knot's class path and loads (doesn't
 * initialise) its client entrypoint classes;</li>
 * <li>on the render thread (one task, a few ms): adds its {@code ModContainer} to Fabric Loader (so
 * {@code FabricLoader.getAllMods()}, {@code isModLoaded} and Mod Menu's list see it), adds its entrypoints, runs its
 * {@code client} entrypoints, then gives it what it missed by starting late: the key mappings it registered join
 * {@code Options.keyMappings} (Controls lists them, the key works), its {@code CLIENT_STARTED} and, in a world, {@code JOIN}
 * listeners run once, and its translations join the current language.</li>
 * </ol>
 * Never throws: anything unexpected completes the future with false (the caller swaps). Once the container is in, a later
 * failure still answers false, and the swap starts a game that loads the mod the normal way.
 */
public final class HotLoader {
	/** True while a hot-loaded mod's client entrypoints run (Fabric API's key mapping registry then accepts late keys). */
	private static final AtomicBoolean INITIALISING = new AtomicBoolean();
	private static final Object LOCK = new Object();
	/** Mod ids between the class path step and the render thread step (a second load of the same mod is refused). */
	private static final java.util.Set<String> PENDING = new java.util.HashSet<>();

	private HotLoader() {
	}

	/** A load's answer; {@code renderThreadMs} is the time the render thread spent on it (the frame it cost). */
	public record Result(boolean loaded, String modId, String reason, long prepareMs, long renderThreadMs) {
		static Result no(@Nullable String id, String reason) {
			return new Result(false, id == null ? "?" : id, reason, 0, 0);
		}
	}

	/** Whether a hot-loaded mod's client entrypoints are running now (the key mapping hook). */
	public static boolean initialising() {
		return INITIALISING.get();
	}

	/** Only the rule, no loading (the Library may show "instant" before Install). */
	public static HotLoadRule.Verdict check(Path jar) {
		try {
			return read(jar).verdict;
		} catch (Throwable t) {
			return new HotLoadRule.Verdict(false, "unreadable jar: " + t);
		}
	}

	/**
	 * Loads {@code jar} into the running game. Call it from any thread; the future completes on the render thread (or the
	 * caller's, for a quick "no"). Never completes exceptionally.
	 */
	public static CompletableFuture<Result> load(Path jar) {
		CompletableFuture<Result> done = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				prepare(jar, done);
			} catch (Throwable t) {
				FlintLog.LOGGER.warn("Hot load of {} failed: {}", jar.getFileName(), t.toString());
				done.complete(Result.no(null, "failed: " + t));
			}
		}, "Flint hot load");
		worker.setDaemon(true);
		worker.start();
		return done;
	}

	/** QA and logs: loads {@code jar} and logs the answer ("Hot load: ..."). */
	public static String qaLoad(String jar) {
		long start = System.nanoTime();
		load(Path.of(jar)).thenAccept(r -> FlintLog.LOGGER.info("Hot load: loaded={} mod={} reason={} prepareMs={} renderThreadMs={} totalMs={}", r.loaded(),
			r.modId(), r.reason(), r.prepareMs(), r.renderThreadMs(), (System.nanoTime() - start) / 1_000_000));
		return "started";
	}

	/** QA: presses a key mapping by its name as the keyboard would (one click). */
	public static String qaClick(String name) {
		KeyMapping key = null;
		for (KeyMapping k : Minecraft.getInstance().options.keyMappings) {
			if (k.getName().equals(name)) {
				key = k;
			}
		}
		if (key == null) {
			return "no key " + name;
		}
		try {
			KeyMapping.click((com.mojang.blaze3d.platform.InputConstants.Key) field(KeyMapping.class, "key").get(key));
		} catch (ReflectiveOperationException e) {
			return "failed " + e;
		}
		return "clicked " + name + " (" + key.saveString() + ")";
	}

	/** QA: whether FabricLoader lists the mod. */
	public static String qaListed(String id) {
		long keys = Arrays.stream(Minecraft.getInstance().options.keyMappings).filter(k -> k.getName().contains(id)).count();
		return id + " listed=" + FabricLoader.getInstance().isModLoaded(id) + " mods=" + FabricLoader.getInstance().getAllMods().size() + " optionsKeys=" + keys;
	}

	// ---------- off the render thread ----------

	private record Read(@Nullable JsonObject fmj, byte[] fmjBytes, List<String> entries, HotLoadRule.Verdict verdict, Map<String, byte[]> lang) {
	}

	private static Read read(Path jar) throws Exception {
		List<String> entries = new ArrayList<>();
		List<String> nested = new ArrayList<>();
		Map<String, byte[]> lang = new HashMap<>();
		byte[] fmjBytes = null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (var it = zip.entries(); it.hasMoreElements();) {
				ZipEntry e = it.nextElement();
				entries.add(e.getName());
				if (e.getName().equals("fabric.mod.json")) {
					fmjBytes = zip.getInputStream(e).readAllBytes();
				} else if (e.getName().startsWith("META-INF/jars/") && e.getName().endsWith(".jar")) {
					nested.add(nestedId(zip, e));
				} else if (e.getName().matches("assets/[^/]+/lang/[^/]+\\.json") && e.getSize() < (4 << 20)) {
					lang.put(e.getName(), zip.getInputStream(e).readAllBytes());
				}
			}
		}
		JsonObject fmj = fmjBytes == null ? null
			: JsonParser.parseString(new String(fmjBytes, StandardCharsets.UTF_8).replace("﻿", "")).getAsJsonObject();
		FabricLoader loader = FabricLoader.getInstance();
		HotLoadRule.Verdict verdict = HotLoadRule.check(fmj, entries, nested, loader::isModLoaded);
		return new Read(fmj, fmjBytes == null ? new byte[0] : fmjBytes, entries, verdict, lang);
	}

	private static @Nullable String nestedId(ZipFile zip, ZipEntry jarEntry) throws Exception {
		try (ZipInputStream in = new ZipInputStream(zip.getInputStream(jarEntry))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				if (e.getName().equals("fabric.mod.json")) {
					JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
					return o.has("id") ? o.get("id").getAsString() : null;
				}
			}
		}
		return null;
	}

	private static void prepare(Path jar, CompletableFuture<Result> done) throws Exception {
		long start = System.nanoTime();
		if (!Files.isRegularFile(jar)) {
			done.complete(Result.no(null, "no such jar"));
			return;
		}
		Read read = read(jar);
		String id = read.fmj != null && read.fmj.has("id") ? read.fmj.get("id").getAsString() : null;
		if (!read.verdict.ok()) {
			FlintLog.LOGGER.info("Hot load: {} needs a swap ({})", jar.getFileName(), read.verdict.reason());
			done.complete(Result.no(id, read.verdict.reason()));
			return;
		}
		FabricLoaderImpl impl = FabricLoaderImpl.INSTANCE;
		LoaderModMetadata meta;
		try (InputStream in = new ByteArrayInputStream(read.fmjBytes)) {
			meta = ModMetadataParser.parseMetadata(in, jar.toString(), List.of(), new VersionOverrides(), new DependencyOverrides(impl.getConfigDir()),
				impl.isDevelopmentEnvironment());
		}
		String unmet = unmetDependency(meta);
		if (unmet != null) {
			done.complete(Result.no(id, unmet));
			return;
		}
		synchronized (LOCK) {
			if (impl.isModLoaded(meta.getId()) || !PENDING.add(meta.getId())) {
				done.complete(Result.no(id, meta.getId() + " is loaded already"));
				return;
			}
			ModContainerImpl container = container(jar, meta);
			FabricLauncherBase.getLauncher().addToClassPath(jar);
			// Class loading (reading, the transformer pass, verification) here, not on the render thread.
			ClassLoader knot = FabricLauncherBase.getLauncher().getTargetClassLoader();
			for (String key : meta.getEntrypointKeys()) {
				for (EntrypointMetadata ep : meta.getEntrypoints(key)) {
					String value = ep.getValue();
					String cls = value.contains("::") ? value.substring(0, value.indexOf("::")) : value;
					try {
						Class.forName(cls, false, knot);
					} catch (Throwable t) {
						if (key.equals("client")) {
							throw t;
						}
					}
				}
			}
			long prepareMs = (System.nanoTime() - start) / 1_000_000;
			Minecraft.getInstance().execute(() -> {
				Result result = onRenderThread(container, meta, read, prepareMs);
				synchronized (LOCK) {
					PENDING.remove(meta.getId());
				}
				done.complete(result);
			});
		}
	}

	/** Why a dependency (or a break) stops the load, else null. Fabric's matcher, against the loaded mods' versions. */
	private static @Nullable String unmetDependency(LoaderModMetadata meta) {
		FabricLoader loader = FabricLoader.getInstance();
		for (ModDependency dep : meta.getDependencies()) {
			ModContainer have = loader.getModContainer(dep.getModId()).orElse(null);
			switch (dep.getKind()) {
				case DEPENDS -> {
					if (have == null) {
						return "needs " + dep.getModId() + ", which isn't loaded";
					}
					if (!dep.matches(have.getMetadata().getVersion())) {
						return "needs " + dep.getModId() + " " + dep.getVersionRequirements() + ", the game has " + have.getMetadata().getVersion();
					}
				}
				case BREAKS -> {
					if (have != null && dep.matches(have.getMetadata().getVersion())) {
						return "breaks with " + dep.getModId() + " " + have.getMetadata().getVersion();
					}
				}
				default -> {
				}
			}
		}
		return null;
	}

	private static ModContainerImpl container(Path jar, LoaderModMetadata meta) throws Exception {
		Class<?> candidates = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
		Method plain = candidates.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
		plain.setAccessible(true);
		Object candidate = plain.invoke(null, List.of(jar), meta, false, List.of());
		return ModContainerImpl.class.getConstructor(candidates).newInstance(candidate);
	}

	// ---------- on the render thread ----------

	@SuppressWarnings("unchecked")
	private static Result onRenderThread(ModContainerImpl container, LoaderModMetadata meta, Read read, long prepareMs) {
		long start = System.nanoTime();
		String id = meta.getId();
		try {
			FabricLoaderImpl impl = FabricLoaderImpl.INSTANCE;
			Minecraft mc = Minecraft.getInstance();
			((List<ModContainerImpl>) field(FabricLoaderImpl.class, "mods").get(impl)).add(container);
			Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) field(FabricLoaderImpl.class, "modMap").get(impl);
			modMap.put(id, container);
			for (String provides : meta.getProvides()) {
				modMap.putIfAbsent(provides, container);
			}
			EntrypointStorage storage = (EntrypointStorage) field(FabricLoaderImpl.class, "entrypointStorage").get(impl);
			Map<String, Object> adapters = (Map<String, Object>) field(FabricLoaderImpl.class, "adapterMap").get(impl);
			Method add = EntrypointStorage.class.getMethod("add", ModContainerImpl.class, String.class, EntrypointMetadata.class, Map.class);
			for (String key : meta.getEntrypointKeys()) {
				for (EntrypointMetadata ep : meta.getEntrypoints(key)) {
					add.invoke(storage, container, key, ep, adapters);
				}
			}

			List<Object> startedBefore = handlers("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents", "CLIENT_STARTED");
			List<Object> joinBefore = handlers("net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents", "JOIN");
			KeyMapping[] keysBefore = mc.options.keyMappings;
			INITIALISING.set(true);
			try {
				for (EntrypointContainer<ClientModInitializer> ep : FabricLoader.getInstance().getEntrypointContainers("client", ClientModInitializer.class)) {
					if (ep.getProvider() == container) {
						ep.getEntrypoint().onInitializeClient();
					}
				}
			} finally {
				INITIALISING.set(false);
			}
			addKeyMappings(mc, keysBefore);
			replay("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents", "CLIENT_STARTED", startedBefore, mc);
			if (mc.getConnection() != null) {
				replayJoin(joinBefore, mc);
			}
			addTranslations(read.lang, mc);
			long ms = (System.nanoTime() - start) / 1_000_000;
			FlintLog.LOGGER.info("Hot load: {} {} is in the running game (prepare {} ms off-thread, {} ms on the render thread)", meta.getName(),
				meta.getVersion().getFriendlyString(), prepareMs, ms);
			return new Result(true, id, "loaded", prepareMs, ms);
		} catch (Throwable t) {
			FlintLog.LOGGER.warn("Hot load of {} failed on the render thread: {}", id, t.toString());
			return new Result(false, id, "failed: " + t, prepareMs, (System.nanoTime() - start) / 1_000_000);
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	/** Fabric API registered the mod's keys in its list (see the hook); they join Options.keyMappings as at start. */
	private static void addKeyMappings(Minecraft mc, KeyMapping[] before) throws Exception {
		Class<?> registry;
		try {
			registry = Class.forName("net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl", true, HotLoader.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			return;
		}
		KeyMapping[] merged = (KeyMapping[]) registry.getMethod("process", KeyMapping[].class).invoke(null, (Object) before);
		if (merged.length == before.length) {
			return;
		}
		List<KeyMapping> added = new ArrayList<>(Arrays.asList(merged));
		added.removeAll(Arrays.asList(before));
		KeyMapping[] next = Arrays.copyOf(before, before.length + added.size());
		for (int i = 0; i < added.size(); i++) {
			next[before.length + i] = added.get(i);
		}
		field(net.minecraft.client.Options.class, "keyMappings").set(mc.options, next);
		KeyMapping.resetMapping();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> handlers(String owner, String name) {
		try {
			Object event = Class.forName(owner, true, HotLoader.class.getClassLoader()).getField(name).get(null);
			Object[] list = (Object[]) field(event.getClass(), "handlers").get(event);
			return new ArrayList<>(Arrays.asList(list));
		} catch (Throwable t) {
			return null;
		}
	}

	/** The listeners the new mod added to a "started" event run once now (the event fired before it was here). */
	private static void replay(String owner, String name, @Nullable List<Object> before, Minecraft mc) throws Exception {
		List<Object> now = handlers(owner, name);
		if (before == null || now == null) {
			return;
		}
		now.removeAll(before);
		for (Object h : now) {
			for (Method m : h.getClass().getInterfaces()[0].getMethods()) {
				if (m.getParameterCount() == 1 && !m.isDefault()) {
					m.invoke(h, mc);
				}
			}
		}
	}

	private static void replayJoin(@Nullable List<Object> before, Minecraft mc) throws Exception {
		String owner = "net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents";
		List<Object> now = handlers(owner, "JOIN");
		if (before == null || now == null) {
			return;
		}
		now.removeAll(before);
		if (now.isEmpty()) {
			return;
		}
		Object sender = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", true, HotLoader.class.getClassLoader())
			.getMethod("getSender").invoke(null);
		for (Object h : now) {
			for (Method m : h.getClass().getInterfaces()[0].getMethods()) {
				if (m.getParameterCount() == 3 && !m.isDefault()) {
					m.invoke(h, mc.getConnection(), sender, mc);
				}
			}
		}
	}

	/** The mod's translations (English, and the selected language's) on top of the current language, until the next reload. */
	private static void addTranslations(Map<String, byte[]> files, Minecraft mc) {
		if (files.isEmpty()) {
			return;
		}
		String selected = mc.options.languageCode;
		Map<String, String> extra = new HashMap<>();
		for (String code : List.of("en_us", selected)) {
			files.forEach((name, bytes) -> {
				if (name.endsWith("/lang/" + code + ".json")) {
					try {
						Language.loadFromJson(new ByteArrayInputStream(bytes), extra::put);
					} catch (Throwable ignored) {
						// a broken file: its keys show as keys
					}
				}
			});
		}
		if (extra.isEmpty()) {
			return;
		}
		Language base = Language.getInstance();
		Language.inject(new Language() {
			// Both forms, without @Override: older Minecraft (1.19) has only the one-argument method, newer has both.
			public String getOrDefault(String key, String fallback) {
				return base.has(key) ? base.getOrDefault(key) : extra.getOrDefault(key, fallback);
			}

			public String getOrDefault(String key) {
				return getOrDefault(key, key);
			}

			@Override
			public boolean has(String key) {
				return base.has(key) || extra.containsKey(key);
			}

			@Override
			public boolean isDefaultRightToLeft() {
				return base.isDefaultRightToLeft();
			}

			@Override
			public FormattedCharSequence getVisualOrder(FormattedText text) {
				return base.getVisualOrder(text);
			}
		});
	}
}

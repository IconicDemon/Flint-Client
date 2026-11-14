package com.flintclient.legacy.swap;

import com.flintclient.client.hotload.HotLoadRule;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.mixin.swap.HotLoadI18nAccess;
import com.flintclient.legacy.mixin.swap.HotLoadKeysAccess;
import com.flintclient.legacy.mixin.swap.HotLoadLocaleAccess;
import com.flintclient.shaded.gson.JsonObject;
import com.flintclient.shaded.gson.JsonParser;
import java.io.BufferedReader;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import org.jspecify.annotations.Nullable;

/**
 * Puts a Fabric mod into the running 1.8.9 game, no restart: the 1.8.9 counterpart of 26.x's {@code hotload.HotLoader}, on the
 * same Fabric Loader internals (Loader 0.20.1 through Ornithe; the same calls as 0.19.5) and the same rule
 * ({@link HotLoadRule}: no mixins, no access widener, no content). What differs is what a mod that starts late misses on
 * 1.8.9: its key bindings (made by its client entrypoint, they register themselves with Minecraft's list) join the game's
 * options, so Controls lists them and the key works, and its translations ({@code assets/NS/lang/en_US.lang} and the selected
 * language's) join the current language until the next reload. Never throws: anything unexpected completes with false (the
 * Library then swaps). Work off the render thread, the entrypoints on it.
 */
public final class HotLoader189 {
	private static final Object LOCK = new Object();
	private static final Set<String> PENDING = new HashSet<>();
	private static final AtomicBoolean INITIALISING = new AtomicBoolean();

	private HotLoader189() {
	}

	/** A load's answer; {@code renderThreadMs} is the time the render thread spent on it. */
	public record Result(boolean loaded, String modId, String reason, long prepareMs, long renderThreadMs) {
		static Result no(@Nullable String id, String reason) {
			return new Result(false, id == null ? "?" : id, reason, 0, 0);
		}
	}

	/** Loads {@code jar} into the running game; completes on the render thread (or at once for a quick "no"). Never fails. */
	public static CompletableFuture<Result> load(Path jar) {
		CompletableFuture<Result> done = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				prepare(jar, done);
			} catch (Throwable t) {
				Flint.LOG.warn("Hot load of {} failed: {}", jar.getFileName(), t.toString());
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
		load(Path.of(jar)).thenAccept(r -> Flint.LOG.info("Hot load: loaded={} mod={} reason={} prepareMs={} renderThreadMs={} totalMs={}", r.loaded(),
			r.modId(), r.reason(), r.prepareMs(), r.renderThreadMs(), (System.nanoTime() - start) / 1_000_000));
		return "started";
	}

	/** QA: whether Fabric Loader lists the mod, and its keys in the options. */
	public static String qaListed(String id) {
		Minecraft mc = Minecraft.getInstance();
		long keys = Arrays.stream(mc.options.keyBindings).filter(k -> k.getName().toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT))).count();
		return id + " listed=" + FabricLoader.getInstance().isModLoaded(id) + " mods=" + FabricLoader.getInstance().getAllMods().size() + " optionsKeys=" + keys;
	}

	// ---------- off the render thread ----------

	private record Read(@Nullable JsonObject fmj, byte[] fmjBytes, HotLoadRule.Verdict verdict, Map<String, byte[]> lang) {
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
				} else if (e.getName().matches("assets/[^/]+/lang/[^/]+\\.lang") && e.getSize() < (4 << 20)) {
					lang.put(e.getName(), zip.getInputStream(e).readAllBytes());
				}
			}
		}
		JsonObject fmj = fmjBytes == null ? null
			: JsonParser.parseString(new String(fmjBytes, StandardCharsets.UTF_8).replace("﻿", "")).getAsJsonObject();
		FabricLoader loader = FabricLoader.getInstance();
		HotLoadRule.Verdict verdict = HotLoadRule.check(fmj, entries, nested, loader::isModLoaded);
		return new Read(fmj, fmjBytes == null ? new byte[0] : fmjBytes, verdict, lang);
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
			Flint.LOG.info("Hot load: {} needs a swap ({})", jar.getFileName(), read.verdict.reason());
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
						if (key.equals("client") || key.equals("client-init")) {
							throw t;
						}
					}
				}
			}
			long prepareMs = (System.nanoTime() - start) / 1_000_000;
			Minecraft.getInstance().executeTask(() -> {
				Result result = onRenderThread(container, meta, read, prepareMs);
				synchronized (LOCK) {
					PENDING.remove(meta.getId());
				}
				done.complete(result);
			});
		}
	}

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
			List<KeyBinding> keysBefore = new ArrayList<>(HotLoadKeysAccess.flint$all());
			INITIALISING.set(true);
			try {
				// Ornithe's own client entrypoint ("client-init", OSL) and Fabric's ("client").
				for (String key : List.of("client", "client-init")) {
					for (EntrypointContainer<Object> ep : FabricLoader.getInstance().getEntrypointContainers(key, Object.class)) {
						if (ep.getProvider() == container) {
							Object e = ep.getEntrypoint();
							if (e instanceof ClientModInitializer client) {
								client.onInitializeClient();
							} else {
								// OSL's ClientModInitializer: initClient().
								for (Method m : e.getClass().getMethods()) {
									if (m.getParameterCount() == 0 && (m.getName().equals("initClient") || m.getName().equals("onInitializeClient"))) {
										m.invoke(e);
										break;
									}
								}
							}
						}
					}
				}
			} finally {
				INITIALISING.set(false);
			}
			int keys = addKeyBindings(mc, keysBefore);
			int words = addTranslations(read.lang, mc);
			long ms = (System.nanoTime() - start) / 1_000_000;
			Flint.LOG.info("Hot load: {} {} is in the running game (prepare {} ms off-thread, {} ms on the render thread, {} keys, {} translations)",
				meta.getName(), meta.getVersion().getFriendlyString(), prepareMs, ms, keys, words);
			return new Result(true, id, "loaded", prepareMs, ms);
		} catch (Throwable t) {
			Flint.LOG.warn("Hot load of {} failed on the render thread: {}", id, t.toString());
			return new Result(false, id, "failed: " + t, prepareMs, (System.nanoTime() - start) / 1_000_000);
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	/** The key bindings the mod made (each registers itself with Minecraft's list) join the options, as at start. */
	private static int addKeyBindings(Minecraft mc, List<KeyBinding> before) {
		List<KeyBinding> added = new ArrayList<>(HotLoadKeysAccess.flint$all());
		added.removeAll(before);
		List<KeyBinding> in = Arrays.asList(mc.options.keyBindings);
		added.removeIf(in::contains);
		if (added.isEmpty()) {
			return 0;
		}
		KeyBinding[] next = Arrays.copyOf(mc.options.keyBindings, mc.options.keyBindings.length + added.size());
		for (int i = 0; i < added.size(); i++) {
			next[mc.options.keyBindings.length + i] = added.get(i);
		}
		mc.options.keyBindings = next;
		KeyBinding.resetMapping();
		return added.size();
	}

	/** The mod's translations (English, and the selected language's) on top of the current language, until the next reload. */
	private static int addTranslations(Map<String, byte[]> files, Minecraft mc) {
		if (files.isEmpty()) {
			return 0;
		}
		Map<String, String> into = ((HotLoadLocaleAccess) HotLoadI18nAccess.flint$locale()).flint$translations();
		String selected = mc.options.language == null ? "en_US" : mc.options.language;
		int n = 0;
		for (String code : List.of("en_US", selected)) {
			for (Map.Entry<String, byte[]> file : files.entrySet()) {
				if (!file.getKey().toLowerCase(Locale.ROOT).endsWith("/lang/" + code.toLowerCase(Locale.ROOT) + ".lang")) {
					continue;
				}
				try (BufferedReader in = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(file.getValue()), StandardCharsets.UTF_8))) {
					for (String line; (line = in.readLine()) != null;) {
						int eq = line.indexOf('=');
						if (eq > 0 && !line.startsWith("#")) {
							into.put(line.substring(0, eq), line.substring(eq + 1));
							n++;
						}
					}
				} catch (java.io.IOException ignored) {
					// a broken file: its keys show as keys
				}
			}
		}
		return n;
	}
}

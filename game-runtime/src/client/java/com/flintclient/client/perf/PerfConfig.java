package com.flintclient.client.perf;

import com.flintclient.client.FlintClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Flint Performance Core settings for this launch, from {@code config/flint-performance.json} inside the
 * profile. The launcher writes the file before every launch; the runtime only reads it, once. A missing
 * file means defaults. An unusable one (corrupt JSON, a value of the wrong type, a newer schema) means
 * defaults and one warning. Keys the runtime doesn't use are ignored.
 */
public final class PerfConfig {
	public static final String FILE_NAME = "flint-performance.json";
	/** The newest file layout this runtime reads. */
	static final int SCHEMA = 1;

	private final boolean automaticOptimization;
	private final Map<String, Boolean> moduleOverrides;
	private final boolean frameStatsLog;
	private final boolean gpuResources;
	private final boolean fontTiming;
	private final boolean network;
	private final String source;

	private PerfConfig(boolean automaticOptimization, Map<String, Boolean> moduleOverrides, boolean frameStatsLog, boolean gpuResources,
		boolean fontTiming, boolean network, String source) {
		this.automaticOptimization = automaticOptimization;
		this.moduleOverrides = moduleOverrides;
		this.frameStatsLog = frameStatsLog;
		this.gpuResources = gpuResources;
		this.fontTiming = fontTiming;
		this.network = network;
		this.source = source;
	}

	static PerfConfig defaults(String source) {
		return new PerfConfig(true, Map.of(), false, false, false, false, source);
	}

	public static PerfConfig load(Path file) {
		if (!Files.isRegularFile(file)) {
			return defaults("defaults (no " + FILE_NAME + ")");
		}
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			FlintClient.LOGGER.warn("Couldn't use {} ({}); Flint Performance Core is using defaults", file, e.getMessage());
			return defaults("defaults (" + FILE_NAME + " unusable)");
		}
	}

	/** Reads the file's contents; throws {@link IllegalArgumentException} when they can't be used. */
	static PerfConfig parse(String json) {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(json);
		} catch (JsonParseException e) {
			throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
		}
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("not a JSON object");
		}
		JsonObject root = parsed.getAsJsonObject();
		JsonElement schema = root.get("schema");
		if (schema != null) {
			if (!schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
				|| schema.getAsDouble() != Math.rint(schema.getAsDouble()) || schema.getAsDouble() < 1) {
				throw new IllegalArgumentException("schema is not a version number");
			}
			if (schema.getAsDouble() > SCHEMA) {
				throw new IllegalArgumentException("schema " + schema.getAsString() + " is newer than this runtime reads (" + SCHEMA + ")");
			}
		}
		boolean automaticOptimization = root.has("automaticOptimization") ? bool(root.get("automaticOptimization"), "automaticOptimization") : true;
		Map<String, Boolean> overrides = new HashMap<>();
		JsonObject modules = object(root, "modules");
		if (modules != null) {
			for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
				overrides.put(entry.getKey(), bool(entry.getValue(), "modules." + entry.getKey()));
			}
		}
		JsonObject dev = object(root, "dev");
		boolean frameStatsLog = devFlag(dev, "frameStatsLog");
		boolean gpuResources = devFlag(dev, "gpuResources");
		boolean fontTiming = devFlag(dev, "fontTiming");
		boolean network = devFlag(dev, "network");
		JsonElement writtenBy = root.get("writtenBy");
		if (writtenBy != null && !(writtenBy.isJsonPrimitive() && writtenBy.getAsJsonPrimitive().isString())) {
			throw new IllegalArgumentException("writtenBy is not text");
		}
		return new PerfConfig(automaticOptimization, Map.copyOf(overrides), frameStatsLog, gpuResources, fontTiming, network,
			writtenBy == null ? FILE_NAME : FILE_NAME + " from " + writtenBy.getAsString());
	}

	/** A developer flag in {@code dev}: false when absent. */
	private static boolean devFlag(@Nullable JsonObject dev, String name) {
		return dev != null && dev.has(name) && bool(dev.get(name), "dev." + name);
	}

	private static boolean bool(JsonElement value, String name) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(name + " is not true or false");
		}
		return value.getAsBoolean();
	}

	private static @Nullable JsonObject object(JsonObject root, String name) {
		JsonElement value = root.get(name);
		if (value != null && !value.isJsonObject()) {
			throw new IllegalArgumentException(name + " is not an object");
		}
		return value == null ? null : value.getAsJsonObject();
	}

	/** The one user-facing setting. When off, every optimization module defaults to off (the developer frame log follows its own flag). */
	public boolean automaticOptimization() {
		return automaticOptimization;
	}

	/** Whether a module runs: an override in the file ({@code modules}) wins over the module's default. */
	public boolean enabled(String moduleId, boolean byDefault) {
		return moduleOverrides.getOrDefault(moduleId, byDefault);
	}

	/** Developer frame-time logging ({@code dev.frameStatsLog}); never shown to players. */
	public boolean frameStatsLog() {
		return frameStatsLog;
	}

	/** Developer log of Flint's live GPU textures at each world join and leave ({@code dev.gpuResources}). */
	public boolean gpuResources() {
		return gpuResources;
	}

	/** Developer log of render-thread time per Flint font set made ({@code dev.fontTiming}). */
	public boolean fontTiming() {
		return fontTiming;
	}

	/** Developer log of every connection phase change ({@code dev.network}); the per-attempt line is always on. */
	public boolean network() {
		return network;
	}

	/** Where these settings came from, for the start-up log line. */
	public String source() {
		return source;
	}
}

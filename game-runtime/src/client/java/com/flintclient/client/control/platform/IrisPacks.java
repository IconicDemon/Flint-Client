package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Iris's shader pack selection, for a pack the Flint app added while the game runs ({@link Mc263LivePacks}). Iris is optional
 * and Flint is built without it, so everything goes by name: Iris's public API ({@code net.irisshaders.iris.api.v0.IrisApi},
 * whose {@code IrisApiConfig.setShadersEnabledAndApply} turns shaders on, saves and reloads) for applying, and its config
 * ({@code Iris.getIrisConfig().setShaderPackName}) for the pack's name, which the public API has no call for. That is exactly
 * what Iris's own Apply button does. The same names are in every Iris Flint installs (1.8.14 for 1.21.1, 1.9.7 for 1.21.10,
 * 1.10.8 for 1.21.11, 1.11.4 for 26.1 and 26.2, 1.11.7 for 26.3); any of them missing, or any failure, is a result, never an
 * exception. Render thread (Iris rebuilds its pipeline there).
 */
final class IrisPacks {
	/** What turning a pack on did. */
	enum Result {
		ON,
		/** Iris says it isn't a shader pack (no {@code shaders} folder). */
		INVALID,
		/** Iris couldn't be asked, or didn't take the pack (it reports why itself). */
		FAILED
	}

	private static final String IRIS = "net.irisshaders.iris.Iris";
	private static final String API = "net.irisshaders.iris.api.v0.IrisApi";
	private static final String API_CONFIG = "net.irisshaders.iris.api.v0.IrisApiConfig";

	private IrisPacks() {
	}

	/** Whether Iris is loaded in this game. */
	static boolean present() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	/** The shader pack Iris has selected (its file or folder name), or null. */
	static @Nullable String selected() {
		try {
			Object config = config();
			return config.getClass().getMethod("getShaderPackName").invoke(config) instanceof Optional<?> name && name.orElse(null) instanceof String s ? s : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Whether shaders are on in Iris. */
	static boolean enabled() {
		try {
			Object config = config();
			return Boolean.TRUE.equals(config.getClass().getMethod("areShadersEnabled").invoke(config));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Selects {@code name} (a pack in Iris's shader pack folder) and turns shaders on, as Iris's Apply does. */
	static Result select(String name) {
		try {
			Class<?> iris = Class.forName(IRIS);
			Path pack = ((Path) iris.getMethod("getShaderpacksDirectory").invoke(null)).resolve(name);
			if (!Boolean.TRUE.equals(iris.getMethod("isValidShaderpack", Path.class).invoke(null, pack))) {
				return Result.INVALID;
			}
			// Another pack's option changes waiting to be applied belong to that pack (Iris's Apply clears them too).
			if (!name.equals(iris.getMethod("getCurrentPackName").invoke(null))) {
				optional(iris, "clearShaderPackOptionQueue");
			}
			Object config = config();
			config.getClass().getMethod("setShaderPackName", String.class).invoke(config, name);
			apply(iris, config, true);
			return name.equals(iris.getMethod("getCurrentPackName").invoke(null)) ? Result.ON : Result.FAILED;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't turn on shader pack {} through Iris", name, e);
			return Result.FAILED;
		}
	}

	/** Turns shaders off (the selected pack was removed). */
	static boolean turnOff() {
		try {
			apply(Class.forName(IRIS), config(), false);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: couldn't turn shaders off through Iris", e);
			return false;
		}
	}

	private static Object config() throws ReflectiveOperationException {
		return Class.forName(IRIS).getMethod("getIrisConfig").invoke(null);
	}

	/**
	 * Shaders on or off, saved and reloaded: Iris's public API when it has the call, else the same three steps on its config
	 * and {@code Iris.reload()}.
	 */
	private static void apply(Class<?> iris, Object config, boolean on) throws ReflectiveOperationException {
		Method apply = null;
		Object apiConfig = null;
		try {
			Class<?> api = Class.forName(API);
			apiConfig = api.getMethod("getConfig").invoke(api.getMethod("getInstance").invoke(null));
			apply = Class.forName(API_CONFIG).getMethod("setShadersEnabledAndApply", boolean.class);
		} catch (ReflectiveOperationException | LinkageError e) {
			apply = null;
		}
		if (apply != null && apiConfig != null) {
			apply.invoke(apiConfig, on);
			return;
		}
		config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, on);
		config.getClass().getMethod("save").invoke(config);
		iris.getMethod("reload").invoke(null);
	}

	private static void optional(Class<?> owner, String method) {
		try {
			owner.getMethod(method).invoke(null);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			FlintClient.LOGGER.debug("Flint: Iris has no {} here", method, e);
		}
	}
}

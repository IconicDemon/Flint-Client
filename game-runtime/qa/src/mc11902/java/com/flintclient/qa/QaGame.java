package com.flintclient.qa;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jspecify.annotations.Nullable;

/**
 * Where Minecraft keeps the screen, the overlay and the chat (1.19.2: on Minecraft itself, and Minecraft.gui is the HUD), and
 * what 1.19.2 does its own way: making a world, leaving it, resource packs, the frame rate. The same on 1.19 (chat signing,
 * which 1.19.1 changed, is QaChat).
 */
final class QaGame {
	private QaGame() {
	}

	static @Nullable Screen screen(Minecraft mc) {
		return mc.screen;
	}

	static void setScreen(Minecraft mc, @Nullable Screen screen) {
		mc.setScreen(screen);
	}

	static @Nullable Overlay overlay(Minecraft mc) {
		return mc.getOverlay();
	}

	static ChatComponent chat(Minecraft mc) {
		return mc.gui.getChat();
	}

	static RenderTarget mainRenderTarget(Minecraft mc) {
		return mc.getMainRenderTarget();
	}

	static Camera mainCamera(Minecraft mc) {
		return mc.gameRenderer.getMainCamera();
	}

	/**
	 * create-world: a creative world with commands (normal difficulty, not hardcore, as 26.x's default) with this seed, no
	 * structures and no bonus chest, made as 1.19.2's title screen makes its demo world (the built-in registries, the normal
	 * world preset).
	 */
	static void createWorld(Minecraft mc, String name, long seed) {
		LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), DataPackConfig.DEFAULT);
		RegistryAccess registries = RegistryAccess.builtinCopy().freeze();
		mc.createWorldOpenFlows().createFreshLevel(name, settings, registries, WorldPresets.createNormalWorldFromPreset(registries, seed, false, false));
	}

	/** The GUI laid out again (a new GUI scale). */
	static void resizeGui(Minecraft mc) {
		mc.resizeDisplay();
	}

	/** Leaves the world as the pause menu's Disconnect does on 1.19.2. */
	static void leaveWorld(Minecraft mc) {
		boolean local = mc.isLocalServer();
		if (mc.level != null) {
			mc.level.disconnect();
		}
		if (local) {
			mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
		} else {
			mc.clearLevel();
		}
		mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
	}

	/**
	 * A resource pack turned on or off in the repository (true when that changed it), as later versions' addPack and
	 * removePack do: 1.19.2 only takes the whole selection, which keeps its fixed packs in place.
	 */
	static boolean selectPack(PackRepository repository, String id, boolean on) {
		List<String> selected = new ArrayList<>(repository.getSelectedIds());
		if (on) {
			if (selected.contains(id) || !repository.isAvailable(id)) {
				return false;
			}
			selected.add(id);
		} else if (!selected.remove(id)) {
			return false;
		}
		repository.setSelected(selected);
		return true;
	}

	/** The options take the repository's selection and the game reloads its resources if it changed, as 1.19.2's Options screen does. */
	static void updateResourcePacks(Minecraft mc, PackRepository repository) {
		List<String> before = List.copyOf(mc.options.resourcePacks);
		mc.options.resourcePacks.clear();
		mc.options.incompatibleResourcePacks.clear();
		for (Pack pack : repository.getSelectedPacks()) {
			if (!pack.isFixedPosition()) {
				mc.options.resourcePacks.add(pack.getId());
				if (!pack.getCompatibility().isCompatible()) {
					mc.options.incompatibleResourcePacks.add(pack.getId());
				}
			}
		}
		mc.options.save();
		if (!before.equals(mc.options.resourcePacks)) {
			mc.reloadResourcePacks();
		}
	}

	/** The frames Minecraft counted in the last second (1.19.2 keeps the number to itself). */
	static int fps() throws ReflectiveOperationException {
		return QaNames.field(Minecraft.class, "fps").getInt(null);
	}
}

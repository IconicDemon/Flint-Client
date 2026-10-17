package com.flintclient.client.control.platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * Minecraft 26.3's resource packs for Pack Organizer: what's installed (and whether each is on, required, made for this
 * version), and turning packs on or off when the player presses Apply, through Minecraft's own repository and options
 * (which save the list and reload resources, exactly as the vanilla Resource Packs screen does). Nothing here touches pack
 * files. Render thread.
 */
public final class Mc263Packs {
	/** One installed pack, as the organizer shows it. */
	public record PackInfo(String id, String title, String description, boolean compatible, String compatibility, boolean selected,
		boolean required) {
	}

	private Mc263Packs() {
	}

	private static PackRepository repository() {
		return Minecraft.getInstance().getResourcePackRepository();
	}

	/**
	 * Your resource packs (the ones in the resource packs folder, and server or world packs), in the repository's order.
	 * Minecraft's own and the packs mods carry inside them are left out, as the vanilla screen keeps them fixed.
	 */
	public static List<PackInfo> packs() {
		PackRepository repo = repository();
		Collection<String> selected = repo.getSelectedIds();
		List<PackInfo> out = new ArrayList<>();
		for (Pack pack : repo.getAvailablePacks()) {
			if (pack.isRequired() || pack.isFixedPosition() || pack.getPackSource() == net.minecraft.server.packs.repository.PackSource.BUILT_IN
				|| pack.getPackSource() == net.minecraft.server.packs.repository.PackSource.FEATURE) {
				continue;
			}
			boolean compatible = pack.getCompatibility().isCompatible();
			out.add(new PackInfo(pack.getId(), pack.getTitle().getString(), pack.getDescription().getString().replace('\n', ' '), compatible,
				compatible ? "" : pack.getCompatibility().getDescription().getString(), selected.contains(pack.getId()), pack.isRequired()));
		}
		return out;
	}

	/**
	 * Turns packs on (true) or off (false); packs turned on go on top, as in the vanilla screen; the others keep their order.
	 * Returns whether anything changed (resources then reload).
	 */
	public static boolean apply(Map<String, Boolean> changes) {
		PackRepository repo = repository();
		boolean changed = false;
		for (Map.Entry<String, Boolean> change : changes.entrySet()) {
			Pack pack = repo.getPack(change.getKey());
			if (pack == null || pack.isRequired() && !change.getValue()) {
				continue;
			}
			changed |= change.getValue() ? repo.addPack(change.getKey()) : repo.removePack(change.getKey());
		}
		if (changed) {
			Minecraft.getInstance().options.updateResourcePacks(repo);
		}
		return changed;
	}
}

package com.flintclient.client.compat.data;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.lwjgl.system.MemoryUtil;

/**
 * Calls the shared sources make that Minecraft 1.19.2 has no method for (the frame rate, copying between images, turning
 * resource packs on and off): each does what the newer method does, with 1.19.2's own pieces.
 */
public final class DataCompat {
	private DataCompat() {
	}

	/**
	 * The frames drawn in the last second ({@code Minecraft.getFps}, 1.20): 1.19.2 keeps the count to itself and puts it at the
	 * head of its fps text ("60 fps T: 120 ..."), read here without allocating; 0 before the first second.
	 */
	public static int fps(Minecraft minecraft) {
		String text = minecraft.fpsString;
		int fps = 0;
		for (int i = 0; text != null && i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < '0' || c > '9') {
				break;
			}
			fps = fps * 10 + (c - '0');
		}
		return fps;
	}

	/**
	 * Copies the {@code w × h} block at ({@code fromX}, {@code fromY}) of {@code from} to ({@code x}, {@code y}) of {@code to}
	 * (1.21's {@code NativeImage.copyRect} to another image; 1.19.2's copies within one image only), one memory copy per row.
	 * The two are different images of the same format; a block reaching outside either throws, as 1.21's does.
	 */
	public static void copyRect(NativeImage from, NativeImage to, int fromX, int fromY, int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (from.format() != to.format()) {
			throw new IllegalArgumentException("copyRect between images of different formats: " + from.format() + ", " + to.format());
		}
		if (fromX < 0 || fromY < 0 || x < 0 || y < 0 || fromX + w > from.getWidth() || fromY + h > from.getHeight() || x + w > to.getWidth()
			|| y + h > to.getHeight()) {
			throw new IllegalArgumentException("copyRect of " + w + " x " + h + " from (" + fromX + ", " + fromY + ") of " + from.getWidth() + " x "
				+ from.getHeight() + " to (" + x + ", " + y + ") of " + to.getWidth() + " x " + to.getHeight() + " reaches outside");
		}
		long source = from.getPointer();
		long target = to.getPointer();
		if (source == 0L || target == 0L) {
			throw new IllegalStateException("Image is not allocated.");
		}
		int bytes = from.format().components();
		long fromRow = (long) from.getWidth() * bytes;
		long toRow = (long) to.getWidth() * bytes;
		long length = (long) w * bytes;
		source += fromY * fromRow + (long) fromX * bytes;
		target += y * toRow + (long) x * bytes;
		for (int row = 0; row < h; row++) {
			MemoryUtil.memCopy(source + row * fromRow, target + row * toRow, length);
		}
	}

	/**
	 * Turns a pack on ({@code PackRepository.addPack}, 1.20): it goes on top of the selected packs. False when there's no such
	 * pack or it's on already. 1.19.2 sets the whole selection (keeping the required packs in it).
	 */
	public static boolean addPack(PackRepository repository, String id) {
		Collection<String> selected = repository.getSelectedIds();
		if (repository.getPack(id) == null || selected.contains(id)) {
			return false;
		}
		List<String> ids = new ArrayList<>(selected);
		ids.add(id);
		repository.setSelected(ids);
		return true;
	}

	/** Turns a pack off ({@code PackRepository.removePack}, 1.20); false when there's no such pack or it's off already. */
	public static boolean removePack(PackRepository repository, String id) {
		Collection<String> selected = repository.getSelectedIds();
		if (repository.getPack(id) == null || !selected.contains(id)) {
			return false;
		}
		List<String> ids = new ArrayList<>(selected);
		ids.remove(id);
		repository.setSelected(ids);
		return true;
	}

	/**
	 * The selection saved in the options and resources reloaded if the list changed ({@code Options.updateResourcePacks}, 1.20):
	 * what Done on 1.19.2's Resource Packs screen does ({@code OptionsScreen.updatePackList}).
	 */
	public static void updateResourcePacks(Options options, PackRepository repository) {
		List<String> before = new ArrayList<>(options.resourcePacks);
		options.resourcePacks.clear();
		options.incompatibleResourcePacks.clear();
		for (Pack pack : repository.getSelectedPacks()) {
			if (!pack.isFixedPosition()) {
				options.resourcePacks.add(pack.getId());
				if (!pack.getCompatibility().isCompatible()) {
					options.incompatibleResourcePacks.add(pack.getId());
				}
			}
		}
		options.save();
		if (!options.resourcePacks.equals(before)) {
			Minecraft.getInstance().reloadResourcePacks();
		}
	}
}

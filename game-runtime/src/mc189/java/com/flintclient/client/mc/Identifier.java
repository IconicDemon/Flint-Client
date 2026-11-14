package com.flintclient.client.mc;

/**
 * A texture's name as newer Minecraft spells it, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}), with the
 * 1.8.9 name it stands for: a newer texture Flint's menu draws that 1.8.9 keeps under another path is renamed here.
 */
public final class Identifier {
	private final net.minecraft.resource.Identifier id;

	private Identifier(String namespace, String path) {
		this.id = new net.minecraft.resource.Identifier(namespace, older(namespace, path));
	}

	public static Identifier withDefaultNamespace(String path) {
		return new Identifier("minecraft", path);
	}

	public static Identifier fromNamespaceAndPath(String namespace, String path) {
		return new Identifier(namespace, path);
	}

	/** The 1.8.9 texture. */
	public net.minecraft.resource.Identifier id() {
		return id;
	}

	private static String older(String namespace, String path) {
		if (namespace.equals("minecraft") && path.equals("textures/block/grass_block_side.png")) {
			return "textures/blocks/grass_side.png";
		}
		return path;
	}

	@Override
	public String toString() {
		return id.toString();
	}
}

package com.flintclient.client.compat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementList;

/**
 * 1.20.2's advancement tree ({@code net.minecraft.advancements.AdvancementTree}) over Minecraft 1.19.2's advancement list, whose
 * advancements know their parents and children themselves: the roots, and every advancement as a node that knows its root.
 */
public final class AdvancementTree {
	private final List<AdvancementNode> roots = new ArrayList<>();
	private final List<AdvancementNode> nodes = new ArrayList<>();

	public AdvancementTree(AdvancementList list) {
		Map<Advancement, AdvancementNode> rootNodes = new IdentityHashMap<>();
		for (Advancement root : list.getRoots()) {
			AdvancementNode node = new AdvancementNode(root, null);
			rootNodes.put(root, node);
			roots.add(node);
		}
		for (Advancement advancement : list.getAllAdvancements()) {
			Advancement top = advancement;
			while (top.getParent() != null) {
				top = top.getParent();
			}
			AdvancementNode root = rootNodes.get(top);
			nodes.add(root != null && root.holder() == advancement ? root : new AdvancementNode(advancement, root));
		}
	}

	public List<AdvancementNode> roots() {
		return roots;
	}

	public List<AdvancementNode> nodes() {
		return nodes;
	}
}

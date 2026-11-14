package com.flintclient.client.compat;

import java.util.Optional;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.2's advancement node on Minecraft 1.19.2 ({@link AdvancementTree}): the advancement ({@link #holder}, which 1.20.2 calls
 * the holder and 1.19.2 the advancement itself), its display through {@code advancement().display()}, and its tree's root (a
 * root is its own).
 */
public final class AdvancementNode {
	private final Advancement holder;
	private final @Nullable AdvancementNode root;

	AdvancementNode(Advancement holder, @Nullable AdvancementNode root) {
		this.holder = holder;
		this.root = root;
	}

	public Advancement holder() {
		return holder;
	}

	/** The node's advancement as 1.20.2's {@code AdvancementHolder.value()} gives it: here, the node answers for it. */
	public AdvancementNode advancement() {
		return this;
	}

	public Optional<DisplayInfo> display() {
		return Optional.ofNullable(holder.getDisplay());
	}

	public AdvancementNode root() {
		return root == null ? this : root;
	}
}

package com.flintclient.client.compat;

/**
 * Minecraft 1.8.9's answers to the shared model's version questions. The 26.x and 1.21.x lines each have a McCompat with
 * Minecraft calls; on 1.8.9 only the shared model is compiled (src/mc189/shared-sources.rules), so this one holds just the
 * constants it reads.
 */
public final class McCompat {
	/** 1.8.9 draws nametags as Minecraft always did: no shaped backgrounds (rounded corners, borders). */
	public static final boolean SHAPED_NAMETAGS = false;
	/** 1.8.9's item models have none of the 1.21.x gaps Model Gap Fix fills. */
	public static final boolean ITEM_MODEL_GAPS = false;
	/** Whether this Minecraft has an offhand (1.9 and later), and the items Armor's slots look for that 1.8.9 lacks: shields, tridents, maces. */
	public static final boolean OFFHAND = false;

	private McCompat() {
	}
}

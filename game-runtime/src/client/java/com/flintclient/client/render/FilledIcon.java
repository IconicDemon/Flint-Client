package com.flintclient.client.render;

import java.util.function.Supplier;

/**
 * Solid icons on the same 24-unit grid as {@link Icon}, for the few states where a filled shape carries
 * the meaning (a favourite is a filled star, not just a coloured one). Each is a polygon grown by half
 * of {@link Icon#STROKE}, so it covers exactly the area of the matching outline icon and the two can be
 * swapped in place without anything shifting.
 */
public enum FilledIcon {
	STAR(() -> Sdf.Polygon.of(Icon.starPoints()));

	private final Supplier<Sdf.Polygon> shape;
	private Sdf.Polygon polygon;

	FilledIcon(Supplier<Sdf.Polygon> shape) {
		this.shape = shape;
	}

	Sdf.Polygon polygon() {
		if (polygon == null) {
			polygon = shape.get();
		}
		return polygon;
	}
}

package com.flintclient.client.compat.gizmos;

/**
 * Minecraft 1.21.11's {@code net.minecraft.gizmos.GizmoStyle} for 1.21.10, which has no gizmos: a stroke colour and width
 * and a fill colour (alpha 0: none), as Flint's world drawing names them.
 */
public record GizmoStyle(int stroke, float strokeWidth, int fill) {
	public static GizmoStyle stroke(int color) {
		return new GizmoStyle(color, 2.5F, 0);
	}

	public static GizmoStyle stroke(int color, float width) {
		return new GizmoStyle(color, width, 0);
	}

	public static GizmoStyle fill(int color) {
		return new GizmoStyle(0, 0, color);
	}

	public static GizmoStyle strokeAndFill(int stroke, float width, int fill) {
		return new GizmoStyle(stroke, width, fill);
	}

	public boolean hasFill() {
		return (fill >>> 24) != 0;
	}

	public boolean hasStroke() {
		return (stroke >>> 24) != 0;
	}
}

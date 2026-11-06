package com.flintclient.client.compat.gizmos;

/** What a gizmo call returns (1.21.11's {@code GizmoProperties}): drawn over the world (through walls) when asked. */
public final class GizmoProperties {
	boolean onTop;

	GizmoProperties() {
	}

	public GizmoProperties setAlwaysOnTop() {
		onTop = true;
		return this;
	}
}

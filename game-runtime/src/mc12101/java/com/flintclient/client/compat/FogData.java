package com.flintclient.client.compat;

/**
 * The fog distances Fog Customizer adjusts, as 1.21.6's {@code FogData} names them. 1.21.1 works out one start and end per
 * fog (FogRenderer.setupFog): mixin.feature.FogRendererMixin fills these from them and reads the result back.
 */
public final class FogData {
	/**
	 * The clouds' fog start and end this frame, when Fog Customizer gave the terrain another fog (No fog, the distance
	 * multiplier): 1.21.1 draws the clouds with the terrain's fog, so mixin.feature.CloudFogMixin swaps these in around them,
	 * as 26.x keeps a cloud fog of its own. NaN: the clouds keep the terrain's fog.
	 */
	public static float cloudsStart = Float.NaN;
	public static float cloudsEnd = Float.NaN;
	/** Whether the clouds are being drawn (CloudFogMixin): a fog set up meanwhile (Sodium's clouds set one up) is theirs. */
	public static boolean drawingClouds;

	public float environmentalStart;
	public float environmentalEnd;
	public float renderDistanceStart;
	public float renderDistanceEnd;
	public float skyEnd;
	public float cloudEnd;

	/** Every distance from 1.21.1's one start and end. */
	public FogData set(float start, float end) {
		environmentalStart = start;
		environmentalEnd = end;
		renderDistanceStart = start;
		renderDistanceEnd = end;
		skyEnd = end;
		cloudEnd = end;
		return this;
	}

	/** The fog's start as 1.21.1 applies it: the nearer of the two fogs. */
	public float start() {
		return Math.min(environmentalStart, renderDistanceStart);
	}

	/** The fog's end as 1.21.1 applies it: the nearer of the two fogs. */
	public float end() {
		return Math.min(environmentalEnd, renderDistanceEnd);
	}
}

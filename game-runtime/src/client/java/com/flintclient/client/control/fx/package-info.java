/**
 * Optional in-game visual effects. {@link com.flintclient.client.control.fx.MotionBlur} (each version's overlay; off by
 * default) is Natural Motion Blur rebuilt: velocity based blur split around players and mobs, frame blending, hybrid
 * blending and accumulation. {@link com.flintclient.client.control.fx.NmbTiming} holds its rules without Minecraft classes,
 * the same on every line (1.8.9 included); {@link com.flintclient.client.control.fx.ShaderPacks} asks Iris whether a shader
 * pack draws the world; {@link com.flintclient.client.control.fx.BlurMath} keeps the gameplay blur switch's strength range.
 */
package com.flintclient.client.control.fx;

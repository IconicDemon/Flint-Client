/**
 * Optional in-game visual effects. {@link com.flintclient.client.control.fx.MotionBlur} (each version's overlay; off by
 * default) has two modes ({@link com.flintclient.client.control.fx.BlurRules}): Smooth, AFKZ Motion Blur's frame blend (the
 * trail Lunar Client draws) fading by time, and Natural, Natural Motion Blur's velocity blur split around players and mobs.
 * {@link com.flintclient.client.control.fx.BlurRules} and {@link com.flintclient.client.control.fx.NmbTiming} hold their rules
 * without Minecraft classes, the same on every line (1.8.9 included); {@link com.flintclient.client.control.fx.ShaderPacks} asks
 * Iris whether a shader pack draws the world; {@link com.flintclient.client.control.fx.BlurMath} keeps the gameplay blur switch's
 * strength range.
 */
package com.flintclient.client.control.fx;

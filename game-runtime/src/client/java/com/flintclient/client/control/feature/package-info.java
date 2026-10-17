/**
 * Flint's feature modules' shared logic, free of Minecraft types so every supported Minecraft version uses the same
 * code: combat tracking, movement toggles, zoom easing, chat stacking, waypoints. The version layer
 * ({@code control.platform.Mc263*}, {@code mixin.feature}) feeds it from Minecraft's hooks and applies its answers.
 */
package com.flintclient.client.control.feature;

/**
 * Flint Network Core: what happens to each multiplayer connection attempt, from the connect screen to the
 * session ending, and a pull API for a future HUD. It only observes: it never sends, delays, drops or changes
 * a packet, never touches the Netty pipeline and never reconnects. Nothing here imports Minecraft; the hooks
 * and the live readings are in {@code platform/} and the {@code mixin.network} package.
 */
package com.flintclient.client.network;

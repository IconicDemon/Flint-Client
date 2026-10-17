/**
 * Saving the Control Center's settings and layout profiles as JSON under {@code config/flintclient}: atomic
 * writes, unknown keys kept, damaged files copied aside, newer files left untouched. Nothing here imports
 * Minecraft; the caller passes the config folder.
 */
package com.flintclient.client.control.store;

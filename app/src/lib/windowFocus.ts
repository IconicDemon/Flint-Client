import { getCurrentWindow } from "@tauri-apps/api/window";
import { isDesktop } from "./api";

/**
 * Calls `handler` when the native window gains or loses focus. Tauri reports this from the window
 * itself, which is reliable when another app comes to the front; the web view's own blur/focus
 * events are kept by callers as a fallback (and are all a browser preview has).
 */
export function onWindowFocus(handler: (focused: boolean) => void): () => void {
  if (!isDesktop()) return () => {};
  let disposed = false;
  let unlisten: (() => void) | null = null;
  void getCurrentWindow()
    .onFocusChanged(({ payload }) => handler(payload))
    .then((stop) => (disposed ? stop() : (unlisten = stop)))
    .catch(() => {});
  return () => {
    disposed = true;
    unlisten?.();
  };
}

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

/**
 * Calls `handler` when the native window is minimized or restored (asked of the window after it is resized or loses or
 * gains focus, which minimizing and restoring both do). A web view may keep `document.hidden` false while minimized, so
 * anything that animates on its own timer listens to this too. Nothing in a browser preview.
 */
export function onWindowMinimized(handler: (minimized: boolean) => void): () => void {
  if (!isDesktop()) return () => {};
  const win = getCurrentWindow();
  let disposed = false;
  let last = false;
  const offs: (() => void)[] = [];
  const check = () =>
    void win
      .isMinimized()
      .then((now) => {
        if (disposed || now === last) return;
        last = now;
        handler(now);
      })
      .catch(() => {});
  for (const listen of [(cb: () => void) => win.onResized(cb), (cb: () => void) => win.onFocusChanged(cb)]) {
    void listen(check)
      .then((off) => (disposed ? off() : offs.push(off)))
      .catch(() => {});
  }
  return () => {
    disposed = true;
    offs.forEach((off) => off());
  };
}

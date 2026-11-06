import { useEffect, useLayoutEffect, useRef } from "react";
import { api, subscribe, type PackSection } from "./api";

/** Flint's profile file (src-tauri/src/flintpack.rs). */
export const PACK_EXTENSION = "flintpack";

/** Sections in the order the export and import dialogs list them (ProfilePack.tsx names and describes each). */
export const PACK_SECTIONS: { id: PackSection }[] = [
  { id: "mods" },
  { id: "resourcepacks" },
  { id: "shaderpacks" },
  { id: "config" },
  { id: "options" },
  { id: "flint" },
  { id: "worlds" },
];

/*
 * A `.flintpack` the launcher was opened with (double-click, or a second launch handing its file to this
 * window) waits here until the Profiles page takes it as it mounts. Kept outside React so it survives
 * the page still loading.
 */
let pending: string | null = null;

export function takePendingPack(): string | null {
  const path = pending;
  pending = null;
  return path;
}

/**
 * For the Shell: asks the backend for a file Flint was opened with, at start-up and whenever another
 * launch hands one over, then shows Profiles afresh (every visit mounts a new page), which opens the
 * import preview for it.
 */
export function usePackOpenRequests(showProfiles: () => void) {
  const show = useRef(showProfiles);
  useLayoutEffect(() => {
    show.current = showProfiles;
  });
  useEffect(() => {
    // The backend hands a file over only once, so an answer is never dropped, even one arriving after
    // this effect was cleaned up (React runs effects twice in development).
    const take = () =>
      void api
        .packTakeOpened()
        .then((path) => {
          if (!path) return;
          pending = path;
          show.current();
        })
        .catch(() => {});
    take();
    return subscribe<null>("flintpack-open", take);
  }, []);
}

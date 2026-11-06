import { api, errorText, type Profile } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { SIDEBAR_PROFILES } from "../../lib/profileOrder";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { useToast } from "../../ui/overlays";

/** Pins a profile to the sidebar or unpins it (the sidebar lists pinned profiles first; five at most). */
export function usePinToggle() {
  const store = useStoreSelect((s) => ({ reloadProfiles: s.reloadProfiles, profiles: s.profiles }));
  const t = useT();
  const toast = useToast();
  return (p: Profile) => {
    // The sidebar shows five: a sixth pin is refused here (and by the app).
    if (!p.pinnedAt && store.profiles.filter((o) => o.pinnedAt).length >= SIDEBAR_PROFILES) {
      sound.play("error");
      toast({ tone: "danger", title: t("workspace.pin.limit", { count: SIDEBAR_PROFILES }), body: t("workspace.pin.limitBody") });
      return;
    }
    void api
      .setProfilePinned(p.id, !p.pinnedAt)
      .then(() => store.reloadProfiles())
      .then(() => toast({ tone: "success", title: t(p.pinnedAt ? "workspace.pin.unpinned" : "workspace.pin.pinned", { name: p.name }) }))
      .catch((e) => toast({ tone: "danger", title: t("workspace.pin.failed"), body: errorText(e) }));
  };
}

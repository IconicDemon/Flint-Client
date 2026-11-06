import { getCurrentWindow } from "@tauri-apps/api/window";
import { isDesktop } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { Icon } from "../../ui/Icon";

/** macOS keeps its native traffic lights (overlay title bar); Windows and Linux get drawn controls. */
export function WindowChrome() {
  const t = useT();
  const info = useStoreSelect((s) => s.info);
  if (info.platform === "macos") return null;
  const win = () => (isDesktop() ? getCurrentWindow() : null);
  return (
    <div className="win-controls">
      <button type="button" aria-label={t("shell.window.minimize")} onClick={() => void win()?.minimize()}>
        <Icon name="minimize" size={15} />
      </button>
      <button type="button" aria-label={t("shell.window.maximize")} onClick={() => void win()?.toggleMaximize()}>
        <Icon name="maximize" size={13} />
      </button>
      <button type="button" aria-label={t("common.close")} className="is-close" onClick={() => void win()?.close()}>
        <Icon name="x" size={15} />
      </button>
    </div>
  );
}

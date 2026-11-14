import curseforgeLogo from "../../assets/launchers/curseforge.svg";
import { isCurseForge } from "../../lib/contentSource";
import { useT } from "../../lib/i18n";

/** The small "CurseForge" label next to an item installed from CurseForge. Modrinth items show nothing. */
export function SourceTag({ projectId }: { projectId?: string | null }) {
  const t = useT();
  if (!isCurseForge(projectId)) return null;
  return (
    <span className="source-tag" title={t("workspace.row.fromCurseForge")}>
      <img src={curseforgeLogo} alt="" />
      CurseForge
    </span>
  );
}

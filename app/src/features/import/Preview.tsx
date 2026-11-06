import { useEffect, useMemo, useState } from "react";
import type { ImportPart, ImportPartPreview, ImportProfilePreview, ImportSourceProfile } from "../../lib/api";
import { bytes, number } from "../../lib/format";
import { useT } from "../../lib/i18n";
import { sound } from "../../lib/sound";
import { versionMeta } from "../../lib/versions";
import { Button, Callout, Checkbox, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { loaderLabel, nameList, partLabel, partLine, partsByStatus } from "./model";
import type { ImportFlow } from "./useImportFlow";

/** Up to this many profiles show their details open; more start folded to one line each. */
const OPEN_UP_TO = 3;

/**
 * Before anything is copied: per profile, what comes over, what needs a look and what stays behind, the Flint
 * profile it becomes and its size; then the total against free space. Blocked profiles are listed and left out.
 * Escape goes back to the table.
 */
export function PreviewView({ flow }: { flow: ImportFlow }) {
  const t = useT();
  const { preview, previewLoading, previewError, listing } = flow;
  const sources = useMemo(() => new Map((listing?.profiles ?? []).map((p) => [p.key, p])), [listing]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape" || e.defaultPrevented) return;
      e.preventDefault();
      flow.back();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [flow]);

  if (!preview) {
    return previewError ? (
      <Callout
        tone="danger"
        title={t("import.preview.failed")}
        action={
          <Button size="sm" icon="refresh" onClick={flow.retryPreview}>
            {t("common.tryAgain")}
          </Button>
        }
        live
      >
        {previewError}
      </Callout>
    ) : (
      <div className="imp-preview-wait" role="status">
        <Spinner size={16} />
        <span className="muted">{t("import.preview.checking")}</span>
      </div>
    );
  }

  const ok = preview.profiles.filter((p) => !p.blocker);
  const blocked = preview.profiles.filter((p) => p.blocker);
  return (
    <div className={`imp-preview ${previewLoading ? "is-refreshing" : ""}`} aria-busy={previewLoading || undefined}>
      <div className="imp-preview-sum">
        <dl className="imp-stats">
          <div>
            <dt>{t("import.preview.stat.profiles")}</dt>
            <dd>{ok.length}</dd>
          </div>
          <div>
            <dt>{t("import.preview.stat.toCopy")}</dt>
            <dd>{bytes(preview.bytes)}</dd>
          </div>
          <div>
            <dt>{t("import.preview.stat.free")}</dt>
            <dd className={preview.enoughSpace ? "" : "is-danger"}>{preview.freeBytes === null ? t("import.preview.stat.freeUnknown") : bytes(preview.freeBytes)}</dd>
          </div>
        </dl>
        <Checkbox className="imp-worlds" checked={flow.worlds} onChange={flow.setWorlds}>
          {t("import.copyWorlds.label")}
        </Checkbox>
      </div>
      {!preview.enoughSpace && (
        <Callout tone="danger" title={t("import.preview.noSpace.title")} live>
          {preview.freeBytes !== null
            ? t("import.preview.noSpace.bodyFree", { needed: bytes(preview.bytes), free: bytes(preview.freeBytes) })
            : t("import.preview.noSpace.body", { needed: bytes(preview.bytes) })}
        </Callout>
      )}
      {flow.startError && (
        <Callout tone="danger" title={t("import.preview.startFailed")} live>
          {flow.startError}
        </Callout>
      )}
      <ul className="imp-plans">
        {ok.map((p) => (
          <PlanCard key={p.key} plan={p} source={sources.get(p.key) ?? null} worlds={flow.worlds} startOpen={ok.length <= OPEN_UP_TO} />
        ))}
      </ul>
      {blocked.length > 0 && (
        <section className="imp-blocked" aria-label={t("import.preview.leftOut")}>
          <h3 className="ob-label">{t("import.preview.leftOut")}</h3>
          <ul>
            {blocked.map((p) => (
              <li key={p.key}>
                <Icon name="minimize" size={14} />
                <strong>{p.name}</strong>
                <span className="muted">{p.blocker}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

const STATUS_HEAD = {
  compatible: { title: "import.preview.status.compatible", icon: "check" },
  attention: { title: "import.preview.status.attention", icon: "alert" },
  unsupported: { title: "import.preview.status.unsupported", icon: "minimize" },
} as const;

function PlanCard({ plan, source, worlds, startOpen }: { plan: ImportProfilePreview; source: ImportSourceProfile | null; worlds: boolean; startOpen: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(startOpen);
  const groups = partsByStatus(plan);
  // Worlds left behind by choice are listed with what stays, so the size difference is visible.
  const worldsLeft = !worlds && plan.worldBytes > 0 && source?.worlds ? source.worlds : 0;
  const staying: (ImportPartPreview | { id: "worlds-off" })[] = [...groups.unsupported, ...(worldsLeft ? [{ id: "worlds-off" as const }] : [])];
  const target = plan.target!;
  // Folded, the line says only what the player should know: what needs a look and what stays behind.
  const labels = (parts: { id: ImportPart | "worlds-off" }[]) => nameList(parts.map((p, i) => partLabel(p.id === "worlds-off" ? "worlds" : p.id, i > 0)));
  const summary = [
    groups.attention.length ? t("import.preview.summary.attention", { parts: labels(groups.attention) }) : null,
    staying.length ? t("import.preview.summary.unsupported", { parts: labels(staying) }) : null,
  ].filter(Boolean);
  if (!summary.length) summary.push(t("import.preview.summary.everything"));
  const renamed = target.name !== plan.name;
  return (
    <li className={`imp-plan ${open ? "is-open" : ""}`}>
      <button
        type="button"
        className="imp-plan-head"
        aria-expanded={open}
        onClick={() => {
          sound.play("tap");
          setOpen((o) => !o);
        }}
      >
        <span className="imp-picon is-lg" aria-hidden>
          {source?.icon ? <img src={source.icon} alt="" /> : <Icon name="cube" size={16} />}
        </span>
        <span className="imp-plan-name">
          <strong>{plan.name}</strong>
          <span className="faint">
            {open
              ? source
                ? t("import.preview.plan.source", { version: source.gameVersion ?? t("import.preview.plan.unknownVersion"), loader: loaderLabel(source.loader) })
                : t("import.preview.plan.from", { name: plan.name })
              : summary.join(" · ")}
          </span>
        </span>
        <Icon name="arrowRight" size={15} className="imp-plan-arrow" />
        <span className="imp-plan-target">
          <span className="imp-plan-thumb" style={{ backgroundImage: `url(${versionMeta(target.gameVersion).thumb})` }} aria-hidden />
          <span>
            <strong title={renamed ? t("import.preview.plan.renamed") : undefined}>{target.name}</strong>
            <span className="faint">{t("import.preview.plan.target", { version: target.gameVersion, loader: loaderLabel(target.loader) })}</span>
          </span>
        </span>
        <span className="imp-plan-size">{bytes(plan.bytes)}</span>
        <Icon name="chevronDown" size={15} className="imp-plan-fold" />
      </button>
      {open && (
        <div className="imp-plan-body">
          {/* What comes over flows in columns; what needs a look or stays behind follows, only when there is some. */}
          {(["compatible", "attention", "unsupported"] as const).map((status) => {
            const list = status === "unsupported" ? staying : groups[status];
            if (!list.length && status !== "compatible") return null;
            return (
              <section key={status} className={`imp-plan-group is-${status}`} aria-label={t(STATUS_HEAD[status].title)}>
                <span className="imp-plan-group-head">
                  <Icon name={STATUS_HEAD[status].icon} size={13} />
                  {t(STATUS_HEAD[status].title)}
                </span>
                {list.length === 0 ? (
                  <span className="faint">{t("import.preview.plan.onlyNameAndVersion")}</span>
                ) : (
                  <ul>
                    {list.map((part) =>
                      part.id === "worlds-off" ? (
                        <li key="worlds-off">
                          <span>{t("import.part.withCount", { part: partLabel("worlds"), amount: number(worldsLeft) })}</span>
                          <span className="faint">{t("import.preview.plan.worldsOff", { size: bytes(plan.worldBytes) })}</span>
                        </li>
                      ) : (
                        <li key={part.id}>
                          <span>{partLine(part)}</span>
                          {status === "compatible" ? part.bytes > 0 && <span className="faint">{bytes(part.bytes)}</span> : part.note && <span className="faint">{part.note}</span>}
                        </li>
                      ),
                    )}
                  </ul>
                )}
              </section>
            );
          })}
          {plan.warnings.length > 0 && (
            <ul className="imp-plan-warnings">
              {plan.warnings.map((w) => (
                <li key={w}>
                  <Icon name="alert" size={13} />
                  {w}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </li>
  );
}

import { useEffect, useRef, useState } from "react";
import { api, errorText, type LibraryHit, type LibraryKind, type LibraryProject, type LibraryVersion, type Profile } from "../../lib/api";
import { filePage, projectPage, sourceOf, SOURCE_NAME } from "../../lib/contentSource";
import { ago, bytes, compact, shortDate } from "../../lib/format";
import { useT, type MessageKey } from "../../lib/i18n";
import { Markdown } from "../../lib/markdown";
import { Badge, Button, EmptyState, IconButton, Spinner, Tabs } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { KIND_LABEL, placementNote, ProjectIcon, type InstallTarget } from "./InstallDialog";

type Section = "overview" | "gallery" | "versions" | "details";

const SIDE: Record<string, MessageKey> = { required: "library.side.required", optional: "library.side.optional", unsupported: "library.side.unsupported", unknown: "library.side.unknown" };

/** A card's or the drawer's Uninstall: removes the project's files from the chosen profile, after a short confirm. */
export type UninstallAction = { locked: boolean; busy: boolean; ask: () => void };

export function UninstallButton({ action }: { action: UninstallAction }) {
  const t = useT();
  return (
    <Button
      size="sm"
      variant="ghost"
      icon="trash"
      className="result-uninstall"
      onClick={action.ask}
      loading={action.busy}
      disabled={action.locked}
      title={action.locked ? t("library.install.runningMods") : undefined}
    >
      {t("library.card.uninstall")}
    </Button>
  );
}

export function ProjectDrawer({
  hit,
  kind,
  profile,
  installed,
  uninstall,
  onClose,
  onInstall,
  onOpenProfile,
}: {
  hit: LibraryHit | null;
  kind: LibraryKind | null;
  profile: Profile | null;
  installed: boolean;
  /** Offered only when the project's files in this profile are known (not for modpacks). */
  uninstall: UninstallAction | null;
  onClose: () => void;
  onInstall: (target: InstallTarget) => void;
  onOpenProfile: (id: string) => void;
}) {
  const t = useT();
  const [project, setProject] = useState<LibraryProject | null>(null);
  const [versions, setVersions] = useState<LibraryVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [section, setSection] = useState<Section>("overview");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [allVersions, setAllVersions] = useState(false);
  /** CurseForge lists files without changelogs: each is asked for when opened (by version id; null while loading). */
  const [changelogs, setChangelogs] = useState<Record<string, string | null>>({});
  const panel = useRef<HTMLDivElement>(null);
  const filterVersion = kind !== "modpack" && !allVersions ? profile?.gameVersion ?? null : null;
  const source = sourceOf(hit?.projectId);

  const toggleChangelog = (v: LibraryVersion) => {
    const next = expanded === v.id ? null : v.id;
    setExpanded(next);
    if (!next || !hit || source !== "curseforge" || v.changelog || v.id in changelogs) return;
    setChangelogs((c) => ({ ...c, [v.id]: null }));
    void api
      .curseforgeChangelog(hit.projectId, v.id)
      .then((text) => setChangelogs((c) => ({ ...c, [v.id]: text })))
      .catch(() => setChangelogs((c) => ({ ...c, [v.id]: "" })));
  };

  useEffect(() => {
    setProject(null);
    setError(null);
    setSection("overview");
    setAllVersions(false);
    setChangelogs({});
    if (!hit) return;
    let live = true;
    void api
      .libraryProject(hit.projectId)
      .then((p) => live && setProject(p))
      .catch((e) => live && setError(errorText(e)));
    return () => {
      live = false;
    };
  }, [hit]);

  useEffect(() => {
    setVersions(null);
    setExpanded(null);
    if (!hit || !kind) return;
    let live = true;
    void api
      .libraryVersions(hit.projectId, kind, filterVersion)
      .then((v) => live && setVersions(v.versions))
      .catch(() => live && setVersions([]));
    return () => {
      live = false;
    };
  }, [hit, kind, filterVersion]);

  useEffect(() => {
    if (!hit) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    panel.current?.focus();
    return () => window.removeEventListener("keydown", onKey);
  }, [hit, onClose]);

  if (!hit || !kind) return null;
  // A CurseForge pack file may name no loader at all: its manifest decides (Flint refuses one that isn't Fabric).
  const fabricPack = (v: LibraryVersion) => v.loaders.includes("fabric") || (source === "curseforge" && !v.loaders.some((l) => ["forge", "neoforge", "quilt"].includes(l)));
  const compatible = versions?.filter((v) => (kind === "modpack" ? fabricPack(v) : true)) ?? null;
  const best = compatible?.find((v) => v.versionType === "release") ?? compatible?.[0] ?? null;
  const unavailable = kind !== "modpack" && !!profile && compatible !== null && compatible.length === 0 && !allVersions;
  const site = project?.websiteUrl ?? hit.websiteUrl ?? null;
  /** A CurseForge file its author lets players get on CurseForge only: Flint links to it there. */
  const keptOnSite = (v: LibraryVersion | null) => !!v && v.downloadable === false;
  const openOnSite = (v: LibraryVersion | null) => void api.openExternal(filePage(site, v?.id) ?? projectPage(hit, kind));
  const target = (v?: LibraryVersion | null): InstallTarget => ({
    kind,
    projectId: hit.projectId,
    title: hit.title,
    iconUrl: hit.iconUrl,
    versionId: v?.id ?? null,
    versionNumber: v?.versionNumber ?? null,
  });
  const gallery = project?.gallery ?? [];
  const url = projectPage({ ...hit, websiteUrl: site }, kind);

  return (
    <div className="drawer-backdrop" onPointerDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside ref={panel} className="drawer" role="dialog" aria-modal="true" aria-label={hit.title} tabIndex={-1}>
        <header className="drawer-head">
          <ProjectIcon src={project?.iconUrl ?? hit.iconUrl} title={hit.title} kind={kind ?? hit.projectType} size={64} />
          <div className="drawer-title">
            <span className="eyebrow">{KIND_LABEL[kind]}</span>
            <h2>{hit.title}</h2>
            <span className="faint">
              {t("library.card.by", { author: project?.author ?? hit.author })} · {t("library.drawer.downloads", { downloads: compact(project?.downloads ?? hit.downloads) })}
              {/* CurseForge has no followers. */}
              {source === "modrinth" && ` · ${t("library.drawer.followers", { followers: compact(project?.followers ?? hit.follows ?? 0) })}`}
            </span>
          </div>
          <IconButton icon="x" label={t("common.close")} onClick={onClose} />
        </header>

        <div className="drawer-cta">
          {installed ? (
            <>
              <span className="result-installed">
                <Icon name="check" size={15} /> {t("library.drawer.installedIn", { profile: profile?.name ?? "" })}
              </span>
              {uninstall && <UninstallButton action={uninstall} />}
              {profile && (
                <Button size="sm" variant="ghost" onClick={() => onOpenProfile(profile.id)}>
                  {t("library.drawer.manage")}
                </Button>
              )}
            </>
          ) : unavailable ? (
            <span className="drawer-unavailable">
              <Icon name="alert" size={15} />
              {t(kind === "shader" ? "library.drawer.unavailableIris" : kind === "mod" ? "library.drawer.unavailableFabric" : "library.drawer.unavailable", {
                version: profile?.gameVersion ?? "",
              })}
            </span>
          ) : keptOnSite(best) ? (
            <>
              <Button variant="primary" iconRight="external" onClick={() => openOnSite(best)}>
                {t("library.curseforge.download")}
              </Button>
              <span className="drawer-kept faint">{t("library.curseforge.keptOnSite")}</span>
            </>
          ) : (
            <Button variant="primary" icon="download" disabled={versions === null || (kind !== "modpack" && !profile)} onClick={() => onInstall(target(best))}>
              {kind === "modpack" ? t("library.drawer.installPack") : profile ? t("library.drawer.installTo", { profile: profile.name }) : t("library.drawer.chooseProfile")}
            </Button>
          )}
          <Button size="sm" variant="ghost" iconRight="external" onClick={() => void api.openExternal(url)}>
            {SOURCE_NAME[source]}
          </Button>
        </div>
        <p className="drawer-note faint">{placementNote(kind)}</p>

        <Tabs
          label={t("library.drawer.sections")}
          value={section}
          onChange={setSection}
          tabs={[
            { value: "overview", label: t("library.drawer.overview") },
            { value: "gallery", label: t("library.drawer.gallery"), count: gallery.length || undefined },
            { value: "versions", label: t("library.drawer.versions"), count: versions?.length || undefined },
            { value: "details", label: t("library.drawer.details") },
          ]}
        />

        <div className="drawer-body scroll">
          {error ? (
            <EmptyState icon="wifiOff" title={t("library.drawer.loadFailed")} body={error} compact />
          ) : !project ? (
            <div className="drawer-loading">
              <Spinner />
            </div>
          ) : section === "overview" ? (
            <>
              <p className="drawer-summary">{project.description}</p>
              {project.body ? <Markdown source={project.body} /> : <p className="faint">{t("library.drawer.noDescription")}</p>}
            </>
          ) : section === "gallery" ? (
            gallery.length === 0 ? (
              <EmptyState icon="image" title={t("library.drawer.noGallery")} compact />
            ) : (
              <div className="gallery">
                {gallery.map((g) => (
                  <figure key={g.url}>
                    <img src={g.url} alt={g.title ?? ""} loading="lazy" referrerPolicy="no-referrer" />
                    {g.title && <figcaption>{g.title}</figcaption>}
                  </figure>
                ))}
              </div>
            )
          ) : section === "versions" ? (
            <>
              {profile && kind !== "modpack" && (
                <label className="version-toggle">
                  <input type="checkbox" checked={allVersions} onChange={(e) => setAllVersions(e.target.checked)} />
                  {t("library.drawer.otherReleases")}
                </label>
              )}
              {versions === null ? (
                <Spinner />
              ) : versions.length === 0 ? (
                <EmptyState icon="info" title={profile ? t("library.drawer.noVersionsFor", { version: profile.gameVersion }) : t("library.drawer.noVersions")} compact />
              ) : (
                <ul className="version-list">
                  {versions.map((v) => {
                    const file = v.files.find((f) => f.primary) ?? v.files[0];
                    const fits = !profile || kind === "modpack" || v.gameVersions.includes(profile.gameVersion);
                    return (
                      <li key={v.id} className="version-item">
                        <div className="version-top">
                          <span>
                            <strong>{v.versionNumber}</strong>
                            <Badge tone={v.versionType === "release" ? "success" : "warning"}>{v.versionType}</Badge>
                          </span>
                          {fits && !installed && (kind === "modpack" || profile) && keptOnSite(v) ? (
                            <Button size="sm" variant="ghost" iconRight="external" onClick={() => openOnSite(v)}>
                              {SOURCE_NAME.curseforge}
                            </Button>
                          ) : (
                            fits &&
                            !installed &&
                            (kind === "modpack" || profile) && (
                              <Button size="sm" variant="ghost" icon="download" onClick={() => onInstall(target(v))}>
                                {t("library.card.install")}
                              </Button>
                            )
                          )}
                        </div>
                        <span className="faint">
                          {v.gameVersions.slice(-4).join(", ")}
                          {/* CurseForge packs name no loader: Flint's own placeholder loaders aren't shown. */}
                          {source === "modrinth" || ["fabric", "forge", "neoforge", "quilt"].some((l) => v.loaders.includes(l)) ? ` · ${v.loaders.join(", ")}` : ""} · {shortDate(v.datePublished)}
                          {file ? ` · ${bytes(file.size)}` : ""}
                        </span>
                        {(v.changelog || source === "curseforge") && (
                          <>
                            <button type="button" className="version-changelog-toggle" aria-expanded={expanded === v.id} onClick={() => toggleChangelog(v)}>
                              {t(expanded === v.id ? "library.drawer.hideChangelog" : "library.drawer.changelog")}
                            </button>
                            {expanded === v.id &&
                              (v.changelog ? (
                                <Markdown source={v.changelog} className="is-small" />
                              ) : changelogs[v.id] == null ? (
                                <Spinner size={14} />
                              ) : changelogs[v.id] ? (
                                <Markdown source={changelogs[v.id]!} className="is-small" />
                              ) : (
                                <p className="faint">{t("updates.changelog.none")}</p>
                              ))}
                          </>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </>
          ) : (
            <dl className="details">
              <dt>{t("library.details.license")}</dt>
              <dd>
                {project.license?.url ? (
                  <button type="button" className="link" onClick={() => void api.openExternal(project.license!.url!)}>
                    {project.license.name || project.license.id}
                  </button>
                ) : (
                  project.license?.name || project.license?.id || t("library.details.notSpecified")
                )}
              </dd>
              {/* CurseForge doesn't say where a project runs. */}
              {source === "modrinth" && (
                <>
                  <dt>{t("library.details.client")}</dt>
                  <dd>{SIDE[project.clientSide] ? t(SIDE[project.clientSide]) : project.clientSide}</dd>
                  <dt>{t("library.details.server")}</dt>
                  <dd>{SIDE[project.serverSide] ? t(SIDE[project.serverSide]) : project.serverSide}</dd>
                </>
              )}
              <dt>{t("library.details.loaders")}</dt>
              <dd>{project.loaders.join(", ") || "—"}</dd>
              <dt>{t("library.details.minecraft")}</dt>
              <dd>{project.gameVersions.length ? `${project.gameVersions[0]} – ${project.gameVersions[project.gameVersions.length - 1]}` : "—"}</dd>
              <dt>{t("library.categories.label")}</dt>
              <dd>{[...project.categories, ...(project.additionalCategories ?? [])].join(", ") || "—"}</dd>
              <dt>{t("library.details.updated")}</dt>
              <dd>
                {shortDate(project.updated)} ({ago(project.updated)})
              </dd>
              <dt>{t("library.details.published")}</dt>
              <dd>{shortDate(project.published)}</dd>
              {project.members.length > 0 && (
                <>
                  <dt>{t("library.details.team")}</dt>
                  <dd>{project.members.map((m) => `${m.username}${m.role ? ` (${m.role})` : ""}`).join(", ")}</dd>
                </>
              )}
              {(project.sourceUrl || project.issuesUrl || project.wikiUrl) && (
                <>
                  <dt>{t("library.details.links")}</dt>
                  <dd className="detail-links">
                    {(
                      [
                        ["library.details.source", project.sourceUrl],
                        ["library.details.issues", project.issuesUrl],
                        ["library.details.wiki", project.wikiUrl],
                      ] as const
                    )
                      .filter(([, href]) => href && href.startsWith("https://"))
                      .map(([label, href]) => (
                        <button key={label} type="button" className="link" onClick={() => void api.openExternal(href!)}>
                          {t(label)}
                        </button>
                      ))}
                  </dd>
                </>
              )}
            </dl>
          )}
        </div>
      </aside>
    </div>
  );
}

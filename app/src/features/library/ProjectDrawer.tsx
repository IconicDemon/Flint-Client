import { useEffect, useRef, useState } from "react";
import { api, errorText, type LibraryHit, type LibraryKind, type LibraryProject, type LibraryVersion, type Profile } from "../../lib/api";
import { ago, bytes, compact, shortDate } from "../../lib/format";
import { Markdown } from "../../lib/markdown";
import { Badge, Button, EmptyState, IconButton, Spinner, Tabs } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { KIND_LABEL, placementNote, ProjectIcon, type InstallTarget } from "./InstallDialog";

type Section = "overview" | "gallery" | "versions" | "details";

const SIDE: Record<string, string> = { required: "Required", optional: "Optional", unsupported: "Not used", unknown: "Unknown" };

export function ProjectDrawer({
  hit,
  kind,
  profile,
  installed,
  onClose,
  onInstall,
  onOpenProfile,
}: {
  hit: LibraryHit | null;
  kind: LibraryKind | null;
  profile: Profile | null;
  installed: boolean;
  onClose: () => void;
  onInstall: (target: InstallTarget) => void;
  onOpenProfile: (id: string) => void;
}) {
  const [project, setProject] = useState<LibraryProject | null>(null);
  const [versions, setVersions] = useState<LibraryVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [section, setSection] = useState<Section>("overview");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [allVersions, setAllVersions] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  const filterVersion = kind !== "modpack" && !allVersions ? profile?.gameVersion ?? null : null;

  useEffect(() => {
    setProject(null);
    setError(null);
    setSection("overview");
    setAllVersions(false);
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
  const compatible = versions?.filter((v) => (kind === "modpack" ? v.loaders.includes("fabric") : true)) ?? null;
  const best = compatible?.find((v) => v.versionType === "release") ?? compatible?.[0] ?? null;
  const unavailable = kind !== "modpack" && !!profile && compatible !== null && compatible.length === 0 && !allVersions;
  const target = (v?: LibraryVersion | null): InstallTarget => ({
    kind,
    projectId: hit.projectId,
    title: hit.title,
    iconUrl: hit.iconUrl,
    versionId: v?.id ?? null,
    versionNumber: v?.versionNumber ?? null,
  });
  const gallery = project?.gallery ?? [];
  const url = `https://modrinth.com/${hit.projectType === "datapack" || kind === "datapack" ? "datapack" : hit.projectType}/${hit.slug}`;

  return (
    <div className="drawer-backdrop" onPointerDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside ref={panel} className="drawer" role="dialog" aria-modal="true" aria-label={hit.title} tabIndex={-1}>
        <header className="drawer-head">
          <ProjectIcon src={project?.iconUrl ?? hit.iconUrl} title={hit.title} size={64} />
          <div className="drawer-title">
            <span className="eyebrow">{KIND_LABEL[kind]}</span>
            <h2>{hit.title}</h2>
            <span className="faint">
              by {project?.author ?? hit.author} · {compact(project?.downloads ?? hit.downloads)} downloads · {compact(project?.followers ?? hit.follows)} followers
            </span>
          </div>
          <IconButton icon="x" label="Close" onClick={onClose} />
        </header>

        <div className="drawer-cta">
          {installed ? (
            <>
              <span className="result-installed">
                <Icon name="check" size={15} /> Installed in {profile?.name}
              </span>
              {profile && (
                <Button size="sm" variant="ghost" onClick={() => onOpenProfile(profile.id)}>
                  Manage in profile
                </Button>
              )}
            </>
          ) : unavailable ? (
            <span className="drawer-unavailable">
              <Icon name="alert" size={15} />
              Not available for Minecraft {profile?.gameVersion}
              {kind === "mod" || kind === "shader" ? ` with ${kind === "shader" ? "Iris" : "Fabric"}` : ""}.
            </span>
          ) : (
            <Button variant="primary" icon="download" disabled={versions === null || (kind !== "modpack" && !profile)} onClick={() => onInstall(target(best))}>
              {kind === "modpack" ? "Install as new profile" : profile ? `Install to ${profile.name}` : "Choose a profile to install"}
            </Button>
          )}
          <Button size="sm" variant="ghost" iconRight="external" onClick={() => void api.openExternal(url)}>
            Modrinth
          </Button>
        </div>
        <p className="drawer-note faint">{placementNote(kind)}</p>

        <Tabs
          label="Project sections"
          value={section}
          onChange={setSection}
          tabs={[
            { value: "overview", label: "Overview" },
            { value: "gallery", label: "Gallery", count: gallery.length || undefined },
            { value: "versions", label: "Versions", count: versions?.length || undefined },
            { value: "details", label: "Details" },
          ]}
        />

        <div className="drawer-body scroll">
          {error ? (
            <EmptyState icon="wifiOff" title="Couldn't load this project" body={error} compact />
          ) : !project ? (
            <div className="drawer-loading">
              <Spinner />
            </div>
          ) : section === "overview" ? (
            <>
              <p className="drawer-summary">{project.description}</p>
              {project.body ? <Markdown source={project.body} /> : <p className="faint">No description provided.</p>}
            </>
          ) : section === "gallery" ? (
            gallery.length === 0 ? (
              <EmptyState icon="image" title="No gallery images" compact />
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
                  Show versions for other Minecraft releases
                </label>
              )}
              {versions === null ? (
                <Spinner />
              ) : versions.length === 0 ? (
                <EmptyState icon="info" title={profile ? `No versions for Minecraft ${profile.gameVersion}` : "No versions"} compact />
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
                          {fits && !installed && (kind === "modpack" || profile) && (
                            <Button size="sm" variant="ghost" icon="download" onClick={() => onInstall(target(v))}>
                              Install
                            </Button>
                          )}
                        </div>
                        <span className="faint">
                          {v.gameVersions.slice(-4).join(", ")} · {v.loaders.join(", ")} · {shortDate(v.datePublished)}
                          {file ? ` · ${bytes(file.size)}` : ""}
                        </span>
                        {v.changelog && (
                          <>
                            <button type="button" className="version-changelog-toggle" aria-expanded={expanded === v.id} onClick={() => setExpanded(expanded === v.id ? null : v.id)}>
                              {expanded === v.id ? "Hide changelog" : "Changelog"}
                            </button>
                            {expanded === v.id && <Markdown source={v.changelog} className="is-small" />}
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
              <dt>License</dt>
              <dd>
                {project.license?.url ? (
                  <button type="button" className="link" onClick={() => void api.openExternal(project.license!.url!)}>
                    {project.license.name || project.license.id}
                  </button>
                ) : (
                  project.license?.name || project.license?.id || "Not specified"
                )}
              </dd>
              <dt>Client</dt>
              <dd>{SIDE[project.clientSide] ?? project.clientSide}</dd>
              <dt>Server</dt>
              <dd>{SIDE[project.serverSide] ?? project.serverSide}</dd>
              <dt>Loaders</dt>
              <dd>{project.loaders.join(", ") || "—"}</dd>
              <dt>Minecraft</dt>
              <dd>{project.gameVersions.length ? `${project.gameVersions[0]} – ${project.gameVersions[project.gameVersions.length - 1]}` : "—"}</dd>
              <dt>Categories</dt>
              <dd>{[...project.categories, ...(project.additionalCategories ?? [])].join(", ") || "—"}</dd>
              <dt>Updated</dt>
              <dd>
                {shortDate(project.updated)} ({ago(project.updated)})
              </dd>
              <dt>Published</dt>
              <dd>{shortDate(project.published)}</dd>
              {project.members.length > 0 && (
                <>
                  <dt>Team</dt>
                  <dd>{project.members.map((m) => `${m.username}${m.role ? ` (${m.role})` : ""}`).join(", ")}</dd>
                </>
              )}
              {(project.sourceUrl || project.issuesUrl || project.wikiUrl) && (
                <>
                  <dt>Links</dt>
                  <dd className="detail-links">
                    {[
                      ["Source", project.sourceUrl],
                      ["Issues", project.issuesUrl],
                      ["Wiki", project.wikiUrl],
                    ]
                      .filter(([, href]) => href && href.startsWith("https://"))
                      .map(([label, href]) => (
                        <button key={label} type="button" className="link" onClick={() => void api.openExternal(href!)}>
                          {label}
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

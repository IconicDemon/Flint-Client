import { Suspense, lazy, useEffect, useMemo, useRef, useState } from "react";
import { PARTNERS } from "../../data/partners";
import { api, errorText, type Category, type LibraryHit, type LibraryKind, type LibraryResults, type LibrarySearchParams, type Profile } from "../../lib/api";
import { ago, compact } from "../../lib/format";
import { useSettings } from "../../lib/settings";
import { sound } from "../../lib/sound";
import { useStoreSelect } from "../../lib/store";
import { loaderLabel, versionMeta } from "../../lib/versions";
import { Button, EmptyState, IconButton, Segmented } from "../../ui/controls";
import { FlintMark } from "../../ui/FlintMark";
import { Icon, type IconName } from "../../ui/Icon";
import { Dropdown, Menu, Popover } from "../../ui/overlays";
import type { Navigate } from "../shell/Shell";
import { InstallDialog, ProjectIcon, type InstallTarget } from "./InstallDialog";
import { ProjectDrawer } from "./ProjectDrawer";

const FlintAI = lazy(() => import("./FlintAI"));

type Tab = LibraryKind | "partners";

const TABS: { id: Tab; label: string; icon: IconName }[] = [
  { id: "modpack", label: "Modpacks", icon: "box" },
  { id: "mod", label: "Mods", icon: "puzzle" },
  { id: "resourcepack", label: "Resource Packs", icon: "image" },
  { id: "datapack", label: "Data Packs", icon: "database" },
  { id: "shader", label: "Shaders", icon: "sun" },
  { id: "partners", label: "Partnership", icon: "handshake" },
];

const SORTS: { value: LibrarySearchParams["index"]; label: string }[] = [
  { value: "relevance", label: "Relevance" },
  { value: "downloads", label: "Downloads" },
  { value: "follows", label: "Follows" },
  { value: "updated", label: "Recently updated" },
  { value: "newest", label: "Newest" },
];

const PAGE = 24;
const ANY = "__any__";
const NONE: string[] = [];

export default function LibraryPage({ navigate, target }: { navigate: Navigate; target?: string }) {
  // Only what this page shows: download progress elsewhere in the app doesn't re-render the results.
  const store = useStoreSelect((s) => ({ profiles: s.profiles, selectedId: s.selectedId, selected: s.selected, contentRevision: s.contentRevision }));
  // Settings → General → Flint Assist: when off, the Library is plain search with no AI entry point.
  const { flintAssist } = useSettings().settings;
  const initialProfile = target && store.profiles.some((p) => p.id === target.split(":")[0]) ? target.split(":")[0] : store.selectedId ?? ANY;
  const initialTab = (target?.split(":")[1] as Tab | undefined) ?? "mod";
  const [tab, setTab] = useState<Tab>(TABS.some((t) => t.id === initialTab) ? initialTab : "mod");
  const [profileId, setProfileId] = useState<string>(initialProfile);
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [sort, setSort] = useState<LibrarySearchParams["index"]>("relevance");
  const [view, setView] = useState<"grid" | "list">(() => (localStorage.getItem("flint-library-view") as "grid" | "list") ?? "grid");
  // Category picks belong to a tab, and the page to one set of filters: both are derived while
  // rendering (not reset by an effect afterwards), so a change sends one search, not a stale one first.
  const [categoryPick, setCategoryPick] = useState<{ tab: Tab; list: string[] }>({ tab, list: NONE });
  const categories = categoryPick.tab === tab ? categoryPick.list : NONE;
  const setCategories = (list: string[]) => setCategoryPick({ tab, list });
  const filters = [debounced, sort, categories.join(","), tab, profileId].join("\n");
  const [pageAt, setPageAt] = useState({ filters, page: 0 });
  const page = pageAt.filters === filters ? pageAt.page : 0;
  const setPage = (n: number) => setPageAt({ filters, page: n });
  const [results, setResults] = useState<LibraryResults | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [tags, setTags] = useState<Category[]>([]);
  const [open, setOpen] = useState<LibraryHit | null>(null);
  /** The kind of the project open in the drawer when it came from Flint AI rather than this tab. */
  const [openKind, setOpenKind] = useState<LibraryKind | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const [installing, setInstalling] = useState<InstallTarget | null>(null);
  const [installed, setInstalled] = useState<Set<string>>(new Set());
  const [assisting, setAssisting] = useState(false);
  const request = useRef(0);
  const listRef = useRef<HTMLDivElement>(null);

  const profile: Profile | null = profileId === ANY ? null : store.profiles.find((p) => p.id === profileId) ?? null;
  /** Flint AI always works for one profile: the one picked here, else the one selected for Play. */
  const aiProfile = profile ?? store.selected ?? null;
  const kind = tab === "partners" ? null : tab;

  useEffect(() => localStorage.setItem("flint-library-view", view), [view]);

  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(query.trim()), 320);
    return () => window.clearTimeout(t);
  }, [query]);

  useEffect(() => {
    void api
      .libraryTags()
      .then((t) => setTags(t.categories))
      .catch(() => setTags([]));
  }, []);

  const revision = profile ? store.contentRevision[profile.id] ?? 0 : 0;
  useEffect(() => {
    if (!profile) return setInstalled(new Set());
    let live = true;
    void api
      .content(profile.id)
      .then((c) => {
        if (!live) return;
        const ids = [...c.mods, ...c.resourcepacks, ...c.shaders, ...c.datapacks].map((i) => i.projectId).filter((x): x is string => !!x);
        setInstalled(new Set(ids));
      })
      .catch(() => {});
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the profile's id: a profile list reload gives new objects for the same profile
  }, [profile?.id, revision]);

  useEffect(() => {
    if (!kind) return;
    const id = ++request.current;
    setLoading(true);
    setError(null);
    const params: LibrarySearchParams = {
      kind,
      query: debounced,
      index: sort,
      offset: page * PAGE,
      limit: PAGE,
      gameVersion: profile && kind !== "modpack" ? profile.gameVersion : null,
      loader: profile && kind !== "modpack" ? profile.loader : kind === "modpack" ? "fabric" : null,
      categories,
    };
    void api
      .librarySearch(params)
      .then((r) => {
        if (id !== request.current) return;
        setResults(r);
        listRef.current?.scrollTo({ top: 0 });
      })
      .catch((e) => id === request.current && setError(errorText(e)))
      .finally(() => id === request.current && setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on what the search uses, not the profile object, so a profile list reload doesn't search again
  }, [kind, debounced, sort, page, categories, profile?.id, profile?.gameVersion, profile?.loader, retry]);

  const kindTags = useMemo(() => {
    if (!kind) return [];
    const type = tags.some((t) => t.projectType === kind) ? kind : "mod";
    return tags.filter((t) => t.projectType === type && t.header !== "resolutions" && !["fabric", "forge", "neoforge", "quilt", "iris", "optifine", "canvas"].includes(t.name));
  }, [tags, kind]);

  const pages = results ? Math.max(1, Math.ceil(Math.min(results.total, 10_000) / PAGE)) : 1;

  const startInstall = (hit: LibraryHit) =>
    kind && setInstalling({ kind, projectId: hit.projectId, title: hit.title, iconUrl: hit.iconUrl });

  return (
    <div className="page-body library">
      <header className="lib-head">
        <div>
          <h1>Library</h1>
          <p className="muted">Mods, packs and shaders from Modrinth, matched to your profile.</p>
        </div>
        <div className="lib-head-actions">
          <ProfilePicker profiles={store.profiles} value={profileId} onChange={setProfileId} kind={kind} />
        </div>
      </header>

      <nav className="lib-tabs" role="tablist" aria-label="Content type">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={`lib-tab ${tab === t.id ? "is-active" : ""} ${t.id === "partners" ? "is-partner" : ""}`}
            onClick={() => setTab(t.id)}
          >
            <Icon name={t.icon} size={16} />
            {t.label}
          </button>
        ))}
      </nav>

      {tab === "partners" ? (
        <Partnership />
      ) : (
        <>
          <div className="lib-toolbar">
            <label className="lib-search">
              <Icon name="search" size={16} />
              <input
                ref={searchRef}
                type="search"
                placeholder={`Search ${TABS.find((t) => t.id === tab)?.label.toLowerCase()}`}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                aria-label="Search Modrinth"
                spellCheck={false}
              />
              {query && <IconButton icon="x" size="sm" label="Clear search" onClick={() => setQuery("")} />}
            </label>
            {flintAssist && (
              <button
                type="button"
                className="lib-ai"
                aria-expanded={assisting}
                disabled={!aiProfile}
                title={aiProfile ? "Describe what you want; Flint AI finds compatible mods and resource packs" : "Create a profile first"}
                onClick={() => (sound.play(assisting ? "tap" : "open"), setAssisting((a) => !a))}
              >
                <FlintMark size={20} />
                Find with Flint AI
              </button>
            )}
            <CategoryFilter tags={kindTags} value={categories} onChange={setCategories} />
            <Dropdown label="Sort by" value={sort} onChange={setSort} options={SORTS} className="lib-sort" />
            <Segmented
              label="View"
              value={view}
              onChange={setView}
              options={[
                { value: "grid", label: "Grid" },
                { value: "list", label: "List" },
              ]}
            />
          </div>

          <div className="lib-context">
            {profile && kind !== "modpack" ? (
              <span>
                Showing {kind === "datapack" ? "data packs" : "results"} for <strong>Minecraft {profile.gameVersion}</strong>
                {kind === "mod" || kind === "shader" ? ` · ${kind === "shader" ? "Iris" : loaderLabel(profile.loader)}` : ""}
              </span>
            ) : kind === "modpack" ? (
              <span>Fabric modpacks. Installing one creates a new profile.</span>
            ) : (
              <span>Showing every version. Choose a profile to see only what's compatible.</span>
            )}
            {categories.length > 0 && (
              <button type="button" className="lib-clear" onClick={() => setCategories([])}>
                Clear {categories.length} filter{categories.length === 1 ? "" : "s"}
              </button>
            )}
            {results?.stale && <span className="lib-stale">Offline · showing saved results</span>}
            {results?.staging && <span className="lib-stale">Development API (staging)</span>}
            {results && <span className="lib-count">{compact(results.total)} results</span>}
          </div>

          <div className="lib-results" ref={listRef}>
            {flintAssist && assisting && aiProfile && (
              <Suspense fallback={null}>
                <FlintAI
                  key={aiProfile.id}
                  profile={aiProfile}
                  onClose={() => setAssisting(false)}
                  onInspect={(hit, k) => (setOpenKind(k), setOpen(hit))}
                  onBrowse={(q) => {
                    setAssisting(false);
                    if (q) setQuery(q.slice(0, 80));
                    searchRef.current?.focus();
                  }}
                />
              </Suspense>
            )}
            {error ? (
              <EmptyState
                icon="wifiOff"
                title="Couldn't reach Modrinth"
                body={error}
                action={
                  <Button icon="refresh" onClick={() => setRetry((r) => r + 1)}>
                    Try again
                  </Button>
                }
              />
            ) : loading && !results ? (
              <Skeletons view={view} />
            ) : results && results.hits.length === 0 ? (
              <EmptyState
                icon="search"
                title={debounced ? `Nothing matches “${debounced}”` : "Nothing here yet"}
                body={profile ? `No ${TABS.find((t) => t.id === tab)?.label.toLowerCase()} for Minecraft ${profile.gameVersion} match these filters.` : "Try a different search or fewer filters."}
              />
            ) : (
              <div className={`lib-${view} ${loading ? "is-refreshing" : ""}`}>
                {results?.hits.map((hit) => (
                  <ResultCard
                    key={hit.projectId}
                    hit={hit}
                    view={view}
                    installed={installed.has(hit.projectId)}
                    canInstall={kind === "modpack" || !!profile}
                    onOpen={() => (setOpenKind(null), setOpen(hit))}
                    onInstall={() => startInstall(hit)}
                  />
                ))}
              </div>
            )}
          </div>

          {results && results.total > PAGE && (
            <footer className="lib-pager">
              <Button size="sm" icon="chevronLeft" disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>
                Previous
              </Button>
              <span className="faint">
                Page {page + 1} of {compact(pages)}
              </span>
              <Button size="sm" iconRight="chevronRight" disabled={page + 1 >= pages || loading} onClick={() => setPage(page + 1)}>
                Next
              </Button>
            </footer>
          )}
        </>
      )}

      <ProjectDrawer
        hit={open}
        kind={openKind ?? kind}
        profile={openKind ? aiProfile : profile}
        installed={open ? installed.has(open.projectId) : false}
        onClose={() => setOpen(null)}
        onInstall={(t) => setInstalling(t)}
        onOpenProfile={(id) => navigate("profiles", `${id}:content`)}
      />
      <InstallDialog target={installing} profile={openKind ? aiProfile : profile} onClose={() => setInstalling(null)} />
    </div>
  );
}

function ProfilePicker({ profiles, value, onChange, kind }: { profiles: Profile[]; value: string; onChange: (id: string) => void; kind: LibraryKind | null }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const current = profiles.find((p) => p.id === value);
  return (
    <>
      <button ref={anchor} type="button" className="lib-profile" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={kind === "modpack"}>
        {current ? <img src={versionMeta(current.gameVersion).thumb} alt="" /> : <span className="lib-profile-any"><Icon name="globe" size={16} /></span>}
        <span>
          <span className="faint">{kind === "modpack" ? "Modpacks create new profiles" : "Installing to"}</span>
          <strong>{kind === "modpack" ? "New profile" : current ? current.name : "No profile (browse only)"}</strong>
        </span>
        <Icon name="chevronDown" size={15} />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-end" label="Profile" className="profile-pop">
        <Menu
          onClose={() => setOpen(false)}
          items={[
            { type: "heading", label: "Filter and install for" },
            ...profiles.map((p) => ({
              label: (
                <span className="profile-row">
                  <img src={versionMeta(p.gameVersion).thumb} alt="" />
                  <span>
                    <strong>{p.name}</strong>
                    <span className="faint">
                      Minecraft {p.gameVersion} · {loaderLabel(p.loader)}
                    </span>
                  </span>
                </span>
              ),
              active: p.id === value,
              onSelect: () => onChange(p.id),
            })),
            { type: "separator" },
            { label: "No profile (browse every version)", icon: <Icon name="globe" size={15} />, active: value === ANY, onSelect: () => onChange(ANY) },
          ]}
        />
      </Popover>
    </>
  );
}

function CategoryFilter({ tags, value, onChange }: { tags: Category[]; value: string[]; onChange: (v: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const groups = useMemo(() => {
    const map = new Map<string, Category[]>();
    for (const t of tags) map.set(t.header, [...(map.get(t.header) ?? []), t]);
    return [...map.entries()];
  }, [tags]);
  const toggle = (name: string) => onChange(value.includes(name) ? value.filter((v) => v !== name) : [...value, name].slice(0, 6));
  return (
    <>
      <button ref={anchor} type="button" className={`dropdown-trigger lib-filter ${value.length ? "has-value" : ""}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)} disabled={!tags.length}>
        <Icon name="filter" size={15} />
        <span>{value.length ? `${value.length} selected` : "Categories"}</span>
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} placement="bottom-start" label="Categories" className="lib-filter-pop">
        {groups.map(([header, list]) => (
          <section key={header}>
            <span className="eyebrow">{header}</span>
            <div className="chip-wrap">
              {list.map((t) => (
                <button key={t.name} type="button" className={`chip ${value.includes(t.name) ? "is-on" : ""}`} aria-pressed={value.includes(t.name)} onClick={() => toggle(t.name)}>
                  {t.name.replace(/-/g, " ")}
                </button>
              ))}
            </div>
          </section>
        ))}
      </Popover>
    </>
  );
}

function ResultCard({
  hit,
  view,
  installed,
  canInstall,
  onOpen,
  onInstall,
}: {
  hit: LibraryHit;
  view: "grid" | "list";
  installed: boolean;
  canInstall: boolean;
  onOpen: () => void;
  onInstall: () => void;
}) {
  const tags = (hit.categories ?? []).filter((c) => !["fabric", "forge", "neoforge", "quilt", "iris", "optifine", "minecraft", "datapack"].includes(c)).slice(0, view === "grid" ? 2 : 4);
  return (
    <article className={`result result-${view}`}>
      <button type="button" className="result-open" onClick={onOpen} aria-label={`Open ${hit.title}`}>
        {view === "grid" && (
          <span className="result-banner" style={hit.color != null ? { background: `#${hit.color.toString(16).padStart(6, "0")}` } : undefined}>
            {hit.gallery && <img src={hit.gallery} alt="" loading="lazy" />}
          </span>
        )}
        <span className="result-main">
          <ProjectIcon src={hit.iconUrl} title={hit.title} size={view === "grid" ? 48 : 52} />
          <span className="result-text">
            <strong>{hit.title}</strong>
            <span className="faint">by {hit.author}</span>
          </span>
        </span>
        <span className="result-desc">{hit.description}</span>
        <span className="result-meta">
          <span title="Downloads">
            <Icon name="download" size={13} />
            {compact(hit.downloads)}
          </span>
          <span title="Followers">
            <Icon name="heart" size={13} />
            {compact(hit.follows)}
          </span>
          {hit.dateModified && <span title="Last updated">{ago(hit.dateModified)}</span>}
          {tags.map((t) => (
            <span key={t} className="result-tag">
              {t.replace(/-/g, " ")}
            </span>
          ))}
        </span>
      </button>
      <div className="result-action">
        {installed ? (
          <span className="result-installed">
            <Icon name="check" size={14} /> Installed
          </span>
        ) : (
          <Button size="sm" variant="secondary" icon="download" onClick={onInstall} disabled={!canInstall} title={canInstall ? undefined : "Choose a profile first"}>
            Install
          </Button>
        )}
      </div>
    </article>
  );
}

function Skeletons({ view }: { view: "grid" | "list" }) {
  return (
    <div className={`lib-${view}`} aria-busy="true" aria-label="Loading results">
      {Array.from({ length: view === "grid" ? 12 : 8 }, (_, i) => (
        <div key={i} className={`result result-${view} is-skeleton`}>
          {view === "grid" && <span className="result-banner" />}
          <span className="result-main">
            <span className="sk sk-icon" />
            <span className="result-text">
              <span className="sk sk-line" style={{ width: "60%" }} />
              <span className="sk sk-line" style={{ width: "34%" }} />
            </span>
          </span>
          <span className="sk sk-line" style={{ width: "92%" }} />
          <span className="sk sk-line" style={{ width: "70%" }} />
        </div>
      ))}
    </div>
  );
}

function Partnership() {
  if (PARTNERS.length === 0) {
    return (
      <section className="partner-empty">
        <span className="partner-mark">
          <Icon name="handshake" size={26} />
        </span>
        <h2>Partnership</h2>
        <p>Servers and creators who partner with Flint will be featured here, clearly labelled. There aren't any partners yet, and Flint never shows placeholder listings.</p>
      </section>
    );
  }
  return (
    <div className="partner-grid">
      {PARTNERS.map((p) => (
        <article key={p.name} className="partner-card">
          <strong>{p.name}</strong>
          <p className="muted">{p.description}</p>
          <Button size="sm" iconRight="external" onClick={() => void api.openExternal(p.url)}>
            Visit
          </Button>
        </article>
      ))}
    </div>
  );
}

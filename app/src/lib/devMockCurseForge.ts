/*
 * The browser preview's CurseForge (src-tauri/src/curseforge.rs answers in these shapes): a few Fabric mods, packs and
 * shaders with `cf:` ids. `?cf=down` answers as when Flint's proxy isn't deployed (404/503), `?cf=busy` as when CurseForge
 * rate limits (429), `?cf=offline` as with no internet.
 */

type Fixture = { id: number; slug: string; name: string; summary: string; author: string; downloads: number; categories: string[]; color: string; kind: string; keptOnSite?: boolean };

const FIXTURES: Fixture[] = [
  { id: 238222, slug: "jei-fabric", name: "Just Enough Items", summary: "View items and recipes, right in your inventory.", author: "mezz", downloads: 412_000_000, categories: ["Map and Information"], color: "#3b6ea8", kind: "mod" },
  { id: 248787, slug: "appleskin", name: "AppleSkin", summary: "Food and saturation shown on your hunger bar.", author: "squeek502", downloads: 168_000_000, categories: ["Food", "Map and Information"], color: "#c4473a", kind: "mod" },
  { id: 324717, slug: "jade", name: "Jade 🔍", summary: "Shows what you're looking at: blocks, entities, and more.", author: "Snownee", downloads: 190_000_000, categories: ["Map and Information"], color: "#2f9e7a", kind: "mod" },
  { id: 60089, slug: "mouse-tweaks", name: "Mouse Tweaks", summary: "Smoother inventory handling with the mouse wheel and drags.", author: "YaLTeR", downloads: 230_000_000, categories: ["Utility & QoL"], color: "#8a5cc2", kind: "mod" },
  { id: 306612, slug: "fabric-api", name: "Fabric API", summary: "Core API module providing key hooks for Fabric mods.", author: "modmuss50", downloads: 98_000_000, categories: ["API and Library"], color: "#c9b48a", kind: "mod" },
  { id: 451136, slug: "simple-voice-chat", name: "Simple Voice Chat", summary: "Proximity voice chat for your worlds and servers.", author: "henkelmax", downloads: 60_000_000, categories: ["Server Utility"], color: "#e0913f", kind: "mod", keptOnSite: true },
  { id: 396246, slug: "fabulously-optimized", name: "Fabulously Optimized", summary: "Improved performance, graphics and familiar features.", author: "robotkoer", downloads: 21_000_000, categories: ["Multiplayer"], color: "#5d9b3c", kind: "modpack" },
  { id: 457458, slug: "stay-true", name: "Stay True", summary: "A faithful take on vanilla textures with extra variety.", author: "haaayden", downloads: 4_800_000, categories: ["16x"], color: "#7a5534", kind: "resourcepack" },
  { id: 385587, slug: "complementary-reimagined", name: "Complementary Reimagined", summary: "Shaders with a vanilla-faithful look.", author: "EminGTR", downloads: 9_300_000, categories: ["Fantasy"], color: "#3f8fbf", kind: "shader" },
  { id: 667899, slug: "terralith-cf", name: "Terralith", summary: "Over 95 new biomes, using only vanilla blocks.", author: "Starmute", downloads: 12_000_000, categories: ["Worldgen"], color: "#4a7d3a", kind: "datapack" },
];

const logo = (letter: string, color: string) =>
  `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="${color}"/><text x="32" y="43" font-family="Segoe UI,Arial" font-size="30" font-weight="700" text-anchor="middle" fill="#fff">${letter}</text></svg>`)}`;
const banner = (color: string) =>
  `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 140"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${color}"/><stop offset="1" stop-color="#151515"/></linearGradient></defs><rect width="400" height="140" fill="url(#g)"/></svg>`)}`;

const site = (f: Fixture) => `https://www.curseforge.com/minecraft/${{ mod: "mc-mods", modpack: "modpacks", resourcepack: "texture-packs", shader: "shaders", datapack: "data-packs" }[f.kind]}/${f.slug}`;
const byRef = (id: string) => FIXTURES.find((f) => `cf:${f.id}` === id);

export function curseforgeMock(params: URLSearchParams) {
  const mode = params.get("cf") ?? "";
  const hit = (f: Fixture) => ({
    projectId: `cf:${f.id}`,
    slug: f.slug,
    title: f.name,
    description: f.summary,
    iconUrl: logo(f.name[0], f.color),
    author: f.author,
    downloads: f.downloads,
    follows: null,
    categories: f.categories,
    versions: ["26.2", "26.1.2", "1.21.11"],
    projectType: f.kind,
    dateModified: "2026-10-28T10:00:00Z",
    gallery: banner(f.color),
    color: null,
    websiteUrl: site(f),
    source: "curseforge",
  });
  const files = (f: Fixture) =>
    [3, 2, 1].map((n, i) => ({
      id: `cf:${f.id * 10 + n}`,
      projectId: `cf:${f.id}`,
      name: `${f.name} ${n}.0.0`,
      versionNumber: `${f.slug}-fabric-${n}.0.0`,
      versionType: i === 0 ? "beta" : "release",
      gameVersions: ["26.2", "26.1.2"],
      loaders: f.kind === "mod" || f.kind === "modpack" ? ["fabric"] : ["minecraft", "iris", "datapack"],
      datePublished: `2026-10-${28 - i * 7}T10:00:00Z`,
      downloads: 120_000 - i * 30_000,
      changelog: "",
      dependencies: f.id === 306612 ? [] : [{ project_id: "cf:306612", version_id: null, dependency_type: "required" }],
      files: [{ filename: `${f.slug}-fabric-${n}.0.0.${f.kind === "mod" ? "jar" : "zip"}`, size: 1_300_000, primary: true }],
      downloadable: !f.keptOnSite,
    }));
  return {
    async search(p: { kind: string; query: string; offset: number; limit: number }) {
      await new Promise((r) => setTimeout(r, 220));
      const calm = mode === "down" ? "unavailable" : mode === "busy" ? "busy" : mode === "offline" ? "offline" : null;
      if (calm) return { hits: [], offset: p.offset, limit: 0, total: 0, stale: false, staging: false, rateLimit: {}, unavailable: calm, retryIn: calm === "busy" ? 60 : null };
      const q = p.query.toLowerCase();
      const hits = FIXTURES.filter((f) => f.kind === p.kind && (!q || f.name.toLowerCase().includes(q))).map(hit);
      return { hits, offset: p.offset, limit: p.limit, total: hits.length, stale: false, staging: false, rateLimit: {}, unavailable: null };
    },
    categories() {
      const list: [number, string, string][] = [
        [423, "Map and Information", "mod"], [424, "Cosmetic", "mod"], [425, "Miscellaneous", "mod"], [435, "Server Utility", "mod"], [436, "Food", "mod"], [5191, "Utility & QoL", "mod"], [421, "API and Library", "mod"],
        [4484, "Multiplayer", "modpack"], [4475, "Adventure and RPG", "modpack"], [393, "16x", "resourcepack"], [394, "32x", "resourcepack"], [6553, "Realistic", "shader"], [6554, "Fantasy", "shader"], [6946, "Worldgen", "datapack"],
      ];
      return { categories: list.map(([id, label, projectType]) => ({ name: String(id), label, projectType, header: "categories" })) };
    },
    owns: (id: unknown) => typeof id === "string" && /^cf:\d+$/.test(id),
    project(id: string) {
      const f = byRef(id) ?? FIXTURES[0];
      return {
        id: `cf:${f.id}`, slug: f.slug, title: f.name, description: f.summary,
        body: `<h2>${f.name}</h2><p>${f.summary}</p><ul><li>Browser preview of a CurseForge project.</li><li>Its description comes from CurseForge as HTML.</li></ul>`,
        iconUrl: logo(f.name[0], f.color), projectType: f.kind, categories: f.categories, additionalCategories: [], loaders: ["fabric"], gameVersions: ["1.21.11", "26.1.2", "26.2"],
        clientSide: "unknown", serverSide: "unknown", downloads: f.downloads, followers: 0, license: null, published: "2019-06-01T00:00:00Z", updated: "2026-10-28T00:00:00Z",
        gallery: [{ url: banner(f.color), title: "Screenshot", featured: false }], sourceUrl: null, issuesUrl: null, wikiUrl: null, websiteUrl: site(f), color: null,
        author: f.author, members: [{ username: f.author, role: "Author", owner: true }], stale: false, source: "curseforge",
      };
    },
    versions(id: string) {
      return { versions: files(byRef(id) ?? FIXTURES[0]), stale: false };
    },
    changelog() {
      return "<ul><li>Fixed a crash when opening the inventory with a full hotbar.</li><li>Faster startup.</li></ul>";
    },
    /** content_plan for CurseForge projects: the project, and Fabric API from CurseForge for mods. */
    plan(requests: { kind: string; project: string; versionId?: string | null; source?: string }[]) {
      const items: any[] = requests.map((r) => {
        const f = byRef(r.project) ?? FIXTURES[0];
        const file = files(f)[1];
        const base = { projectId: `cf:${f.id}`, slug: f.slug, title: f.name, description: f.summary, iconUrl: logo(f.name[0], f.color), kind: r.kind, versionId: file.id, versionNumber: file.versionNumber, versionType: "release", fileName: file.files[0].filename, size: 1_300_000, source: r.source ?? "user", required: true };
        return f.keptOnSite ? { ...base, status: "unavailable", message: "Its author allows downloads only on CurseForge", externalUrl: `${site(f)}/files/${f.id * 10 + 2}` } : { ...base, status: "ok" };
      });
      if (requests.some((r) => r.kind === "mod") && !requests.some((r) => r.project === "cf:306612")) {
        const api = FIXTURES.find((f) => f.id === 306612)!;
        items.push({ projectId: "cf:306612", slug: api.slug, title: api.name, description: api.summary, iconUrl: logo("F", api.color), kind: "mod", versionId: "cf:3066122", versionNumber: "fabric-api-0.130.0+26.2", versionType: "release", fileName: "fabric-api-0.130.0+26.2.jar", size: 2_400_000, source: "dependency", requiredBy: items[0]?.title, required: true, status: "ok" });
      }
      const blockers = items.filter((i) => i.status === "unavailable").map((i) => `${i.title}: ${i.message}`);
      return { items, unmatched: [], blockers, gameVersion: "26.2", loader: "fabric", gameApplies: true };
    },
    /** The title an installed CurseForge file shows. */
    titleOf(versionId: string) {
      return FIXTURES.find((f) => versionId.startsWith(`cf:${f.id}`))?.name ?? null;
    },
  };
}

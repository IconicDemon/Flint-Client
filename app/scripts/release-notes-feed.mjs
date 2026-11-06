#!/usr/bin/env node
// Writes release-notes.json, the public changelog feed flintclient.com builds its release list and one page per version from,
// using the same release notes the launcher's "What's new" shows (src/data/releaseNotes.json). Every public release carries
// it as an asset, and promote-update.yml copies it to the fixed `site-data` release, which is where the site reads it:
// https://github.com/<repo>/releases/download/site-data/release-notes.json (Flint's releases are prereleases, so
// releases/latest/ does not resolve).
//
//   node scripts/release-notes-feed.mjs --repo IconicDemon/Flint-Client --version 1.0.0 --out dist/release-notes.json [--dist dist]
//
// Schema 1 (agreed with the site): { schema, generated_at, versions: [{ version, date (YYYY-MM-DD), title, summary, points,
// sections?: [{heading, points}], release_url, page_url, downloads? }] }, newest first, plain text only. `page_url` is the
// version's own page on the site, exactly https://flintclient.com/releases/<version>/ (agreed with the site; the launcher's
// news opens it), as releaseNotes.json gives it. The version being released gets `downloads` when --dist holds its files:
// per platform { url, file_name, size_bytes, sha256 }, only for files that exist.
// The site skips a version whose text says "beta", "soon", "yet", "planned" or "coming soon", so the release stops instead.
import { createHash } from "node:crypto";
import { existsSync, readFileSync, statSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const arg = (name) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
};
const repo = arg("repo");
const current = arg("version");
const out = arg("out");
const dist = arg("dist");
if (!repo || !/^[\w.-]+\/[\w.-]+$/.test(repo) || !current || !out) {
  console.error("usage: --repo owner/name --version x.y.z --out file [--dist dir]");
  process.exit(2);
}
const here = dirname(fileURLToPath(import.meta.url));
const notes = JSON.parse(readFileSync(join(here, "..", "src", "data", "releaseNotes.json"), "utf8"));
if (!Array.isArray(notes) || !notes.some((n) => n.version === current)) {
  console.error(`releaseNotes.json has no entry for ${current}: add it before releasing`);
  process.exit(1);
}

const UNFINISHED = /\b(beta|soon|yet|planned)\b|coming soon/i;
// The site's own checks (flintclient.com drops a version that fails any): no HTML or Markdown, no control characters.
const plain = (t) => typeof t === "string" && !/[<>]/.test(t) && !/[\u0000-\u001f\u007f]/.test(t) && !/\[[^\]]*\]\(|\*\*|__|`/.test(t);
const realDate = (d) => /^\d{4}-\d{2}-\d{2}$/.test(d ?? "") && new Date(`${d}T00:00:00Z`).toISOString().slice(0, 10) === d;
const semver = (v) => v.split(/[.-]/).map((p) => (/^\d+$/.test(p) ? Number(p) : p));
const newerFirst = (a, b) => {
  const x = semver(a.version);
  const y = semver(b.version);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if (x[i] === undefined) return -1;
    if (y[i] === undefined) return 1;
    if (x[i] !== y[i]) return x[i] > y[i] ? -1 : 1;
  }
  return 0;
};

const base = `https://github.com/${repo}/releases`;
/** A version's page on flintclient.com, the one address the site serves it at. */
const pageUrl = (v) => `https://flintclient.com/releases/${v}/`;
/** The files the release publishes per platform (as release.yml collects them), by folder under --dist. */
const installers = (v) => ({
  windows: ["windows", `Flint_Client_${v}_x64-setup.exe`],
  mac_apple: ["macos", `flintclient-v${v}.dmg`],
  mac_intel: ["macos-intel", `flintclient-v${v}-intel.dmg`],
  linux_appimage: ["linux", `Flint_Client_${v}_amd64.AppImage`],
  linux_deb: ["linux", `Flint_Client_${v}_amd64.deb`],
});
function downloads(v) {
  const found = {};
  for (const [key, [folder, name]] of Object.entries(installers(v))) {
    const file = dist ? join(dist, folder, name) : null;
    if (!file || !existsSync(file)) continue;
    found[key] = {
      url: `${base}/download/v${v}/${name}`,
      file_name: name,
      size_bytes: statSync(file).size,
      sha256: createHash("sha256").update(readFileSync(file)).digest("hex"),
    };
  }
  return Object.keys(found).length > 0 ? found : undefined;
}

const problems = [];
const versions = notes
  .filter((n) => typeof n.version === "string" && /^\d+\.\d+\.\d+$/.test(n.version))
  .sort(newerFirst)
  .map((n) => {
    const points = Array.isArray(n.points) ? n.points.filter((p) => typeof p === "string") : [];
    const sections = Array.isArray(n.sections)
      ? n.sections.filter((s) => s && typeof s.heading === "string" && Array.isArray(s.points)).map((s) => ({ heading: s.heading, points: s.points.filter((p) => typeof p === "string") }))
      : undefined;
    const texts = [n.title, n.summary, ...points, ...(sections ?? []).flatMap((s) => [s.heading, ...s.points])];
    if (!realDate(n.date)) problems.push(`${n.version}: date must be a real YYYY-MM-DD`);
    if ((n.title ?? "").length > 120) problems.push(`${n.version}: title over 120 characters`);
    if ((n.summary ?? "").length > 400) problems.push(`${n.version}: summary over 400 characters`);
    if (texts.some((t) => UNFINISHED.test(t ?? ""))) problems.push(`${n.version}: says beta/soon/yet/planned (the site would skip it)`);
    if (texts.some((t) => t != null && !plain(t))) problems.push(`${n.version}: text must be plain (no HTML or Markdown)`);
    if ([...points, ...(sections ?? []).flatMap((s) => s.points)].some((p) => p.length > 300)) problems.push(`${n.version}: a point over 300 characters`);
    const page = pageUrl(n.version);
    if (n.page_url !== page) problems.push(`${n.version}: page_url must be exactly ${page}`);
    const entry = { version: n.version, date: n.date, title: n.title ?? "", summary: n.summary ?? "", points, release_url: `${base}/tag/v${n.version}`, page_url: page };
    if (sections?.length) entry.sections = sections;
    if (n.version === current) {
      const d = downloads(current);
      if (d) entry.downloads = d;
    }
    return entry;
  });
// The version being released must be publishable as it is; an older one only warns (the site skips it).
const mine = problems.filter((p) => p.startsWith(`${current}:`));
for (const p of problems) console.log(`${mine.includes(p) ? "error" : "warning"}: ${p}`);
if (mine.length > 0) process.exit(1);
const body = JSON.stringify({ schema: 1, generated_at: new Date().toISOString(), versions }, null, 2) + "\n";
if (Buffer.byteLength(body) > 1024 * 1024) {
  console.error("release-notes.json is over 1 MiB");
  process.exit(1);
}
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, body);
const withDownloads = versions.find((v) => v.version === current)?.downloads;
console.log(`release-notes.json: ${versions.length} versions, newest ${versions[0]?.version}; ${current} downloads: ${withDownloads ? Object.keys(withDownloads).join(", ") : "none"}`);

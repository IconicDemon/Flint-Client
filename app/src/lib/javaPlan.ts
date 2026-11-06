// Settings → Java and memory: how the Java each Minecraft version runs on reads, and how a custom Java fits
// (pure, tested with `npm test`). The plan itself comes from the launcher (src-tauri/src/javaplan.rs), which uses
// the same rules a launch does.
import type { JavaOverview, JavaUse } from "./api";
import { list } from "./format.ts";
import { t } from "./i18n.ts";

type Version = JavaOverview["versions"][number];

export type JavaRow = { versions: string[]; withMenu: JavaUse; withoutMenu: JavaUse; note?: string };

const same = (a: JavaUse, b: JavaUse) => a.major === b.major && a.minimum === b.minimum && a.fallback === b.fallback;

/** Versions that run the same way, side by side, in the order Flint lists them (newest first). */
export function groupVersions(versions: Version[]): JavaRow[] {
  const rows: JavaRow[] = [];
  for (const v of versions) {
    const last = rows[rows.length - 1];
    if (last && same(last.withMenu, v.withMenu) && same(last.withoutMenu, v.withoutMenu) && last.note === v.note) last.versions.push(v.version);
    else rows.push({ versions: [v.version], withMenu: v.withMenu, withoutMenu: v.withoutMenu, note: v.note });
  }
  return rows;
}

/** "1.21.11, 1.21.10 and 1.21.1", in the interface language. */
export function andList(items: string[]): string {
  return list(items);
}

/** What a row runs on, in words: "Java 25", or "Java 25 with Flint's in-game menu (Fabric), otherwise Java 21". */
export function rowJava(row: JavaRow, inGameMenu: boolean): string {
  if (row.withMenu.major === row.withoutMenu.major) return `Java ${row.withMenu.major}`;
  if (!inGameMenu) return `Java ${row.withoutMenu.major}`;
  return t("settings.java.plan.withMenu", { menu: row.withMenu.major, own: row.withoutMenu.major });
}

/** The detail under a row: the fallback where Mojang has no build, and anything this computer can't run. */
export function rowDetail(row: JavaRow, inGameMenu: boolean): string | null {
  const parts: string[] = [];
  if (inGameMenu && row.withMenu.fallback && row.withMenu.fallback !== row.withMenu.major)
    parts.push(t("settings.java.plan.fallback", { fallback: row.withMenu.fallback, major: row.withMenu.major }));
  if (row.note) parts.push(row.note);
  return parts.length ? parts.join(" ") : null;
}

/** "Java 25" for a runtime component Flint keeps, from its release file or its name. */
export function runtimeLabel(r: JavaOverview["runtimes"][number]): string {
  if (r.major) return `Java ${r.major}`;
  const known: Record<string, string> = { "java-runtime-epsilon": "Java 25", "java-runtime-delta": "Java 21", "java-runtime-gamma": "Java 17", "jre-legacy": "Java 8" };
  return known[r.component] ?? r.component;
}

/**
 * Where a custom Java (it runs every launch) won't work, one sentence each: versions that need a newer Java, and
 * 1.8.9 without Flint's menu, which is made for Java 8. Nothing when its version isn't known.
 */
export function customJavaWarnings(major: number | null | undefined, versions: Version[], inGameMenu: boolean): string[] {
  if (!major) return [];
  // Each way a version can run, and the oldest Java that way takes.
  const ways: { label: string; need: number; madeFor?: number }[] = [];
  for (const v of versions) {
    if (!inGameMenu || v.withMenu.minimum === v.withoutMenu.minimum) {
      ways.push({ label: v.version, need: v.withoutMenu.minimum, madeFor: v.withoutMenu.major <= 8 ? v.withoutMenu.major : undefined });
    } else {
      ways.push({ label: t("settings.java.custom.withMenu", { version: v.version }), need: v.withMenu.minimum });
      ways.push({ label: t("settings.java.custom.withoutMenu", { version: v.version }), need: v.withoutMenu.minimum, madeFor: v.withoutMenu.major <= 8 ? v.withoutMenu.major : undefined });
    }
  }
  const out: string[] = [];
  const needs = [...new Set(ways.filter((w) => major < w.need).map((w) => w.need))].sort((a, b) => b - a);
  for (const need of needs) {
    const labels = ways.filter((w) => w.need === need && major < need).map((w) => w.label);
    out.push(
      labels.length === 1
        ? t("settings.java.custom.cantRunSingle", { major, version: labels[0], need })
        : t("settings.java.custom.cantRunSeveral", { major, versions: andList(labels), need }),
    );
  }
  const old = ways.filter((w) => w.madeFor && major > w.madeFor && major >= w.need);
  if (old.length) out.push(t("settings.java.custom.madeFor", { versions: andList(old.map((w) => w.label)), madeFor: old[0].madeFor!, major }));
  return out;
}

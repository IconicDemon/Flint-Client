import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
// @ts-expect-error type error without @types/node package
import process from "node:process";
// @ts-expect-error type error without @types/node package
import { readdirSync, readFileSync } from "node:fs";
import { coverageOf, type Messages } from "./src/lib/i18nCatalog.ts";
// Development only: stand-in partner pictures for the browser mock (never in a build).
import { partnerMediaPlugin } from "./scripts/dev-partner-media.mjs";
const host = process.env.TAURI_DEV_HOST;

/**
 * `virtual:i18n-coverage`: how much of English each language translates, counted from the catalogs in
 * src/locales when the app is built, so Settings → Language shows real numbers without loading every
 * language.
 */
function i18nCoverage(): Plugin {
  const id = "virtual:i18n-coverage";
  let dir = "src/locales";
  return {
    name: "flint-i18n-coverage",
    configResolved(config) {
      dir = `${config.root}/src/locales`;
    },
    resolveId: (source) => (source === id ? `\0${id}` : undefined),
    load(resolved) {
      if (resolved !== `\0${id}`) return;
      const read = (file: string): Messages => {
        this.addWatchFile(`${dir}/${file}`);
        return JSON.parse(readFileSync(`${dir}/${file}`, "utf8"));
      };
      const english = read("en.json");
      const coverage: Record<string, { translated: number; total: number }> = {};
      for (const file of (readdirSync(dir) as string[]).filter((f) => f.endsWith(".json")).sort()) {
        const locale = file.slice(0, -".json".length);
        coverage[locale] = coverageOf(english, file === "en.json" ? english : read(file), locale);
      }
      return `export default ${JSON.stringify(coverage)};`;
    },
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [react(), i18nCoverage(), partnerMediaPlugin("src/lib/partnerFixtures.json")],
  // `--mode perf`: a production build with React's profiling build, so src/lib/perf.ts can time
  // commits. Normal builds never include it.
  resolve: mode === "perf" ? { alias: [{ find: /^react-dom\/client$/, replacement: "react-dom/profiling" }] } : undefined,
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    fs: {
      allow: [".."],
    },
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
  },
}));

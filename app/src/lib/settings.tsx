import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "./api";
import { sound } from "./sound";

export type ThemeId = "ember" | "obsidian" | "moss" | "bloodstone" | "dusk";
export type FontId = "default" | "minecraft";
export type EffectsLevel = "full" | "balanced" | "minimal";

export const THEMES: { id: ThemeId; name: string; note: string; swatch: [string, string, string] }[] = [
  { id: "ember", name: "Ember", note: "Signature amber", swatch: ["#0c0b0a", "#2a2724", "#cd8b3e"] },
  { id: "obsidian", name: "Obsidian", note: "Graphite, neutral", swatch: ["#080809", "#242427", "#a8abb6"] },
  { id: "moss", name: "Moss", note: "Forest and sage", swatch: ["#0b0c0b", "#262b26", "#76986a"] },
  { id: "bloodstone", name: "Bloodstone", note: "Deep crimson", swatch: ["#0d0a0a", "#2b2323", "#ac383e"] },
  { id: "dusk", name: "Dusk", note: "Plum violet", swatch: ["#0b0a0e", "#292432", "#9276ba"] },
];

/** Older saved themes map to the closest current one. Frost (cool blue) becomes Obsidian, the
 *  neutral theme, rather than a different colour the player never chose. */
const THEME_ALIASES: Record<string, ThemeId> = { mono: "obsidian", slate: "obsidian", frost: "obsidian" };

export const FONTS: { id: FontId; name: string; note: string; display: string; body: string }[] = [
  { id: "default", name: "Flint Default", note: "Outfit headings, Inter text", display: "Outfit", body: "Inter" },
  { id: "minecraft", name: "Minecraft", note: "Pixel headings, clear text", display: "Monocraft", body: "Inter" },
];

export const EFFECTS: { id: EffectsLevel; name: string; note: string }[] = [
  { id: "full", name: "Full", note: "Glass panels, parallax and a 60 fps character" },
  { id: "balanced", name: "Balanced", note: "Solid panels and a 30 fps character" },
  { id: "minimal", name: "Minimal", note: "No blur or motion; best for older computers" },
];

export type Settings = {
  theme: ThemeId;
  font: FontId;
  sounds: boolean;
  volume: number;
  animations: boolean;
  parallax: boolean;
  effects: EffectsLevel;
  /** 0 keeps wallpapers at full brightness, 1 is the darkest allowed treatment. */
  wallpaperDim: number;
  launchAtLogin: boolean;
  memoryMb: number;
  javaPath: string;
  minimizeOnLaunch: boolean;
  /** New Fabric profiles start with Flint's performance mods. */
  performanceMods: boolean;
  /** Flint Performance Core picks Java settings per Minecraft version and runs the in-game runtime's optimization modules; off uses Flint 0.4.0's Java flags and turns those modules off. */
  automaticOptimization: boolean;
  /** Flint Assist: Flint's AI features (Flint AI in the Library and profiles, AI mod suggestions). */
  flintAssist: boolean;
  /** Install Flint's in-game main menu into supported profiles (Fabric on Minecraft 26.3). */
  inGameMenu: boolean;
  /** Show "Playing Flint Client" on Discord while a game launched through Flint runs. */
  discordPresence: boolean;
  /** Include the Minecraft version and loader in that activity. */
  discordDetails: boolean;
  aiGatewayUrl: string;
  aiEffort: "low" | "medium" | "high";
  /** Auto Sync keeps compatible game settings, packs, shaders and mod settings consistent across profiles. */
  autoSync: boolean;
  fontVersion: 2;
};

export const DEFAULT_SETTINGS: Settings = {
  theme: "ember",
  font: "default",
  sounds: true,
  volume: 0.5,
  animations: true,
  parallax: true,
  effects: "full",
  wallpaperDim: 0.3,
  launchAtLogin: false,
  memoryMb: 4096,
  javaPath: "",
  minimizeOnLaunch: false,
  performanceMods: true,
  automaticOptimization: true,
  flintAssist: true,
  inGameMenu: true,
  discordPresence: true,
  discordDetails: true,
  aiGatewayUrl: "",
  aiEffort: "medium",
  autoSync: true,
  fontVersion: 2,
};

type Ctx = {
  settings: Settings;
  update: (patch: Partial<Settings>) => void;
  motionOk: boolean;
  /** True unless effects are Minimal: 3D character, crossfades and glass are allowed. */
  effectsOk: boolean;
  effects: EffectsLevel;
};

const SettingsContext = createContext<Ctx | null>(null);

const prefersReduced = () => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;

export function applyAppearance(s: Settings, systemReduced: boolean) {
  const root = document.documentElement;
  root.dataset.theme = s.theme;
  root.dataset.font = s.font;
  root.dataset.motion = !s.animations || s.effects === "minimal" || systemReduced ? "reduced" : "full";
  root.dataset.effects = s.effects;
  root.style.setProperty("--wallpaper-dim", String(s.wallpaperDim));
  sound.configure(s.sounds, s.volume);
}

export function SettingsProvider({ initial, children }: { initial: Settings; children: ReactNode }) {
  const [settings, setSettings] = useState(initial);
  const [systemReduced, setSystemReduced] = useState(prefersReduced);
  const saveTimer = useRef<number | undefined>(undefined);

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setSystemReduced(query.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  useEffect(() => applyAppearance(settings, systemReduced), [settings, systemReduced]);

  const update = useCallback((patch: Partial<Settings>) => {
    setSettings((prev) => {
      const next = { ...prev, ...patch };
      window.clearTimeout(saveTimer.current);
      saveTimer.current = window.setTimeout(() => void api.settingsSet(next).catch(() => {}), 250);
      return next;
    });
  }, []);

  const value = useMemo(
    () => ({
      settings,
      update,
      motionOk: settings.animations && settings.effects !== "minimal" && !systemReduced,
      effectsOk: settings.effects !== "minimal",
      effects: settings.effects,
    }),
    [settings, update, systemReduced],
  );
  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

export function useSettings() {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error("SettingsProvider missing");
  return ctx;
}

export function normalizeSettings(raw: Record<string, unknown>): Settings {
  const s = { ...DEFAULT_SETTINGS, ...raw } as Settings;
  s.theme = THEME_ALIASES[s.theme] ?? s.theme;
  if (!THEMES.some((t) => t.id === s.theme)) s.theme = "ember";
  if (!EFFECTS.some((e) => e.id === s.effects)) s.effects = raw.reducedEffects === true ? "minimal" : "full";
  delete (s as Record<string, unknown>).reducedEffects;
  s.wallpaperDim = Math.min(1, Math.max(0, Number.isFinite(Number(s.wallpaperDim)) ? Number(s.wallpaperDim) : DEFAULT_SETTINGS.wallpaperDim));
  if (raw.fontVersion !== 2) {
    s.font = (raw.font as string) === "pixel" ? "minecraft" : "default";
  }
  // Editorial (modern) and Technical were retired; they fall back to Flint Default.
  if (!FONTS.some((f) => f.id === s.font)) s.font = "default";
  s.fontVersion = 2;
  s.volume = Math.min(1, Math.max(0, Number(s.volume) || 0));
  s.memoryMb = Math.min(32768, Math.max(1024, Number(s.memoryMb) || 4096));
  s.performanceMods = s.performanceMods !== false;
  s.automaticOptimization = s.automaticOptimization !== false;
  s.autoSync = s.autoSync !== false;
  return s;
}

import w1_8_9 from "../assets/wallpapers/1_8_9.jpg";
import w1_21_1 from "../assets/wallpapers/1_21_1.jpg";
import w1_21_10 from "../assets/wallpapers/1_21_10.jpg";
import w1_21_11 from "../assets/wallpapers/1_21_11.jpg";
import w26_1_1 from "../assets/wallpapers/26_1_1.jpg";
import w26_2 from "../assets/wallpapers/26_2.jpg";
import w26_3 from "../assets/wallpapers/26_3.jpg";
import t1_8_9 from "../assets/wallpapers/thumbs/1_8_9.jpg";
import t1_21_1 from "../assets/wallpapers/thumbs/1_21_1.jpg";
import t1_21_10 from "../assets/wallpapers/thumbs/1_21_10.jpg";
import t1_21_11 from "../assets/wallpapers/thumbs/1_21_11.jpg";
import t26_1_1 from "../assets/wallpapers/thumbs/26_1_1.jpg";
import t26_2 from "../assets/wallpapers/thumbs/26_2.jpg";
import t26_3 from "../assets/wallpapers/thumbs/26_3.jpg";
import wGeneric from "../assets/wallpapers/java_edition.jpg";
import tGeneric from "../assets/wallpapers/thumbs/java_edition.jpg";
// Portrait crops for the Profiles carousel, sized for its largest card (300×420 CSS px) at 2× density.
import c1_8_9 from "../assets/wallpapers/cards/1_8_9.jpg";
import c1_21_1 from "../assets/wallpapers/cards/1_21_1.jpg";
import c1_21_10 from "../assets/wallpapers/cards/1_21_10.jpg";
import c1_21_11 from "../assets/wallpapers/cards/1_21_11.jpg";
import c26_1_1 from "../assets/wallpapers/cards/26_1_1.jpg";
import c26_2 from "../assets/wallpapers/cards/26_2.jpg";
import c26_3 from "../assets/wallpapers/cards/26_3.jpg";
import cGeneric from "../assets/wallpapers/cards/java_edition.jpg";

export type VersionMeta = {
  id: string;
  drop?: string;
  java: number;
  wallpaper: string;
  thumb: string;
  /** Portrait card art (600×840) for the Profiles carousel. */
  card: string;
  fabric: boolean;
  note?: string;
};

export const VERSIONS: VersionMeta[] = [
  { id: "26.3", java: 25, wallpaper: w26_3, thumb: t26_3, card: c26_3, fabric: true },
  { id: "26.2", java: 25, wallpaper: w26_2, thumb: t26_2, card: c26_2, fabric: true },
  { id: "26.1.2", java: 25, wallpaper: w26_1_1, thumb: t26_1_1, card: c26_1_1, fabric: true },
  { id: "26.1.1", java: 25, wallpaper: w26_1_1, thumb: t26_1_1, card: c26_1_1, fabric: true },
  { id: "26.1", java: 25, wallpaper: w26_1_1, thumb: t26_1_1, card: c26_1_1, fabric: true },
  { id: "1.21.11", drop: "Mounts of Mayhem", java: 21, wallpaper: w1_21_11, thumb: t1_21_11, card: c1_21_11, fabric: true },
  { id: "1.21.10", drop: "The Copper Age", java: 21, wallpaper: w1_21_10, thumb: t1_21_10, card: c1_21_10, fabric: true },
  { id: "1.21.1", drop: "Tricky Trials", java: 21, wallpaper: w1_21_1, thumb: t1_21_1, card: c1_21_1, fabric: true },
  {
    id: "1.8.9",
    drop: "Bountiful Update",
    java: 8,
    wallpaper: w1_8_9,
    thumb: t1_8_9,
    card: c1_8_9,
    fabric: false,
    note: "Official Fabric doesn't support 1.8.9, so it runs vanilla.",
  },
];

function javaFor(id: string): number {
  const [major, minor = 0, patch = 0] = id.split(".").map((n) => parseInt(n, 10) || 0);
  if (major >= 26) return 25;
  if (major !== 1) return 21;
  if (minor > 20 || (minor === 20 && patch >= 5)) return 21;
  if (minor >= 18) return 17;
  if (minor === 17) return 16;
  return 8;
}

/** Wallpapers come only from the explicit mapping; anything else uses the generic Java Edition art. */
export const versionMeta = (id: string): VersionMeta =>
  VERSIONS.find((v) => v.id === id) ?? { id, java: javaFor(id), wallpaper: wGeneric, thumb: tGeneric, card: cGeneric, fabric: true };

export const loaderLabel = (loader: string) => (loader === "fabric" ? "Fabric" : "Vanilla");

/**
 * Java 8 versions use Mojang's Intel-only runtime and LWJGL 2 natives, so they can't run natively on
 * Apple silicon. The backend refuses them too; this is only for explaining it up front.
 */
export function platformBlock(id: string, info: { platform: string; arch: string }): string | null {
  if (info.platform === "macos" && info.arch === "aarch64" && versionMeta(id).java === 8) {
    return `Minecraft ${id} isn't supported on Apple silicon Macs yet.`;
  }
  return null;
}

export const defaultProfileName = (id: string) => versionMeta(id).drop ?? `Minecraft ${id}`;

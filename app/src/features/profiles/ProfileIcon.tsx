import { useEffect, useState } from "react";
import { api, type Profile, type ProfileIcon as Icon } from "../../lib/api";
import { normalizeHex, tileShades } from "../../lib/color";
import { versionMeta } from "../../lib/versions";

/** The icon picker's items (in groups), tiles and item pictures: read once from the player's installed Minecraft, then kept. */
export type IconCatalog = {
  groups: { name: string; icons: string[] }[];
  icons: string[];
  colors: string[];
  textures: Record<string, string>;
};

let catalog: IconCatalog | null = null;
let pending: Promise<IconCatalog> | null = null;
const listeners = new Set<(c: IconCatalog) => void>();

/** Asks again (a version may have been installed since): only while no pictures were found. */
export function loadIconCatalog(): Promise<IconCatalog> {
  if (catalog && Object.keys(catalog.textures).length > 0) return Promise.resolve(catalog);
  pending ??= api
    .profileIconTextures()
    .then((found) => {
      catalog = found;
      for (const listener of listeners) listener(found);
      return found;
    })
    .finally(() => {
      pending = null;
    });
  return pending;
}

export function useIconCatalog(): IconCatalog | null {
  const [value, setValue] = useState(catalog);
  useEffect(() => {
    listeners.add(setValue);
    if (!catalog) void loadIconCatalog().catch(() => {});
    return () => {
      listeners.delete(setValue);
    };
  }, []);
  return value;
}

/** A profile's own picture as a data URL, by profile and file (a new picture is a new file). */
const pictures = new Map<string, string | null>();

function usePicture(profile: Profile): string | null {
  const icon = profile.icon;
  const key = icon?.kind === "image" ? `${profile.id}/${icon.file}` : null;
  const [url, setUrl] = useState<string | null>(key ? (pictures.get(key) ?? null) : null);
  useEffect(() => {
    if (!key) {
      setUrl(null);
      return;
    }
    if (pictures.has(key)) {
      setUrl(pictures.get(key) ?? null);
      return;
    }
    let live = true;
    void api
      .profileIconPicture(profile.id)
      .then((found) => {
        pictures.set(key, found);
        if (live) setUrl(found);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [key, profile.id]);
  return url;
}

/** Just the item on its tile (the picker's cells and the preview use it too): a named tile, or one of any colour. */
export function ItemTile({ name, color, size, texture }: { name: string; color: string; size: number; texture?: string }) {
  const picked = normalizeHex(color);
  const shades = picked ? tileShades(picked) : null;
  const style = shades ? ({ width: size, height: size, "--tile-a": shades[0], "--tile-b": shades[1] } as React.CSSProperties) : { width: size, height: size };
  return (
    <span className={`picon picon-tile ${picked ? "" : `tile-${color}`}`} style={style} data-item={name}>
      {texture && <img className="picon-item" src={texture} alt="" draggable={false} />}
    </span>
  );
}

/**
 * A profile's icon at `size` px: its item on a tile, its own picture, or (none, or a picture that can't be read) the
 * version's picture, cropped square.
 */
export function ProfileIcon({ profile, size = 36, className = "" }: { profile: Profile; size?: number; className?: string }) {
  const found = useIconCatalog();
  const picture = usePicture(profile);
  const icon: Icon | null | undefined = profile.icon;
  if (icon?.kind === "item") {
    return (
      <span className={`picon-wrap ${className}`}>
        <ItemTile name={icon.name} color={icon.color} size={size} texture={found?.textures[icon.name]} />
      </span>
    );
  }
  if (icon?.kind === "image" && picture) {
    return (
      <span className={`picon-wrap ${className}`}>
        <img className="picon picon-picture" src={picture} alt="" style={{ width: size, height: size }} draggable={false} />
      </span>
    );
  }
  return (
    <span className={`picon-wrap ${className}`}>
      <span
        className="picon picon-version"
        style={{
          width: size,
          height: size,
          backgroundImage: `url(${versionMeta(profile.gameVersion).thumb})`,
        }}
      />
    </span>
  );
}

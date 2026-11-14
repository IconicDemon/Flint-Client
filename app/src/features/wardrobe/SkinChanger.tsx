import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, type SavedSkin } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { sound } from "../../lib/sound";
import { loadFavourites, saveFavourites, skinQuery, sortedSkins } from "../../lib/wardrobe";
import { Button, EmptyState, IconButton } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { useToast } from "../../ui/overlays";
import { CardGrid } from "./WardrobeCard";

/**
 * The skin changer's list for one account (src-tauri/src/skin_library.rs keeps it on this computer): skins added by a
 * Minecraft username or an image address. Adding never changes the account's skin; Apply does, through Minecraft's skin
 * API. Favourites are this computer's too, and come first.
 */
export function useSkinLibrary(uuid: string) {
  const t = useT();
  const toast = useToast();
  const [skins, setSkins] = useState<SavedSkin[]>([]);
  const [pngs, setPngs] = useState<Record<string, string>>({});
  const [favourites, setFavourites] = useState<Set<string>>(() => loadFavourites(uuid));
  const [adding, setAdding] = useState(false);
  const [applying, setApplying] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const asked = useRef(new Set<string>());

  const loadPng = useCallback(
    (id: string) => {
      if (asked.current.has(id)) return;
      asked.current.add(id);
      api.skinLibraryPng(uuid, id).then(
        (url) => setPngs((p) => ({ ...p, [id]: url })),
        () => asked.current.delete(id),
      );
    },
    [uuid],
  );

  useEffect(() => {
    let cancelled = false;
    api.skinLibraryList(uuid).then(
      (list) => {
        if (cancelled) return;
        setSkins(list);
        setLoaded(true);
      },
      () => !cancelled && setLoaded(true),
    );
    return () => {
      cancelled = true;
    };
  }, [uuid]);

  useEffect(() => skins.forEach((s) => loadPng(s.id)), [skins, loadPng]);

  const add = async (text: string): Promise<string | null> => {
    const query = skinQuery(text);
    if (!query) return null;
    if ("error" in query) return t(query.error);
    setAdding(true);
    try {
      const skin = await api.skinLibraryAdd(uuid, query);
      setSkins((list) => [skin, ...list.filter((s) => s.id !== skin.id)]);
      loadPng(skin.id);
      sound.play("success");
      return null;
    } catch (e) {
      return errorText(e);
    } finally {
      setAdding(false);
    }
  };

  const remove = async (skin: SavedSkin) => {
    try {
      await api.skinLibraryRemove(uuid, skin.id);
      setSkins((list) => list.filter((s) => s.id !== skin.id));
    } catch (e) {
      toast({ tone: "danger", title: t("wardrobe.skins.removeFailed"), body: errorText(e) });
    }
  };

  const toggleFavourite = (id: string) =>
    setFavourites((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      saveFavourites(uuid, next);
      return next;
    });

  const apply = async (skin: SavedSkin, variant: "classic" | "slim"): Promise<boolean> => {
    setApplying(skin.id);
    try {
      await api.skinLibraryApply(uuid, skin.id, variant);
      sound.play("success");
      toast({ tone: "success", title: t("wardrobe.skins.applied", { name: skin.name }) });
      return true;
    } catch (e) {
      toast({ tone: "danger", title: t("wardrobe.skins.applyFailed"), body: errorText(e) });
      return false;
    } finally {
      setApplying(null);
    }
  };

  const sorted = useMemo(() => sortedSkins(skins, favourites), [skins, favourites]);
  return { skins: sorted, pngs, favourites, adding, applying, loaded, add, remove, toggleFavourite, apply };
}

export type SkinLibrary = ReturnType<typeof useSkinLibrary>;

/** The front of a skin, flat and pixel for pixel: head, body, arms and legs (with their outer layers). */
export function SkinFront({ src, slim = false }: { src: string | null; slim?: boolean }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || !src) return;
    const img = new Image();
    img.onload = () => {
      const g = canvas.getContext("2d");
      if (!g) return;
      g.imageSmoothingEnabled = false;
      g.clearRect(0, 0, 16, 32);
      const legacy = img.height === 32;
      const arm = slim ? 3 : 4;
      const part = (sx: number, sy: number, w: number, h: number, dx: number, dy: number, flip = false) => {
        if (!flip) return g.drawImage(img, sx, sy, w, h, dx, dy, w, h);
        g.save();
        g.translate(dx + w, dy);
        g.scale(-1, 1);
        g.drawImage(img, sx, sy, w, h, 0, 0, w, h);
        g.restore();
      };
      // Inner layer, then the outer layer over it (a 64×32 skin has only the hat for that).
      part(8, 8, 8, 8, 4, 0);
      part(20, 20, 8, 12, 4, 8);
      part(44, 20, arm, 12, 4 - arm, 8);
      if (legacy) part(44, 20, arm, 12, 12, 8, true);
      else part(36, 52, arm, 12, 12, 8);
      part(4, 20, 4, 12, 4, 20);
      if (legacy) part(4, 20, 4, 12, 8, 20, true);
      else part(20, 52, 4, 12, 8, 20);
      part(40, 8, 8, 8, 4, 0);
      if (!legacy) {
        part(20, 36, 8, 12, 4, 8);
        part(44, 36, arm, 12, 4 - arm, 8);
        part(52, 52, arm, 12, 12, 8);
        part(4, 36, 4, 12, 4, 20);
        part(4, 52, 4, 12, 8, 20);
      }
    };
    img.src = src;
  }, [src, slim]);
  return <canvas ref={ref} className="wd-skin-front" width={16} height={32} aria-hidden />;
}

export function SkinChanger({
  library,
  selected,
  onSelect,
}: {
  uuid: string;
  library: SkinLibrary;
  selected: string | null;
  onSelect: (skin: SavedSkin | null) => void;
}) {
  const t = useT();
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    const problem = await library.add(text);
    if (problem) setError(problem);
    else setText("");
  };

  return (
    <div className="wd-skins">
      <form
        className="wd-skin-add"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label className={`store-search wd-search ${error ? "has-error" : ""}`}>
          <Icon name="user" size={15} />
          <input
            value={text}
            placeholder={t("wardrobe.skins.addPlaceholder")}
            aria-label={t("wardrobe.skins.addPlaceholder")}
            aria-invalid={!!error}
            aria-describedby={error ? "wd-skin-error" : undefined}
            spellCheck={false}
            onChange={(e) => {
              setText(e.target.value);
              setError(null);
            }}
          />
        </label>
        <Button type="submit" variant="primary" icon="plus" loading={library.adding} disabled={!text.trim()}>
          {t("wardrobe.skins.add")}
        </Button>
      </form>
      {error ? (
        <p className="store-inline-error" id="wd-skin-error" role="alert">
          <Icon name="alert" size={14} />
          {error}
        </p>
      ) : (
        <p className="faint wd-note">{t("wardrobe.skins.addHint")}</p>
      )}

      {library.skins.length ? (
        <CardGrid label={t("wardrobe.cat.skins")} className="is-skins">
          {library.skins.map((skin) => {
            const favourite = library.favourites.has(skin.id);
            return (
              <div key={skin.id} data-card className={`wd-card wd-skin ${selected === skin.id ? "is-selected" : ""}`}>
                <button type="button" className="wd-skin-pick" aria-pressed={selected === skin.id} onClick={() => onSelect(selected === skin.id ? null : skin)}>
                  <span className="wd-card-art">
                    <SkinFront src={library.pngs[skin.id] ?? null} slim={skin.variant === "slim"} />
                  </span>
                  <span className="wd-card-body">
                    <span className="wd-card-name" title={skin.name}>
                      {skin.name}
                    </span>
                    <span className="wd-card-state" title={skin.origin}>
                      {skin.source === "username" ? t("wardrobe.skins.fromUser", { name: skin.origin }) : t("wardrobe.skins.fromUrl")}
                    </span>
                  </span>
                </button>
                <div className="wd-skin-tools">
                  <IconButton
                    icon={favourite ? "starFill" : "star"}
                    size="sm"
                    className={favourite ? "is-favourite" : ""}
                    label={favourite ? t("wardrobe.skins.unfavourite", { name: skin.name }) : t("wardrobe.skins.favourite", { name: skin.name })}
                    aria-pressed={favourite}
                    onClick={() => library.toggleFavourite(skin.id)}
                  />
                  <IconButton
                    icon="trash"
                    size="sm"
                    label={t("wardrobe.skins.remove", { name: skin.name })}
                    onClick={() => {
                      if (selected === skin.id) onSelect(null);
                      void library.remove(skin);
                    }}
                  />
                </div>
              </div>
            );
          })}
        </CardGrid>
      ) : library.loaded ? (
        <EmptyState icon="user" title={t("wardrobe.skins.emptyTitle")} body={t("wardrobe.skins.emptyBody")} />
      ) : null}
    </div>
  );
}

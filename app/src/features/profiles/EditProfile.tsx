import { useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, type Profile } from "../../lib/api";
import { tileColor } from "../../lib/color";
import { sound } from "../../lib/sound";
import { useT, type MessageKey } from "../../lib/i18n";
import { useStoreSelect } from "../../lib/store";
import { versionMeta } from "../../lib/versions";
import { Button, Segmented, Spinner, TextField } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { Modal, Tooltip, useToast } from "../../ui/overlays";
import { ColorGraph, rememberColor } from "./ColorGraph";
import { ItemTile, loadIconCatalog, ProfileIcon, useIconCatalog } from "./ProfileIcon";

type Mode = "minecraft" | "picture" | "version";

/** The item groups the backend offers (profileicon.rs ICON_GROUPS), by their English name; any other name shows as it comes. */
const GROUP_NAME: Partial<Record<string, MessageKey>> = {
  Treasure: "workspace.edit.group.treasure",
  Combat: "workspace.edit.group.combat",
  Tools: "workspace.edit.group.tools",
  Magic: "workspace.edit.group.magic",
  Food: "workspace.edit.group.food",
  Blocks: "workspace.edit.group.blocks",
};

/** What each offered texture is called (the tooltip and the screen reader's name). */
function itemName(path: string): string {
  const base = path
    .slice(path.indexOf("/") + 1)
    .replace(/_(side|front|standby|00)$/, "");
  return base
    .replace(/_/g, " ")
    .replace(/^\w/, (c) => c.toUpperCase())
    .replace("Tnt", "TNT");
}

/**
 * Edit a profile: its name and its icon. The icon is a Minecraft item on a coloured tile (the items' pictures come from the
 * player's own installed Minecraft), the player's own picture, or the version's picture as before.
 */
export function EditProfile({ profile, onClose }: { profile: Profile | null; onClose: () => void }) {
  const store = useStoreSelect((s) => ({
    reloadProfiles: s.reloadProfiles,
    profiles: s.profiles,
  }));
  const t = useT();
  const toast = useToast();
  const found = useIconCatalog();
  // The profile as it is now (a picture chosen in this dialog applies at once and is read back from the store).
  const live = profile ? (store.profiles.find((p) => p.id === profile.id) ?? profile) : null;
  const [name, setName] = useState("");
  const [mode, setMode] = useState<Mode>("version");
  const [item, setItem] = useState("item/flint_and_steel");
  const [color, setColor] = useState("ember");
  const [busy, setBusy] = useState(false);
  const [choosing, setChoosing] = useState(false);
  const [query, setQuery] = useState("");
  const list = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!profile) return;
    setName(profile.name);
    const icon = profile.icon;
    if (icon?.kind === "item") {
      setMode("minecraft");
      setItem(icon.name);
      setColor(icon.color);
    } else {
      setMode(icon?.kind === "image" ? "picture" : "version");
    }
    setQuery("");
    // A version installed since the picker last looked brings its pictures.
    void loadIconCatalog().catch(() => {});
  }, [profile]);

  // The profile's own item in view when the items show (it may be far down the list).
  const showing = !!profile && mode === "minecraft" && !!found;
  useEffect(() => {
    if (showing) list.current?.querySelector(".ep-cell.is-on")?.scrollIntoView({ block: "center" });
    // Only when the list appears, not as the player picks.
  }, [showing]);

  // The groups as the search leaves them (by name, as the tooltips say).
  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (found?.groups ?? [])
      .map((g) => ({ ...g, icons: q ? g.icons.filter((n) => itemName(n).toLowerCase().includes(q)) : g.icons }))
      .filter((g) => g.icons.length > 0);
  }, [found, query]);

  if (!live)
    return (
      <Modal open={false} onClose={onClose} title={t("workspace.edit.title")}>
        {null}
      </Modal>
    );

  const textures = found?.textures ?? {};
  const hasPictures = Object.keys(textures).length > 0;
  const groupLabel = (name: string) => {
    const label = GROUP_NAME[name];
    return label ? t(label) : name;
  };

  const choosePicture = async () => {
    setChoosing(true);
    try {
      const updated = await api.chooseProfileIconPicture(live.id);
      if (updated) {
        sound.play("tap");
        await store.reloadProfiles();
      }
    } catch (e) {
      toast({
        tone: "danger",
        title: t("workspace.edit.pictureFailed"),
        body: errorText(e),
      });
    } finally {
      setChoosing(false);
    }
  };

  const save = async () => {
    if (!name.trim()) return;
    setBusy(true);
    try {
      if (name.trim() !== live.name) await api.renameProfile(live.id, name.trim());
      if (mode === "minecraft" && hasPictures) {
        const icon = live.icon;
        if (icon?.kind !== "item" || icon.name !== item || icon.color !== color)
          await api.setProfileIcon(live.id, {
            kind: "item",
            name: item,
            color,
          });
        if (color.startsWith("#")) rememberColor(color);
      } else if (mode === "version" && live.icon) {
        await api.setProfileIcon(live.id, null);
      }
      await store.reloadProfiles();
      onClose();
    } catch (e) {
      toast({
        tone: "danger",
        title: t("workspace.edit.saveFailed"),
        body: errorText(e),
      });
    } finally {
      setBusy(false);
    }
  };

  // What the profile will look like once saved.
  const preview =
    mode === "minecraft" && hasPictures ? (
      <ItemTile name={item} color={color} size={76} texture={textures[item]} />
    ) : mode === "picture" && live.icon?.kind === "image" ? (
      <ProfileIcon profile={live} size={76} />
    ) : (
      <ProfileIcon profile={{ ...live, icon: null }} size={76} />
    );

  return (
    <Modal
      open={!!profile}
      onClose={onClose}
      title={t("workspace.edit.title")}
      width={720}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button variant="primary" onClick={() => void save()} disabled={!name.trim() || busy}>
            {busy ? t("workspace.edit.saving") : t("common.save")}
          </Button>
        </>
      }
    >
      <div className="edit-profile">
        <div className="ep-head">
          <span className="ep-preview" key={`${mode}|${item}|${color}|${live.icon?.kind === "image" ? live.icon.file : ""}`}>
            {preview}
          </span>
          <TextField
            label={t("workspace.edit.name")}
            value={name}
            maxLength={40}
            data-autofocus
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && void save()}
          />
        </div>

        <section className="ep-section" aria-label={t("workspace.edit.icon")}>
          <div className="ep-section-head">
            <strong>{t("workspace.edit.icon")}</strong>
            <Segmented<Mode>
              label={t("workspace.edit.icon")}
              value={mode}
              onChange={setMode}
              options={[
                { value: "minecraft", label: t("workspace.edit.mode.minecraft") },
                { value: "picture", label: t("workspace.edit.mode.picture") },
                { value: "version", label: t("workspace.edit.mode.version") },
              ]}
            />
          </div>

          {mode === "minecraft" &&
            (hasPictures ? (
              <div className="ep-pane ep-minecraft">
                <div className="ep-tile-color">
                  <span className="ep-label">{t("workspace.edit.tileColor")}</span>
                  <ColorGraph value={tileColor(color)} onChange={setColor} />
                </div>
                <div className="ep-items">
                  <label className="ep-search">
                    <Icon name="search" size={14} />
                    <input value={query} placeholder={t("workspace.edit.search")} aria-label={t("workspace.edit.search")} spellCheck={false} onChange={(e) => setQuery(e.target.value)} />
                  </label>
                  <div ref={list} className="ep-groups" role="radiogroup" aria-label={t("workspace.edit.itemPicker")}>
                    {groups.map((g) => (
                      <div key={g.name} className="ep-group">
                        <span className="ep-label">{groupLabel(g.name)}</span>
                        <div className="ep-grid">
                          {g.icons.map((n, i) => (
                            <Tooltip key={n} label={itemName(n)}>
                              <button
                                type="button"
                                role="radio"
                                aria-checked={n === item}
                                aria-label={itemName(n)}
                                className={`ep-cell ${n === item ? "is-on" : ""}`}
                                style={{ "--i": Math.min(i, 16) } as React.CSSProperties}
                                onClick={() => setItem(n)}
                              >
                                <ItemTile name={n} color={color} size={42} texture={textures[n]} />
                              </button>
                            </Tooltip>
                          ))}
                        </div>
                      </div>
                    ))}
                    {groups.length === 0 && <p className="ep-note">{t("workspace.edit.noItem", { query: query.trim() })}</p>}
                  </div>
                </div>
              </div>
            ) : (
              <p className="ep-note">{t("workspace.edit.noItems")}</p>
            ))}

          {mode === "picture" && (
            <div className="ep-pane ep-picture">
              {live.icon?.kind === "image" ? (
                <ProfileIcon profile={live} size={88} className="ep-big" />
              ) : (
                <span className="ep-empty" aria-hidden>
                  <Icon name="image" size={26} />
                </span>
              )}
              <Button variant="secondary" icon="image" onClick={() => void choosePicture()} disabled={choosing}>
                {choosing ? <Spinner size={14} /> : null}
                {t(live.icon?.kind === "image" ? "workspace.edit.chooseAnother" : "workspace.edit.choosePicture")}
              </Button>
              <p className="ep-note">{t("workspace.edit.pictureNote")}</p>
            </div>
          )}

          {mode === "version" && (
            <div className="ep-pane ep-version">
              <span
                className="picon picon-version ep-big"
                style={{
                  width: 88,
                  height: 88,
                  backgroundImage: `url(${versionMeta(live.gameVersion).thumb})`,
                }}
              />
              <p className="ep-note">{t("workspace.edit.versionNote", { version: live.gameVersion })}</p>
            </div>
          )}
        </section>
      </div>
    </Modal>
  );
}

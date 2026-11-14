import { useState } from "react";
import { EMOTE_SLOTS, kindOf } from "../../lib/cosmeticTypes";
import { useT } from "../../lib/i18n";
import { shownName } from "../../lib/storePage";
import type { StoreInventory, StoreItem } from "../../lib/storeSync";
import { wheelOf, wheelWith } from "../../lib/wardrobe";
import { Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { ItemArt } from "./ItemArt";

/**
 * The emote wheel: eight places around a circle, as the game's wheel shows them. With an emote selected below, a place
 * takes it; a filled place selects its emote, and its × empties it. The Store keeps the wheel (`PUT /me/emotes/`), so it is
 * the same in every game.
 */
export function EmoteWheel({
  uuid,
  inventory,
  selected,
  readOnly,
  motion,
  onSet,
  onSelect,
}: {
  uuid: string;
  inventory: StoreInventory;
  selected: StoreItem | null;
  readOnly: boolean;
  motion: boolean;
  onSet: (wheel: (string | null)[]) => Promise<void>;
  onSelect: (item: StoreItem | null) => void;
}) {
  const t = useT();
  const wheel = wheelOf(inventory);
  const [busy, setBusy] = useState<number | null>(null);
  const supported = inventory.emotes != null;
  const owned = new Map(inventory.owned.map((o) => [o.cosmeticId, o]));
  const placing = selected && kindOf(selected.kind) === "emote" && !selected.listed ? selected : null;

  const put = (place: number, id: string | null) => {
    if (readOnly || !supported) return;
    setBusy(place);
    void onSet(wheelWith(wheel, place, id)).finally(() => setBusy(null));
  };

  return (
    <div className={`wd-wheel-card ${supported ? "" : "is-off"}`}>
      <div className="wd-wheel" role="group" aria-label={t("wardrobe.emotes.wheel")}>
        {Array.from({ length: EMOTE_SLOTS }, (_, place) => {
          const item = wheel[place] ? (owned.get(wheel[place]!) ?? null) : null;
          const angle = (place / EMOTE_SLOTS) * 360;
          return (
            <div key={place} className="wd-wheel-place" style={{ ["--a" as string]: `${angle}deg` }}>
              <button
                type="button"
                className={`wd-wheel-slot ${item ? "is-filled" : ""} ${placing ? "is-target" : ""} ${item && selected?.cosmeticId === item.cosmeticId ? "is-selected" : ""}`}
                aria-label={item ? t("wardrobe.emotes.placeFilled", { n: place + 1, name: shownName(item) }) : t("wardrobe.emotes.placeEmpty", { n: place + 1 })}
                disabled={!supported || (!placing && !item)}
                onClick={() => (placing ? put(place, placing.cosmeticId) : item && onSelect(item))}
                onKeyDown={(e) => {
                  if ((e.key === "Delete" || e.key === "Backspace") && item) {
                    e.preventDefault();
                    put(place, null);
                  }
                }}
              >
                {busy === place ? <Spinner size={14} /> : item ? <ItemArt uuid={uuid} item={item} motion={motion} small /> : <span className="wd-wheel-n">{place + 1}</span>}
              </button>
              {item && supported && !readOnly && (
                <button type="button" className="wd-wheel-clear" aria-label={t("wardrobe.emotes.clearPlace", { n: place + 1 })} onClick={() => put(place, null)}>
                  <Icon name="x" size={10} />
                </button>
              )}
            </div>
          );
        })}
        <div className="wd-wheel-hub" aria-live="polite">
          <Icon name="smile" size={18} />
          <span>{!supported ? t("wardrobe.emotes.notYet") : placing ? t("wardrobe.emotes.pickPlace", { name: shownName(placing) }) : t("wardrobe.emotes.hint")}</span>
        </div>
      </div>
    </div>
  );
}

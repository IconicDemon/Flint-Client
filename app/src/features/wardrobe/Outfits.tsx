import { useEffect, useRef, useState } from "react";
import type { StoreSlot } from "../../lib/cosmeticTypes";
import { useT } from "../../lib/i18n";
import type { StoreInventory } from "../../lib/storeSync";
import { MAX_OUTFITS, MAX_OUTFIT_NAME, nextOutfitName, outfitItems, outfitPlan, wornSlots, type Outfit } from "../../lib/wardrobe";
import { Button, IconButton } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import { useToast } from "../../ui/overlays";
import { ItemArt } from "./ItemArt";
import { CardGrid } from "./WardrobeCard";

/**
 * Outfits: saved combinations of what is worn, kept on this computer for the account. "Create new outfit" saves what is
 * worn now under a name; selecting one shows it on the preview; Wear asks the Store for each slot that differs (an item
 * the account no longer has is skipped, and said so).
 */
export function OutfitsView({
  uuid,
  inventory,
  outfits,
  selected,
  worn,
  motion,
  readOnly,
  onSelect,
  onChange,
  onWear,
}: {
  uuid: string;
  inventory: StoreInventory;
  outfits: Outfit[];
  selected: string | null;
  worn: string | null;
  motion: boolean;
  readOnly: boolean;
  onSelect: (outfit: Outfit | null) => void;
  onChange: (outfits: Outfit[]) => void;
  onWear: (slot: StoreSlot, cosmeticId: string | null) => void;
}) {
  const t = useT();
  const toast = useToast();
  const [naming, setNaming] = useState<{ id: string | null; name: string } | null>(null);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (naming) input.current?.select();
  }, [naming?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const save = () => {
    if (!naming) return;
    const name = naming.name.trim().slice(0, MAX_OUTFIT_NAME) || nextOutfitName(outfits, t("wardrobe.outfits.defaultName"));
    if (naming.id) onChange(outfits.map((o) => (o.id === naming.id ? { ...o, name } : o)));
    else {
      const outfit: Outfit = { id: `o${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`, name, createdAt: Date.now(), slots: wornSlots(inventory) };
      onChange([outfit, ...outfits].slice(0, MAX_OUTFITS));
      onSelect(outfit);
      toast({ tone: "success", title: t("wardrobe.outfits.saved", { name }) });
    }
    setNaming(null);
  };

  const wear = (outfit: Outfit) => {
    const { changes, missing } = outfitPlan(outfit, inventory);
    changes.forEach((c) => onWear(c.slot, c.cosmeticId));
    if (missing) toast({ tone: "neutral", title: t("wardrobe.outfits.missing", { count: missing }) });
  };

  return (
    <div className="wd-outfits">
      {naming && (
        <form
          className="wd-name-form"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <label className="store-search wd-search">
            <Icon name="edit" size={15} />
            <input
              ref={input}
              value={naming.name}
              maxLength={MAX_OUTFIT_NAME}
              aria-label={t("wardrobe.outfits.nameLabel")}
              placeholder={t("wardrobe.outfits.nameLabel")}
              onChange={(e) => setNaming({ ...naming, name: e.target.value })}
              onKeyDown={(e) => e.key === "Escape" && setNaming(null)}
            />
          </label>
          <Button type="button" variant="ghost" onClick={() => setNaming(null)}>
            {t("common.cancel")}
          </Button>
          <Button type="submit" variant="primary" icon="check">
            {t("wardrobe.outfits.save")}
          </Button>
        </form>
      )}
      <CardGrid label={t("wardrobe.cat.outfits")} className="is-outfits">
        <button
          type="button"
          className="wd-card wd-outfit-new"
          disabled={outfits.length >= MAX_OUTFITS}
          onClick={() => setNaming({ id: null, name: nextOutfitName(outfits, t("wardrobe.outfits.defaultName")) })}
        >
          <span className="wd-card-art">
            <Icon name="plus" size={22} />
          </span>
          <span className="wd-card-body">
            <span className="wd-card-name">{t("wardrobe.outfits.create")}</span>
            <span className="wd-card-state">{t("wardrobe.outfits.createHint")}</span>
          </span>
        </button>
        {outfits.map((outfit) => {
          const items = outfitItems(outfit, inventory);
          const on = worn === outfit.id;
          return (
            <div key={outfit.id} data-card className={`wd-card wd-outfit ${selected === outfit.id ? "is-selected" : ""} ${on ? "is-equipped" : ""}`}>
              <button type="button" className="wd-outfit-pick" aria-pressed={selected === outfit.id} onClick={() => onSelect(selected === outfit.id ? null : outfit)}>
                <span className="wd-card-art wd-outfit-art">
                  {items.slice(0, 4).map((item) => (
                    <span key={item.cosmeticId}>
                      <ItemArt uuid={uuid} item={item} motion={motion} small />
                    </span>
                  ))}
                  {!items.length && <Icon name="layers" size={20} />}
                </span>
                <span className="wd-card-body">
                  <span className="wd-card-name">{outfit.name}</span>
                  {on ? (
                    <span className="wd-card-state is-on">
                      <i aria-hidden />
                      {t("wardrobe.outfits.wearing")}
                    </span>
                  ) : (
                    <span className="wd-card-state">{t("wardrobe.outfits.items", { count: items.length })}</span>
                  )}
                </span>
              </button>
              <div className="wd-outfit-actions">
                <Button size="sm" variant={on ? "secondary" : "primary"} disabled={readOnly || on} onClick={() => wear(outfit)}>
                  {on ? t("wardrobe.outfits.wearing") : t("wardrobe.outfits.wear")}
                </Button>
                <IconButton icon="edit" size="sm" label={t("wardrobe.outfits.rename", { name: outfit.name })} onClick={() => setNaming({ id: outfit.id, name: outfit.name })} />
                <IconButton
                  icon="trash"
                  size="sm"
                  label={t("wardrobe.outfits.delete", { name: outfit.name })}
                  onClick={() => {
                    onChange(outfits.filter((o) => o.id !== outfit.id));
                    if (selected === outfit.id) onSelect(null);
                  }}
                />
              </div>
            </div>
          );
        })}
      </CardGrid>
      {!outfits.length && <p className="faint wd-note wd-center-note">{t("wardrobe.outfits.empty")}</p>}
    </div>
  );
}

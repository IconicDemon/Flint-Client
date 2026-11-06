import { useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import type { Profile, ProfileState } from "../../lib/api";
import { useT } from "../../lib/i18n";
import { sound } from "../../lib/sound";
import { isBusy, stateOf, useStoreSelect } from "../../lib/store";
import { loaderLabel, platformBlock, versionMeta } from "../../lib/versions";
import { Badge, Button, IconButton, Spinner } from "../../ui/controls";
import { Icon } from "../../ui/Icon";
import type { Navigate } from "../shell/Shell";
import { ProfileMenu, STATE_LABEL, type ProfileActions } from "./ProfilesPage";

/**
 * Every profile at once, as a grid of cards that wraps to the window. Each card carries the same
 * artwork, details and state as the deck, and its own actions: select for Play (or go to Play once
 * selected), open, and the ··· menu. Clicking the artwork does what clicking a deck card does: it
 * selects the profile, and opens it once it is selected.
 *
 * Arrow keys move between cards (by row and column, following the grid as it wraps), Home and End
 * jump to the ends; Tab still reaches every button in order.
 */
export function ProfileGrid({
  profiles,
  selectedId,
  navigate,
  actions,
  onSelect,
  onOpen,
}: {
  profiles: Profile[];
  selectedId: string | null;
  navigate: Navigate;
  actions: ProfileActions;
  onSelect: (p: Profile) => void;
  onOpen: (p: Profile) => void;
}) {
  const t = useT();
  // Like the deck, only state names re-render the grid; download progress doesn't.
  const store = useStoreSelect((s) => ({ info: s.info, stateNames: profiles.map((p) => stateOf(s.states, p).state).join(" ") }));
  const stateNames = store.stateNames.split(" ");
  const gridRef = useRef<HTMLUListElement>(null);

  // The profile selected for Play starts in view, however far down a long list it is.
  useLayoutEffect(() => {
    gridRef.current?.querySelector<HTMLElement>(".pg-card.is-selected")?.scrollIntoView({ block: "nearest" });
  }, []);

  const onKeyDown = (e: KeyboardEvent<HTMLUListElement>) => {
    const target = e.target as HTMLElement;
    if (!target.classList.contains("pg-hit") || e.altKey || e.metaKey || e.ctrlKey) return;
    const hits = Array.from(gridRef.current?.querySelectorAll<HTMLButtonElement>(".pg-hit") ?? []);
    const index = hits.indexOf(target as HTMLButtonElement);
    const columns = Math.max(1, getComputedStyle(gridRef.current!).gridTemplateColumns.split(" ").filter(Boolean).length);
    const next: Record<string, number> = {
      ArrowLeft: index - 1,
      ArrowRight: index + 1,
      ArrowUp: index - columns,
      ArrowDown: index + columns,
      Home: 0,
      End: hits.length - 1,
    };
    if (!(e.key in next)) return;
    e.preventDefault();
    const to = hits[Math.min(hits.length - 1, Math.max(0, next[e.key]))];
    if (to && to !== target) {
      to.focus();
      to.scrollIntoView({ block: "nearest" });
    }
  };

  return (
    <ul ref={gridRef} className="pg" aria-label={t("profiles.list.label")} onKeyDown={onKeyDown}>
      {profiles.map((p, i) => (
        <GridCard
          key={p.id}
          profile={p}
          state={stateNames[i] as ProfileState["state"]}
          selected={p.id === selectedId}
          blocked={platformBlock(p.gameVersion, store.info)}
          navigate={navigate}
          actions={actions}
          onSelect={() => onSelect(p)}
          onOpen={() => onOpen(p)}
        />
      ))}
    </ul>
  );
}

function GridCard({
  profile: p,
  state,
  selected,
  blocked,
  navigate,
  actions,
  onSelect,
  onOpen,
}: {
  profile: Profile;
  state: ProfileState["state"];
  selected: boolean;
  blocked: string | null;
  navigate: Navigate;
  actions: ProfileActions;
  onSelect: () => void;
  onOpen: () => void;
}) {
  const t = useT();
  const [menu, setMenu] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const meta = versionMeta(p.gameVersion);
  const status = blocked ? t("profiles.state.notSupported") : STATE_LABEL[state];
  const loader = `${loaderLabel(p.loader)}${p.loaderVersion ? ` ${p.loaderVersion}` : ""}`;
  const busy = isBusy({ profileId: p.id, state });
  const hitLabel = t(selected ? "profiles.grid.hitSelected" : blocked ? "profiles.card.label" : "profiles.grid.hitSelect", { name: p.name, version: p.gameVersion, loader, status });

  return (
    <li className={`pg-card ${selected ? "is-selected" : ""} ${blocked ? "is-blocked" : ""}`}>
      <button
        type="button"
        className="pg-hit"
        aria-label={hitLabel}
        title={blocked ?? undefined}
        onClick={(e) => {
          // The second click of a double-click would open the profile the first one just selected.
          if (e.detail > 1) return;
          sound.play("tap");
          if (selected || blocked) onOpen();
          else onSelect();
        }}
      >
        <img className="pg-art" src={meta.card} alt="" width={600} height={840} loading="lazy" decoding="async" draggable={false} />
        <span className="pg-shade" aria-hidden />
        <span className="pg-top">
          {selected && <span className="pc-flag is-play">{t("profiles.card.selectedFlag")}</span>}
          {p.modpack && <Badge>{t("profiles.card.modpack")}</Badge>}
        </span>
        <span className="pg-info">
          <strong className="pg-name" title={p.name}>
            {p.name}
          </strong>
          <span className="pg-meta">
            Minecraft {p.gameVersion} · {loader}
          </span>
          <span className={`state-pill state-${blocked ? "error" : state}`}>
            {busy && <Spinner size={10} />}
            {status}
          </span>
        </span>
        <span className={`pg-cta ${selected || blocked ? "is-open" : ""}`} aria-hidden>
          {t(selected || blocked ? "profiles.action.open" : "profiles.action.select")}
          <Icon name={selected || blocked ? "chevronRight" : "check"} size={13} />
        </span>
      </button>
      <div className="pg-foot">
        {selected ? (
          <Button variant="primary" size="sm" icon="play" className="pg-main" onClick={() => navigate("play")} disabled={!!blocked}>
            {t("profiles.action.goToPlay")}
          </Button>
        ) : (
          <Button size="sm" icon="check" className="pg-main" onClick={onSelect} disabled={!!blocked} title={blocked ?? undefined}>
            {t("profiles.action.selectForPlay")}
          </Button>
        )}
        <Button size="sm" variant="ghost" icon="layers" onClick={onOpen}>
          {t("profiles.action.open")}
        </Button>
        <IconButton ref={anchor} size="sm" icon="more" label={t("profiles.grid.more", { name: p.name })} aria-haspopup="menu" aria-expanded={menu} onClick={() => setMenu((m) => !m)} />
        <ProfileMenu profile={p} navigate={navigate} actions={actions} anchor={anchor} open={menu} onClose={() => setMenu(false)} />
      </div>
    </li>
  );
}

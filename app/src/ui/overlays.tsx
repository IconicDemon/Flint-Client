import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from "react";
import { createPortal } from "react-dom";
import { sound } from "../lib/sound";
import { Icon } from "./Icon";

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

export function useDismiss(open: boolean, onClose: () => void, refs: RefObject<HTMLElement | null>[]) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
      }
    };
    const onDown = (e: PointerEvent) => {
      if (refs.some((r) => r.current?.contains(e.target as Node))) return;
      onClose();
    };
    document.addEventListener("keydown", onKey, true);
    document.addEventListener("pointerdown", onDown, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      document.removeEventListener("pointerdown", onDown, true);
    };
  }, [open, onClose, refs]);
}

/** Must outlast --t-exit in tokens.css so the closing animation finishes before the overlay unmounts. */
const EXIT_MS = 140;

/**
 * Keeps an overlay mounted for a short exit animation after `open` turns false. Returns "open",
 * "closing" or null (unmounted). Reduced motion skips the exit.
 */
function usePresence(open: boolean): "open" | "closing" | null {
  const [shown, setShown] = useState(open);
  if (open && !shown) setShown(true);
  useEffect(() => {
    if (open || !shown) return;
    const reduced = document.documentElement.dataset.motion === "reduced";
    const timer = window.setTimeout(() => setShown(false), reduced ? 0 : EXIT_MS);
    return () => window.clearTimeout(timer);
  }, [open, shown]);
  return open ? "open" : shown ? "closing" : null;
}

/** The last value seen while open, so a closing overlay keeps showing what it showed (callers often clear their state on close). */
function useHeld<T>(value: T, open: boolean): T {
  const held = useRef(value);
  if (open) held.current = value;
  return open ? value : held.current;
}

type Placement = "bottom-start" | "bottom-end" | "top-start" | "top-end" | "right";
type Side = "top" | "bottom" | "right" | "left";

export function Popover({
  anchor,
  open,
  onClose,
  placement = "bottom-start",
  children,
  className = "",
  label,
  offset = 8,
}: {
  anchor: RefObject<HTMLElement | null>;
  open: boolean;
  onClose: () => void;
  placement?: Placement;
  children: ReactNode;
  className?: string;
  label?: string;
  offset?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number; side: Side; origin: string } | null>(null);
  const refs = useRef([ref, anchor]).current;
  const presence = usePresence(open);
  const content = useHeld(children, open);
  useDismiss(open, onClose, refs);

  useLayoutEffect(() => {
    if (!open || !anchor.current || !ref.current) return;
    const place = () => {
      if (!anchor.current || !ref.current) return;
      const a = anchor.current.getBoundingClientRect();
      // offsetWidth/Height ignore the entrance transform, so measuring mid-animation stays exact.
      const w = ref.current.offsetWidth;
      const h = ref.current.offsetHeight;
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const m = 8;
      // Prefer the requested side; flip to the opposite one when it doesn't fit there but does (or fits better) on the other.
      let side: Side = placement === "right" ? "right" : placement.startsWith("top") ? "top" : "bottom";
      const room = { top: a.top - offset - m, bottom: vh - a.bottom - offset - m, left: a.left - offset - m, right: vw - a.right - offset - m };
      if (side === "bottom" && h > room.bottom && room.top > room.bottom) side = "top";
      else if (side === "top" && h > room.top && room.bottom > room.top) side = "bottom";
      else if (side === "right" && w > room.right && room.left > room.right) side = "left";
      let top: number;
      let left: number;
      if (side === "right" || side === "left") {
        top = a.top;
        left = side === "right" ? a.right + offset : a.left - offset - w;
      } else {
        top = side === "top" ? a.top - h - offset : a.bottom + offset;
        left = placement.endsWith("end") ? a.right - w : a.left;
      }
      top = Math.max(m, Math.min(top, vh - h - m));
      left = Math.max(m, Math.min(left, vw - w - m));
      // Grow out of the trigger: the transform origin is the trigger's centre on the facing edge.
      const ox = Math.max(0, Math.min(w, a.left + a.width / 2 - left));
      const oy = Math.max(0, Math.min(h, a.top + a.height / 2 - top));
      const origin = side === "top" ? `${ox}px 100%` : side === "bottom" ? `${ox}px 0` : side === "right" ? `0 ${oy}px` : `100% ${oy}px`;
      setPos((prev) => (prev && prev.top === top && prev.left === left && prev.side === side && prev.origin === origin ? prev : { top, left, side, origin }));
    };
    place();
    // Stay attached when the window resizes, a scroller under the trigger moves, or either box changes size.
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, { capture: true, passive: true });
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(place);
    observer?.observe(ref.current);
    observer?.observe(anchor.current);
    return () => {
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, { capture: true });
      observer?.disconnect();
    };
  }, [open, anchor, placement, offset]);

  useEffect(() => {
    if (!open) return;
    sound.play("open");
    const first = ref.current?.querySelector<HTMLElement>(FOCUSABLE);
    first?.focus({ preventScroll: true });
    const anchorEl = anchor.current;
    return () => anchorEl?.focus({ preventScroll: true });
  }, [open, anchor]);

  if (!presence) return null;
  return createPortal(
    <div
      ref={ref}
      role="dialog"
      aria-label={label}
      className={`popover ${className} ${presence === "closing" ? "is-closing" : ""}`}
      data-side={pos?.side}
      inert={presence === "closing"}
      style={{ top: pos?.top ?? -9999, left: pos?.left ?? -9999, transformOrigin: pos?.origin }}
    >
      {content}
    </div>,
    document.body,
  );
}

export type MenuItem =
  | { type?: "item"; label: ReactNode; hint?: ReactNode; icon?: ReactNode; onSelect: () => void; danger?: boolean; disabled?: boolean; active?: boolean }
  | { type: "separator" }
  | { type: "heading"; label: string };

export function Menu({ items, onClose }: { items: MenuItem[]; onClose: () => void }) {
  const listRef = useRef<HTMLDivElement>(null);
  const move = (dir: 1 | -1) => {
    const buttons = Array.from(listRef.current?.querySelectorAll<HTMLButtonElement>("button:not([disabled])") ?? []);
    const i = buttons.indexOf(document.activeElement as HTMLButtonElement);
    buttons[(i + dir + buttons.length) % buttons.length]?.focus();
  };
  return (
    <div
      ref={listRef}
      className="menu"
      role="menu"
      onKeyDown={(e) => {
        if (e.key === "ArrowDown") {
          e.preventDefault();
          move(1);
        } else if (e.key === "ArrowUp") {
          e.preventDefault();
          move(-1);
        }
      }}
    >
      {items.map((item, i) => {
        if (item.type === "separator") return <div key={i} className="menu-sep" role="separator" />;
        if (item.type === "heading") return <div key={i} className="menu-heading">{item.label}</div>;
        return (
          <button
            key={i}
            type="button"
            role="menuitem"
            disabled={item.disabled}
            className={`menu-item ${item.danger ? "is-danger" : ""} ${item.active ? "is-active" : ""}`}
            onClick={() => {
              sound.play("tap");
              item.onSelect();
              onClose();
            }}
          >
            {item.icon && <span className="menu-icon">{item.icon}</span>}
            <span className="menu-label">{item.label}</span>
            {item.hint && <span className="menu-hint">{item.hint}</span>}
            {item.active && <Icon name="check" size={15} className="menu-check" />}
          </button>
        );
      })}
    </div>
  );
}

export function Dropdown<T extends string>({
  value,
  options,
  onChange,
  label,
  className = "",
}: {
  value: T;
  options: { value: T; label: string; hint?: string }[];
  onChange: (value: T) => void;
  label: string;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const current = options.find((o) => o.value === value);
  return (
    <>
      <button
        ref={anchor}
        type="button"
        className={`dropdown-trigger ${className}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
        onClick={() => setOpen((o) => !o)}
      >
        <span>{current?.label ?? "Select"}</span>
        <Icon name="chevronDown" size={15} />
      </button>
      <Popover anchor={anchor} open={open} onClose={() => setOpen(false)} label={label} placement="bottom-start">
        <Menu
          onClose={() => setOpen(false)}
          items={options.map((o) => ({ label: o.label, hint: o.hint, active: o.value === value, onSelect: () => onChange(o.value) }))}
        />
      </Popover>
    </>
  );
}

export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  width = 520,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const presence = usePresence(open);
  const held = useHeld({ title, children, footer }, open);
  useEffect(() => {
    if (!open) return;
    sound.play("open");
    const previous = document.activeElement as HTMLElement | null;
    const node = ref.current;
    (node?.querySelector<HTMLElement>("[data-autofocus]") ?? node?.querySelector<HTMLElement>(FOCUSABLE))?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
      }
      if (e.key === "Tab" && node) {
        const items = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE));
        if (!items.length) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      previous?.focus?.();
    };
  }, [open, onClose]);
  if (!presence) return null;
  // While closing, the dialog is inert and lets clicks through, so the page behind is usable at once.
  return createPortal(
    <div
      className={`modal-backdrop ${presence === "closing" ? "is-closing" : ""}`}
      inert={presence === "closing"}
      onPointerDown={(e) => open && e.target === e.currentTarget && onClose()}
    >
      <div ref={ref} className="modal" role="dialog" aria-modal="true" aria-label={held.title} style={{ width }}>
        <header className="modal-head">
          <h2>{held.title}</h2>
          <button type="button" className="icon-btn icon-btn-sm icon-btn-ghost" aria-label="Close" onClick={onClose}>
            <Icon name="x" size={15} />
          </button>
        </header>
        <div className="modal-body">{held.children}</div>
        {held.footer && <footer className="modal-foot">{held.footer}</footer>}
      </div>
    </div>,
    document.body,
  );
}

export function Tooltip({ label, children, side = "top" }: { label: string; children: ReactNode; side?: "top" | "right" | "bottom" }) {
  const anchor = useRef<HTMLSpanElement>(null);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const show = () => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setRect(anchor.current?.getBoundingClientRect() ?? null), 380);
  };
  const hide = () => {
    window.clearTimeout(timer.current);
    setRect(null);
  };
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const style =
    rect &&
    (side === "right"
      ? { top: rect.top + rect.height / 2, left: rect.right + 12 }
      : side === "bottom"
        ? { top: rect.bottom + 8, left: rect.left + rect.width / 2 }
        : { top: rect.top - 8, left: rect.left + rect.width / 2 });
  return (
    <span ref={anchor} className="tooltip-anchor" onPointerEnter={show} onPointerLeave={hide} onFocus={show} onBlur={hide}>
      {children}
      {rect &&
        createPortal(
          <span role="tooltip" className={`tooltip tooltip-${side}`} style={style!}>
            {label}
          </span>,
          document.body,
        )}
    </span>
  );
}

type Toast = { id: number; tone: "neutral" | "success" | "danger"; title: string; body?: string };
const ToastContext = createContext<(t: Omit<Toast, "id">) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((t: Omit<Toast, "id">) => {
    const id = Date.now() + Math.random();
    sound.play(t.tone === "danger" ? "error" : t.tone === "success" ? "success" : "tap");
    setToasts((prev) => [...prev.slice(-3), { ...t, id }]);
    window.setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== id)), t.tone === "danger" ? 7000 : 4200);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.tone}`} role={t.tone === "danger" ? "alert" : "status"}>
            <Icon name={t.tone === "danger" ? "alert" : t.tone === "success" ? "check" : "info"} size={16} />
            <div>
              <strong>{t.title}</strong>
              {t.body && <p>{t.body}</p>}
            </div>
            <button
              type="button"
              className="icon-btn icon-btn-sm icon-btn-ghost"
              aria-label="Dismiss"
              onClick={() => setToasts((prev) => prev.filter((x) => x.id !== t.id))}
            >
              <Icon name="x" size={14} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

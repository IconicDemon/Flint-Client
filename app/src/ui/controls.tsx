import {
  forwardRef,
  useId,
  useLayoutEffect,
  useRef,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type TextareaHTMLAttributes,
} from "react";
import { sound } from "../lib/sound";
import { Icon, type IconName } from "./Icon";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost" | "danger";
  size?: "sm" | "md" | "lg" | "xl";
  icon?: IconName;
  iconRight?: IconName;
  loading?: boolean;
  silent?: boolean;
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "secondary", size = "md", icon, iconRight, loading, silent, className = "", children, onClick, disabled, ...rest },
  ref,
) {
  const iconSize = size === "sm" ? 15 : 17;
  // While loading, nothing is added or removed, so the button never changes width: the spinner takes the
  // leading icon's slot, or (with no icon) sits over the label, which keeps its space but is hidden.
  const overLabel = loading && !icon;
  return (
    <button
      ref={ref}
      className={`btn btn-${variant} btn-${size} ${loading ? "is-loading" : ""} ${className}`}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      onClick={(e) => {
        if (!silent) sound.play("tap");
        onClick?.(e);
      }}
      {...rest}
    >
      {icon && (loading ? <Spinner size={iconSize} /> : <Icon name={icon} size={iconSize} />)}
      {children &&
        (overLabel ? (
          <span className="btn-stack">
            <span className="btn-hold">{children}</span>
            <Spinner size={iconSize - 1} />
          </span>
        ) : (
          <span>{children}</span>
        ))}
      {!children && overLabel && <Spinner size={iconSize - 1} />}
      {iconRight && <Icon name={iconRight} size={iconSize} className={loading ? "btn-hold" : undefined} />}
    </button>
  );
});

type IconButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { icon: IconName; label: string; size?: "sm" | "md"; variant?: "ghost" | "solid" };

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon, label, size = "md", variant = "ghost", className = "", onClick, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      className={`icon-btn icon-btn-${size} icon-btn-${variant} ${className}`}
      aria-label={label}
      title={label}
      onClick={(e) => {
        sound.play("tap");
        onClick?.(e);
      }}
      {...rest}
    >
      <Icon name={icon} size={size === "sm" ? 15 : 18} />
    </button>
  );
});

/**
 * Slides one highlight (`.seg-ind`) under the selected option of a Segmented or Tabs control with a
 * transform, instead of repainting each button. Re-measures when the options resize (fonts, counts).
 * Without a measurement (hidden, or no ResizeObserver) the selected button paints its own highlight.
 */
function useIndicator(selectedKey: string) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const host = ref.current;
    if (!host) return;
    const place = () => {
      const el = host.querySelector<HTMLElement>(':scope > [aria-checked="true"], :scope > [aria-selected="true"]');
      if (!el || !el.offsetWidth) {
        delete host.dataset.ind;
        return;
      }
      host.style.setProperty("--ind-x", `${el.offsetLeft}px`);
      host.style.setProperty("--ind-y", `${el.offsetTop}px`);
      host.style.setProperty("--ind-w", `${el.offsetWidth}px`);
      host.style.setProperty("--ind-h", `${el.offsetHeight}px`);
      // The first placement jumps into position; later ones slide.
      host.dataset.ind = host.dataset.ind ? "on" : "init";
    };
    place();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(place);
    observer.observe(host);
    host.querySelectorAll(":scope > button").forEach((b) => observer.observe(b));
    return () => observer.disconnect();
  }, [selectedKey]);
  return ref;
}

export function Spinner({ size = 16 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} aria-hidden />;
}

export function Switch({
  checked,
  onChange,
  label,
  disabled,
  id,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label: string;
  disabled?: boolean;
  id?: string;
}) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={`switch ${checked ? "is-on" : ""}`}
      onClick={() => {
        sound.play("toggle");
        onChange(!checked);
      }}
    >
      <span className="switch-thumb" />
    </button>
  );
}

/**
 * `indeterminate`: a group checkbox with only some of its options picked (shown as a dash; assistive
 * tech reads it as "mixed"). Clicking it picks everything.
 */
export function Checkbox({
  checked,
  onChange,
  label,
  disabled,
  children,
  indeterminate = false,
  className = "",
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  label?: string;
  disabled?: boolean;
  children?: ReactNode;
  indeterminate?: boolean;
  className?: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  // A DOM property with no attribute, and a click clears it, so it is set again after every render.
  useLayoutEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate && !checked;
  });
  return (
    <label className={`checkbox ${disabled ? "is-disabled" : ""} ${className}`}>
      <input
        ref={ref}
        type="checkbox"
        checked={checked}
        disabled={disabled}
        aria-label={label}
        onChange={(e) => {
          sound.play("toggle");
          onChange(e.target.checked);
        }}
      />
      <span className="checkbox-box" aria-hidden>
        <Icon name={indeterminate && !checked ? "minimize" : "check"} size={13} />
      </span>
      {children}
    </label>
  );
}

/**
 * A short note inside a page or dialog: information, a warning to read before acting, or a blocker.
 * `live`: the callout appears in answer to an action (an error, say), so screen readers announce it;
 * a callout that is simply part of the page isn't announced.
 */
export function Callout({
  tone = "info",
  icon,
  title,
  children,
  action,
  className = "",
  live = false,
}: {
  tone?: "info" | "warn" | "danger";
  icon?: IconName;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  className?: string;
  live?: boolean;
}) {
  return (
    <div className={`callout callout-${tone} ${className}`} role={live ? "status" : "note"}>
      <Icon name={icon ?? (tone === "info" ? "info" : "alert")} size={15} />
      <div className="callout-body">
        {title && <strong>{title}</strong>}
        {children && <span>{children}</span>}
      </div>
      {action && <div className="callout-action">{action}</div>}
    </div>
  );
}

export function RadioCards<T extends string>({
  value,
  onChange,
  options,
  name,
  className = "",
}: {
  value: T;
  onChange: (value: T) => void;
  options: { value: T; title: ReactNode; description?: ReactNode; extra?: ReactNode }[];
  name: string;
  className?: string;
}) {
  return (
    <div className={`radio-cards ${className}`} role="radiogroup">
      {options.map((option) => (
        <label key={option.value} className={`radio-card ${value === option.value ? "is-selected" : ""}`}>
          <input
            type="radio"
            name={name}
            value={option.value}
            checked={value === option.value}
            onChange={() => {
              sound.play("toggle");
              onChange(option.value);
            }}
          />
          {option.extra}
          <span className="radio-card-body">
            <span className="radio-dot" aria-hidden />
            <span>
              <span className="radio-card-title">{option.title}</span>
              {option.description && <span className="radio-card-desc">{option.description}</span>}
            </span>
          </span>
        </label>
      ))}
    </div>
  );
}

export function Segmented<T extends string>({
  value,
  onChange,
  options,
  label,
}: {
  value: T;
  onChange: (value: T) => void;
  options: { value: T; label: string }[];
  label: string;
}) {
  const ref = useIndicator(`${value}|${options.map((o) => o.value).join(",")}`);
  return (
    <div ref={ref} className="segmented" role="radiogroup" aria-label={label}>
      <span className="seg-ind" aria-hidden />
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          className={value === o.value ? "is-active" : ""}
          onClick={() => {
            sound.play("toggle");
            onChange(o.value);
          }}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Slider({
  value,
  min,
  max,
  step = 1,
  onChange,
  label,
  format,
  disabled,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  onChange: (value: number) => void;
  label: string;
  format?: (value: number) => string;
  disabled?: boolean;
}) {
  const pct = ((value - min) / (max - min)) * 100;
  return (
    <div className={`slider ${disabled ? "is-disabled" : ""}`}>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        aria-label={label}
        aria-valuetext={format?.(value)}
        style={{ "--pct": `${pct}%` } as React.CSSProperties}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      {format && <output className="slider-value">{format(value)}</output>}
    </div>
  );
}

export function Progress({
  value,
  label,
  tone = "accent",
  className = "",
}: {
  value: number | null;
  label?: string;
  tone?: "accent" | "neutral";
  className?: string;
}) {
  const indeterminate = value === null;
  return (
    <div
      className={`progress progress-${tone} ${indeterminate ? "is-indeterminate" : ""} ${className}`}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={indeterminate ? undefined : Math.round(value * 100)}
    >
      <span style={{ transform: indeterminate ? undefined : `scaleX(${Math.max(0, Math.min(1, value))})` }} />
    </div>
  );
}

export const TextField = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> & { label?: string; hint?: string; error?: string; icon?: IconName }
>(function TextField({ label, hint, error, icon, className = "", id, ...rest }, ref) {
  const autoId = useId();
  const inputId = id ?? autoId;
  return (
    <div className={`field ${error ? "has-error" : ""} ${className}`}>
      {label && <label htmlFor={inputId}>{label}</label>}
      <div className="field-control">
        {icon && <Icon name={icon} size={16} className="field-icon" />}
        <input ref={ref} id={inputId} aria-invalid={!!error} {...rest} />
      </div>
      {(error || hint) && <p className={error ? "field-error" : "field-hint"}>{error ?? hint}</p>}
    </div>
  );
});

export const TextArea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & { label?: string }>(
  function TextArea({ label, className = "", id, ...rest }, ref) {
    const autoId = useId();
    return (
      <div className={`field ${className}`}>
        {label && <label htmlFor={id ?? autoId}>{label}</label>}
        <textarea ref={ref} id={id ?? autoId} {...rest} />
      </div>
    );
  },
);

export function Badge({ tone = "neutral", children }: { tone?: "neutral" | "accent" | "success" | "danger" | "warning"; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function EmptyState({
  icon,
  title,
  body,
  action,
  compact,
}: {
  icon: IconName;
  title: string;
  body?: ReactNode;
  action?: ReactNode;
  compact?: boolean;
}) {
  return (
    <div className={`empty ${compact ? "is-compact" : ""}`}>
      <span className="empty-icon">
        <Icon name={icon} size={22} />
      </span>
      <h3>{title}</h3>
      {body && <p>{body}</p>}
      {action}
    </div>
  );
}

export function Tabs<T extends string>({
  value,
  onChange,
  tabs,
  label,
}: {
  value: T;
  onChange: (value: T) => void;
  tabs: { value: T; label: string; count?: number }[];
  label: string;
}) {
  const ref = useIndicator(`${value}|${tabs.map((t) => t.value).join(",")}`);
  return (
    <div ref={ref} className="tabs" role="tablist" aria-label={label}>
      <span className="seg-ind" aria-hidden />
      {tabs.map((t) => (
        <button
          key={t.value}
          role="tab"
          type="button"
          aria-selected={value === t.value}
          className={value === t.value ? "is-active" : ""}
          onClick={() => {
            sound.play("nav");
            onChange(t.value);
          }}
          onKeyDown={(e) => {
            if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
            const i = tabs.findIndex((x) => x.value === value);
            const next = tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
            onChange(next.value);
            (e.currentTarget.parentElement?.querySelectorAll("button")[tabs.indexOf(next)] as HTMLButtonElement)?.focus();
          }}
        >
          {t.label}
          {t.count !== undefined && <span className="tab-count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

/**
 * Flint AI's emblem: the Flint shard with a small spark. Still at rest; while Flint AI works the
 * spark glints and the shard breathes (CSS only, transform/opacity, paused when the window is in
 * the background or motion is reduced). Colours follow the theme accent.
 */
export function FlintMark({ working = false, size = 28 }: { working?: boolean; size?: number }) {
  return (
    <span className={`flint-mark ${working ? "is-working" : ""}`} style={{ width: size, height: size }} aria-hidden>
      <svg viewBox="0 0 32 32" width={size} height={size}>
        <g className="fm-shard">
          <polygon points="16,3.5 22,14 16,19.3 10,14" className="fm-dark" />
          <polygon points="16,3.5 22,14 16,11.6" className="fm-light" />
          <polygon points="14.9,10.7 19.9,17.8 14.9,25.6 12.1,17.8" className="fm-core" />
        </g>
        <path className="fm-spark" d="M25.5 3.2 26.4 5.8 29 6.7 26.4 7.6 25.5 10.2 24.6 7.6 22 6.7 24.6 5.8Z" />
      </svg>
    </span>
  );
}

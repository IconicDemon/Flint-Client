export function FlintMark({ size = 28, className }: { size?: number; className?: string }) {
  return (
    <svg
      className={className}
      width={size * 0.58}
      height={size}
      viewBox="150 50 212 370"
      role="img"
      aria-label="Flint Client"
    >
      {/* The theme's colours (tokens.css --logo-*): Flint's own amber on Ember, the chosen accent on the others. */}
      <polygon points="256,60 351,228 256,312 161,228" fill="var(--logo-dark)" />
      <polygon points="256,60 351,228 256,186" fill="var(--logo-light)" />
      <polygon points="239,172 318,284 239,410 194,284" fill="var(--logo-core)" />
    </svg>
  );
}

export function Wordmark({ size = 22 }: { size?: number }) {
  return (
    <span className="wordmark" style={{ fontSize: size }}>
      <FlintMark size={size * 1.25} />
      <span>
        Flint <span className="wordmark-dim">Client</span>
      </span>
    </span>
  );
}

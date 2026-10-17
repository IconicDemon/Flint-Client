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
      <polygon points="256,60 351,228 256,312 161,228" fill="#B45309" />
      <polygon points="256,60 351,228 256,186" fill="#D97706" />
      <polygon points="239,172 318,284 239,410 194,284" fill="#FEF3E2" />
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

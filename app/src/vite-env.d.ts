/// <reference types="vite/client" />

/** Per language, how many of the English messages its catalog translates (vite.config.ts). */
declare module "virtual:i18n-coverage" {
  const coverage: Record<string, { translated: number; total: number }>;
  export default coverage;
}

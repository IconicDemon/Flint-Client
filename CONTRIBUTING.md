# Contributing to Flint Client

Thanks for helping. Flint is in beta, so the best contributions right now are bug reports with clear steps,
fixes for those bugs, and small, focused improvements.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Build and run Flint as described in [BUILDING.md](BUILDING.md).

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep the change focused on one thing, and follow the style of the surrounding code (naming, comment density,
   formatting).
3. Run the checks before pushing:
   - `cd game-runtime && ./gradlew build`
   - `cd app && npm run typecheck && npm test`
   - `cd app/src-tauri && cargo clippy --no-default-features -- -D warnings && cargo test --no-default-features`
4. Describe what changed and how you tested it, with screenshots for interface changes.
5. CI runs automatically. For first-time contributors, a maintainer approves the run.

## Rules

- **Your own work only.** Don't submit code, art or assets you don't have the right to license under GPL-3.0. That
  includes code taken from other Minecraft clients and Mojang assets.
- **No secrets.** Never commit tokens, keys, `.env` files, logs or personal data (usernames, UUIDs, home paths).
  Tests use invented identifiers.
- **Fair play.** Flint must not give players an unfair advantage on servers: no automation, no hidden information
  overlays, no packet tricks.
- **Licence.** By submitting a contribution you agree that it is licensed under the
  [GNU General Public License v3.0 only](LICENSE).

## Reporting bugs

Open an issue with:
- your operating system and Flint version (Settings → About Flint);
- what you did, what happened, and what you expected;
- logs if relevant, with your username and any tokens removed.

Security problems go through [SECURITY.md](SECURITY.md), not public issues.

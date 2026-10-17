# Flint Client architecture

Flint is a desktop product with an internal Minecraft runtime. These are separate applications with different responsibilities and release lifecycles.

## Desktop application (`app/`)

The user-facing product is a Tauri 2 application. React and TypeScript render the interface, while Rust provides the native boundary: accounts, installation, process management and content all live there. Native capabilities should remain narrow and explicit.

Current boundaries:

| Location | Responsibility |
| --- | --- |
| `src/features` | Pages and flows (shell, home, library, profiles, pages, onboarding) |
| `src/ui` | Reusable visual components |
| `src/lib` | Typed native API (`api.ts`), app state, settings, sound, formatting |
| `src/assets` | Curated runtime assets only |
| `src/styles/tokens.css` | Flint design decisions expressed as variables |
| `src/styles/*.css` | Baseline, shell, component and per-page styles |
| `src-tauri/src` | Rust services: `auth`, `game`, `content`, `modpack`, `modrinth`, `jobs`, `skins`, `data`, `ai`, `gamesettings` |

The frontend calls the backend only through the typed wrappers in `src/lib/api.ts`. The web view has no direct file system, shell or network access; it can only control its own window.

## Game runtime (`game-runtime/`)

The Fabric module is Flint's in-game component. The app embeds its jar and installs it into supported profiles at launch (`src-tauri/src/runtime.rs`: Fabric on Minecraft 26.3, while Settings → "Flint main menu in Minecraft" is on) and removes it everywhere else. Its first feature is Flint's main menu in place of Minecraft's title screen; `game-runtime/README.md` describes how it works.

Inside the runtime, Flint Control Center (`control/`) keeps its logic Minecraft-free (`control/model`, `control/store`, `control/hud`) and its Minecraft calls behind `control/platform/` and the mixins, so the same split as Graphics and Network Core applies when other versions are added.

## Settings: three kinds, kept apart

| Kind | Owner | Where |
| --- | --- | --- |
| Minecraft's own settings | Minecraft (read and edited by vanilla screens; copied by the launcher only while the game is closed) | each profile's `options.txt` |
| Flint in-game settings and HUD layouts | the game runtime | each profile's `config/flintclient/` (`control-center.json`, `layouts/<id>.json`) |
| Launcher preferences | the launcher | the app data folder's `settings.json` |

A Minecraft *installation profile* (a game folder the launcher manages) is not a *HUD layout profile* (a layout file inside one game folder). The launcher's Auto Sync (`src-tauri/src/gamesettings/autosync/`) keeps installation profiles consistent automatically: it captures a profile's changes when its game exits and applies newer compatible ones just before another profile launches. Minecraft options, Flint settings, packs and mod configurations are separate categories, so copying FOV never touches a HUD layout and the other way round. Every write is backed up first (`.flint-backup/sync/`), atomic, refused while that profile's game runs, and limited to Minecraft versions whose formats were verified against their jars.

Fabric split environment source sets remain enabled. Code under `src/client/java` may use Minecraft client classes. Future code under `src/main/java` must be safe in both client and server environments.

## Dependency policy

Add a dependency only for a current requirement. Review its maintenance, security, license, binary size, and runtime cost first. Prefer platform capabilities and small internal abstractions over speculative frameworks.

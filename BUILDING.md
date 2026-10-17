# Building Flint Client

## Requirements

| Tool | Version |
|---|---|
| Java (JDK) | 25 (for example Eclipse Temurin) |
| Node.js | 22 or newer |
| Rust | stable (on Windows the MSVC toolchain and the Visual Studio C++ Build Tools) |
| macOS | 11 or newer, Xcode Command Line Tools (`xcode-select --install`) |
| Windows | 10 or 11 x64; WebView2 (built into Windows 11) |

## macOS and Linux shells

```sh
# The in-game runtime (Fabric mod). The launcher embeds this jar.
cd game-runtime
./gradlew build

# The launcher
cd ../app
npm ci
npm run typecheck
npm test
(cd src-tauri && cargo test --no-default-features)
npm run tauri dev        # development window with hot reload
```

Release build (an `.app`; add `-- --bundles app,dmg` for a disk image):

```sh
cd app
npm run release
```

Output goes to `app/src-tauri/target.noindex/release/bundle/`. The release script builds the game runtime first and
stops if the jar for the current version is missing.

## Windows (PowerShell)

```powershell
Set-Location game-runtime
.\gradlew.bat build
Set-Location ..\app
npm ci
npm test
Set-Location src-tauri; cargo test --no-default-features; Set-Location ..
npm run tauri dev
npm run release          # NSIS installer in src-tauri\target.noindex\release\bundle\nsis\
```

## Notes

- **Your builds and updates.** Official releases are signed with Flint's updater key. Builds you make yourself have no
  updater key, so they produce no update archive, and the official channel's updates won't verify against a build that
  uses a different public key. To ship your own fork, generate your own key (`npx tauri signer generate`), put your
  public key and update address in `app/src-tauri/tauri.conf.json`, and set `TAURI_SIGNING_PRIVATE_KEY` and
  `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` when building.
- **Flint Assist** (AI suggestions) needs a gateway address. This source has none configured: set one in Settings or
  with the `FLINT_AI_GATEWAY_URL` environment variable.
- **Separate test data.** `FLINT_DATA_DIR=/absolute/path` runs the launcher with its own data folder, leaving your
  normal Flint profiles and accounts alone.
- **Minecraft versions.** Beta 1 targets Minecraft 26.3. See docs/MULTI_VERSION_PLAN.md for how other versions are
  added.

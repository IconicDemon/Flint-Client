# Update system

Flint Client updates itself with Tauri's official updater (`tauri-plugin-updater` 2.14, `tauri-plugin-process` 2.4).
Every update is signed with Flint's private key and checked against the public key built into the app before anything
is installed. This document describes how it works, what was tested, and its limits.

## How it works

1. **Check.** About 4 seconds after start-up, and every 6 hours while Flint is open, the launcher fetches
   `https://github.com/IconicDemon/Flint-Client/releases/download/updater-beta/latest.json` (15-second timeout).
   The request runs in the background and never delays the launcher. A quiet check that fails (offline, server down,
   no build for this computer) shows nothing.
2. **Offer.** If the manifest names a version newer than the installed one (semantic-version comparison), an
   **Update** chip appears in the top bar, beside the account chip. Its panel shows the version and release notes.
3. **Download.** "Download update" downloads with real progress (bytes received from the updater's events; the chip
   shows the percentage). The download is then checked against the signature in the manifest and the public key in
   the app. A file that fails the check is discarded.
4. **Install.** "Restart to update" replaces the app and restarts Flint into the new version. It is disabled while a
   game launched from Flint is starting or running, so a game is never cut short. Profiles, worlds, accounts and
   settings live outside the app (`~/Library/Application Support/com.flintclient.desktop`, `%APPDATA%\com.flintclient.desktop`)
   and are not touched.
5. **Manual check.** Settings → General → About Flint → "Check for updates" reports "You're up to date", the new
   version, or a short error.

| State | Top bar | Settings → About Flint |
|---|---|---|
| Up to date / quiet check | nothing | "You're up to date" (after a manual check) |
| Checking (manual) | nothing | "Checking…" |
| Update available | **Update** | "Flint x.y.z is available" |
| Downloading | spinner and percentage | "Downloading Flint x.y.z" |
| Ready | **Restart to update** (filled orange) | "Restart Flint to finish updating" |
| Failed check (manual) | nothing | the error |
| Failed download or install | **Update failed** (red) with Retry | nothing |

### Safety properties

- **Signed updates only.** The app contains only the *public* key (minisign key ID `EE44136DEFE21C45`). Each update file
  needs a signature from the matching private key, which never leaves the release machine or the protected GitHub
  environment.
- **Signed version.** `requireSignedVersion` is on: each signature records the version it was made for, so an older
  (validly signed) build can't be offered as a newer version.
- **No downgrades.** Only a strictly newer version is offered (`allowDowngrades` is off).
- **HTTPS only.** Release builds refuse a plain-HTTP update address. Local tests pass a test-only override on the build
  command line; it is never in `tauri.conf.json` (a unit test checks this).
- **Only built platforms.** `latest.json` lists only the platforms that were built and signed in that release
  (`app/scripts/updater-manifest.mjs` refuses a platform without a signature file, or a non-HTTPS URL). Until Windows is
  verified on a real PC, the manifest lists only `darwin-aarch64`, and Windows copies see no update.
- **Players get an update only when it is promoted.** Releases are created as drafts. Installed copies read only the
  `updater-beta` release, which changes only through the "Promote update" workflow (owner approval required).

## Files

| File | Role |
|---|---|
| `app/src/lib/updater.ts` | Shared update state, checks, download with progress, install and restart, error messages |
| `app/src/features/shell/UpdateButton.tsx` | The top-bar chip and its panel |
| `app/src/features/pages/SettingsPage.tsx` (`AboutVersion`) | Manual check in Settings |
| `app/src/styles/shell.css` (`.update-*`) | Styling (Flint charcoal and orange, same chip shape as Downloads) |
| `app/src-tauri/tauri.conf.json` → `plugins.updater` | Public key, update address, `requireSignedVersion`; `bundle.createUpdaterArtifacts` |
| `app/src-tauri/src/lib.rs` | Registers the updater and process plugins |
| `app/src-tauri/capabilities/default.json` | `updater:default`, `process:allow-restart` |
| `app/scripts/updater-manifest.mjs` | Writes `latest.json` from the signed build outputs |
| `app/scripts/release-build.mjs` | Release build; produces signed update archives when the key is present |
| `.github/workflows/release.yml`, `promote-update.yml` | Signed release drafts; promotion to the update channel |

## The update manifest (`latest.json`)

```json
{
  "version": "0.5.1",
  "notes": "Title\nSummary\n• point",
  "pub_date": "2026-10-20T12:00:00Z",
  "platforms": {
    "darwin-aarch64": {
      "url": "https://github.com/IconicDemon/Flint-Client/releases/download/v0.5.1/Flint_Client_0.5.1_aarch64.app.tar.gz",
      "signature": "<contents of the .sig file the build wrote>"
    }
  }
}
```

Platform keys: `darwin-aarch64` (Apple silicon), `darwin-x86_64` (Intel Mac, not built yet), `windows-x86_64` (NSIS
installer `…_x64-setup.exe`, not verified yet).

## Signing key

- Private key: `~/.flint-signing/flint-updater.key` on the owner's Mac (folder mode 700, file mode 600), encrypted with a
  password stored in the macOS Keychain (service "Flint Client updater signing key password", account `flint-updater`).
- Public key: `~/.flint-signing/flint-updater.key.pub` and `plugins.updater.pubkey` in `tauri.conf.json`.
- **Back it up off this Mac now** (for example in a password manager: the key file's contents and the password, as two
  separate items). If the key is lost, installed copies can never be updated again: they would need a manual reinstall
  of a build with a new public key.
- For GitHub releases the same two values are stored as the environment secrets `TAURI_SIGNING_PRIVATE_KEY` (the file's
  contents) and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` (docs/RELEASE_PROCESS.md).
- The key must never be committed, pasted into an issue or log, or placed in the app, the installer or the public
  repository.

Updater signing is separate from Apple code signing and notarization (not set up; see "Limits").

## Test results (2026-10-17, macOS 27, Apple silicon)

Two builds of the real launcher from this source were signed with Flint's key: 0.5.0 and 0.5.1 (the same code; 0.5.1
only by a build-time version override). Both pointed at a local test server through the test-only command-line override.

### In the app (isolated copy, own data folder; the owner's installed Flint and account untouched)

| Check | Result |
|---|---|
| 0.5.0 finds 0.5.1 a few seconds after start-up; Update chip beside the account | PASS |
| Panel shows version, notes, "Signed by Flint" | PASS |
| Download progress is real (12% → 25% → 37% …, "2.0 MB of 15.6 MB") | PASS |
| "Restart to update" installs and relaunches; app bundle now 0.5.1; signature valid | PASS |
| Relaunched app shows 0.5.1 and offers no update | PASS |
| Data folder byte-identical before and after (checksums) | PASS |
| Settings → Check for updates → "You're up to date" | PASS |
| Corrupted download → "didn't pass Flint's signature check", app stays 0.5.0, Retry shown | PASS |

### Windowless (`tauri-plugin-updater` 2.14 on Tauri's mock runtime, same plugin code as the app)

| Case | Result |
|---|---|
| Same version offered → no update | PASS |
| Older version offered (0.6.0 installed, 0.5.1 offered) → ignored | PASS |
| Update downloaded in 47 chunks with Content-Length, signature verified | PASS |
| Corrupted archive (bytes flipped) → rejected | PASS |
| Connection cut halfway → rejected | PASS |
| Signature of another file → rejected | PASS |
| Signed with a different key → rejected | PASS |
| Older signed build announced as newer → rejected ("signed for version 0.5.0") | PASS |
| Archive missing (404) → rejected | PASS |
| Offline / nothing listening → check fails (quiet in the launcher) | PASS |
| Server error 503, manifest 404, manifest not JSON → check fails | PASS |
| Server never answers → check fails after the timeout | PASS |
| Manifest without this platform (Windows only) → "no update for this computer" | PASS |
| Intel Mac (`darwin-x86_64`) against an Apple-silicon-only manifest → no update | PASS |
| Plain-HTTP address in a release configuration → refused | PASS |
| Install replaces the app bundle (0.5.0 → 0.5.1) | PASS |

Each failure's exact message is mapped to a short, URL-free text in `friendlyError` and checked in
`app/src/lib/updater.test.ts`. The install gate (`canInstallNow`) is unit-tested: no install while a game is
starting or running.

### Not yet verified

- **Minecraft running during an update, in the app**: covered by the unit test and the disabled button; not yet
  exercised with a real game open.
- **Retry and interrupted download in the app UI**: verified in the windowless harness; in the app only the corrupted
  case was clicked through (testing stopped at the owner's request to avoid windows).
- **Windows**: nothing on Windows is verified. Windows uses the NSIS installer in passive mode.
- **The real GitHub address**: the release workflows have not run yet; the first real update will be 0.5.1.

## Limits

- **macOS signing.** Builds are ad-hoc signed and not notarized. Gatekeeper blocks the first launch of a downloaded DMG
  (right-click → Open, or System Settings → Privacy & Security → Open Anyway). Updates installed by the updater are not
  quarantined, so they open normally. Because an ad-hoc build's identity is its code hash, macOS may ask once for
  Keychain access to the saved account after each update. A Developer ID certificate removes both.
- **Games started outside Flint** aren't detected, so they don't hold back the install button.
- **Install location.** If the app sits where the user can't write (installed by another admin account), macOS asks for
  an administrator password during install.
- **Prerelease labels.** Use plain versions (0.5.1, 0.6.0). The channel check in `promote-update.yml` uses `sort -V`,
  which orders prerelease labels differently from semantic versioning.

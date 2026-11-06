# Release process

How a Flint Client version goes from source to installed copies. Nothing reaches players until the last step. Every
step that can use the signing key runs only when the owner starts it (the only account with write access), only from
`main` or a `v*` tag, and never from a pull request.

```
private repo (Flint-Client-Development)              public repo (Flint-Client)
  push / pull request ──► CI: tests, lint, build, secrets scan   (nothing delivered)
  tag v0.5.1 or "Release" (owner) ──► signed builds ──► DRAFT release v0.5.1 + latest.json
                                                         owner tests the downloads, publishes the release
  "Promote update" (owner) ──► verifies signatures ──► latest.json → updater-beta  ◄── installed copies check here
```

## One-time setup

1. **Repositories.** See docs/GITHUB_REPOSITORIES.md (private `Flint-Client-Development`, public `Flint-Client`).
2. **Back up the signing key** (docs/UPDATE_SYSTEM.md → Signing key). Do this before anything else.
3. **Environment `release`** in the private repository: **done on 2026-10-17.** Its deployment branches and tags are
   limited to `main` and `v*`, so its secrets never reach other branches or pull requests. Required reviewers aren't
   available for private repositories on this account's plan. If the plan changes, add yourself under Settings →
   Environments → `release` → Required reviewers. Until then, the safeguard is that only you can start these workflows
   (keep the repository free of other collaborators with write access).
4. **Environment secrets** (Settings → Environments → `release` → Add secret). Type each value into GitHub's own form,
   never into a chat, issue or file:
   - `TAURI_SIGNING_PRIVATE_KEY`: the full contents of `~/.flint-signing/flint-updater.key`. On the Mac, copy it with
     `pbcopy < ~/.flint-signing/flint-updater.key` and paste it into the secret field.
   - `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`: the password, from Keychain Access, item "Flint Client updater signing key
     password".
   - `PUBLIC_RELEASE_TOKEN`: a fine-grained personal access token (GitHub → Settings → Developer settings → Fine-grained
     tokens). Repository access: only `IconicDemon/Flint-Client`. Permissions: Contents read and write. Expiry of 90
     days or less; renew it before it lapses.
5. **Public repository settings**: Settings → Actions → General → "Require approval for all outside collaborators" for
   fork pull requests. The public repository has no secrets and no release workflows.
6. **Short profile codes** (Right Shift → Profiles → Share gives `K7QX3M` instead of a long `FLINT-…` code). The game
   runtime is built with Flint Backend's Supabase address and its **publishable (anon)** key, read from the build's
   environment by `game-runtime/flint-services.gradle` and written into each runtime jar as
   `assets/flintclient/flint-services.json`. They are never kept in the repository. Add them as environment
   **variables** (Settings → Environments → `release` → Add variable; they are public values, the database limits
   what they can do):
   - `FLINT_SUPABASE_URL`: `https://<project ref>.supabase.co`
   - `FLINT_SUPABASE_ANON_KEY`: Supabase → Project Settings → API Keys → the publishable (anon) key. The build refuses
     a secret / service-role key.

   For a local release build, set the same two in the shell before `npm run release`. Without them the build still
   works and Share copies long codes. The Backend's migration `20261027000100_profile_share_codes.sql` must be applied
   first (Flint Backend README → Profile share codes).

Apple notarization (optional, later): with an Apple Developer account, add `APPLE_CERTIFICATE`,
`APPLE_CERTIFICATE_PASSWORD`, `APPLE_SIGNING_IDENTITY`, `APPLE_API_ISSUER`, `APPLE_API_KEY` and the key file (see
Tauri's "macOS code signing" guide) to the `release` environment and to the macOS build step's `env:` in
`release.yml`. Until then, builds are ad-hoc signed and labelled as test builds.

## Each release

1. **Version.** Set the same version in `app/package.json` and `app/src-tauri/Cargo.toml` (the game runtime and the
   app's About read it from package.json), then run `npm install` in `app/` so `package-lock.json` follows. Use plain
   versions newer than the last one (0.5.1, 0.6.0). A unit test fails if the two files differ.
2. **Notes.** Add an entry at the top of `app/src/data/releaseNotes.json` (`version`, `date`, `title`, `summary`,
   `points`). Its text becomes the update's notes in the Update panel.
3. **Checks.** Push to the private repository; CI must be green.
4. **Build.** Either push a tag (`git tag v0.5.1 && git push origin v0.5.1`), or Actions → Release → Run workflow
   (version `0.5.1`, optional disk-image name, Windows off until verified). The workflow builds and signs, and creates a **draft** release `v0.5.1` on the public repository with:
   - `flintclient-v0.5.1.dmg` (or the name given), the macOS installer
   - `Flint_Client_0.5.1_aarch64.app.tar.gz` and `.sig`, the update archive and its signature
   - `latest.json`, the manifest for this version (macOS only unless Windows was built)
   - `SHA256SUMS-macos.txt`
5. **Test the draft.** Download the DMG from the draft, install it on a test Mac, and check that it opens, signs in
   and plays.
6. **Publish** the draft on GitHub (edit the notes first). People can now download it; installed copies still see
   nothing.
7. **Promote.** Actions → Promote update → version `0.5.1` → Run workflow. The workflow:
   - refuses drafts and versions that aren't newer;
   - downloads every file `latest.json` lists, checks each against Flint's public key and the signed version;
   - uploads `latest.json` to the `updater-beta` release.

   Installed copies on the beta channel then offer the update at their next check (within 6 hours, or at next start).

## Stopping a bad update

Delete the `latest.json` asset from the `updater-beta` release. Installed copies then quietly find nothing, and those
that already updated stay on that version (no downgrades). Fix the problem and release a newer version.

## Local release build (Mac)

```sh
cd app
export TAURI_SIGNING_PRIVATE_KEY="$HOME/.flint-signing/flint-updater.key"
export TAURI_SIGNING_PRIVATE_KEY_PASSWORD="$(security find-generic-password -s 'Flint Client updater signing key password' -a flint-updater -w)"
CI=true npm run release -- --bundles app,dmg
```

`CI=true` skips the Finder window that the disk-image step otherwise opens to arrange the DMG. Without the key
variables, the build still works but makes no update archive. Output: `app/src-tauri/target.noindex/release/bundle/`
(`dmg/`, `macos/Flint Client.app`, `macos/Flint Client.app.tar.gz` and `.sig`).

## Windows

The release workflow can build Windows (`windows: true`), but leave it off until docs/WINDOWS_DEVELOPMENT.md's checklist
passes on a real PC, including an update from one Windows build to a newer one. Only then may `latest.json` list
`windows-x86_64`. The Windows installer is unsigned (SmartScreen warns) until publisher signing is set up.

### Windows publisher signing (Azure Artifact Signing)

The updater key (`TAURI_SIGNING_PRIVATE_KEY`) only lets installed copies trust updates; it is not a Windows publisher
signature. A Microsoft Store/Partner Center developer account doesn't sign installers either. The release workflow
signs `flint-app.exe` and the installer with Azure Artifact Signing once these exist in the private repository
(Settings → Secrets and variables → Actions):

- Variables: `ARTIFACT_SIGNING_ENDPOINT` (the account's region endpoint, e.g. `https://eus.codesigning.azure.net`),
  `ARTIFACT_SIGNING_ACCOUNT`, `ARTIFACT_SIGNING_PROFILE` (the Public Trust certificate profile).
- Secrets in the `release` environment: `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, an app
  registration with the "Artifact Signing Certificate Profile Signer" role on the certificate profile.

Tauri signs before it writes the update signature, so updates keep verifying. "Check the publisher signature" fails the
release if signing is configured but the installer doesn't come out validly signed. Public Trust identity validation
is open to organizations in the US, Canada, EU, UK, Australia, New Zealand, Japan, South Korea, Singapore,
Switzerland, Norway and Israel, and to individuals in the US and Canada; elsewhere, use an OV certificate from a
commercial CA through `bundle.windows.signCommand` instead. Signed builds still build SmartScreen reputation over time.

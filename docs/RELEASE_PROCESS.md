# Release process

How a Flint Client version goes from source to installed copies. Nothing reaches players until the last step, and
every step that can use the signing key waits for the owner's approval.

```
private repo (Flint-Client-Development)              public repo (Flint-Client)
  push / pull request ──► CI: tests, lint, build, secrets scan   (nothing delivered)
  tag v0.5.1 or "Release" ──► [approval] ──► signed builds ──► DRAFT release v0.5.1 + latest.json
                                                         owner tests the downloads, publishes the release
  "Promote update" ──► [approval] ──► verifies signatures ──► latest.json → updater-beta  ◄── installed copies check here
```

## One-time setup

1. **Repositories.** See docs/GITHUB_REPOSITORIES.md (private `Flint-Client-Development`, public `Flint-Client`).
2. **Back up the signing key** (docs/UPDATE_SYSTEM.md → Signing key). Do this before anything else.
3. **Protected environment** in the private repository: Settings → Environments → New environment `release`:
   - Required reviewers: yourself. Every release and promotion job then waits for your approval.
   - Deployment branches and tags: "Selected", add `main` and the tag pattern `v*`.
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
   (version `0.5.1`, optional disk-image name, Windows off until verified). Approve the `release` environment when
   asked. The workflow builds and signs, and creates a **draft** release `v0.5.1` on the public repository with:
   - `flintclient-v0.5.1.dmg` (or the name given), the macOS installer
   - `Flint_Client_0.5.1_aarch64.app.tar.gz` and `.sig`, the update archive and its signature
   - `latest.json`, the manifest for this version (macOS only unless Windows was built)
   - `SHA256SUMS-macos.txt`
5. **Test the draft.** Download the DMG from the draft, install it on a test Mac, and check that it opens, signs in
   and plays.
6. **Publish** the draft on GitHub (edit the notes first). People can now download it; installed copies still see
   nothing.
7. **Promote.** Actions → Promote update → version `0.5.1` → approve. The workflow:
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
`windows-x86_64`. The Windows installer is unsigned (SmartScreen warns) until a code-signing certificate is added.

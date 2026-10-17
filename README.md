# Flint Client

Flint Client is a desktop launcher and in-game client for Minecraft: Java Edition. The launcher signs you in with your
Microsoft account, installs Minecraft and Fabric, manages profiles, mods, skins and capes, and starts the game. Inside
Minecraft, Flint's runtime adds its own main menu (with Quick Play server shortcuts), HUD modules and performance
tooling.

**Status: Beta 1 (0.5.0).** Built and tested on macOS (Apple silicon) with Minecraft 26.3. Windows builds are prepared
but not yet verified. Expect rough edges.

Flint is not affiliated with Mojang or Microsoft. You need to own Minecraft: Java Edition to play.

## Download

Installers are on the [Releases](https://github.com/IconicDemon/Flint-Client/releases) page (versioned releases such
as `v0.5.0`; the `updater-beta` entry is used by the built-in updater and isn't meant for downloading).

- **macOS (Apple silicon):** open the `.dmg` and drag Flint Client to Applications. Beta builds aren't notarized by
  Apple yet. The first time, right-click the app → Open, or allow it in System Settings → Privacy & Security.
- **Windows:** not yet released.

Flint updates itself. When a new version is out, an **Update** button appears in the top bar. Each update is signed,
and its signature is checked before installing. Details: [docs/UPDATE_SYSTEM.md](docs/UPDATE_SYSTEM.md).

## Repository

```text
app/            Desktop launcher (Tauri 2: Rust core, React interface)
game-runtime/   Fabric mod that runs inside Minecraft (Java 25)
docs/           Architecture, multi-version plan, update system
```

## Building

See [BUILDING.md](BUILDING.md). In short: Java 25, Node 22, Rust stable, then

```sh
cd game-runtime && ./gradlew build
cd ../app && npm ci && npm run tauri dev
```

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md): how to propose changes.
- [SECURITY.md](SECURITY.md): how to report a vulnerability privately.

## Licence

Flint Client is free software under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only). Third-party
components and their licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Minecraft is a trademark of Mojang Synergies AB. This repository contains no Minecraft game files; the launcher
downloads them from Mojang for players who own the game. The version wallpapers are original placeholders.

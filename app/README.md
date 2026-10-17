# Flint desktop application

This is the primary Flint Client product: a Tauri 2 native desktop application with a React and TypeScript interface.

```sh
npm install
npm run tauri dev
```

Microsoft sign-in uses the OAuth authorization-code flow with PKCE and a
loopback redirect (`src-tauri/src/auth.rs`). The public Azure application
client ID is a constant in that file; never provide or embed a client secret.
Refresh tokens are stored in the operating-system credential vault.

Useful checks:

```sh
npm run typecheck
npm run build
npm run tauri build -- --debug
```

Game downloads, installation and launching, profiles, Modrinth content
management, skins and settings are implemented by native Rust services. Flint
does not update itself yet. See `../docs/STATUS.md` for the verified current state.

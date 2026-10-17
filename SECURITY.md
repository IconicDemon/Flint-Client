# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue. On this repository's
[Security tab](https://github.com/IconicDemon/Flint-Client/security), choose **Report a vulnerability**. Only the
maintainers can see the report.

Include what's affected, how to reproduce it, and the impact you expect. You'll get an acknowledgement within a week.
Please give us a reasonable time to release a fix before disclosing it.

Especially in scope:
- the updater: signature checks, the update manifest, install and restart;
- Microsoft account sign-in and token storage (macOS Keychain, Windows Credential Manager);
- anything that lets downloaded content (mods, packs, skins) run code or write outside Flint's folders;
- the in-game runtime's handling of server data.

## Supported versions

Only the latest released version receives fixes. Flint updates itself, so most players get fixes automatically.

## How updates are protected

Every update is signed with a private key held only by the maintainers. The app contains only the public key and
refuses any download whose signature doesn't match, including an older signed version presented as a newer one.
Updates are fetched over HTTPS only. Details: [docs/UPDATE_SYSTEM.md](docs/UPDATE_SYSTEM.md).

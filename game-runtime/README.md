# Flint game runtime

Flint's in-game component: a small Fabric mod (Fabric Loader only, no Fabric API) that replaces
Minecraft's title screen with Flint's main menu and adds Flint Control Center (Right Shift): HUD modules,
a visual HUD editor, layouts and in-game settings. Players don't install it: the Flint app embeds the
jar and copies it into a profile's `mods` folder at launch, only for supported profiles and only
while Settings → "Flint main menu in Minecraft" is on (see `app/src-tauri/src/runtime.rs`).

**Supported:** Minecraft 26.3, 26.2, 26.1 (26.1, 26.1.1, 26.1.2), 1.21.11, 1.21.10 and 1.8.9 (its own code base, see below) with Fabric, one jar
per line (its `fabric.mod.json` requires `~26.3`, `~26.2`, `~26.1`, or exactly `1.21.11` or `1.21.10`). The
sources are shared (`src/client`); the few files that differ per version live in an overlay, `src/mc263`,
`src/mc262`, `src/mc261`, `src/mc12111` or `src/mc12110`, picked by `minecraft_version`: one
`compat/McCompat` class for small API differences (and the names a shared mixin targets where a version moved
them: `McCompat.HUD_CLASS` and the like), same-named copies where a hook or GPU pass differs, version-only
mixins in `flintclient.version.mixins.json`, and Flint's post shaders. A file moved into the overlays exists
in every one of them. Older versions (1.21.1 and before) use different screen and rendering APIs; the app
doesn't install the runtime there, so they keep Minecraft's own title screen.

26.1's `Minecraft.gui` is the HUD itself (26.2 split it into `Gui` for screens and `Gui.hud`). Loom adds
`compat/GuiBridge` to 26.1's `Gui` at compile time (`src/mc261/loom/fabric.mod.json`, read only by Loom)
and `mixin/GuiBridgeMixin` at run time, so shared `mc.gui.screen()` / `setScreen()` / `overlay()` /
`toastManager()` calls work unchanged there; the HUD itself is `McCompat.hud()` everywhere. The 26.1 jar
is built against 26.1.2; its classes compile byte for byte the same against 26.1 and 26.1.1, and its
mixins check against all three.

1.21.11 is obfuscated, so it has a build of its own, `legacy/` (Loom's remap plugin with Mojang's names,
Java 21, mixin compatibility level `JAVA_21`). It compiles the same shared sources without touching them:
`legacy/buildSrc/SharedSources` copies `src/client/java`, applies `src/mc12111/shared-sources.rules`
(renames such as `GuiGraphicsExtractor` → `GuiGraphics` and the GUI's `extract…` → `render…`, plus a few
per-file patches; a rule that stops matching fails the build) and lets `src/mc12111/java` replace whole
files (the mixins whose targets differ most, `McCompat`). Methods 26.x added to Minecraft's own classes come
from bridges Loom adds at compile time (`compat/GraphicsBridge` on `GuiGraphics`, `compat/GuiBridge` on
`Gui`, `compat/Bridges.*`; `src/mc12111/loom/fabric.mod.json`, intermediary names) and mixins implement at
run time. The remapped jar carries intermediary names in its mixin annotations (no refmap is needed).
The 26.x jars don't change: none of this is part of their build.

1.21.10 builds the same way, with its overlay layered on 1.21.11's (`src/mc12110/overlay.properties`:
`base=mc12111`): 1.21.11's rules and files first, then `src/mc12110/shared-sources.rules` over all of that (the
names 1.21.11 changed: `Identifier` → `ResourceLocation`, moved packages, a few signatures) and
`src/mc12110/java` for what 1.21.11 added: Flint's own `compat/gizmos` (1.21.10 has no debug gizmos; the same
calls are drawn after its debug renderers), the chat mixins for 1.21.10's two-pass chat drawing, Time Changer
on the client level's day time. Its `compileOnly` adds jspecify, which 1.21.10's Minecraft doesn't bring.

```sh
./gradlew build                              # 26.3: build/libs/flint-game-runtime-<app version>.jar, plus unit tests
./gradlew build -Pminecraft_version=26.2     # 26.2: build/mc-26.2/libs/flint-game-runtime-<app version>+26.2.jar
./gradlew build -Pminecraft_version=26.1.2   # 26.1.x: build/mc-26.1/libs/flint-game-runtime-<app version>+26.1.jar
./gradlew -p legacy build -Pminecraft_version=1.21.11   # 1.21.11: build/mc-1.21.11/libs/flint-game-runtime-<app version>+1.21.11.jar
./gradlew -p legacy build -Pminecraft_version=1.21.10   # 1.21.10: build/mc-1.21.10/libs/flint-game-runtime-<app version>+1.21.10.jar
./gradlew -p legacy189 build                          # 1.8.9: build/mc-1.8.9/libs/flint-game-runtime-<app version>+1.8.9.jar
```

`npm run release` in `app/` builds every version's jar first and embeds them in the app
(`app/src-tauri/build.rs`); at launch the profile gets the jar for its Minecraft version.

## Minecraft 1.8.9 (`legacy189/`)

1.8.9 has a code base of its own: a 2015 engine (Java 8, LWJGL 2, fixed-function OpenGL) shares nothing with the
26.x sources. It is built with Ornithe's toolchain (the Ploceus plugin on Loom's remap plugin, Feather mappings,
Calamus intermediary gen 2) and runs on the official Fabric Loader, which the launcher installs on 1.8.9 from
Ornithe's meta (Legacy Fabric's servers are offline). It compiles against Mojang's own 1.8.9 and its libraries,
exactly what the launcher runs. Its assets are Flint's own, taken from `src/client/resources` at build time: the
card pictures and the Font Awesome glyph font (OFL, licence bundled).

- `ui/`: drawing in window pixels (`Gfx`: rounded shapes and rings from one atlas; `Glyph`: Font Awesome
  rasterised by Java's font engine at the exact size; `Type`: Minecraft's font at whole-pixel scales with no
  shadow; `Pictures`; `Mark`), immediate-mode interaction (`Ui`), and `FastText`: Minecraft's font for the HUD in
  one batch per line instead of 1.8.9's one immediate-mode strip per letter.
- `menu/`: the Right Shift menu in the 26.x design (Dawn's tokens and sizes: `Style`, `MenuScreen`, `ModsPage`,
  `SettingsPage`, `Controls`, `Popup`) and the HUD page Right Shift opens in a world (`HudScreen`: drag to move,
  scroll to resize, snapping with guides).
- `hud/` and `mods/`: the HUD renderer (no per-frame allocation: a mod's text is remade only when what it shows
  changes) and 23 mods; `mixin/` hooks them into the game. Settings and HUD places live in
  `config/flintclient/legacy189.json`.
- QA: `node qa/run_qa_189.mjs <script> <run>` (the QA mod is `qa/legacy189`); `-Dflint.hudTiming=true` (through
  `FLINTQA_JVM`) logs each HUD mod's draw time.

## How it works

- `mixin/GuiMixin` (per version: 26.1 hooks `Minecraft.setScreen`) swaps a vanilla `TitleScreen` for
  `FlintTitleScreen` inside `Gui.setScreen`, so every route to the title screen (start-up, leaving a world,
  disconnects) shows Flint's menu.
  `FlintTitleScreen` extends `TitleScreen`, so Minecraft still treats it as the title screen.
- Controls are real Minecraft widgets (`ui/widget/FlintButton` extends `AbstractButton`): Tab and
  arrow-key focus, Enter/Space, narration and click sounds behave like vanilla. Only the drawing is
  Flint's.
- Drawing happens in physical pixels (`render/Canvas`). Rounded shapes, icons and the Flint mark are
  antialiased masks rasterised once per GUI scale into a small texture (`render/MaskAtlas`) and tinted
  when drawn. Text uses Inter and Outfit from the launcher, rasterised at exactly the GUI scale
  (`render/FlintFonts`; font sets are created on first use by `mixin/FontManagerMixin`).
- The background is Flint's menu artwork scaled to cover the window under a light veil (`ui/Backdrop`);
  controls are translucent dark surfaces with an antialiased border ring drawn on top.
- Hover, focus and press states ease with real frame time (`ui/Motion`). A panorama speed of 0
  (Accessibility settings) turns transitions off.

| Part | Source |
| --- | --- |
| Singleplayer, Multiplayer, Options, Skin customization, Friends | Minecraft's own screens |
| Screenshots | the profile's `screenshots` folder (`module/screenshots`) |
| S1–S5 shortcuts | Minecraft's server list; assignments in `config/flintclient.json` (`module/servers`) |
| What's new | `app/src/data/releaseNotes.json`, bundled at build time (`module/news`) |
| Version line | the mod's version (the app's version) and Minecraft's own version |
| Cosmetics, Store, Messages | honest "not available yet" pages |

Fonts are generated from the launcher's fonts by `tools/make_fonts.py`; licences are in
`src/client/resources/META-INF/licenses/`.

## Flint Control Center (`control/`, `ui/kit/`, `mixin/control/`)

| Part | Where |
| --- | --- |
| Entry points the mixins call (fault-isolated: a failing path turns itself off) | `control/ControlCenter` |
| Saved state, read once at start-up | `control/ControlState`; `control/store/` (`JsonStore`, `ControlSettings`, `LayoutStore`, `LayoutCodec`) |
| Modules, settings, layouts, presets, search, open/close state (no Minecraft imports) | `control/model/` |
| HUD placement, snapping, undo, CPS counter (no Minecraft imports) | `control/hud/` |
| HUD renderer, the 8 module views, styles, previews | `control/hud/render/` |
| Right Shift menu: the HUD page (`HudPageScreen`, what Right Shift opens in a world) and the window (`ModMenuScreen`: Mod Menu, the settings tabs, Mod Profiles, Module Presets, Theme), built from the reference catalog `assets/flintclient/rshift/catalog.json` (`Catalog`) | `control/rshift/` |
| HUD editing engine under the HUD page (select, drag, snap, scale, nudge, undo) | `control/editor/` |
| First-world hint | `control/cc/` |
| 26.3 adapter: key mappings, key names and states, sounds, player data | `control/platform/` |
| Component kit (screen, focus, scroll, controls, dialogs) | `ui/kit/` |

**Mixins** (`mixin/control/`, all verified against 26.3):
- `OptionsMixin`: appends Flint's two key mappings to `Options.keyMappings` before `load()` (idempotent, keeps
  other mods' entries; same approach as Fabric API).
- `MinecraftMixin`: `tick()` HEAD (held-key latch, HUD tick, first-world hint) and `handleKeybinds()` HEAD (opens
  the Control Center or HUD editor; runs only with no screen open).
- `HudMixin` (per version: 26.1's HUD is `Gui`): two allocation-free `@WrapWithCondition`s in `Hud.extractRenderState`: Flint's HUD is drawn just
  before chat (so F1 and the loading screen hide it the way they hide vanilla's), and vanilla's effect icons are
  left out when the Effects module asks.
- `MouseHandlerMixin`: counts real left/right presses that reach gameplay (CPS).
- `SoundBufferLibraryMixin`: hands Minecraft's sound engine the samples of Flint's two generated UI sounds
  (`control/sound/ToneSynth`; no audio files ship).
- `ClientPackSourceMixin`: when Fabric's resource loader is absent, Flint's own assets are added as a built-in
  resource pack (its metadata comes from code at the running game's pack format, so the jar carries no `pack.mcmeta` for Fabric to pick up); with Fabric API nothing changes.
- `KeyMappingAccessor`: the bound key, for key names and physical key state.

**Files** (in the profile's `config/`): `flintclient/control-center.json` (global settings) and
`flintclient/layouts/<id>.json` (one self-contained file per layout). Both have `"schema": 1`, are written
atomically only on close, Save or a layout action, keep unknown keys, copy a damaged file aside instead of replacing
it, and are never written when they come from a newer Flint. Key bindings live in Minecraft's `options.txt`.

**Per frame:** the HUD path allocates nothing of Flint's own (cached text and digit glyphs, revision-cached
styles); vanilla's own draw records are the only per-frame objects. `ui/FlintFrame` gives the shape atlas and
animation clock exactly one owner per frame, whether the HUD, a Flint screen or both draw.

## Flint Store capes (`control/feature/StoreCapes`)

Capes players equipped in the Flint Store are drawn on them, on every line. Equipping happens in the launcher; the game holds
no Store token and reads only the Store's public lookup, `GET {store}/api/v1/players/cosmetics/?uuids=a,b` (Store
`docs/LAUNCHER-API.md`). Nothing on this computer (a file, a setting, the texture cache) can make a cape appear: only what
that lookup answers for that player, now, or within 30 minutes of its last answer while the Store can't be reached.

| Part | Where |
| --- | --- |
| The Store's address and which asset addresses are allowed | `control/store/StoreEndpoint` |
| The launcher's handoff (`.flint-store.json`): which Store, and when you equipped something | `control/store/StoreHandoff` |
| The lookup and downloads (no redirects, no credentials, answers capped at 4 MiB, posters at 2 MiB, animation strips at their manifest's size; every field checked) | `control/store/StoreCosmeticsClient` |
| An animated cape's manifest, checked, and its wall-clock timing | `control/store/CapeAnimation` |
| Frames cut from strips, kept at most 16 px per unit (box filter) within a memory budget | `control/feature/CapeFrames` |
| Batching, freshness, back-off, revocation, the asset pipeline and texture budget (pure Java, every line) | `control/feature/StoreCapes` |
| The PNG decoder (pure Java: header checked before inflating, inflating capped at the header's size, CRCs checked) | `control/feature/CapePng` |
| 26.x and 1.21.x: players gathered, textures uploaded (`platform/Texture`), the cape put in the skin, animated capes advanced once a frame | `control/platform/Mc263StoreCapes`, `mixin/feature/PlayerInfoMixin`, `mixin/DebugScreenOverlayMixin`, `compat/McCompat.writeImage` |
| 1.8.9: the same with 1.8.9's dynamic textures; the cape location the cape layer binds (an animated cape advanced just before) | `legacy189/.../StoreCapes189`, `legacy189/.../mixin/ClientPlayerEntityMixin` |
| The switch ("Flint Store capes", on by default; General → Cosmetics) | `control/model/GameOptions.STORE_CAPES` |

- **Who is asked about:** you (from the title screen on), then the players in the world, nearest first (at most 200), once a
  second; at most 100 per request, one request at a time, a newly seen player waits 0.75 s so arrivals share a request. Only
  Mojang accounts (version-4 UUIDs) are sent; offline-mode players and server NPCs never are (a Store on loopback is asked
  about anyone, for development and QA).
- **Freshness:** an answer is kept for the Store's `max-age` (15 s to 10 min; 60 s today), then asked again. A player the answer
  leaves out wears nothing, so an unequipped, refunded or revoked cape goes at the next refresh. A failed lookup (offline, 429,
  5xx, malformed JSON) keeps what was known and waits: `Retry-After` when given (up to an hour), else 30 s doubling to 10 min.
- **Assets:** downloaded only from the Store's own origin or over https from a Supabase project's public `cosmetic-assets`
  bucket (no query, credentials or `.`/`..` segments), checked against the Store's SHA-256 and size (64×32 at 1, 2, 4, 8 or 16×,
  the Store's own rule), decoded by `CapePng` (8-bit, not interlaced), uploaded with nearest filtering. Verified files are
  cached in `<game dir>/flint/cache/cosmetics/<sha256>.png` (rehashed on every read; a changed file is deleted and downloaded
  again; at most 256 files). At most 64 textures and 16 MiB of pixels; a texture nobody in view wears is released after 2 min.
  A refused asset is retried after 30 min.
- **Animated capes** (the asset's `animation`, Store `docs/LAUNCHER-API.md` "Animated capes"): the manifest is checked as the
  Store checks it (version 1, the 22×17 atlas at 4/8/16/32 px per unit, 2–120 frames of 10–10000 ms, at most 60 s a loop,
  strips of `floor(2048 / height)` frames, each with a SHA-256 and at most 8 MiB, 32 MiB together, at an allowed address) and the
  poster must be its frame 0's texture; anything else shows the poster as a static cape. The poster loads first, as any cape;
  then the strips, one per loader task, each cached and checked like a texture and decoded at exactly the manifest's size.
  Frames are kept at the animation's scale capped at 16 px per unit (the poster's own cap: a cape's front is then at most
  160×256 pixels, more than it covers on screen), box-filtered down, halved again while one cape's frames would take more
  than 24 MiB (a standard cape of up to 65 frames plays at full size, a longer one at 8 px per unit), and all animated capes
  together at most 96 MiB: past that, animations nobody in view wears are released first, and one that still doesn't fit
  keeps its poster until there is room. One texture per animation, shared by every wearer: the poster's own when the frames
  are its size (else one of their own, 64×32 at their scale), so the cape layer, the elytra and Wavy Capes draw it unchanged.
  Timing is the wall clock's (`now % loop`, each frame its own duration): every Flint player sees the same frame. Once a frame,
  between two frames (26.x and 1.21.x: the frame-time hook, after the frame was presented; 1.8.9: as the cape layer binds it),
  a cape drawn in the last second gets its current frame written into the cape rectangle of its texture, only when the frame
  changed (one sub-image upload: 26.2+ `writeToTexture` at an offset, 26.1 and 1.21.11/1.21.10 its rectangle form, 1.21.1 and
  1.8.9 `glTexSubImage2D`), with no allocation. A strip that fails its checks keeps the poster and is retried like a texture.
- **Precedence:** an equipped Store cape takes the place of the player's Minecraft cape (the wearer chose it in the launcher;
  unequipping brings Minecraft's back). The wearer's own "Cape" skin-customization switch still hides it, Nick Hider's hidden
  skins stay default (no Store cape), a Minecraft elytra texture stays, and with "Flint Store capes" off only Minecraft's capes
  are drawn and the Store isn't asked. The elytra shows the Store cape exactly as it shows a Minecraft cape when the cape has
  elytra art (x 22–45, y 0–21 of the 64×32 layout); one without (the Store's test capes, every animated cape's poster) would
  leave the wings empty, so they get what Minecraft would give them without it: the wearer's Minecraft cape while shown, else
  the elytra's own texture (`StoreCapes.hasWings`, `Mc263StoreCapes.wingsCape`, `mixin/feature/WingsLayerMixin`; 1.21.1:
  `ElytraLayer`). Wavy Capes draws it like any cape. 1.8.9 has no elytra.
- **Who sees it:** players running Flint, through the same public lookup. Other clients don't know about Flint capes.

**Launcher handoff** (`control/store/StoreHandoff`; the launcher's contract is `docs/STORE-INTEGRATION.md`, "Game handoff
(v1)", on its `store-launcher-sync` branch): `.flint-store.json` in the game directory (the profile folder), written by the
launcher at every launch and rewritten, atomically, while the game runs whenever the player equips or removes something.
The game only reads it, and only as a hint: it says which Store to ask and when to ask about you again, never what anyone
wears, and holds no token or account.

```json
{ "schema": 1, "contractVersion": 1, "storeBaseUrl": "https://store.flintclient.com",
  "publicCosmeticsUrl": "https://store.flintclient.com/api/v1/players/cosmetics/", "equipRevision": 0 }
```

- Ignored (the built-in `https://store.flintclient.com` is used) when missing, unreadable, over 16 KiB, of a `schema` or
  `contractVersion` other than 1, with a `storeBaseUrl` that isn't https (or http on `localhost`/`127.0.0.1`), or with a
  `publicCosmeticsUrl` that isn't that Store's lookup.
- Watched once a second with one `stat` (modification time and size); read only when either changed. A read that fails while
  the launcher replaces the file keeps what was read before and tries again next second.
- `equipRevision` is compared for inequality (it goes back to 0 when the launcher restarts): when it changes, your own player is
  asked about again at once (at most every 5 s), with `Cache-Control: no-cache`, instead of at the next 60 s refresh. The Store
  may still answer from a copy up to 60 s old. A different `storeBaseUrl` starts over with that Store.
- `-Dflint.store.baseUrl=` overrides the file's Store (development and QA); `equipRevision` still counts.

## Nametags and the Flint mark (`control/feature/NametagRules`, `FlintPresence`)

**One nametag per player.** Minecraft decides every nametag (`LivingEntityRenderer.shouldShowName`: teams, invisibility, F1,
sneaking, riders); Flint decorates the one it shows (the Flint mark, ping, Tier Tagger's tier, Totem Tracker's count, Nick
Hider's name with the team's prefix and suffix kept) or hides it (the Nametags mod's keys), and never adds one beside it. The
one exception is your own nametag in third person ("Nametag in perspective"), which Minecraft never draws for the camera's
entity: it shows only where Minecraft would show a teammate's (your team's `nametagVisibility`: `never` and `hideForOwnTeam`
hide it; invisible), with nothing riding you, F1 off, and never on your picture in the inventory or the Player Model HUD. A server
that draws names itself (the TAB plugin's nametags, a text display or armour stand above you) hides Minecraft's with a `never`
team, so its name is the only one. Flint never changes a server's own name entities, except Tier Tagger adding a tier line to a
text display riding a player (never the Flint mark). The rules are pure Java (`NametagRules`, every line); a failing hook leaves
Minecraft's answer.

**Who wears the mark** (`FlintPresence`, `PresenceTargets`, Backend `flint_presence_*`): you, while "Flint icon on nametags" is on;
others when Flint's services say they announced in the last 15 minutes with "Show I'm on Flint" on. Asked about, once a second:
the player entities in the world (nearest first), then the player list's entries, then the entries the server keeps unlisted
(servers with their own tab layouts list fake entries and leave real players out); each once, never you, at most 200, and only
Mojang accounts (version-4 UUIDs). One lookup at a time: every minute, and 5 s after someone new is around (not while lookups
fail: 1 min doubling to 30); the services allow 30 a minute per address. Another server (connection) drops the marks at once and
any answer still on its way. Announcing needs the challenge sign-in (Backend 6500); until the services have it, the announce is
retried with the same back-off and others' marks still come from lookups.

Limits: an offline-mode (cracked) server gives players name-based UUIDs, which Flint's services can't know, so nobody wears a mark
there but you; a server that hides every nametag and draws names only in its own hologram shows no Flint mark (Flint doesn't
write into a server's entities); clients without Flint never see marks.

QA: `node qa/tools/mock-presence.mjs --tags UUID=MARK,...` answers lookups on 127.0.0.1:8788 for a QA-only build made with
`FLINT_SUPABASE_URL=http://127.0.0.1:8788 FLINT_SUPABASE_ANON_KEY=flint-qa-stub` (never shipped), and `qa/.../QaBots` adds remote
players to a single-player world (listed, unlisted or without a list entry), as `~/FlintQA/scripts/nametag-fixes/` does.

## Packs added while playing (`.flint-packs.json`)

The Flint app can add resource packs, shader packs and data packs to a profile while its game runs (and remove them); mods
still need a restart there. The app writes the pack into its folder (a name nothing there has, so a pack the game has open is
never replaced; renamed into place in one step) and then the handoff, atomically (launcher `app/src-tauri/src/content/livepacks.rs`):

```json
{ "schema": 1, "revision": 1762380000123,
  "changes": [{ "revision": 1762380000123, "kind": "resourcepack", "file": "Dirt Tint.zip", "title": "Dirt Tint",
                "action": "added", "enable": true }] }
```

| Part | Where |
| --- | --- |
| The file read and checked (every line, 1.8.9 included; pure Java) | `control/store/PackHandoff` |
| 26.x and 1.21.x: resource packs, Iris shader packs, data pack notices | `control/platform/Mc263LivePacks`, `control/platform/IrisPacks` |
| 1.8.9: resource packs | `legacy189/.../LivePacks189` |

- **Watched** once a second with one `stat`, read only when it changed (as the Store handoff). Each change is applied once: those
  newer than the last applied and newer than this game's start (the launcher also clears the file at every launch). A file that
  is missing, over 64 KiB, not JSON or of another `schema` gives nothing; a change of an unknown kind or action, or naming
  anything but a plain file in its kind's folder (a path, `..`, a hidden or Windows device name), is left out.
- **When:** on the game's thread, never while resources reload or a world loads or a server connects; a change waits until then.
- **Resource pack:** the pack list is read again (Options → Resource Packs shows it at once; that screen, when open, watches its
  folder itself). With `enable`, the pack goes on top of the selected packs, the options are saved and resources reload, exactly
  as Done on that screen; a pack made for another version stays listed but off. While that screen is open, turning on waits
  until it closes (its Done would undo it). A pack removed while it was on leaves the selection (resources reload).
- **Shader pack (Iris):** Iris's list is refreshed when its screen is open (it reads the folder whenever it opens). With
  `enable`, the pack is selected and applied as Iris's own Apply does: `Iris.getIrisConfig().setShaderPackName` (the public API
  has no call for the name), then the public `IrisApi…getConfig().setShadersEnabledAndApply(true)`, all by reflection, every
  failure a notice. The same calls are in every Iris Flint installs (1.8.14 on 1.21.1, 1.9.7 on 1.21.10, 1.10.8 on 1.21.11,
  1.11.4 on 26.1 and 26.2, 1.11.7 on 26.3). Without Iris, a notice says shaders need it. 1.8.9 has no shader loader (the app
  doesn't offer shader packs there).
- **Data pack:** a notice says to run `/reload` in that world or rejoin it; data packs load with the world.
- Every change shows a short Flint notice ("Dirt Tint turned on"; on 1.8.9 the Friends notice cards). Fault-isolated: a failure
  turns this off for the session, never the game.
- QA: `node qa/tools/live-packs.mjs TOUR --mc VERSION [--mods DIR]` runs a tour in a real game and answers each `await-signal`
  with the launcher's own install code; the tours are in `~/FlintQA/scripts/live-packs/`.

## Flint Performance Core (`perf/`)

The runtime half of Settings → Minecraft → "Automatic Optimization". It exists only where the
runtime is installed (Minecraft 26.3, 26.2, 26.1.x, 1.21.11 or 1.21.10 with Fabric, while "Flint main menu in Minecraft" is on); the
setting's Java flags are the launcher's (`app/src-tauri/src/perfcore.rs`). It reads
`config/flint-performance.json` once at start-up. The launcher writes that file at every launch and
the runtime never writes it. A missing, corrupt or newer-schema file falls back to defaults. One INFO
line at start-up lists the config source, the detected performance mods (`Compat`) and the enabled
modules.

**Modules** (on by default when Automatic Optimization is on; each can be switched off for diagnosis
with `"modules": {"<id>": false}` in the config file):

| Id | What it does |
| --- | --- |
| `menuResources` | Releases the menu's backdrop and shape-atlas textures while you're in a world; decodes the backdrop off the render thread when you leave |
| `atlasBatching` | Shape atlas without per-frame allocation or full rebuilds per new shape (grows in place, uploads changed rows once per frame) |
| `decodeExecutor` | Screenshot thumbnails decode on one low-priority thread instead of Minecraft's chunk-building pool |
| `fontPreload` | Graphics Core: Flint's TrueType font sets for the current GUI scale are loaded (file, FreeType face, every glyph) on a worker after each resource reload and GUI-scale change, the way vanilla loads its own fonts; the render thread only builds the font set from the finished provider. Falls back to the old on-the-spot load. |
| `networkCore` | Network Core (always on, observe-only): tracks the multiplayer connection you start (resolving → opening → logging in → connected → disconnected), one INFO line per join and per ending with phase timings and an outcome category (refused, unknown host, timed out, kicked, server closed, quit, cancelled…), never a host, IP, port or server text; last 8 attempts kept in memory for the future HUD (`NetworkCore.stats()`: phase, time connected, server-reported ping, packets per second — read on demand, no packets sent). `dev.network` adds one line per phase change |
| `graphicsCore` | Graphics Core (always on, read-only): one start-up line with the graphics backend, device, max texture size and loaded rendering optimizers; a WARN if Flint menu textures are still alive after joining a world |

**Always on (lifecycle fixes, not optimizations):**
- Server status pings (S1–S5) run in parallel (at most 5 threads, which exit when idle); each slot's 15 s
  limit starts when its own ping starts, and an unknown host shows offline at once. Before, they ran one at
  a time, so a dead server could make the other slots show offline without ever being pinged.
- Server status pings stop when the menu closes.
- Late ping and thumbnail results are discarded.
- `servers.dat` is read once per menu visit.

**Shared and developer pieces:**
- `FrameStats` is an allocation-free frame-time ring for future HUD modules. It records nothing until
  something acquires it.
- `FLINT_DEV_FRAMESTATS=1`, set for the launcher, adds a once-a-minute FPS and frame-time log line in
  worlds (`dev.frameStatsLog`).
- `graphics/` (Flint Graphics Core): `GraphicsInfo`, `GpuResourceTracker` (live Flint textures by kind)
  and `Preparations` (off-thread work handed to the render thread only when finished). Minecraft calls
  live only in `graphics/platform/` (one class per Minecraft version, in its overlay: 26.3, 26.2, 26.1 and 1.21.11, which 1.21.10 shares), see
  `docs/MULTI_VERSION_PLAN.md`.
- Developer flags set by hand in `flint-performance.json` survive the launcher's rewrite:
  `dev.fontTiming` logs each Flint font set's render-thread cost, `dev.gpuResources` logs Flint's live
  textures at each world join and leave.
- The font hook on `FontManager.getFontSetRaw` returns vanilla's result for every id and allocates
  nothing per call (it used to allocate a callback object on every font switch).

**Measured effect.** With Sodium and the rest of Flint's performance set, on Minecraft 26.3 on one
MacBook Air (Apple M2, 16 GB, plugged in, Low Power Mode off), two or three runs per configuration:
- In-world FPS and frame times: no difference from the 0.4.0 runtime beyond run-to-run spread.
- Screenshots of the main menu (one window size) were byte-identical to the 0.4.0 runtime's.
- These are resource and lifecycle fixes, not FPS gains.
- Not measured: other Macs, battery power or Low Power Mode, Windows and Linux.

**Graphics Core check** (one launch each way, same Mac, Sodium stack): render-thread time to create
Flint's 6 menu font sets on the first menu frame was 22.8 ms (worst 11.2 ms) without `fontPreload` and
1.2 ms (worst 0.3 ms) with it; menu screenshots stayed byte-identical. A start-up hitch removed, not an
in-world FPS change; GUI-scale changes and resource reloads take the same path but weren't exercised.

Benchmarks use a separate dev-only probe mod in `bench/` (a standalone Gradle build, never shipped).

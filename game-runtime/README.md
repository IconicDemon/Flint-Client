# Flint game runtime

Flint's in-game component: a small Fabric mod (Fabric Loader only, no Fabric API) that replaces
Minecraft's title screen with Flint's main menu and adds Flint Control Center (Right Shift): HUD modules,
a visual HUD editor, layouts and in-game settings. Players don't install it: the Flint app embeds the
jar and copies it into a profile's `mods` folder at launch, only for supported profiles and only
while Settings → "Flint main menu in Minecraft" is on (see `app/src-tauri/src/runtime.rs`).

**Supported:** Minecraft 26.3 with Fabric (`fabric.mod.json` requires `~26.3`). Other versions use
different screen and rendering APIs; the app doesn't install the runtime there, so they keep
Minecraft's own title screen.

```sh
./gradlew build      # jar in build/libs/flint-game-runtime-<app version>.jar, plus unit tests
```

`npm run release` in `app/` builds this first and embeds the jar in the app.

## How it works

- `mixin/GuiMixin` swaps a vanilla `TitleScreen` for `FlintTitleScreen` inside `Gui.setScreen`, so
  every route to the title screen (start-up, leaving a world, disconnects) shows Flint's menu.
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
| Control Center screen and pages | `control/ControlCenterScreen`, `control/cc/` |
| HUD editor | `control/HudEditorScreen`, `control/editor/` |
| 26.3 adapter: key mappings, key names and states, sounds, player data | `control/platform/` |
| Component kit (screen, focus, scroll, controls, dialogs) | `ui/kit/` |

**Mixins** (`mixin/control/`, all verified against 26.3):
- `OptionsMixin`: appends Flint's two key mappings to `Options.keyMappings` before `load()` (idempotent, keeps
  other mods' entries; same approach as Fabric API).
- `MinecraftMixin`: `tick()` HEAD (held-key latch, HUD tick, first-world hint) and `handleKeybinds()` HEAD (opens
  the Control Center or HUD editor; runs only with no screen open).
- `HudMixin`: two allocation-free `@WrapWithCondition`s in `Hud.extractRenderState`: Flint's HUD is drawn just
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

## Flint Performance Core (`perf/`)

The runtime half of Settings → Minecraft → "Automatic Optimization". It exists only where the
runtime is installed (Minecraft 26.3 with Fabric, while "Flint main menu in Minecraft" is on); the
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
  live only in `graphics/platform/` (one class per Minecraft version family; 26.3 today), see
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

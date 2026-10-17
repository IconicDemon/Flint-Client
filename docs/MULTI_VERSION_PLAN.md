# Multi-version plan for Flint's in-game runtime

Status: plan only (Graphics Core milestone, 2026-10-15). Only Minecraft 26.3 has a Flint runtime today. The
launcher can install and launch every version below; the runtime is a separate, per-version question.
Nothing here counts as verified for a version until that version's runtime has been built **and**
launched.

## Target versions (official metadata, fetched 2026-10-14)

| Version | Released | Java | Client jar | Fabric Loader | Fabric API |
| --- | --- | --- | --- | --- | --- |
| 26.3 | 2026-09-15 | 25 (`java-runtime-epsilon`) | unobfuscated | 0.19.5 | 0.163.0+26.3 |
| 26.2 | 2026-06-16 | 25 | unobfuscated | 0.19.5 | 0.161.0+26.2 |
| 26.1.2 | 2026-04-09 | 25 | unobfuscated | 0.19.5 | 0.155.3+26.1.2 |
| 26.1.1 | 2026-04-01 | 25 | unobfuscated | 0.19.5 | 0.155.3 (tagged 26.1–26.1.2) |
| 1.21.11 | 2025-12-09 | 21 (`java-runtime-delta`) | obfuscated (Mojang mappings published) | 0.19.5 | 0.141.6+1.21.11 |
| 1.21.10 | 2025-10-07 | 21 | obfuscated | 0.19.5 | 0.138.4+1.21.10 |
| 1.21.1 | 2024-08-08 | 21 | obfuscated | 0.19.5 | 0.116.17+1.21.1 |

## Why one jar can't cover them

These differences were checked against the 26.1–26.3 jars:

| Area | Version split |
| --- | --- |
| Screens | 26.2/26.3: `Gui.setScreen` / `Gui.screen()`. 26.1.x: `Minecraft.setScreen` / `Minecraft.screen`. This breaks `GuiMixin` and 19 call sites. |
| GPU API | 26.3: moved to `com.mojang.renderpearl.api.*`. 26.2 and older: `com.mojang.blaze3d.*`. This affects the atlas upload, the backdrop and screenshot blits, and the Graphics Core platform adapter. |
| `OptionsScreen` | Constructor arity differs (26.3 vs 26.2 and 26.1.x). |
| `Blaze3D.openPath` | 26.3 only. |
| Friends overlay and social APIs | 26.2 and newer only. |
| `NativeImage.getPixelBytes` | Missing in 26.1.x. |
| Tooltip pass override | Missing in 26.1.x. |

**1.21.x** (from prior knowledge, not yet checked against jars):
- `GuiGraphics` with `render`/`renderWidget` instead of the 26.x extractor;
- no `RenderPipelines`/`GpuDevice` in 1.21.1;
- `ResourceLocation` instead of `Identifier`;
- raw-int input events;
- Java 21;
- obfuscated jars, so Loom's remap plugin and `officialMojangMappings()` are required.

**Unchanged across 26.x:** `DebugScreenOverlay.logFrameDuration` and the `FontManager` members Flint's font hook uses.

## Structure

```
game-runtime/
  common/      plain Java (release 21), no Minecraft imports: FrameStats, PerfConfig, AtlasLayout,
               MaskKey, Sdf, Palette, PingRound, Thumbnails, layout/motion maths, GpuResourceTracker…
               plus the Platform / adapter interfaces (Graphics Core's GraphicsPlatform is the first)
  mc-26.3/     Loom (no remap), release 25 — today's code
  mc-26.2/     Loom (no remap), release 25 — blaze3d GPU types, 3-arg OptionsScreen
  mc-26.1/     Loom (no remap), release 25 — Minecraft.setScreen, no friends UI; covers 26.1–26.1.2
  mc-1.21.11/  Loom remap + Mojang mappings, release 21 — GuiGraphics (1.21.10 likely shares it; 1.21.1
               likely needs its own)
```

**Per family:**
- Each family's `fabric.mod.json` declares an exact `minecraft` range.
- `common` is compiled into each family's jar; Fabric can't load a plain library jar.

**Launcher:**
- `build.rs` embeds one jar per family.
- `runtime.rs` replaces `SUPPORTED_VERSIONS` with an exact version → jar table.
- The installed file keeps the name `flint-runtime.jar`, so the existing replace and remove logic still works.

## Settings formats (Auto Sync)

The launcher reads a profile's data version from its client jar (`version.json` `world_version`) and only writes
families it has verified against that version's jar:

| Version | Data version | Settings family | State |
| --- | --- | --- | --- |
| 26.3 | 5023 | 26.3 (SDL3 key names) | verified |
| 26.2 | 4903 | 26.2 (GLFW key names) | verified |
| 26.1.2 / 26.1.1 / 26.1 | 4790 / 4788 / 4786 | 26.1 (GLFW key names) | verified |
| 1.21.11, 1.21.10, 1.21.1 | — | — | not verified (read-only) |

Adding a family means extracting its option keys, key-mapping names and key names from its jar with
`app/src-tauri/tools/gen_mc_tables.py` and adding its data versions to `gamesettings/versions.rs`; nothing is
guessed for a version that hasn't been checked.

## Order of work

**Groundwork, already in place:**
- New Graphics Core code keeps its Minecraft calls in `graphics/platform/`.
- Pure logic stays free of Minecraft imports, ready to move into `common`.

**Next steps:**
1. Split out `common` and turn today's code into `mc-26.3`, with no behaviour change: same jar contents, tests pass.
2. Add `mc-26.2`, the closest family, and build and launch it in a disposable profile.
3. Add `mc-26.1`, then the 1.21.x families.

Final Verification tests every family on its versions. No version is called supported before that.

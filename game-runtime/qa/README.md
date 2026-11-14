# Flint QA tour (dev only, never shipped)

Scripted tours of Flint inside a real Minecraft 26.3, used for beta verification. Nothing here is part of the app: the
launcher embeds only `game-runtime/build/libs/flint-game-runtime-*.jar`.

- `src/` — the `flintqa` Fabric mod: runs the script named by `-Dflintqa.script`, one step per client tick, and logs
  `QA PASS` / `QA FAIL` lines. Step list: see `FlintQa.java`.
- `run_qa.py` — prepares an isolated game folder in `~/FlintQA/games/<name>` (never the owner's profiles or saves),
  launches Minecraft from the Flint launcher's installed files (read only) with the offline QA identity, posts real
  keys when the tour asks (`await-key`), and summarises the results in `~/FlintQA/runs/<run>`.
- `tools/keypost.swift` — brings the game window to the front and posts a real Right/Left Shift press.

```sh
cd game-runtime
./gradlew build -x test && ./gradlew -p qa build
swiftc -O qa/tools/keypost.swift -o qa/tools/keypost
python3 -I qa/run_qa.py ~/FlintQA/scripts/mm1.txt mm1
```

On Windows, `node qa/run_qa_windows.mjs SCRIPT RUN` does the same; `--mc 26.2` runs the tour in Minecraft 26.2 with the
26.2 jars (`./gradlew build -Pminecraft_version=26.2 && ./gradlew -p qa build -Pminecraft_version=26.2`); `--mc 26.1.2`
(or 26.1.1, 26.1) likewise with the 26.1 jars (built with `-Pminecraft_version=26.1.2`). The QA mod's key and mouse
input and where it finds the screen and chat differ per version (`src/mc263`, `src/mc262`, `src/mc261`: `QaInput`,
`QaGame`). Prefer `press key.keyboard.right.shift down|up` to `await-key` there: it goes through Minecraft's keyboard
handler, so the window never comes to the front. A version whose client the Flint launcher hasn't downloaded runs from
`~/FlintQA/mc-cache` (`mc_cache.mjs`: every file checked against Mojang's or Fabric's sha1 first; the launcher's own
folder is only read).

`--mc 1.21.11` runs the tour in Minecraft 1.21.11 on Java 21 (`java-runtime-delta`, the version's own; fetched into
the cache, sha1-checked, when the launcher's copy is missing or incomplete) with the 1.21.11 jars
(`./gradlew -p legacy build -Pminecraft_version=1.21.11 && ./gradlew -p qa build -Pminecraft_version=1.21.11`; the QA
mod builds with Loom's remap plugin there, `src/mc12111`). 1.21.x runs under intermediary names, so the tour's
reflection (`option fov`, `expect-screen ChatScreen`, a handler's private method) goes through `QaNames`, which reads
the Mojang-to-intermediary names the QA build bundles from Loom's mappings (`flintqa-names.tsv`); on 26.x it is the
identity. `--mc 1.21.10` likewise (`-Pminecraft_version=1.21.10` for both builds; `src/mc12110`, where a file of the
same path as one in `src/main` replaces it, as `QaSkin` does).

`--mc 1.19.2` and `--mc 1.19` run the tour in those releases on Java 17 (`java-runtime-gamma`, their own) with their jars
(`-Pminecraft_version=1.19.2` or `1.19` for both builds). The QA mod's 1.19.2 files are in `src/mc11902`; 1.19 builds on
them (`src/mc11900/overlay.properties`: `base=mc11902`) with only its own chat (`QaChat`: commands and chat lines before
1.19.1's signing). The steps are the same; what 1.19.x can't do as later versions do: the tour's skin and the Minecraft
cape stand-in are written where Minecraft keeps a player's textures (its player info), so a Flint Store cape or Nick
Hider shows over a tour's own skin; later versions' default skins become Steve or Alex; `QaBots` `unlisted` players are
listed (1.19.2 lists every player it knows) and `mount` hangs a named armour stand on you (text displays came in 1.19.4).

The tours used for the beta are in `~/FlintQA/scripts/` (copied into each run folder as `script.txt`). The local test
server for multiplayer checks is `~/FlintQA/server/run.sh` (127.0.0.1:25599, offline mode, loopback only).

Flint Store capes without the Store: `node qa/tools/mock-store.mjs --revoke-after 120` answers the Store's public cosmetics
lookup on 127.0.0.1:8787 (a test cape for everyone asked about, then nobody after 120 s), and
`FLINTQA_JVM="-Dflint.store.baseUrl=http://127.0.0.1:8787" node qa/run_qa_windows.mjs ~/FlintQA/scripts/store-capes.txt RUN`
films the QA player's back with the cape, then without it after the refresh (`--cape FILE.png` for another texture,
`--bad-hash` for a texture the game must refuse; `--animated standard|hd|long` for the Store's e2e animated capes, with
`--bad-strip` for a strip the game must refuse, keeping the poster).

Which cape wins (Flint's Store cape, else the Minecraft cape, else none): an offline QA player has no Minecraft cape, so
`call com.flintclient.qa.QaSkin.minecraftCape on|off` (1.8.9: `com.flintclient.qa.legacy.QaCape.minecraftCape`) gives it a
stand-in where Minecraft keeps Mojang's (under Flint's own hook: `PlayerInfo.getSkin` at priority 500; on 1.8.9
`PlayerInfo.getCapeTexture`), and `call com.flintclient.qa.QaSkin.capeNow` (`QaCape.capeNow`) logs which texture the skin
carries. With `--handoff GAME_DIR`, `--revoke-after` also rewrites the handoff with a new equipRevision, as the launcher does
when the player takes the cape off. The tours are in `~/FlintQA/scripts/cape-priority/` (with `command item replace entity
@s armor.chest with elytra` for the wings: the Store's test capes have no elytra art, `--cape` with one that has some shows
the wings wearing it).

Minecraft 1.8.9 has its own runner and QA mod: `node qa/run_qa_189.mjs SCRIPT RUN [--size WxH] [--no-runtime] ...`
after `./gradlew -p legacy189 build && ./gradlew -p qa/legacy189 build`. It launches Mojang's 1.8.9 (LWJGL 2 natives
unpacked; Java 21 from Mojang's `java-runtime-delta`, as the app runs Flint on 1.8.9, or Java 8 from `jre-legacy` with
`--java 8` and for `--no-runtime` baselines) with Fabric Loader from Ornithe's profile, every file sha1-checked into
`~/FlintQA/mc-cache`. Only one game runs at a time: it creates `~/FlintQA/game.lock` exclusively before launching
(waits while a lock under 15 minutes old exists, takes over an older one) and deletes it when the game exits. Its
steps are listed in `qa/legacy189/.../FlintQa.java` (`move-node`, `mouse-down`/`mouse-drag`/`mouse-up`, `scroll`,
`fps SECONDS NAME`, `perspective N`…); Flint's elements are found by accessible name. The 1.8.9 tours are in
`~/FlintQA/scripts/legacy189/`.

The crash sweep (`~/FlintQA/scripts/crashproof/sweep.txt`, 1.8.9: `sweep-189.txt`; `QaSweep`, `legacy/QaSweep189`) finds what
takes the game down when a mod, a setting or a screen opens, driven by Flint's own registries so a mod added later is swept with
no tour change: `sweep mods [WxH]` switches every module of `ModuleRegistry` on in a world, opens its page in the Right Shift menu
and sets every setting to its minimum, maximum and middle (through the setting's own clamp), every choice, both boolean states,
empty and long text, a few frames each, then puts the module back (the window resized to WxH half way, every module on at once at
the end); `sweep title` / `sweep pause` press every button of the title screen or pause menu and of the screen each opens (never
one that quits, leaves, connects, opens a browser or folder, deletes or signs out: listed as skipped); `sweep rshift` opens the
HUD page and every tab of the window; `sweep net` checks the safety net (`feature.Faults`: a HUD module made to throw is switched
off, a Flint screen made to throw closes); `sweep deep` presses what opens another screen on Flint's own windows (Friends,
Cosmetics...); `sweep summary` prints the PASS / FAIL list. Nothing stops it: a step's exception and everything the safety net
caught meanwhile is a FAIL with the module / setting / screen it happened in, and a crash is found from the last `QA sweep at`
line. Run it as the other tours, with the mock Store and mock link (`--size 1280x720`).

Nametags and Flint marks over other players, without a second game or account (`~/FlintQA/scripts/nametag-fixes/`):
`call com.flintclient.qa.QaBots.spawn NAME uuid:UUID listed|unlisted|noinfo DX DZ` adds a remote player to a single-player
world the way the client does when a server announces one (1.8.9: `com.flintclient.qa.legacy.QaBots.spawn NAME UUID
listed|noinfo DX DZ`); `QaBots.mount` / `unmount` (1.8.9: `ride TEXT` / `unride`) hang a summoned text display (an armour
stand) on you in the integrated server, as servers draw their own name (vanilla's /ride refuses players); `QaBots.badge on|off`
flips "Flint icon on nametags"; `QaBots.known` logs the marks Flint knows. For the marks, `node qa/tools/mock-presence.mjs
--tags UUID=MARK,...` answers Flint presence on 127.0.0.1:8788 for a QA-only runtime built with
`FLINT_SUPABASE_URL=http://127.0.0.1:8788 FLINT_SUPABASE_ANON_KEY=flint-qa-stub` (passed with `--no-runtime --mods DIR`; never
ship it) and logs whom the game asks about. `APPDATA=<an empty folder>` makes either runner use only `~/FlintQA/mc-cache`.
The Windows runner takes the same `game.lock` as the 1.8.9 one.

Packs the Flint app adds while the game runs (`.flint-packs.json`, see the runtime README): `node qa/tools/live-packs.mjs TOUR
--mc VERSION [--mods DIR] [--size WxH]` generates test packs (resource packs recolouring dirt, grass and stone, one of another
pack format, shader packs that tint the screen, a data pack), runs the tour with the right runner in a folder laid out as Flint's
data folder (`~/FlintQA/live-packs/root/profiles/lp-<version>`), and answers each `await-signal` (rp-on, rp-listed, rp-while-open,
rp-incompatible, rp-remove, shader-on, shader-listed, mod-blocked, datapack) with the launcher's own Rust code (the ignored test
`content::tests::qa_live_pack_change`). Game files come from `~/FlintQA/mc-cache` only (the runner gets an `APPDATA` without the
app); it holds `~/FlintQA/game.lock` while the game runs. 1.8.9 tours have `await-signal` too. The tours are in
`~/FlintQA/scripts/live-packs/`; `--mods ~/FlintQA/bench/<version>/iris` adds the performance set with Iris.

What a screen share's viewer sees (Discord, OBS): `node qa/tools/capture-test.mjs --plan PLAN.json --run RUN [--mc VERSION] [--mods DIR]
[--shader PACK.zip] [--perf-json JSON] [--sodium-ahead N]` runs one game (either runner) through a plan's segments (windowed; FPS
limit; VSync; the game's GPU priority) and captures each the way screen sharing does: Windows.Graphics.Capture of the game window
(`wgc`) or Desktop Duplication of its area (`dda`), every frame Windows delivers, encoded with NVENC H.264, with a portable ffmpeg
that has gfxcapture and ddagrab (`%USERPROFILE%\FlintQA\tools\ffmpeg`). The game stamps each frame it presents with its number
(`QaStamp`; 1.8.9 `LegacyStamp`: black and white blocks in a corner, painted just before the present), so each captured frame says
which game frame the viewer got. Per segment (`capture-results.tsv`/`.json` in the run folder): the game's FPS, 1% low and present
pacing; the delivered frames, gaps, and what a 60 or 30 fps stream shows (repeated frames, judder); GPU load (nvidia-smi). The window
stays behind the owner's windows (`qa/tools/capture-window.ps1`); fullscreen or on-top segments need `--allow-on-top` and the
owner's OK. A segment the owner clicked into is marked `*`. Run it inside heavy-slot.mjs; capture files are deleted unless `--keep`.

The in-game Library and Instant Swap without the launcher: `node qa/tools/swap-link.mjs --game DIR --standby-script A,B` answers
the Library's game-link ops against real Modrinth (CurseForge search through the local relay) and plays the launcher's swap
(`app/src-tauri/src/swap.rs`): a mod change starts the standby game (this runner again with `FLINTQA_NO_LOCK`,
`FLINTQA_KEEP_MODS`, `FLINTQA_PRIORITY=idle`, `FLINT_SWAP_STANDBY`; the first swap runs tour A, the next B), early when free
memory holds a second game (`--prewarm`: always), else after the first game hands over. The tours and drivers are in
`~/FlintQA/scripts/library/` (`run-swap.sh`, `run-mp.sh` with the local server, `run-preview.sh`, `run-fit.sh`).

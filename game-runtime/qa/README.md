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

The tours used for the beta are in `~/FlintQA/scripts/` (copied into each run folder as `script.txt`). The local test
server for multiplayer checks is `~/FlintQA/server/run.sh` (127.0.0.1:25599, offline mode, loopback only).

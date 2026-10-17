#!/usr/bin/env python3
"""run_qa.py SCRIPT RUN [--game NAME] [--reuse] [--size WxH] [--gui N] [--demo] [--timeout S] [--mods DIR]

Dev-only: runs one scripted QA tour of Flint inside a real Minecraft 26.3, in an isolated game folder.

- Game folders and results live in ~/FlintQA (games/<game>, runs/<run>), never in the owner's profiles or saves, and
  not in /tmp (a restart clears it).
- Minecraft's libraries, assets, natives and Java come from the Flint launcher's own data folder, read only; Minecraft
  writes only inside the game folder.
- The identity is the QA one from artifacts/perf-core/launch-template.json ("FlintQA", access token "0"): offline, never
  a real account. Without --demo it is not a demo user, so Singleplayer is the real button.
- Mods: Flint's runtime jar (game-runtime/build/libs) and the QA tour jar (qa/build/libs); --mods adds every jar of a
  folder (a performance set, say).
- A tour step "await-key NAME KEY" makes this runner bring the game to the front and post a real KEY press (qa/tools/
  keypost), then answer by writing runs/<run>/key-NAME.done.

Prints every "QA PASS/FAIL" line and the summary; exit 0 when the tour finished with no failures.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
TEMPLATE = os.path.join(REPO, "artifacts", "perf-core", "launch-template.json")
APP_DATA = os.path.expanduser("~/Library/Application Support/com.flintclient.desktop")
QA_ROOT = os.path.expanduser("~/FlintQA")
RUNTIME_JAR = os.path.join(REPO, "game-runtime", "build", "libs", "flint-game-runtime-%s.jar" % json.load(open(os.path.join(REPO, "app", "package.json")))["version"])
QA_JAR = os.path.join(HERE, "build", "libs", "flint-qa-1.0.0.jar")
KEYPOST = os.path.join(HERE, "tools", "keypost")

FRESH_OPTIONS = [
    "version:5023", "fullscreen:false", "pauseOnLostFocus:false", "onboardAccessibility:false", "tutorialStep:none",
    "soundCategory_master:0.02", "skipMultiplayerWarning:true", "joinedFirstServer:true", "narrator:0",
    "renderDistance:6", "simulationDistance:6",
]


def build_argv(game, out, script, args):
    t = json.load(open(TEMPLATE))
    old = t["cwd"].split("/data/")[0] + "/data"

    def real(p):
        return p.replace(old, APP_DATA)

    natives = os.path.join(game, "natives")
    for sub in ("java", "jna", "lwjgl", "netty"):
        os.makedirs(os.path.join(natives, sub), exist_ok=True)
    jvm = []
    for a in t["jvm"]:
        if a.startswith("-Djava.library.path="):
            a = "-Djava.library.path=" + os.path.join(natives, "java")
        elif a.startswith("-Djna.tmpdir="):
            a = "-Djna.tmpdir=" + os.path.join(natives, "jna")
        elif a.startswith("-Dorg.lwjgl.system.SharedLibraryExtractPath="):
            a = "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + os.path.join(natives, "lwjgl")
        elif a.startswith("-Dio.netty.native.workdir="):
            a = "-Dio.netty.native.workdir=" + os.path.join(natives, "netty")
        else:
            a = real(a)
        jvm.append(a)
    jvm += ["-Dflintqa.script=" + script, "-Dflintqa.out=" + out]
    game_args = []
    skip = False
    for i, a in enumerate(t["game"]):
        if skip:
            skip = False
            continue
        if a == "--gameDir":
            game_args += [a, game]
            skip = True
            continue
        if a == "--demo" and not args.demo:
            continue
        game_args.append(real(a))
    if args.size:
        w, h = args.size.split("x")
        game_args += ["--width", w, "--height", h]
    return [real(t["java"])] + jvm + [t["main"]] + game_args


def prepare(game, args):
    fresh = not os.path.isdir(game)
    if not fresh and not args.reuse:
        sys.exit("game folder exists: %s (use --reuse)" % game)
    mods = os.path.join(game, "mods")
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(mods):
        os.remove(os.path.join(mods, f))
    for src, name in ((RUNTIME_JAR, "flint-runtime.jar"), (QA_JAR, "flint-qa.jar")):
        if not os.path.isfile(src):
            sys.exit("missing jar: " + src)
        shutil.copy(src, os.path.join(mods, name))
    if args.mods:
        for f in sorted(os.listdir(args.mods)):
            if f.endswith(".jar") and f != "flint-runtime.jar":
                shutil.copy(os.path.join(args.mods, f), os.path.join(mods, f))
    options = os.path.join(game, "options.txt")
    lines = open(options).read().splitlines() if os.path.exists(options) else list(FRESH_OPTIONS)
    if args.gui is not None:
        lines = [l for l in lines if not l.startswith("guiScale:")] + ["guiScale:%d" % args.gui]
    open(options, "w").write("\n".join(lines) + "\n")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("script")
    p.add_argument("run")
    p.add_argument("--game")
    p.add_argument("--reuse", action="store_true")
    p.add_argument("--size")
    p.add_argument("--gui", type=int)
    p.add_argument("--demo", action="store_true")
    p.add_argument("--mods")
    p.add_argument("--timeout", type=int, default=900)
    args = p.parse_args()

    game = os.path.join(QA_ROOT, "games", args.game or args.run)
    out = os.path.join(QA_ROOT, "runs", args.run)
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    prepare(game, args)
    script = os.path.abspath(args.script)
    shutil.copy(script, os.path.join(out, "script.txt"))
    argv = build_argv(game, out, script, args)
    log_path = os.path.join(out, "game.log")
    print("[qa] launching", args.run, "in", game, flush=True)
    with open(log_path, "w") as log:
        proc = subprocess.Popen(argv, cwd=game, stdout=log, stderr=subprocess.STDOUT)
    start = time.time()
    handled = set()
    result = "TIMEOUT"
    while True:
        time.sleep(0.5)
        text = open(log_path, errors="replace").read()
        for m in re.finditer(r"QA await-key ([\w-]+) (rshift|lshift)", text):
            name, key = m.group(1), m.group(2)
            if name in handled:
                continue
            handled.add(name)
            ok = False
            for attempt in range(6):
                r = subprocess.run([KEYPOST, str(proc.pid), key], capture_output=True, text=True)
                print("[qa] keypost %s %s: %s" % (name, key, (r.stdout or r.stderr).strip()), flush=True)
                if r.returncode == 0:
                    ok = True
                    break
                time.sleep(1)
            if ok:
                time.sleep(0.6)
                open(os.path.join(out, "key-%s.done" % name), "w").write(key)
        if proc.poll() is not None:
            result = "EXITED %s" % proc.returncode
            break
        if time.time() - start > args.timeout:
            proc.terminate()
            result = "TIMEOUT"
            break
    # Minecraft's launcher log config writes XML to stdout: keep each "QA ..." message, without the markup.
    lines = [m.group(1) for m in re.finditer(r"QA (.*?)(?:\]\]>|$)", open(log_path, errors="replace").read(), re.M)]
    tour = os.path.join(out, "tour.log")
    open(tour, "w").write("\n".join(lines) + "\n")
    shots = os.path.join(out, "screenshots")
    count = len(os.listdir(shots)) if os.path.isdir(shots) else 0
    print("[qa] result %s after %d s; screenshots: %d" % (result, time.time() - start, count), flush=True)
    failed = 0
    finished = False
    for body in lines:
        if body.startswith(("PASS", "FAIL", "DONE")):
            print("[qa]   " + body, flush=True)
        failed += body.startswith("FAIL")
        finished |= body.startswith("DONE")
    sys.exit(0 if finished and failed == 0 else 1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generates src/gamesettings/tables.rs: the per-version Minecraft facts that Game settings & sync relies on,
read from the real game jars instead of written by hand.

What it reads, for each version, from the Mojang-named jars Fabric Loom keeps in the Gradle cache
(~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-{clientonly,common}-deobf/<v>/ for 26.x, and
minecraft-{clientonly,common}/<v>-loom.mappings.<...>/ for the 1.21.x builds, which use layered Mojang mappings):
  - version.json: the data version (`world_version`), which options.txt's `version:` line holds.
  - net.minecraft.client.Options (javap -c): the option keys `processOptions` reads and writes, the vanilla key
    mappings created in the constructor with their default keys, and a normalised fingerprint of how each
    option is constructed (range, default, codec), so a key whose definition differs from the newest family
    that has it is listed instead of assumed to be the same.
  - net.minecraft.client.GraphicsPreset.apply: the options a graphics preset overwrites at every start.
  - com.mojang.blaze3d.platform.InputConstants(+$Type): every registered key name, the prefixes that accept a
    numeric fallback (`key.keyboard.<n>`), the "unknown" key, and whether input is GLFW or SDL3.
  - net.minecraft.util.datafix.DataFixers: the options.txt data fixers and their schema versions.
  - The enums whose serialised values options.txt stores (graphics preset, clouds, particles, ...).
  - assets/minecraft/lang/en_us.json: English labels for key names and key mappings.

Versions with identical tables form one family (26.1, 26.1.1 and 26.1.2 are one; each 1.21.x release is its
own); the script refuses to group versions whose tables differ. Nothing is downloaded and nothing outside the
output file is written. 1.8.9 isn't read here: its options.txt is a different format, translated by
`legacy189.rs`.

Usage (from app/src-tauri):
    python3 -I -X utf8 tools/gen_mc_tables.py [--loom <dir>] [--out src/gamesettings/tables.rs]
Requires a JDK's `javap` and `unzip` on PATH. Rerun it, then `cargo test`, whenever a version is added; a new
family also needs its entry in versions.rs and its overrides (if any) in registry.rs.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import OrderedDict

VERSIONS = ["1.21.1", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"]
DEFAULT_LOOM = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft")

# Enums whose serialised form options.txt stores: (classes it has lived in, oldest place last; "string"|"int";
# index of the value among the constructor's string or int arguments, counting the constant's own name /
# ordinal as 0). A version without any of the classes simply doesn't have the enum (1.21.1 has no graphics
# preset, 1.21.10 no texture filtering method).
ENUMS = [
    (["net.minecraft.client.GraphicsPreset"], "string", 1),
    (["net.minecraft.client.CloudStatus"], "string", 1),
    (["net.minecraft.client.PrioritizeChunkUpdates"], "int", 1),
    (["net.minecraft.client.TextureFilteringMethod"], "int", 1),
    (["net.minecraft.client.NarratorStatus"], "int", 1),
    (["net.minecraft.client.PreferredGraphicsApi"], "string", 1),
    (["net.minecraft.server.level.ParticleStatus", "net.minecraft.client.ParticleStatus"], "int", 1),
    (["net.minecraft.world.entity.player.ChatVisiblity"], "int", 1),
    (["net.minecraft.world.entity.HumanoidArm"], "string", 1),
    (["net.minecraft.sounds.SoundSource"], "string", 1),
    (["net.minecraft.world.entity.player.PlayerModelPart"], "string", 1),
]

STRING = re.compile(r"//\s*String (.*)$")
INT_PUSH = re.compile(r"\b(?:iconst_(m1|\d)|bipush\s+(-?\d+)|sipush\s+(-?\d+)|ldc(?:_w)?\s+#\d+\s+//\s*int\s+(-?\d+))\b")


def run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def jar(loom, kind, version):
    """The Mojang-named jar: `minecraft-<kind>-deobf/<v>/` for versions Loom deobfuscates on its own (26.x), or
    `minecraft-<kind>/<v>-loom.mappings.<...>/` for versions built with layered Mojang mappings (1.21.x)."""
    path = os.path.join(loom, f"minecraft-{kind}-deobf", version, f"minecraft-{kind}-deobf-{version}.jar")
    if os.path.isfile(path):
        return path
    folder = os.path.join(loom, f"minecraft-{kind}")
    if os.path.isdir(folder):
        for name in sorted(os.listdir(folder)):
            if name.startswith(f"{version}-loom.mappings."):
                candidate = os.path.join(folder, name, f"minecraft-{kind}-{name}.jar")
                if os.path.isfile(candidate):
                    return candidate
    sys.exit(f"missing jar: {path} (or a layered-mappings jar for {version} under {folder})")


def javap(jar_path, cls):
    try:
        return run(["javap", "-c", "-p", "-cp", jar_path, cls])
    except subprocess.CalledProcessError:
        return None


def methods(text):
    """Splits javap -c output into {method header: [instruction lines]}."""
    out = OrderedDict()
    current = None
    for line in text.splitlines():
        if line.startswith("  ") and not line.startswith("    ") and line.rstrip().endswith(";"):
            current = line.strip()
            out[current] = []
        elif current is not None and re.match(r"^\s+\d+: ", line):
            out[current].append(line)
    return out


def method(text, needle):
    for header, body in methods(text).items():
        if needle in header:
            return body
    return []


def int_of(line):
    m = INT_PUSH.search(line)
    if not m:
        return None
    if m.group(1) is not None:
        return -1 if m.group(1) == "m1" else int(m.group(1))
    return int(next(g for g in m.groups()[1:] if g is not None))


def normalise(line):
    """An instruction without offsets or constant-pool indices, so the same code compares equal across versions."""
    text = re.sub(r"^\s*\d+:\s*", "", line)
    text = re.sub(r"#\d+(,\s*\d+)?", "", text)
    text = re.sub(r"\b(if\w*|goto(_w)?)\s+\d+", r"\1", text)
    text = re.sub(r"InvokeDynamic\s*:", "InvokeDynamic:", text)
    # What runs when an option changes in game (a Consumer in 26.1, a ValueUpdateListener later; NO_ACTION or an
    # empty lambda) doesn't affect what options.txt holds, so it isn't part of the comparison.
    listener = r"(Ljava/util/function/Consumer;|Lnet/minecraft/client/OptionInstance\$ValueUpdateListener;)"
    if re.search(r"invokedynamic .*\)" + listener + r"$", text) or "OptionInstance.NO_ACTION:" in text:
        return "<listener>"
    text = re.sub(listener, "<listener>", text)
    # How an option is captioned and explained on screen (tooltips, caption functions, translated texts) doesn't
    # affect what options.txt holds either; 1.21.x builds them with other helpers and overloads than 26.x. A helper
    # that takes a translation key is marked, so the key loaded for it is left out too.
    if re.search(r"(invoke\w+|getstatic) .*\)?" + PRESENTATION + r"$", text):
        return "<presentation(String)>" if re.search(r":\(Ljava/lang/String;\)", text) else "<presentation>"
    text = re.sub(PRESENTATION, "", text)
    return re.sub(r"\s+", " ", text).strip()


PRESENTATION = (r"(Lnet/minecraft/client/OptionInstance\$TooltipSupplier;|Lnet/minecraft/client/OptionInstance\$CaptionBasedToString;"
                r"|Lnet/minecraft/network/chat/Component;|Lnet/minecraft/client/gui/components/Tooltip;)")


ENUM_ID_CODEC = [
    "getstatic // Field com/mojang/serialization/Codec.INT:Lcom/mojang/serialization/codecs/PrimitiveCodec;",
    "invokedynamic // InvokeDynamic:apply:()Ljava/util/function/Function;",
    "invokedynamic // InvokeDynamic:apply:()Ljava/util/function/Function;",
    "invokeinterface // InterfaceMethod com/mojang/serialization/codecs/PrimitiveCodec.xmap:(Ljava/util/function/Function;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;",
]

# Enums that moved package between versions, by their newest place (their values are compared through the enum
# tables, so the move itself changes nothing in options.txt).
MOVED_CLASSES = {"net/minecraft/client/ParticleStatus": "net/minecraft/server/level/ParticleStatus"}


def comparable(segment):
    """How one option is constructed (its normalised instructions), without what doesn't change what options.txt
    holds:
    - presentation: the caption key (the first text the option loads; options.txt names the option by the key in
      `processOptions`, compared on its own), tooltips, caption functions and the translation keys they load;
    - listeners, with the `this` a listener lambda captures, and whether the option has one at all;
    - an enum's id codec written inline (`Codec.INT.xmap(byId, getId)`, 1.21.10 and older) or as the enum's own
      `LEGACY_CODEC`, which 26.x defines as exactly that (both read and write the id with the same WRAP strategy;
      the ids themselves are compared through the enum tables), and an enum's package move;
    - the `discrete` flag 26.x passes to `IntRange.xmap`, which only decides how the slider steps with the arrow
      keys (the codec and validation don't read it);
    - a value used only while a `SharedConstants.DEBUG_*` switch is on, which is always off in released games."""
    kept = []
    caption_seen = False
    i = 0
    while i < len(segment):
        line = segment[i]
        following = segment[i + 1] if i + 1 < len(segment) else ""
        i += 1
        if line.startswith("<presentation") or line == "<listener>":
            continue
        if line == "aload_0" and following == "<listener>":
            continue
        if re.fullmatch(r"ldc(_w)? // String [\w.]+", line):
            if following == "<presentation(String)>":
                continue
            if not caption_seen:
                caption_seen = True
                continue
        # `DEBUG_X ? a : b` compiles to getstatic, ifeq, <a>, goto, <b>: only b is ever used.
        if re.fullmatch(r"getstatic // Field net/minecraft/SharedConstants\.DEBUG_\w+:Z", line) and segment[i:i + 1] == ["ifeq"] and segment[i + 2:i + 3] == ["goto"]:
            i += 3
            continue
        if segment[i - 1:i - 1 + len(ENUM_ID_CODEC)] == ENUM_ID_CODEC:
            kept.append("<enum id codec>")
            i += len(ENUM_ID_CODEC) - 1
            continue
        if re.fullmatch(r"getstatic // Field [\w/$]+\.LEGACY_CODEC:Lcom/mojang/serialization/Codec;", line):
            kept.append("<enum id codec>")
            continue
        if line in ("iconst_0", "iconst_1") and "IntRange.xmap:(Ljava/util/function/IntFunction;Ljava/util/function/ToIntFunction;Z)" in following:
            continue
        line = line.replace("ToIntFunction;Z)", "ToIntFunction;)").replace("<listener>", "")
        for old, new in MOVED_CLASSES.items():
            line = line.replace(old, new)
        kept.append(line)
    return kept


def fingerprint(segment):
    """A short hash of `comparable(segment)`: equal when two versions construct the option the same way."""
    return hashlib.sha256("\n".join(comparable(segment)).encode()).hexdigest()[:16]


def read_version_json(client_jar):
    data = json.loads(run(["unzip", "-p", client_jar, "version.json"]))
    return data["id"], int(data["world_version"])


def options_tables(client_jar):
    text = javap(client_jar, "net.minecraft.client.Options")
    if text is None:
        sys.exit(f"no net.minecraft.client.Options in {client_jar}")
    all_methods = methods(text)

    # Option keys in processOptions (+ processDumpedOptions where it exists), and the field each one reads.
    keys, key_field = [], {}
    for header, body in all_methods.items():
        if not re.search(r"\bprocess(Dumped)?Options\(", header):
            continue
        pending = None
        for line in body:
            s = STRING.search(line)
            if s and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", s.group(1).strip()):
                pending = s.group(1).strip()
                keys.append(pending)
                continue
            f = re.search(r"getfield .*// Field (\w+):", line)
            if f and pending and pending not in key_field:
                key_field[pending] = f.group(1)
                pending = None

    # Getter name -> field (GraphicsPreset.apply calls getters).
    getter_field = {}
    for header, body in all_methods.items():
        m = re.match(r"public net\.minecraft\.client\.OptionInstance<[^>]*> (\w+)\(\);", header)
        if m and len(body) == 3:
            f = re.search(r"getfield .*// Field (\w+):", body[1])
            if "aload_0" in body[0] and f and "areturn" in body[2]:
                getter_field[m.group(1)] = f.group(1)

    # Constructor: a fingerprint per OptionInstance field, and the vanilla key mappings.
    init = next((b for h, b in all_methods.items() if h.startswith("public net.minecraft.client.Options(")), [])
    field_print, segment = {}, []
    mappings = []
    current = None
    for line in init:
        norm = normalise(line)
        segment.append(norm)
        f = re.search(r"putfield .*// Field (\w+):Lnet/minecraft/client/OptionInstance;", line)
        if f:
            field_print[f.group(1)] = fingerprint(segment)
            segment = []
            continue
        if re.search(r"putfield", line):
            segment = []
        if re.search(r"new .*// class net/minecraft/client/(Toggle)?KeyMapping$", line):
            current = {"name": None, "type": None, "code": None, "unknown": False}
            continue
        if current is not None:
            s = STRING.search(line)
            if s and current["name"] is None:
                current["name"] = s.group(1).strip()
                continue
            t = re.search(r"getstatic .*InputConstants\$Type\.(\w+):", line)
            if t and current["code"] is None and not current["unknown"]:
                current["type"] = t.group(1)
                continue
            if re.search(r"getstatic .*InputConstants\.UNKNOWN:", line) and current["code"] is None:
                current["unknown"] = True
                continue
            if current["name"] is not None and current["code"] is None and not current["unknown"]:
                n = int_of(line)
                if n is not None:
                    current["code"] = n
                    continue
            if re.search(r"invokespecial .*KeyMapping\.\"<init>\"", line):
                mappings.append(current)
                current = None
    return {
        "keys": keys,
        "key_field": key_field,
        "getter_field": getter_field,
        "field_print": field_print,
        "mappings": mappings,
    }


def preset_managed(client_jar, getter_field, key_field):
    text = javap(client_jar, "net.minecraft.client.GraphicsPreset")
    if text is None:
        # Before 1.21.11 there is no graphics preset: nothing is overwritten at start.
        return []
    body = method(text, " apply(")
    getters = []
    for line in body:
        m = re.search(r"Method net/minecraft/client/Options\.(\w+):\(\)Lnet/minecraft/client/OptionInstance;", line)
        if m and m.group(1) not in getters:
            getters.append(m.group(1))
    field_key = {f: k for k, f in key_field.items()}
    out = []
    for g in getters:
        field = getter_field.get(g)
        if field is None or field not in field_key:
            sys.exit(f"GraphicsPreset.apply calls {g}(), which doesn't map to an option key")
        out.append(field_key[field])
    return sorted(out)


def input_tables(client_jar):
    type_text = javap(client_jar, "com.mojang.blaze3d.platform.InputConstants$Type")
    main_text = javap(client_jar, "com.mojang.blaze3d.platform.InputConstants")
    clinit = method(type_text, "static {}")
    prefixes, names = {}, []
    pending_type = pending_name = None
    current_const = None
    for line in clinit:
        s = STRING.search(line)
        if re.search(r"new .*// class com/mojang/blaze3d/platform/InputConstants\$Type$", line):
            current_const = "?"
            continue
        if current_const is not None and s:
            if current_const == "?":
                current_const = s.group(1).strip()
            else:
                prefixes[current_const] = s.group(1).strip()
                current_const = None
            continue
        t = re.search(r"getstatic .*// Field (\w+):Lcom/mojang/blaze3d/platform/InputConstants\$Type;", line)
        if t:
            pending_type = t.group(1)
            continue
        if s:
            pending_name = s.group(1).strip()
            continue
        n = int_of(line)
        if n is not None and pending_name is not None:
            names.append((pending_type, pending_name, n))
            continue
        if "addKey" in line:
            pending_name = None
    era = "sdl3" if "org/lwjgl/sdl" in main_text else ("glfw" if "org/lwjgl/glfw" in main_text else None)
    if era is None:
        sys.exit(f"can't tell the input library of {client_jar}")
    unknown = None
    lines = method(main_text, "static {}")
    for i, line in enumerate(lines):
        if "putstatic" in line and "UNKNOWN:" in line:
            t = re.search(r"InputConstants\$Type\.(\w+):", lines[i - 3])
            unknown = (t.group(1) if t else None, int_of(lines[i - 2]))
    if unknown is None:
        sys.exit("InputConstants.UNKNOWN not found")
    return {"prefixes": prefixes, "names": names, "era": era, "unknown": unknown}


def options_fixers(common_jar):
    text = javap(common_jar, "net.minecraft.util.datafix.DataFixers")
    body = method(text, "addFixers(")
    slot_version, ints, fixers = {}, [], []
    waiting = None
    last_schema = None
    for line in body:
        n = int_of(line)
        if n is not None:
            ints.append(n)
        if "addSchema" in line:
            last_schema = ints[0] if ints else None
            ints = []
            continue
        m = re.search(r"astore(?:_w)?\s+(\d+)|astore_(\d)", line)
        if m and last_schema is not None:
            slot_version[int(m.group(1) or m.group(2))] = last_schema
            last_schema = None
            continue
        if re.search(r"^\s*\d+: aload_0\s*$", line):
            ints = []
        c = re.search(r"new .*// class net/minecraft/util/datafix/fixes/(Options\w+)$", line)
        if c:
            waiting = c.group(1)
            continue
        if waiting:
            m = re.search(r"aload(?:_w)?\s+(\d+)|aload_(\d)", line)
            if m and "dup" not in line:
                slot = int(m.group(1) or m.group(2))
                if slot in slot_version:
                    fixers.append((waiting, slot_version[slot]))
                    waiting = None
    return fixers


def enum_values(jars, classes, kind, index):
    """The enum's serialised values from whichever of its classes the version has (in either jar), or None."""
    text = cls = None
    for candidate in classes:
        for jar_path in jars.values():
            text = javap(jar_path, candidate)
            if text is not None:
                cls = candidate
                break
        if text is not None:
            break
    if text is None:
        return None
    body = method(text, "static {}")
    values, strings, ints = [], None, None
    simple = cls.rsplit(".", 1)[1]
    for line in body:
        if re.search(rf"new .*// class [\w/$]*{re.escape(simple)}$", line):
            strings, ints = [], []
            continue
        if strings is None:
            continue
        s = STRING.search(line)
        if s:
            strings.append(s.group(1).strip())
            continue
        n = int_of(line)
        if n is not None:
            ints.append(n)
            continue
        if re.search(r"invokespecial .*\"<init>\"", line):
            values.append(strings[index] if kind == "string" else str(ints[index]))
            strings = ints = None
    return values


def en_us(client_jar):
    return json.loads(run(["unzip", "-p", client_jar, "assets/minecraft/lang/en_us.json"]))


def key_name_for(input_tables_, type_name, code, unknown_flag):
    if unknown_flag:
        type_name, code = input_tables_["unknown"]
    type_name = type_name or ("KEYSYM" if input_tables_["era"] == "glfw" else "KEYBOARD")
    for t, name, value in input_tables_["names"]:
        if t == type_name and value == code:
            return name
    prefix = input_tables_["prefixes"][type_name]
    return f"{prefix}.{code}"


def collect(loom, version):
    client = jar(loom, "clientonly", version)
    common = jar(loom, "common", version)
    vid, data_version = read_version_json(client)
    if vid != version:
        sys.exit(f"{client} says it is {vid}")
    opts = options_tables(client)
    inputs = input_tables(client)
    jars = {"client": client, "common": common}
    enums = OrderedDict()
    for classes, kind, index in ENUMS:
        values = enum_values(jars, classes, kind, index)
        if values is not None:
            enums[classes[0].rsplit(".", 1)[1]] = values
    mappings = [(m["name"], key_name_for(inputs, m["type"], m["code"], m["unknown"])) for m in opts["mappings"]]
    fixers = options_fixers(common)
    key_print = {k: opts["field_print"].get(f) for k, f in opts["key_field"].items()}
    return {
        "version": version,
        "data_version": data_version,
        "era": inputs["era"],
        "option_keys": opts["keys"],
        "key_print": key_print,
        "preset_managed": preset_managed(client, opts["getter_field"], opts["key_field"]),
        "mappings": mappings,
        "key_names": sorted({n for _, n, _ in inputs["names"]}),
        "key_prefixes": sorted(set(inputs["prefixes"].values())),
        "options_fixers": fixers,
        "fix_floor": max(v for _, v in fixers),
        "enums": enums,
        "lang": en_us(client),
    }


FAMILY_FIELDS = ["era", "option_keys", "key_print", "preset_managed", "mappings", "key_names", "key_prefixes",
                 "options_fixers", "enums"]


def family_of(version):
    """26.x versions are grouped by their first two numbers (26.1, 26.1.1 and 26.1.2 are one family); each 1.x
    release is its own family, since 1.21.1, 1.21.10 and 1.21.11 differ in their options."""
    parts = version.split(".")
    return version if parts[0] == "1" else ".".join(parts[:2])


def rust_str(s):
    return json.dumps(s, ensure_ascii=False)


def rust_list(items, indent="        ", per_line=4):
    if not items:
        return "&[]"
    lines = []
    for i in range(0, len(items), per_line):
        lines.append(indent + ", ".join(items[i:i + per_line]) + ",")
    return "&[\n" + "\n".join(lines) + "\n" + indent[:-4] + "]"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--loom", default=DEFAULT_LOOM)
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "src", "gamesettings", "tables.rs"))
    args = parser.parse_args()

    per_version = [collect(args.loom, v) for v in VERSIONS]
    families = OrderedDict()
    for data in per_version:
        families.setdefault(family_of(data["version"]), []).append(data)
    for fid, members in families.items():
        first = members[0]
        for other in members[1:]:
            for field in FAMILY_FIELDS:
                if first[field] != other[field]:
                    sys.exit(f"{first['version']} and {other['version']} differ in {field}; they can't share family {fid}")

    newest_id = list(families)[-1]
    report = []

    def reference_print(key):
        # How the newest family that has the key builds it: 26.3 for most keys, 26.2 for one 26.3 dropped (raw
        # input), so a key only older families have is checked between them too.
        for members in reversed(list(families.values())):
            if key in members[0]["key_print"]:
                return members[0]["key_print"][key]
        return None

    for fid, members in families.items():
        data = members[0]
        changed = sorted(k for k in data["option_keys"] if data["key_print"].get(k) != reference_print(k))
        data["changed"] = changed
        report.append(f"{fid}: {', '.join(m['version'] + '=' + str(m['data_version']) for m in members)}; input {data['era']}; "
                      f"{len(data['option_keys'])} option keys, {len(data['mappings'])} key mappings, {len(data['key_names'])} key names; "
                      f"options fix floor {data['fix_floor']}; definitions differing from the newest family with the key: {changed or 'none'}")

    lang = OrderedDict()
    for data in reversed(per_version):
        for k, v in data["lang"].items():
            lang.setdefault(k, v)
    all_key_names = sorted({n for data in per_version for n in data["key_names"]})
    all_mappings = sorted({m for data in per_version for m, _ in data["mappings"]})
    key_labels = [(n, lang[n]) for n in all_key_names if n in lang]
    mapping_labels = [(m, lang[m]) for m in all_mappings if m in lang]
    missing = [m for m in all_mappings if m not in lang]
    if missing:
        sys.exit(f"key mappings without an English label: {missing}")

    out = []
    out.append("//! @generated by `tools/gen_mc_tables.py` from the Minecraft jars in the Fabric Loom cache; do not edit by")
    out.append("//! hand. Rerun the script (see its docstring) to refresh or extend it.")
    out.append("//!")
    for line in report:
        out.append(f"//! {line}")
    out.append("")
    out.append("/// The keyboard and mouse library a family reads keys with; numeric fallback key names mean different keys")
    out.append("/// in each.")
    out.append("#[derive(Clone, Copy, Debug, PartialEq, Eq)]")
    out.append("pub enum Input {")
    out.append("    Glfw,")
    out.append("    Sdl3,")
    out.append("}")
    out.append("")
    out.append("/// Facts about one verified family of Minecraft versions, read from its jars.")
    out.append("pub struct FamilyData {")
    out.append("    /// The family's name, e.g. \"26.3\".")
    out.append("    pub id: &'static str,")
    out.append("    /// Each version in the family with its data version (`world_version`, options.txt's `version:`).")
    out.append("    pub versions: &'static [(&'static str, u32)],")
    out.append("    pub input: Input,")
    out.append("    /// The newest options.txt data fixer's schema: a file with an older `version:` would have Flint's values")
    out.append("    /// rewritten by Minecraft's upgrade at load.")
    out.append("    pub options_fix_floor: u32,")
    out.append("    /// Every key `Options.processOptions` reads and writes, apart from the `key_`, `soundCategory_` and")
    out.append("    /// `modelPart_` families.")
    out.append("    pub option_keys: &'static [&'static str],")
    out.append("    /// Vanilla key mappings (`key_<name>` in options.txt) and the key each is bound to by default.")
    out.append("    pub key_mappings: &'static [(&'static str, &'static str)],")
    out.append("    /// Key names `InputConstants.getKey` knows by name.")
    out.append("    pub key_names: &'static [&'static str],")
    out.append("    /// Prefixes `InputConstants.getKey` also accepts with a number after them (`key.keyboard.65`).")
    out.append("    pub key_prefixes: &'static [&'static str],")
    out.append("    /// Option keys `GraphicsPreset.apply` overwrites at every start unless the preset is \"custom\".")
    out.append("    pub preset_managed: &'static [&'static str],")
    out.append("    /// The values options.txt stores for the enums the registry uses (`SoundSource` -> \"master\", ...).")
    out.append("    pub enums: &'static [(&'static str, &'static [&'static str])],")
    out.append("    /// Option keys constructed differently (range, default or codec) from the newest family that has them")
    out.append(f"    /// ({newest_id} for most).")
    out.append("    pub changed_definitions: &'static [&'static str],")
    out.append("}")
    out.append("")
    out.append(f"pub static FAMILIES: [FamilyData; {len(families)}] = [")
    for fid, members in reversed(list(families.items())):
        data = members[0]
        out.append("    FamilyData {")
        out.append(f"        id: {rust_str(fid)},")
        out.append("        versions: &[" + ", ".join(f"({rust_str(m['version'])}, {m['data_version']})" for m in members) + "],")
        out.append(f"        input: Input::{'Sdl3' if data['era'] == 'sdl3' else 'Glfw'},")
        out.append(f"        options_fix_floor: {data['fix_floor']},")
        out.append("        option_keys: " + rust_list([rust_str(k) for k in data["option_keys"]], "            ", 6) + ",")
        out.append("        key_mappings: " + rust_list([f"({rust_str(m)}, {rust_str(k)})" for m, k in data["mappings"]], "            ", 2) + ",")
        out.append("        key_names: " + rust_list([rust_str(k) for k in data["key_names"]], "            ", 4) + ",")
        out.append("        key_prefixes: &[" + ", ".join(rust_str(p) for p in data["key_prefixes"]) + "],")
        out.append("        preset_managed: " + rust_list([rust_str(k) for k in data["preset_managed"]], "            ", 6) + ",")
        enum_lines = [f"({rust_str(name)}, &[{', '.join(rust_str(v) for v in values)}])" for name, values in data["enums"].items()]
        out.append("        enums: " + rust_list(enum_lines, "            ", 1) + ",")
        out.append("        changed_definitions: &[" + ", ".join(rust_str(k) for k in data["changed"]) + "],")
        out.append("    },")
    out.append("];")
    out.append("")
    out.append("/// English names of keys (from en_us.json), sorted by key name.")
    out.append("pub static KEY_LABELS: &[(&str, &str)] = " + rust_list([f"({rust_str(k)}, {rust_str(v)})" for k, v in key_labels], "    ", 2) + ";")
    out.append("")
    out.append("/// English names of vanilla key mappings (from en_us.json), sorted by mapping name.")
    out.append("pub static MAPPING_LABELS: &[(&str, &str)] = " + rust_list([f"({rust_str(k)}, {rust_str(v)})" for k, v in mapping_labels], "    ", 2) + ";")
    out.append("")
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    for line in report:
        print(line)
    print(f"wrote {os.path.normpath(args.out)}")


if __name__ == "__main__":
    main()

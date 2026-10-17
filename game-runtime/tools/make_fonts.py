"""Regenerates the static TrueType fonts the in-game menu uses from the launcher's own fonts.

Minecraft's font loader needs plain TrueType files at a fixed weight, while the launcher ships
variable WOFF2/TTF files. This instances Inter at 400/500/600 (merging its Latin and Latin Extended
subsets into one file) and Outfit at 700 (the wordmark), then keeps only Latin, Latin Extended-A/B and common
punctuation: Minecraft loads a copy of the font for every text role and GUI scale, so small files
matter. Other scripts fall back to Minecraft's own font. All fonts are SIL Open Font License 1.1.

    python3 -m venv /tmp/fontenv && /tmp/fontenv/bin/pip install fonttools brotli
    /tmp/fontenv/bin/python tools/make_fonts.py
"""

import os
import tempfile

from fontTools import subset
from fontTools.merge import Merger, Options
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "../../app/src/assets/fonts")
TARGET = os.path.join(HERE, "../src/client/resources/assets/flintclient/font")


def instance(path: str, weight: int, dest: str) -> None:
    font = instancer.instantiateVariableFont(TTFont(path), {"wght": weight}, updateFontNames=False)
    font.flavor = None
    font.save(dest)


INTER_UNICODES = "U+0000-024F,U+02C6-02DD,U+2000-206F,U+20AC,U+2122,U+2190-2193,U+2212,U+2026"
OUTFIT_UNICODES = "U+0020-007E,U+00A0-00FF"


def shrink(path: str, unicodes: str) -> None:
    subset.main([path, f"--unicodes={unicodes}", "--layout-features=kern,liga,calt,tnum", f"--output-file={path}"])


def main() -> None:
    with tempfile.TemporaryDirectory() as work:
        for weight, name in [(400, "regular"), (500, "medium"), (600, "semibold")]:
            parts = []
            for script in ["Latin", "LatinExt"]:
                part = os.path.join(work, f"inter-{name}-{script}.ttf")
                instance(os.path.join(SOURCE, f"Inter-{script}.woff2"), weight, part)
                parts.append(part)
            merged = os.path.join(TARGET, f"inter-{name}.ttf")
            Merger(options=Options(drop_tables=["vhea", "vmtx"])).merge(parts).save(merged)
            shrink(merged, INTER_UNICODES)
    # The wordmark only: Outfit Bold, uppercase Latin is all it needs.
    outfit = os.path.join(TARGET, "outfit-bold.ttf")
    instance(os.path.join(SOURCE, "Outfit-Variable.ttf"), 700, outfit)
    shrink(outfit, OUTFIT_UNICODES)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Unpack the web's 14 Nerd Symbols subsets into Android assets, entirely offline.

    python3 -I android/scripts/build-terminal-fonts.py <generated-assets-directory>

Output: fonts/terminal/nerd-symbols-*.ttf plus the existing font notices/licences.
Register the output directory as a generated assets source for :ui:terminal. Monaspace
is reused from :ui:design's resources, not copied or merged with the MIT symbol face.
Needs fontTools and either its Brotli decoder or the existing woff2_decompress executable
(also used by geist-web-subsets.py). Does not download fonts or install dependencies.
"""

import argparse
import io
from pathlib import Path
import shutil
import subprocess
import tempfile

from fontTools.ttLib import TTFont, woff2


REPO = Path(__file__).resolve().parents[2]
FONT_DIR = REPO / "src/web/styles/fonts"
SUBSETS = {
    "material", "octicons", "font-logos", "font-awesome-legacy", "font-awesome",
    "codicons", "devicons", "seti", "weather", "font-awesome-ext", "powerline",
    "pomicons", "misc", "iec-power",
}


def unpack(source, scratch):
    if woff2.haveBrotli:
        return TTFont(source, recalcTimestamp=False)
    decoder = shutil.which("woff2_decompress")
    if decoder is None:
        raise RuntimeError("Need fontTools' Brotli decoder or woff2_decompress on PATH")
    # The native decoder writes beside its input; never let it touch the web source tree.
    local = scratch / source.name
    shutil.copyfile(source, local)
    subprocess.run([decoder, str(local)], check=True, capture_output=True)
    return TTFont(local.with_suffix(".ttf"), recalcTimestamp=False)


def write_if_changed(path, data):
    if path.exists() and path.read_bytes() == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def build(output):
    sources = sorted(FONT_DIR.glob("nerd-symbols-*.woff2"))
    expected = {f"nerd-symbols-{subset}.woff2" for subset in SUBSETS}
    if {source.name for source in sources} != expected:
        raise RuntimeError("Expected the 14 web Nerd Symbols subsets; update TerminalFonts.kt together")

    target = output / "fonts/terminal"
    glyphs = set()
    with tempfile.TemporaryDirectory(prefix="ppm-terminal-fonts-") as scratch:
        for source in sources:
            with unpack(source, Path(scratch)) as font:
                cmap = font.getBestCmap()
                if font.sfntVersion != "\x00\x01\x00\x00" or not cmap:
                    raise RuntimeError(f"Expected a TrueType symbol subset: {source.name}")
                if glyphs.intersection(cmap) or any(0x20 <= code <= 0x7E for code in cmap):
                    raise RuntimeError(f"Overlapping symbols or ordinary text in {source.name}")
                glyphs.update(cmap)
                # Keep outlines, advances, cmaps (including supplementary PUA), and names.
                # No merging: the symbols must precede Monaspace without replacing its text.
                font.flavor = None
                data = io.BytesIO()
                font.save(data)
                write_if_changed(target / source.with_suffix(".ttf").name, data.getvalue())

    # Preserve the repo's upstream attributions, including the individual icon-set terms.
    # Nerd Fonts' patcher is MIT; its bundled icon sets have additional existing licences.
    notices = (REPO / "THIRD-PARTY-NOTICES.md").read_text(encoding="utf-8")
    section = notices.split("<!-- BEGIN nerd-font -->", 1)[1].split("<!-- END nerd-font -->", 1)[0]
    write_if_changed(target / "NOTICE.md", section.strip().encode("utf-8") + b"\n")
    for name in ("MIT", "Apache-2.0", "OFL-1.1", "CC-BY-4.0", "Unlicense"):
        write_if_changed(target / "licenses" / f"{name}.txt", (REPO / "licenses" / f"{name}.txt").read_bytes())
    write_if_changed(
        target / "licenses/Monaspace-OFL.txt",
        (REPO / "android/ui/design/licenses/Monaspace-OFL.txt").read_bytes(),
    )
    print(f"Terminal fonts: {len(sources)} subsets, {len(glyphs)} codepoints -> {target}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Generated assets root (normally under ui/terminal/build)")
    build(parser.parse_args().output.resolve())

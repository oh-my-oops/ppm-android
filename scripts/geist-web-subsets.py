# Writes the web's own Geist files into the app: the variable fonts @fontsource-variable/geist
# splits by unicode-range, one TrueType file per subset and slant.
#
#   python3 -I android/scripts/geist-web-subsets.py node_modules/@fontsource-variable/geist android/ui/design/src/main/res/font
#
# Chrome picks a face for each character by those ranges and shapes each face's run apart, so a
# Vietnamese word is kerned only inside each piece ("một" is 0.6 px wider than one font sets it).
# The app chains the same files, in the same order, so that Android splits the text the same way.
# `woff2_decompress` (google/woff2) unpacks them. The Latin files get the empty U+200B the static
# fonts carry (see add-zero-width-space.py), so the breaks the app writes into a URL stay in the
# Latin run; with no variation of its own, it stays empty at every weight.
import os
import shutil
import subprocess
import sys
import tempfile

from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import Glyph

SUBSETS = ["latin", "latin-ext", "vietnamese", "cyrillic", "cyrillic-ext"]
NAME = "uni200B"


def add_zero_width_space(font):
    if 0x200B in font.getBestCmap():
        return
    for tag in font.keys():
        font[tag]  # every table read before the glyph order changes under it
    order = font.getGlyphOrder() + [NAME]
    font.setGlyphOrder(order)
    glyf = font["glyf"]
    glyf.glyphOrder = order
    glyf.glyphs[NAME] = Glyph()
    font["hmtx"].metrics[NAME] = (0, 0)
    font["gvar"].variations[NAME] = []
    hvar = font["HVAR"].table
    store = hvar.VarStore.VarData[0]
    store.Item.append([0] * store.VarRegionCount)
    store.ItemCount = len(store.Item)
    hvar.AdvWidthMap.mapping[NAME] = len(store.Item) - 1
    for table in font["cmap"].tables:
        if table.isUnicode():
            table.cmap[0x200B] = NAME


source, target = sys.argv[1], sys.argv[2]
with tempfile.TemporaryDirectory() as scratch:
    for subset in SUBSETS:
        for slant in ("normal", "italic"):
            name = f"geist-{subset}-wght-{slant}"
            shutil.copy(os.path.join(source, "files", name + ".woff2"), scratch)
            subprocess.run(["woff2_decompress", os.path.join(scratch, name + ".woff2")], check=True, capture_output=True)
            font = TTFont(os.path.join(scratch, name + ".ttf"))
            if subset == "latin":
                add_zero_width_space(font)
            out = "geist_web_" + subset.replace("-", "_") + ("_italic" if slant == "italic" else "") + ".ttf"
            font.save(os.path.join(target, out))
            print(out)

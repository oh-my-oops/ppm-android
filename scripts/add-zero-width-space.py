# Adds an empty, zero-width glyph for U+200B ZERO WIDTH SPACE to CFF OpenType fonts, in place.
#
#   python3 -I android/scripts/add-zero-width-space.py android/ui/design/src/main/res/font/geist_*.otf
#
# The app writes U+200B where Chrome's `word-break: break-all` may end a line. A font without the
# character hands every one of them to a fallback font, which cuts the text into separate runs:
# Geist's kerning is lost at each, and a URL set that way came out 2.6 % wider than the web's.
# With the glyph the run stays in Geist and HarfBuzz kerns across it, as it does across any
# default-ignorable character. Geist's OFL reserves no font name, so the modified font keeps its
# own; Monaspace's reserves its, so it is not patched (it has no kerning to lose).
import sys

from fontTools.misc.psCharStrings import T2CharString
from fontTools.ttLib import TTFont

NAME = "uni200B"

for path in sys.argv[1:]:
    font = TTFont(path, recalcBBoxes=False, recalcTimestamp=False)
    if 0x200B in font.getBestCmap():
        print(f"{path}: already has U+200B")
        continue
    cff = font["CFF "].cff
    top = cff.topDictIndex[0]
    charstrings = top.CharStrings
    charstrings.charStringsIndex.append(T2CharString(program=["endchar"], private=top.Private, globalSubrs=cff.GlobalSubrs))
    charstrings.charStrings[NAME] = len(charstrings.charStringsIndex) - 1
    # The glyph order is the CFF charset itself: one append extends both.
    order = font.getGlyphOrder()
    order.append(NAME)
    font.setGlyphOrder(order)
    font["hmtx"][NAME] = (0, 0)
    for table in font["cmap"].tables:
        if table.isUnicode():
            table.cmap[0x200B] = NAME
    font.save(path)
    print(f"{path}: added U+200B")

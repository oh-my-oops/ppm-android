# Adds an empty, zero-width glyph for U+200B ZERO WIDTH SPACE to CFF OpenType fonts, in place.
#
#   python3 -I android/scripts/add-zero-width-space.py android/ui/design/src/main/res/font/geist_*.otf
#   python3 -I android/scripts/add-zero-width-space.py --rename "Monaspace Argon=PPM Mono" \
#       android/ui/design/src/main/res/font/monaspace_argon_*.otf
#
# The app writes U+200B where Chrome's `word-break: break-all` may end a line. A font without the
# character hands every one of them to a fallback font, which cuts the text into separate runs:
# Geist's kerning is lost at each, and a URL set that way came out 2.6 % wider than the web's.
# Monaspace has no kerning, but its texture healing is contextual alternates (`calt`), which stop
# at a run's edge too, so a broken command lost them at nearly every letter. With the glyph the
# run stays in one font, and HarfBuzz kerns and matches contexts across it, as it does across any
# default-ignorable character. Geist's OFL reserves no font name, so the modified font keeps its
# own; Monaspace's reserves "Monaspace" and "Argon", so a modified copy is renamed (--rename), in
# the names a font is known by and not in its copyright, trademark and licence notices.
import sys

from fontTools.misc.psCharStrings import T2CharString
from fontTools.ttLib import TTFont

NAME = "uni200B"

# The records a font is known by: family, unique id, full name, version, PostScript name,
# typographic family, compatible full name.
NAME_IDS = {1, 3, 4, 5, 6, 16, 18}


def rename(font, old, new):
    """Every name the font is known by, with [old] (or its PostScript spelling) replaced by [new]."""
    pairs = [(old, new), (old.replace(" ", ""), new.replace(" ", ""))]
    def swap(text):
        for a, b in pairs:
            text = text.replace(a, b)
        return text
    for record in font["name"].names:
        if record.nameID in NAME_IDS:
            record.string = swap(record.toUnicode())
    cff = font["CFF "].cff
    cff.fontNames = [swap(name) for name in cff.fontNames]
    top = cff.topDictIndex[0]
    for key in ("FullName", "FamilyName"):
        if hasattr(top, key):
            setattr(top, key, swap(getattr(top, key)))


args = sys.argv[1:]
renaming = None
if args[:1] == ["--rename"]:
    renaming = args[1].split("=", 1)
    args = args[2:]

for path in args:
    font = TTFont(path, recalcBBoxes=False, recalcTimestamp=False)
    if renaming:
        rename(font, *renaming)
    if 0x200B in font.getBestCmap():
        if renaming:
            font.save(path)
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

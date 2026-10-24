# Writes the font an inline code's edges are set in: one empty glyph, U+E000, one em wide, with no
# ascent or descent, so it takes room on the line and none above or below it.
#
#   python3 -I android/scripts/code-edge-font.py android/ui/design/src/main/res/font/code_edge.ttf
#
# An edge was an inline placeholder at first, but StaticLayout swaps a placeholder's character for
# U+FFFC before it breaks lines, and a line may end on either side of that (UAX #14 CB): the end
# edge went on alone to the next line where Chrome kept the code whole. A private-use character in
# a font of its own stays what it is, which the line breaker takes for a letter.
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

builder = FontBuilder(1000, isTTF=True)
builder.setupGlyphOrder([".notdef", "edge"])
builder.setupCharacterMap({0xE000: "edge"})
builder.setupGlyf({".notdef": TTGlyphPen(None).glyph(), "edge": TTGlyphPen(None).glyph()})
builder.setupHorizontalMetrics({".notdef": (0, 0), "edge": (1000, 0)})
builder.setupHorizontalHeader(ascent=0, descent=0)
builder.setupNameTable({"familyName": "PPM Code Edge", "styleName": "Regular"})
builder.setupOS2(sTypoAscender=0, sTypoDescender=0, sTypoLineGap=0, usWinAscent=0, usWinDescent=0, fsType=0)
builder.setupPost()
builder.save(sys.argv[1])

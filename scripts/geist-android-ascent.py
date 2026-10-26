# Lowers the ascent Android reads from every Geist file in the app, from 1005 to 970 of its 1000
# units (`hhea` and the OS/2 typo ascender, which `USE_TYPO_METRICS` makes the one Skia takes):
#
#   python3 -I android/scripts/geist-android-ascent.py android/ui/design/src/main/res/font
#
# Compose's line-height span cannot put a baseline above the font's own ascent, and Chrome's ascent
# is often a pixel less than Android's rounding of the same 1005 (`AscentDescentWithHacks` takes one
# off whenever the descent rounds down): 12 px text on 16 px lines sat a pixel low at 2x and 2.625x,
# 11 on 14.67 and 9 on 12 at 2x, others at 2.75x and 3.5x. Where the baseline goes is worked out
# from Chrome's metrics (`PpmFonts.chromeTopRatio`), so the lower ascent only gives the span the
# room to put it there; at 970 it reaches Chrome's baseline for every size the app sets, at every
# density from 2 to 4. Glyphs are untouched. Run it again after geist-web-subsets.py.
import glob
import os
import sys

from fontTools.ttLib import TTFont

ASCENT = 970

for path in sorted(glob.glob(os.path.join(sys.argv[1], "geist*"))):
    font = TTFont(path)
    if font["hhea"].ascent == ASCENT and font["OS/2"].sTypoAscender == ASCENT:
        continue
    font["hhea"].ascent = ASCENT
    font["OS/2"].sTypoAscender = ASCENT
    font.save(path)
    print("lowered", os.path.basename(path))

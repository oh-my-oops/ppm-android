This module rebuilds the Kotlin layer of `org.connectbot:termlib:0.3.13`. Upstream sources,
resources and JNI are generated into `build/generated/termlib`; none are vendored in source
control. `android/scripts/patch-terminal-engine.py` pins the sources JAR, original AAR and
POM by SHA-256 and rejects changed artifacts or patch locations before writing output.
After the core patches it imports `scripts/terminal_ime_patch.py` by path, with independent
guards for all three IME targets. Both scripts are Gradle task inputs; Python runs with
`-B -I` to prevent bytecode-cache writes and isolate the generator from environment packages.
It has no network or Python package dependencies. Gradle resolves the three Maven artifacts;
`-PtermlibSources=/path/to/sources.jar` can supply an already-downloaded, hash-checked JAR.

Add `include(":ui:terminal-engine")` to Android settings and replace the original termlib
dependency in `:ui:terminal` with `api(project(":ui:terminal-engine"))`. The package remains
`org.connectbot.terminal`; do not also package the original AAR's classes. POM dependencies
and Compose BOM versions are reproduced in this module's build file. Upstream's native
classes, JNI signatures, cell wire format and all four `.so` files are unchanged. Native
stripping is disabled for these files. Consumer ProGuard rules, resource strings and any
packaged legal notices are retained. The published AAR contains no full license texts;
the generated assets additionally carry Apache-2.0 and the MIT notice for the xterm port.

The added APIs are:

- `TerminalEmulatorFactory.create(maxScrollbackLines = 50_000, maxScrollbackBytes = Runtime.getRuntime().maxMemory() / 6, ...)`: non-negative, zero
  disables retained history. Explicit small values work without changing native code.
  A row of that history costs what it holds, not what it is wide, and `maxScrollbackBytes` evicts the oldest rows when per-cell-colour output exceeds a sixth of the heap before reaching 50,000 rows. Upstream's `PackedCells` kept four arrays as long as the row is
  wide: 2,198 B a row at 105 columns whatever the row held, so one terminal's 50,000 rows were 104 MB of the 192 MB an app's heap is
  capped at, and the 1 MB of `seq` output a server replays on a reconnect built 50,000 rows (102 MB, 4.8 s). `src/main/.../PackedCells.kt`
  replaces the generated file (`REPLACED` in `patch-terminal-engine.py`, and `"replaced"` in `PROVENANCE.json`): the text of the cells
  up to the row's trailing run of one plain character (a line's blank tail, a rule of dashes), which is kept once; the colours and flags
  as runs of the cells that share them; and, only for a row with a wide, combining or emoji cell, where each cell's text starts and its
  width. Every method answers as upstream's did, which `PackedCellsTest` holds against `LegacyPackedCells`, upstream's class as it was
  shipped, over rows made the ways the emulator makes them (one read, a read in pieces, a copy around the cells that changed, `from`,
  `empty`). `TerminalLine`'s three `by lazy` were 120 B a row between them and are fields of the row now (`terminal_line`). Measured on
  a device (`-e only memory` of `EngineInstrumentation`), per row at 105 columns: `seq` output 189 B (9 MB for 50,000 rows), a full-width
  row 389 B (18 MB), a full-width row of 20 colour runs 613 B (29 MB), and the worst case, every cell in a colour of its own, 2,070 B
  (about 100 MB, as before: a run is 16 B), which only an image drawn in terminal cells produces. The replay of 1 MB of `seq` is 7 MB
  and 2.4 s. A history that is full holds the rows an old snapshot still names for as long as the snapshot is the published one
  (3,208 of 50,000 rows in the flood measured, 6%), which at these sizes is 1 MB.
- `emulator.getCursorPosition()`: screen-relative `(row, column)`, zero-based.
- `emulator.getBufferSnapshot()`: one coherent read with `lines`, absolute `cursorRow`,
  `cursorColumn`, `screenStartRow`, and `alternateScreen`. Physical rows include retained
  history before the active screen. Alternate-screen reads exclude primary-screen history.
- `emulator.getScreenSnapshot()`: the same read without the history, for what is read on every key. It holds the
  screen's rows and, above them, only the history rows the screen's first line wraps on from, so the cursor's line is
  whole in it and its cost does not grow with the scrollback (a full read copies up to 50,000 rows while the
  parser's lock is held). `firstRow` says where its first row is in the whole buffer, so `firstRow + cursorRow` is
  the cursor's row in a full read taken at the same moment.
- `emulator.getBufferLines()`: `TerminalBufferLine(text, isWrapped)` values. `isWrapped`
  means this row continues onto the next row, matching libvterm's outgoing soft-wrap flag;
  this is the opposite row convention from xterm's incoming `IBufferLine.isWrapped`.
  A row that scrolled into the history keeps the flag it had on the screen: the shipped JNI sends
  the flag of the row *after* the one it pushes (libvterm shifts its line infos before it calls the
  screen back, and `termSbPushline` reads index 1 after that), so `ScrollbackWrap` hands each pushed
  row the flag that came with the push before it. On the screen, libvterm sets a row's flag when text reaches the row below it and
  the JNI hands it over only with a read of a cell of *that* row, so a line that went on in a later write
  than its first row (a prompt that wraps while it is typed) kept "not wrapped" until `refreshRowsAbove`
  (`WrapFlagRefresh.kt`) made each update read the row above the ones it damaged as well.
  A resize needs more, because libvterm (reflow is off in the shipped JNI) clears the flag of every row when it
  resizes the screen, and the JNI reads one flag for the whole batch of rows a shrink pushes, from the line infos about to
  be cleared. A wrapped line came back from a keyboard that opened and closed as two lines, and every row the shrink pushed
  took the flag of the first. The emulator now carries the screen's own flags through the resize (`ResizedRows`,
  `TerminalLine.wrapKept`): each pushed row takes the flag the screen had for it, the rows that stay or come back from the
  history keep theirs, and a kept flag lasts until a write changes that row, or the library reports the row as wrapped itself
  (a scroll, a new colour scheme, after which the library gives every cell in the new colours, or one rectangle that only
  covers the row does not end it). Limits: a resize in columns drops the flags of the
  screen, since the rows are cut or padded and no longer break where they did (porting xterm's reflow, which joins them again,
  is a later commit).
- The shell's screen under the alternate screen. libvterm keeps the shell's screen aside while `less` or vim has the alternate one up,
  but a resize then pushes the top rows of the *shell's* screen into the history and reads their cells through the screen it shows (the
  alternate one), so the history was given the alternate screen's rows and a screen made taller again took them back as the shell's: a
  shell that read `P0..P9` came back from `less` and a few resizes reading `ALT0..ALT4 P5..P9`. The library switches screens before it
  tells the callback, so the shell's screen can be read from it only until then. `WriteCuts` ends a piece just *before* the `h` of a
  `CSI ? Pm h` with 1047 or 1049 among its arguments (`beforeEntry`; not 47, which is no mode of this libvterm), and there the
  emulator catches the mirror up and keeps its lines, with cells, wraps and links (`saveShell`, `savedShell`). A resize while the
  alternate screen is up gives the history those lines instead of the cells the callback carries (`pushedShellRow`: the library
  names a pushed row only by its number, which is the count of pushes of that resize), and lays the copy out as the library lays out the
  screen it keeps (`ResizedRows` with that resize's pushes and pops, the flags kept for a resize in rows only), so what a taller
  screen takes back is the true row. The alternate screen's own rows are cut on a shrink and stay at the top on a grow, as in the
  library; when that screen is left (`CSI ? Pm l` with 1047 or 1049, where `WriteCuts` ends a piece just past the `l`, `afterExit`),
  `restoreShell` catches the mirror up with the library's primary screen and puts the copy's links, prompts and kept wraps back on
  each row whose cells still hold the text they had, then drops the copy (as `reset` does). A write that holds an entry or an exit is therefore
  handed to the library in one more piece (an empty one is not handed over: the sequence was split between two writes and the cut
  is where the second begins), which is one more `writeInput` for anything that counts them. `ResizeWrapTest` pins the behaviour (the round trip,
  a replay written as one, an entry split at every point, 1047 and `?25;1049`, two shrinks and a grow, wraps and links in the history
  and back on the screen when the alternate screen is left, prompts, columns, a second visit), `WriteCutsTest` the cuts, and `verifyShellUnderAlternateScreen` the same flows through the real library.
  The traces of `ResizeDiff` are the library's and do not change with it. Limits:
  libvterm puts the cursor `?1049l` restores back unclamped, so after a shrink with the screen up it can lie outside the screen.
- `emulator.getTextBetweenRows(start, endExclusive)` and `emulator.getScreenText()`:
  non-mutating reads that join soft wraps, preserve indentation and strip blank line tails.
  Range indices refer to currently retained history, so eviction/reset invalidates saved
  indices. Prefer one snapshot for a coherent multi-field read.
- `SelectionController.getSelectionText()`: reads the current selection with upstream's
  trimming rules, without clipboard writes, clearing, extending, or moving the selection.
- `SelectionController.getVisibleText()`: reads the current scrolled viewport without
  selecting it. Selection-controller calls belong on the UI thread, as existing calls do.
- `emulator.reset()`: replaces the native parser and clears both screens, history, images,
  OSC state and pending callbacks; retains dimensions, callbacks, palette and defaults.
- `emulator.close()` / `isClosed`: idempotent direct JNI destruction and retained-state
  cleanup. Late queued input/snapshot work is harmless. Reset cannot reopen a closed engine.
  Call these from application code, never from synchronous JNI callbacks.
- `Terminal(cursorColor = ..., cursorAccent = ..., minimumContrastRatio = 4.5, ...)`:
  block, underline and bar cursor colours. Unspecified cursor colour retains upstream
  inversion; unspecified accent uses the terminal background. Contrast adjusts each
  resolved foreground against its cell's background after ANSI/truecolor/inverse and
  fixed selection resolution. It never mutates the emulator, palette or cell background.
  `1.0` disables adjustment; values must be finite and within `1.0..21.0`.
- `Terminal(onCursorBoundsChanged = { rect -> ... })`: optional
  `androidx.compose.ui.geometry.Rect?` callback, on the UI thread. The full cursor cell is
  measured with the renderer's font/cell metrics and visual bidi column, then mapped through
  its zoom/pan layer into the outer terminal content box's local physical pixels. Attach the
  caller's `onGloballyPositioned` after any padding, and map `rect.topLeft` / `rect.bottomRight`
  from those coordinates into the page with `page.localPositionOf(terminalCoordinates, ...)`.
  The callback is stable across cursor blinking, returns null for a hidden cursor or scrollback
  view, and clears on disposal. It is not a window/screen coordinate and is not a dp value. The cell is the one the emulator tracks,
  which is stale only where libvterm skips the cursor callback (the limit named with the scroll regions below).
- `Terminal(selectionActions = { controller -> ... })`: optional composable override for
  the selected-text actions. `null` retains the original Copy + More row. Use a `Row` or
  wrapping `FlowRow` for multiple buttons; content is measured within the terminal's available
  width and height, then placed in the same layout pass. The slot receives the existing
  `SelectionController`: read with `getSelectionText()` and explicitly `clearSelection()`
  after handing the text to chat. The engine neither writes the clipboard nor clears selection
  on a custom action's behalf. Actions hide during selection/handle dragging and when the entire
  selected range is outside the viewport; a partially visible selection keeps its actions.
  Placement uses the visible last row, falls back above the visible first row and clamps to
  the terminal box, retaining clearance for the native handles. The custom slot uses the web's
  4 dp gap; the legacy row keeps 8 dp. Handles and magnifier are unchanged.
- `Terminal(imeMode = TerminalImeMode.CommittedText)`: opt-in IME implementation supplied
  by `terminal_ime_patch.py` and the separately maintained IME types. Default `Legacy` preserves
  the upstream input path. This option is independent of the line-buffering ComposeMode;
  do not enable ComposeMode to turn on committed-text IME editing.
- `Terminal(onTerminalTap = ...)`: runs for every tap, after the tap has asked for the focus and, if
  `keyboardEnabled`, the keyboard. A tap that only cleared a selection or opened a link did neither
  upstream, which left the terminal unfocused (the web's capture-phase `touchend` focuses it on every
  tap). A caller that must raise nothing, as in select mode, turns `keyboardEnabled` off and ignores the call.
- `Terminal(touchSelect = true)`: the web's select mode (`useTerminalTouchSelection`). A one-finger drag past the
  touch slop is a character selection from the cell the finger came down on to the one it is on, both included, which
  replaces whatever was selected (xterm's `mousedown`) and ends, kept, when the finger lifts. It is not a scroll, and
  there is no long press, double tap or link while it is on; a second finger is ignored, a tap clears the selection and
  the handles of a finished selection still adjust it. Entering the mode selects nothing and leaving it clears nothing,
  as the web does. `dragSelects` and `SelectionManager.beginDragSelection` (`TerminalTouchSelection.kt`) are the
  testable half; the gesture itself is `touch_select` in `patch-terminal-engine.py` (guarded replaces).
- Links, as the web's xterm has them (`web_links` in `patch-terminal-engine.py`; the rules are `TerminalWebLinks.kt`,
  `WebUrl.kt` and `TerminalLinkInput.kt`, measured against xterm's `WebLinksAddon` and Chrome by
  `scripts/gen-terminal-link-cases.ts` and `gen-terminal-dash-cases.py`). A link is a plain `http(s)` URL found by the
  addon's regex, joined across soft-wrapped rows, or an OSC 8 hyperlink (`http(s)` only, as xterm's `OscLinkProvider`);
  where both claim a cell the OSC 8 link wins and the plain one is dropped whole. Nothing is underlined until a *mouse*
  is over a link, which then gets a solid underline and the hand pointer; an OSC 8 cell is dashed all the time. A link opens
  for a mouse click that went down and came up on it, and never for a touch or a stylus (the web's `touchend` handler
  answers every tap, so no click follows it). A plain URL goes to `onHyperlinkClick`; an OSC 8 one goes to
  `onOsc8HyperlinkClick` (falling back to `onHyperlinkClick`) so the caller can ask first, as the web's default does with
  `confirm(...)`. A hovered link's underline and an OSC 8 cell's dashes have Chrome's thickness and distance under the
  baseline; the underline an SGR style asks for (1 to 3) is still upstream's one-pixel line.
  `TerminalEmulator.autoDetectUrls` is no longer read by drawing or tapping and stays `false`. Limits: an OSC 8 link is
  on one row only (`OscParser` drops one that wraps, upstream), and a different Unicode version in ICU than in the
  browser can split a URL at a character one of them does not know.
- OSC 8 links stay on their rows while the screen scrolls. libvterm merges the scrolls of a write: each row it pushes
  into the history, and each OSC with the cursor's row, reaches Kotlin as it happens, but the one `moverect` only when the
  write is flushed. Upstream kept segments on the rows the screen had before the write, so a write that scrolled gave
  every pushed row the top row's links and put a link written after a scroll on a row about to move (a screenful of `ls
  --hyperlink`, or lines arriving faster than the 16 ms between snapshots). The segments now move up
  one row with each push, as the rows do, and the `moverect` moves the cells only (`shiftSegmentsUp`,
  `segmentsMovedByPushes`); a pushed row keeps only a link whose text is still under it. What cannot be told from the pushes
  is dropped rather than left on a wrong row: a scroll region that stops above the last row, or a write that also scrolled
  the other way. A resize moves rows too, and the links go with them: a shrink's pushed rows take their links into the history,
  the rest of the screen's segments shift up as the rows do (`ResizedRows.newRowOf`), and a taller screen brings back the
  links of the rows it takes from the history (`resizeRestored`, `storeSegmentText`). A resize in columns cuts a link at the new
  width, as before; with the alternate screen up a grow keeps its links where they are and a shrink keeps none, since what the
  library discarded there is not told by the pushes (the shell's rows go into the history with their own links, from the copy kept
  for them, and that is the only place they are). `SegmentScrollTest` feeds the emulator libvterm's order of callbacks;
  `verifyLinksAcrossScrolls` runs it through the real JNI.
- An OSC 8 link goes away with the cells it was written over. libvterm says where a link is when it ends, but what text is
  there the emulator can only read from the library's screen, which it did when it caught up with the write (a frame later, or
  once at the end of a whole replay) before applying the link blindly: a link whose cells had been erased or written over by
  then showed up over whatever they held (`ls --hyperlink`, `clear`, a plain listing, reached through a reconnect, which
  replays the shell's buffer as one write: a click on `https:` of a plain line opened the address of a line that had scrolled
  away). `WriteCuts` follows libvterm's parser over what is written, in UTF-8 mode as the JNI sets it, and the emulator hands
  the library the bytes in pieces that each end right after an OSC 8 (`writeInPieces`), so the link, its row and the text under it
  are read the moment the link ends (`placeLinks`, one small read of the screen per link). Measured on an emulator, a 1 MB replay
  takes the same with no link, 0.58 s instead of 0.48 s with 3,000 links, and 0.92 s instead of 0.65 s with one on every line. A
  link stands for as long as that text is under it when the emulator catches up (`linkTextIntact`), whatever else the rectangle
  libvterm reports as damaged covers (one rectangle for a whole flush, so it covers unchanged cells between two that changed), and
  a redraw of the whole screen ends every link on it (`dropScreenLinks`: a flush whose rectangle covers the screen and that
  scrolled nothing; a resize is not a redraw). A link made over the cells of another replaces it. A link that no piece
  ended at (an OSC 8 whose end the scan does not see and libvterm does) is read when the emulator catches up, as before.
  Limits: a link made again over the same text, or the same text written over it, is not told from the first; a redraw of the whole
  screen in the same flush as a scroll (Enter on the last row, then `clear`, in one write) looks like the scroll to the emulator, so a
  link over text that came back identical stays; and an OSC 8
  that is written over before it closes is linked where it ended, as upstream. The JVM tests (`StaleLinksTest`, `WriteCutsTest`,
  `ResizeWrapTest`) run the real emulator over `FakeVterm`, a model of libvterm's parse, screen and resize (`ScreenNative` is the one
  place they stand in for the JNI); `verifyStaleLinks`, `verifyWriteCuts` and `verifyResize` run the same bytes through the real
  library (22 operations, three ways of writing each, a replay, a resize, random streams for where the writes are cut), so the model
  cannot drift from it unnoticed. For the resize that is also a differential: `src/sharedTest/kotlin/.../ResizeDiff.kt`, compiled by
  both the unit tests and the instrumentation, scripts 52 fixed-seed scenarios (writes of every kind, resizes in rows and columns, the
  alternate screen, soft wraps) and traces what the library says to its callbacks and holds after every step; `ResizeModelTest` and
  `verifyResizeModel` hold the model and the library to the same list of CRCs (`ResizeDiff.GOLDEN`, taken from the library), so a model
  that drifts fails on the JVM and a library that stops doing what the model does fails on the device. Change a script or the trace and
  `GOLDEN` must be taken again: run the instrumentation with it empty and it prints what the library gave.
- The scroll region of the screen that is shown. libvterm keeps one region for the whole terminal and shows either screen through
  it; xterm.js, which the web app is, keeps one for each screen, starts the alternate one whole every time it is entered, gives the
  shell's own back when it is left, and resets both whenever the size really changes. Vim sets `ESC [ 1 ; 18 r` on the alternate
  screen and leaves it with `?1049l`, never `ESC [ r`, so in libvterm the shell went on with vim's region: a line feed on the bottom
  row no longer scrolled and everything printed after it was drawn over the last rows (`$ stty size`, its answer and the next prompt on
  one row; `printf '\033[r'` healed it). `ESC [ 1 ; 18 r` is also not "the whole screen" to libvterm on a screen that is not 18 rows
  tall but an explicit bottom of 18, which a resize only ever clamps downwards: a replay written before the screen had its final height
  left a region the screen outgrew. That is why it only showed after a cold start, when the terminal is the replay of a session that
  ran vim. `WriteCuts` (the scan above, now a model of the parser over CSI and strings as well) keeps the region each screen would have
  in xterm.js, from what the stream says (DECSTBM as `state.c` takes it, the modes 1047 and 1049, RIS, DECSTR, the origin mode) and what
  the emulator says (a real size change), and the one libvterm holds. Where they differ it ends a piece at the next place the parser is
  between sequences, and `writeRegionFix` writes the library `ESC [ top ; bottom r` (`ESC [ r` for none) and puts the cursor back with a
  CUP from where the callbacks last put it, since setting a region homes it. The fix is made inside a replay's window, where the
  answers to queries are dropped, and answers nothing itself. A plain shell, or a program that leaves its region as it found it, is
  never written to; vim costs one write of a dozen bytes when it exits, and a size change with a region standing one more. The scan
  costs, per MB of output on the emulator: 0.43 ms of plain text, 1.0 ms of colours, 4.9 ms of cursor movements with `?25h` and `l` in
  them (the OSC-only scan it replaces took 0.43, 1.0 and 1.9 ms): a CSI that does not end in `r`, `p`, `h` or `l` is skipped whole
  (`skipPlainCsi`). Limits: with the origin mode on, a cursor that is outside the region cannot be put back (the library homes it into
  the region), and libvterm does not tell the emulator about a cursor it moved and put back inside one sequence (`updatecursor` in `state.c`
  compares the position at the end of a sequence with the one at its start and skips `movecursor` when they are equal), so the
  position the emulator has can be stale there, and the cell `onCursorBoundsChanged` reports with it; the pending wrap does not survive a fix (the cursor goes to the same cell, and the next character
  overwrites it instead of wrapping); the left and right margins (DECSLRM) are not followed; mode 47 is not a mode of this libvterm, so
  it switches nothing here either; and a sequence of 16 arguments or more is not modelled as the library's `args[]` holds it. The JVM
  tests are `WriteCutsTest` (the scan: every shape of sequence, cut anywhere, one random stream per seed) and `ScrollRegionTest` (the
  real emulator over `FakeVterm`: the three cases of the report, vim as a live program, the screens apart, the cursor, what is not
  written to, the replay answering nothing). On a device `RegionChecks` (`-e only region` runs just these) runs the same flows through
  the real library, with `src/androidTest/assets/replay-vim.bin`, a vim session captured on a device (its user and host names replaced,
  byte for byte), as a replay at 30 rows, at 6 rows then 18, and at 18 as captured; holds the scan's region, alternate-screen flag, origin
  mode and cursor to the library's own answers (DECRQSS, DECRQM, DSR) at every place the scan says it is between sequences, over 1,500
  random streams of every sequence that can change a region, written in chunks of random sizes and each ended by a resize (the region the
  library has bounded to the new height must be the scan's, and the fix must give the whole screen back); and holds the region the emulator
  leaves on each screen to a model written from xterm.js's rule alone, over 60 random sessions with replays and resizes. `FakeVterm`'s
  region, DECOM and alternate screen are held to the library as the resize is: `ResizeDiff.regionScript` scripts 60 scenarios (regions
  of every shape, line feeds on their last row, the alternate screen, DECOM (alone, and with a region set or a soft reset inside it), DECSTR,
  resizes with a region standing, and a probe that
  shows which rows the region has), `ResizeModelTest` and `verifyRegionModel` hold the model and the library to `GOLDEN_REGION`.
- With `imeMode = CommittedText`, a view that lost the focus tells the IME it is no editor once nothing else took the
  focus (`onCreateInputConnection` answers `null` with `TYPE_NULL`, as Chrome does for a blurred textarea), and asks for a
  new editor, then reports the caret moved and came back, when the focus returns.

The contrast algorithm matches the repository's xterm `Color.ts`, including its alternate
direction search and powerline/box/block exclusions. A bounded 1,024-entry cache belongs to
each renderer paint. The magnifier uses the same paint. This does not claim general xterm
renderer parity: upstream's pixel-inverted selection, inline images, emoji, and any attributes
not exposed by the pinned JNI remain governed by upstream behavior. Configured cursor colours
intentionally bypass contrast adjustment. The default terminal font implementation is separate.

Validation (from `android/`):

```sh
systemd-run --user --scope --quiet ./gradlew :ui:terminal-engine:assembleDebug :ui:terminal-engine:testDebugUnitTest :ui:terminal-engine:assembleDebugAndroidTest
adb -s emulator-5582 install -r ui/terminal-engine/build/outputs/apk/androidTest/debug/terminal-engine-debug-androidTest.apk
adb -s emulator-5582 shell am instrument -w org.connectbot.terminal.test/org.connectbot.terminal.EngineInstrumentation
```

The instrumentation launches no app activity and uses its own package. It tests JNI linkage,
scrollback limits, wrapped lines and wrap flags in the history and across writes, links (OSC 8, plain, wrapped, in the
history, on their own rows while the screen scrolls, and none left over text that is not theirs after any write that erases
or overwrites them, a replay or a resize), a resize (the model of the library's resize held to its traces in 52 scripted scenarios, the wraps
and links of the rows through a shrink and a grow, the alternate screen), where a write is cut for them, the cursor after a replay, read-only text/cursor access, alternate screens, reset from an
unfinished OSC, palette retention, close/late work, actual truecolor/inverse painting and all three cursor shapes.
Unit tests cover 556 fixtures generated from the web's xterm colour functions, range/wrap handling,
decorative-glyph exclusions, cache separation, non-mutating selection reads, measured action
geometry, viewport clipping, and fully/partially offscreen selections.

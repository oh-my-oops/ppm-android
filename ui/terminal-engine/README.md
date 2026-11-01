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

- `TerminalEmulatorFactory.create(maxScrollbackLines = 50_000, ...)`: non-negative, zero
  disables retained history. Explicit small values work without changing native code.
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
  is a later commit); with the alternate screen up, a resize pushes the alternate screen's rows into the history, each with
  its own flag, as the library does, and the shell's rows under it are not kept, so a visit to the alternate screen forgets
  the wraps kept through an earlier resize (the mirror holds one screen).
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
  view, and clears on disposal. It is not a window/screen coordinate and is not a dp value.
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
  library discarded there is not told by the pushes. `SegmentScrollTest` feeds the emulator libvterm's order of callbacks;
  `verifyLinksAcrossScrolls` runs it through the real JNI.
- An OSC 8 link goes away with the cells it was written over. libvterm says where a link is when it ends, but what text is
  there the emulator can only read from the library's screen, which it did when it caught up with the write (a frame later, or
  once at the end of a whole replay) before applying the link blindly: a link whose cells had been erased or written over by
  then showed up over whatever they held (`ls --hyperlink`, `clear`, a plain listing, reached through a reconnect, which
  replays the shell's buffer as one write: a click on `https:` of a plain line opened the address of a line that had scrolled
  away). `HyperlinkCuts` follows libvterm's parser over what is written, in UTF-8 mode as the JNI sets it, and the emulator hands
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
  link over text that came back identical stays; leaving the alternate screen loses the primary screen's links, as before; and an OSC 8
  that is written over before it closes is linked where it ended, as upstream. The JVM tests (`StaleLinksTest`, `HyperlinkCutsTest`,
  `ResizeWrapTest`) run the real emulator over `FakeVterm`, a model of libvterm's parse, screen and resize (`ScreenNative` is the one
  place they stand in for the JNI); `verifyStaleLinks`, `verifyHyperlinkCuts` and `verifyResize` run the same bytes through the real
  library (22 operations, three ways of writing each, a replay, a resize, random streams for where the writes are cut), so the model
  cannot drift from it unnoticed. For the resize that is also a differential: `src/sharedTest/kotlin/.../ResizeDiff.kt`, compiled by
  both the unit tests and the instrumentation, scripts 52 fixed-seed scenarios (writes of every kind, resizes in rows and columns, the
  alternate screen, soft wraps) and traces what the library says to its callbacks and holds after every step; `ResizeModelTest` and
  `verifyResizeModel` hold the model and the library to the same list of CRCs (`ResizeDiff.GOLDEN`, taken from the library), so a model
  that drifts fails on the JVM and a library that stops doing what the model does fails on the device. Change a script or the trace and
  `GOLDEN` must be taken again: run the instrumentation with it empty and it prints what the library gave.
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

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
- `emulator.getBufferLines()`: `TerminalBufferLine(text, isWrapped)` values. `isWrapped`
  means this row continues onto the next row, matching libvterm's outgoing soft-wrap flag;
  this is the opposite row convention from xterm's incoming `IBufferLine.isWrapped`.
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
scrollback limits, read-only text/cursor access, alternate screens, reset from an unfinished OSC,
palette retention, close/late work, actual truecolor/inverse painting and all three cursor shapes.
Unit tests cover 556 fixtures generated from the web's xterm colour functions, range/wrap handling,
decorative-glyph exclusions, cache separation, non-mutating selection reads, measured action
geometry, viewport clipping, and fully/partially offscreen selections.

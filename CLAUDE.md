# android/ — native PPM client for Android (phone + tablet)

Plan: `plans/261019-2119-native-android-app/` (gitignored, in the main checkout). The port follows the Apple app (`~/Projects/ppm-apple/apple`, its `Packages/PPMKit` is the source of every rule here) and, behind both, the web (`src/web/`).

## Layout

- `core/protocol` — wire types. Frames are parsed into a kotlinx `JsonElement` first and read field by field (`Json.kt` extensions), so an unknown field or frame `type` never fails a decode.
- `core/client` — REST (`ApiClient` + `ApiClientSessions`/`ApiClientUploads` extensions), `SocketConnection` (OkHttp WebSocket as a channel of events, never reconnects by itself), `InflightGets` (identical GETs share one request, as `api-client.ts`), `ServerStore` (servers in a `KeyValueStore`, passwords in a `SecretStore`).
- `core/state` — `ChatTranscript` (pure reducer ported from `src/web/hooks/use-chat.ts`: frames in, `ChatEffect`s out), `ChatController` (history + socket + reconnect around it, publishes one `StateFlow<ChatState>`), `GlobalEvents` (`/ws/global`), `HistoryList` / `ChatSearch`.
- `core/state/workspace` — the web's layout (panels, tabs, dock): `WorkspaceStore` is `panel-store.ts` + `dock-actions.ts`, `WorkspaceCache` its local copy (`ppm-panels-*`) with the debounced PUT and the hydrate of `panel-utils.ts`. The blob is the one every browser of that server reads and writes, so a tab type, metadata key or field the app does not know is kept (`metadata` whole, the rest in `extra`) and written back unchanged.

The three `core` modules are plain Kotlin/JVM: their tests run on the JVM, no emulator.

## Rules the code relies on

- **One thread drives every controller.** `ChatController`, `GlobalEvents`, `HistoryList` and `ChatSearch` take a parent `CoroutineScope` whose dispatcher must be a single thread (the main one on Android, `e2e-main` in `EndToEndTest`) and must be called from it. Nothing in them is synchronized; the `StateFlow`s are what other threads may read.
- `ChatController` decodes frames on `Dispatchers.Default` and applies them back on its own thread: a `turn_events` snapshot can be megabytes.
- Two deliberate differences from the Swift code: `ChatController` keeps the reason it closed a silent socket for (Swift's `close()` reports `nil` and overwrites it), and history/frames take `now` in epoch milliseconds.
- **Tabs are the web's layout.** `AppModel` draws `WorkspaceStore`'s state; a chat tab's chat (`ChatTab`) is keyed by the layout's tab id, which is not the session id (`chat:<provider>/<session>`, or a minted id the session came to later): go through `tabSessionId` for unread, running and marking read. The one deliberate difference from `panel-store.ts`: a singleton (`settings`, `git-log`) is looked for on screen, as a table's tab is, not in other projects' keep-alive panels (see the comment in `WorkspaceStore.openTab`).
- `HttpUrl.toString()` always prints a root path (`http://host:8080/`), where Foundation prints none — tests written from the Swift ones must expect the slash.

## Drawing like the web

- Under 768 dp the phone layout (`PhoneShell`), from 768 dp the desktop one (`ui/tablet/`), as `use-is-mobile.ts`; `LocalPpmMobile` says which.
- CSS counts a border into the box; Compose's `border()` draws inside it and takes no room. A `px-2 py-0.5 border` chip is `padding(9.dp, 3.dp)` here, a `p-1` menu inside its border `padding(5.dp)`; `Modifier.edge()` (tablet) is a one-sided border that does make room.
- Inputs are 16 px only on a phone (`globals.css`). A field the tablet shows passes its class's own size as `PpmTextField(desktopStyle = …)`.
- In a `Row` measured for its intrinsic width (a menu is `IntrinsicSize.Max`), every weighted child counts as wide as the widest one: a label plus a weighted `Spacer` made a 128 px menu 168. Weight the label instead.
- What the web shows only on hover (`can-hover:` is hover **and** a fine pointer) is always shown on a touch tablet. Compare with the web under touch emulation (CDP `Emulation.setTouchEmulationEnabled`), not a desktop mouse — and expect Playwright clicks to hang while `setEmitTouchEventsForMouse` is on.
- A handle that moves with the edge it drags measures on the screen (`Modifier.screenDrag`, `TabletShell.kt`): `draggable` reports moves in the handle's own coordinates, which came to about half the finger's travel, and `drag()` drops the last stretch when the release carries it.
- Tailwind 4's bare `border` is `currentColor`, not the border token: the web's menus are outlined in the text colour, and `MenuBox` copies that. Read a colour off the page (`getComputedStyle`), not off the class name.
- `graphicsLayer(alpha < 1)` draws into an offscreen buffer the size of the element, which cuts a shadow off at its edge. An element with a shadow takes `CompositingStrategy.ModulateAlpha` (the tab drag's label).
- `Modifier.pointerHoverIcon` takes effect when the pointer comes in: one attached while the pointer is already over it waits for the next entry. Keep it attached and change its icon (`dragCursor`).

## Building and testing

JDK 21 (`/usr/bin/java`), Android SDK in `~/Android/Sdk`. Run Gradle outside PPM's cgroup, so that its daemon does not die with a PPM restart:

```bash
cd android
systemd-run --user --scope --quiet ./gradlew :core:protocol:test :core:client:test :core:state:test
```

The build needs bun and the repository's `node_modules` (`bun install` at the root): `ui/design`'s `buildHighlighter` makes the code highlighting's assets from them — the web's Shiki as one script for WebView's JavaScript sandbox (`SyntaxHighlighter`), and every grammar — into `build/`, so nothing generated is in git. After touching Shiki or `scripts/highlighter/`, `node scripts/check-highlighter.mjs <dir>` (header says how) checks every sample block's colours against the web's Shiki, on V8 as the sandbox runs it.

End-to-end tests (`EndToEndTest`) run only when a scratch server is named. `scripts/scratch-server.sh start [port]` runs this checkout's server with its own `PPM_HOME` under `/tmp` (never the real `~/.ppm`), a random password and a `demo` project, on `127.0.0.1` — which the emulator reaches as `10.0.2.2`:

```bash
scripts/scratch-server.sh start            # prints "<url> <password>"
source /tmp/ppm-android-scratch.env        # URL, PASSWORD, PROJECT
systemd-run --user --scope --quiet --setenv=PPM_E2E_URL=$URL --setenv=PPM_E2E_PASSWORD=$PASSWORD \
  --setenv=PPM_E2E_PROJECT=$PROJECT ./gradlew :core:state:test --rerun
scripts/scratch-server.sh stop
```

Sessions are created with `providerId: "mock"`, so no AI is paid for. The mock streams a code block (and an approval request) on a session's first turn and keeps its answer without them, so a first turn's text never matches its history.

The Baseline Profile in `app/src/release/generated/baselineProfiles/` is generated and committed: it lists the methods Android compiles ahead of time instead of interpreting them, and its startup half orders the dex. Regenerate it before a release when startup or the chat changed. `:baselineprofile`'s journeys (`PpmJourneys.kt`) sign in and open a mock session with a long transcript, both named in a file on the device, never in instrumentation arguments, which Gradle prints. A run replaces the directory with what its devices reached, a phone never reaches the desktop layout nor a tablet the mobile one, and one emulator runs at a time here, so generate on each and merge:

```bash
adb -s emulator-5580 push target.properties /data/local/tmp/ppm-baseline-profile.properties  # server= password= project= session=
ANDROID_SERIAL=emulator-5580 systemd-run --user --scope --quiet ./gradlew :app:generateBaselineProfile \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
cp -r app/src/release/generated/baselineProfiles /tmp/phone-profiles
# the same on emulator-5582, then
python3 -I scripts/merge-baseline-profiles.py /tmp/phone-profiles
```

Without `leaveApksInstalledAfterRun` Gradle uninstalls the app after the run, saved servers and all. `StartupBenchmarks` times a cold start with and without the profile, `ScrollBenchmarks` the frames of opening the chat and scrolling it: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark`, one test with `-Pandroid.testInstrumentationRunnerArguments.class=com.hienlh.ppm.baselineprofile.ScrollBenchmarks#scrollWithProfile`; the numbers land in `baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/*/*-benchmarkData.json`. On the tablet emulator the start does not depend on the code at all: it draws 2560×1600 in software, and even a fully compiled app (`cmd package compile -m speed -f`) started no faster. The phone emulator does show the profile (10-26: start 502 → 467 ms median, frame CPU P50 31.8 → 25.5 ms), and the tablet's frames do (P95 171 → 115 ms, P99 530 → 201 ms). An install from adb or the self-update compiles nothing at install (`dumpsys package dexopt` reads `verify`): ProfileInstaller writes the profile on the first start and the idle maintenance job compiles it (`speed-profile`), which `adb shell cmd package compile -r bg-dexopt com.hienlh.ppm` runs at once.

## Devices

- Emulators: the AVD `PPM_Phone` on port 5580 (`emulator-5580`) and `PPM_Tablet` on 5582 (`emulator-5582`, a Pixel Tablet at 2560×1600 and 320 dpi: 1280×800 dp, the web viewport to set beside it, less the 24 dp status bar). Always pass `adb -s <serial>`.
- Never touch `emulator-5556` or any `Velox_*` AVD: they belong to other work on this machine.
- The web beside the app on the same device: Chrome on `PPM_Tablet` at `http://10.0.2.2:<port>`. Its page starts 97 dp lower than the app's window, at the same x.
- A mouse: `adb shell input mouse` presses no button, and Compose ignores a mouse press without one. `scripts/virtual-mouse.py` registers a real USB mouse through `uinput` and plays steps on it (move, press, right press, wait, screenshot).

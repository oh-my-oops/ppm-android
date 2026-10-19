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
- `HttpUrl.toString()` always prints a root path (`http://host:8080/`), where Foundation prints none — tests written from the Swift ones must expect the slash.

## Building and testing

JDK 21 (`/usr/bin/java`), Android SDK in `~/Android/Sdk`. Run Gradle outside PPM's cgroup, so that its daemon does not die with a PPM restart:

```bash
cd android
systemd-run --user --scope --quiet ./gradlew :core:protocol:test :core:client:test :core:state:test
```

End-to-end tests (`EndToEndTest`) run only when a scratch server is named. `scripts/scratch-server.sh start [port]` runs this checkout's server with its own `PPM_HOME` under `/tmp` (never the real `~/.ppm`), a random password and a `demo` project, on `127.0.0.1` — which the emulator reaches as `10.0.2.2`:

```bash
scripts/scratch-server.sh start            # prints "<url> <password>"
source /tmp/ppm-android-scratch.env        # URL, PASSWORD, PROJECT
systemd-run --user --scope --quiet --setenv=PPM_E2E_URL=$URL --setenv=PPM_E2E_PASSWORD=$PASSWORD \
  --setenv=PPM_E2E_PROJECT=$PROJECT ./gradlew :core:state:test --rerun
scripts/scratch-server.sh stop
```

Sessions are created with `providerId: "mock"`, so no AI is paid for. The mock streams a code block (and an approval request) on a session's first turn and keeps its answer without them, so a first turn's text never matches its history.

## Devices

- Emulator: the AVD `PPM_Phone`, started on port 5580 (`emulator-5580`). Always pass `adb -s <serial>`.
- Never touch `emulator-5556` or any `Velox_*` AVD: they belong to other work on this machine.

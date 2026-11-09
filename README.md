# PPM for Android

Native Android client (phone and tablet) for [PPM](https://github.com/hienlh/ppm), the self-hosted project manager and AI chat. The app holds no data of its own: it talks to a PPM server over the same HTTP routes and WebSockets as the web app, and draws the web's mobile layout on a phone and its desktop layout on a tablet (768 dp and wider).

## Use it

1. Run PPM on your machine.
2. Install the APK (Android 8.0 or newer) and open it.
3. Enter the server's address (`https://…`, a Tailscale address, or `192.168.x.x:8080` on the same network) and its access password.

Several servers can be saved; switch between them, log out or add one in Settings → Servers.

## Build

Requirements: JDK 17 and the Android SDK (put `sdk.dir=…` in `local.properties`).

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # R8-shrunk; signed with the release key below, else the debug key
./gradlew test                    # JVM tests of core/ and ui/
```

A release build is signed only when these Gradle properties are set (`~/.gradle/gradle.properties`, never in the repository): `ppmReleaseStoreFile`, `ppmReleaseStorePassword`, `ppmReleaseKeyAlias`, `ppmReleaseKeyPassword`. `-PppmVersion=X.Y.Z` sets the version.

## Layout

- `core/protocol`, `core/client`, `core/state` — wire types, REST + sockets, and the state behind every screen. Plain Kotlin/JVM, tested without an emulator.
- `ui/*` — Compose design system and feature UI (chat, terminal, editor, …).
- `app` — the application: shells for phone and tablet, settings, navigation.
- `baselineprofile`, `microbenchmark` — startup profile and decode benchmarks.

`CLAUDE.md` holds the rules the code relies on. The generators in `scripts/` (design tokens, file icons, test fixtures) read the web app's sources and expect a PPM checkout at `../../` (the layout this code was developed in); their output is committed, so the build does not need them.

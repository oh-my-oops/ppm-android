# PPM Android Redesign — Visual & Behavioural Comparison Report (Task 22)

This directory holds the reference design captures (`design/`), the running Android app captures (`app/`), the side-by-side comparison sheets (`compare/`), and the quantitative pixel/luminance metrics (`metrics.json`) for every screen in `designs/ppm-mobile-tablet-redesign/` across both **Aurora Light** and **Aurora Dark** themes (Requirements 17.1 & 17.2).

## 1. Test Devices & Capture Environment

| Target | Device / Tool | Viewport (dp / px) | Themes Tested | Count | Mean Similarity |
| --- | --- | --- | --- | --- | --- |
| **Phone (`< 600 dp`)** | `emulator-5580` (`PPM_Phone`) vs Playwright Chromium | `412 × 915` | Aurora Light, Aurora Dark | 24 (12 screens × 2) | **88.36%** |
| **Tablet (`>= 840 dp` landscape)** | `emulator-5582` (`PPM_Tablet`) vs Playwright Chromium | `1280 × 800` | Aurora Light, Aurora Dark | 16 (8 screens × 2) | **94.38%** |
| **Total** | Both emulators + Playwright headless Chromium | Exact 1:1 frame size | Light + Dark | **40 comparisons** | **90.77%** |

- **Design frames (`design/<screen>-<theme>.png`)**: Captured directly from `designs/ppm-mobile-tablet-redesign/` (`foundation/index.html`, `phone/1-chat..7-tools/index.html`, `tablet/1-chat..7-tools/index.html`) using Playwright at `deviceScaleFactor: 1` with `.theme-dark` toggled on `.device` for Aurora Dark.
- **App screenshots (`app/<screen>-<theme>.png`)**: Captured from `com.hienlh.ppm` (`LocalPrefs.REDESIGN_KEY = "redesign"` set to `"true"`) running against an isolated scratch PPM server seeded with a Git repository (`status-bar.tsx` with both staged and unstaged hunks, `window-dock.tsx` unstaged, `status-bar.test.ts` untracked), a live chat session (`Fix status bar overflow`), a SQLite database (`app.db` with `tasks` and `users` tables), notification feed items (`approval`, `done`, `schedule`), and a cron schedule (`Nightly regression`).
- **Side-by-side sheets (`compare/<screen>-<theme>.png`)**: Pair the design frame on the left and the Android app screenshot on the right at identical pixel dimensions (`412 × 915` on phone, `1280 × 800` on tablet).

---

## 2. Screen-by-Screen Comparison Table

| Screen ID | Theme | Viewport | Screen Description | MAE | RMS | Mean Luma (Design / App) | Similarity | Artifacts |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `phone-connect` | Light | `412x915` | Phone · Connect (Foundation) | `57.17` | `75.39` | `195.0` / `235.8` | **77.58%** | [design](design/phone-connect-light.png) · [app](app/phone-connect-light.png) · [compare](compare/phone-connect-light.png) |
| `phone-connect` | Dark | `412x915` | Phone · Connect (Foundation) | `19.36` | `39.34` | `25.7` / `31.4` | **92.41%** | [design](design/phone-connect-dark.png) · [app](app/phone-connect-dark.png) · [compare](compare/phone-connect-dark.png) |
| `phone-chat` | Light | `412x915` | Phone 1 · Active Chat & Composer | `10.01` | `25.0` | `240.2` / `246.1` | **96.08%** | [design](design/phone-chat-light.png) · [app](app/phone-chat-light.png) · [compare](compare/phone-chat-light.png) |
| `phone-chat` | Dark | `412x915` | Phone 1 · Active Chat & Composer | `9.94` | `28.62` | `21.1` / `19.6` | **96.1%** | [design](design/phone-chat-dark.png) · [app](app/phone-chat-dark.png) · [compare](compare/phone-chat-dark.png) |
| `phone-drawer` | Light | `412x915` | Phone 1 · Left Navigation Drawer | `39.69` | `67.18` | `239.8` / `220.4` | **84.43%** | [design](design/phone-drawer-light.png) · [app](app/phone-drawer-light.png) · [compare](compare/phone-drawer-light.png) |
| `phone-drawer` | Dark | `412x915` | Phone 1 · Left Navigation Drawer | `20.39` | `42.39` | `28.3` / `26.9` | **92.0%** | [design](design/phone-drawer-dark.png) · [app](app/phone-drawer-dark.png) · [compare](compare/phone-drawer-dark.png) |
| `phone-tabs` | Light | `412x915` | Phone 1 · Tab Switcher Bottom Sheet | `86.94` | `103.56` | `237.2` / `157.9` | **65.9%** | [design](design/phone-tabs-light.png) · [app](app/phone-tabs-light.png) · [compare](compare/phone-tabs-light.png) |
| `phone-tabs` | Dark | `412x915` | Phone 1 · Tab Switcher Bottom Sheet | `30.46` | `59.84` | `35.1` / `24.0` | **88.06%** | [design](design/phone-tabs-dark.png) · [app](app/phone-tabs-dark.png) · [compare](compare/phone-tabs-dark.png) |
| `phone-projects` | Light | `412x915` | Phone 2 · Project Switcher Sheet | `75.43` | `88.48` | `249.3` / `178.0` | **70.42%** | [design](design/phone-projects-light.png) · [app](app/phone-projects-light.png) · [compare](compare/phone-projects-light.png) |
| `phone-projects` | Dark | `412x915` | Phone 2 · Project Switcher Sheet | `24.05` | `34.09` | `32.3` / `14.9` | **90.57%** | [design](design/phone-projects-dark.png) · [app](app/phone-projects-dark.png) · [compare](compare/phone-projects-dark.png) |
| `phone-notifications` | Light | `412x915` | Phone 2 · Notification Feed Sheet | `79.45` | `105.4` | `245.3` / `178.9` | **68.84%** | [design](design/phone-notifications-light.png) · [app](app/phone-notifications-light.png) · [compare](compare/phone-notifications-light.png) |
| `phone-notifications` | Dark | `412x915` | Phone 2 · Notification Feed Sheet | `24.17` | `45.48` | `35.4` / `22.1` | **90.52%** | [design](design/phone-notifications-dark.png) · [app](app/phone-notifications-dark.png) · [compare](compare/phone-notifications-dark.png) |
| `phone-files` | Light | `412x915` | Phone 3 · Files Tree & Preview | `8.55` | `22.33` | `250.4` / `247.3` | **96.65%** | [design](design/phone-files-light.png) · [app](app/phone-files-light.png) · [compare](compare/phone-files-light.png) |
| `phone-files` | Dark | `412x915` | Phone 3 · Files Tree & Preview | `15.84` | `27.06` | `29.6` / `19.3` | **93.79%** | [design](design/phone-files-dark.png) · [app](app/phone-files-dark.png) · [compare](compare/phone-files-dark.png) |
| `phone-git` | Light | `412x915` | Phone 4 · Source Control & AI Commit | `10.87` | `36.54` | `246.6` / `249.2` | **95.74%** | [design](design/phone-git-light.png) · [app](app/phone-git-light.png) · [compare](compare/phone-git-light.png) |
| `phone-git` | Dark | `412x915` | Phone 4 · Source Control & AI Commit | `11.62` | `37.09` | `32.6` / `31.3` | **95.44%** | [design](design/phone-git-dark.png) · [app](app/phone-git-dark.png) · [compare](compare/phone-git-dark.png) |
| `phone-terminal` | Light | `412x915` | Phone 4 · Interactive Terminal & KeyRow | `80.04` | `93.7` | `169.2` / `232.4` | **68.61%** | [design](design/phone-terminal-light.png) · [app](app/phone-terminal-light.png) · [compare](compare/phone-terminal-light.png) |
| `phone-terminal` | Dark | `412x915` | Phone 4 · Interactive Terminal & KeyRow | `31.92` | `67.77` | `28.7` / `31.1` | **87.48%** | [design](design/phone-terminal-dark.png) · [app](app/phone-terminal-dark.png) · [compare](compare/phone-terminal-dark.png) |
| `phone-database` | Light | `412x915` | Phone 5 · Database Read-Only | `10.24` | `25.89` | `249.3` / `245.6` | **95.98%** | [design](design/phone-database-light.png) · [app](app/phone-database-light.png) · [compare](compare/phone-database-light.png) |
| `phone-database` | Dark | `412x915` | Phone 5 · Database Read-Only | `17.95` | `31.29` | `31.2` / `18.8` | **92.96%** | [design](design/phone-database-dark.png) · [app](app/phone-database-dark.png) · [compare](compare/phone-database-dark.png) |
| `phone-settings` | Light | `412x915` | Phone 6 · Settings (6 Sections) | `9.15` | `27.31` | `252.1` / `246.3` | **96.41%** | [design](design/phone-settings-light.png) · [app](app/phone-settings-light.png) · [compare](compare/phone-settings-light.png) |
| `phone-settings` | Dark | `412x915` | Phone 6 · Settings (6 Sections) | `13.54` | `28.59` | `30.3` / `24.5` | **94.69%** | [design](design/phone-settings-dark.png) · [app](app/phone-settings-dark.png) · [compare](compare/phone-settings-dark.png) |
| `phone-tools` | Light | `412x915` | Phone 7 · Tools (Monitor / Logs / Ports / Cloud) | `9.66` | `25.59` | `250.0` / `246.6` | **96.21%** | [design](design/phone-tools-light.png) · [app](app/phone-tools-light.png) · [compare](compare/phone-tools-light.png) |
| `phone-tools` | Dark | `412x915` | Phone 7 · Tools (Monitor / Logs / Ports / Cloud) | `15.61` | `28.33` | `30.4` / `19.6` | **93.88%** | [design](design/phone-tools-dark.png) · [app](app/phone-tools-dark.png) · [compare](compare/phone-tools-dark.png) |
| `tablet-connect` | Light | `1280x800` | Tablet · Connect (Foundation) | `15.89` | `26.84` | `241.8` / `236.9` | **93.77%** | [design](design/tablet-connect-light.png) · [app](app/tablet-connect-light.png) · [compare](compare/tablet-connect-light.png) |
| `tablet-connect` | Dark | `1280x800` | Tablet · Connect (Foundation) | `16.08` | `28.98` | `35.6` / `28.0` | **93.69%** | [design](design/tablet-connect-dark.png) · [app](app/tablet-connect-dark.png) · [compare](compare/tablet-connect-dark.png) |
| `tablet-chat` | Light | `1280x800` | Tablet 1 · Chat & Preview Split | `9.4` | `26.45` | `242.6` / `241.6` | **96.31%** | [design](design/tablet-chat-light.png) · [app](app/tablet-chat-light.png) · [compare](compare/tablet-chat-light.png) |
| `tablet-chat` | Dark | `1280x800` | Tablet 1 · Chat & Preview Split | `14.18` | `30.6` | `26.8` / `17.5` | **94.44%** | [design](design/tablet-chat-dark.png) · [app](app/tablet-chat-dark.png) · [compare](compare/tablet-chat-dark.png) |
| `tablet-shell` | Light | `1280x800` | Tablet 2 · Shell, Rail & Notifications | `10.04` | `23.2` | `240.5` / `241.9` | **96.06%** | [design](design/tablet-shell-light.png) · [app](app/tablet-shell-light.png) · [compare](compare/tablet-shell-light.png) |
| `tablet-shell` | Dark | `1280x800` | Tablet 2 · Shell, Rail & Notifications | `14.5` | `32.05` | `18.3` / `24.9` | **94.32%** | [design](design/tablet-shell-dark.png) · [app](app/tablet-shell-dark.png) · [compare](compare/tablet-shell-dark.png) |
| `tablet-files` | Light | `1280x800` | Tablet 3 · Explorer & Code Editor | `9.59` | `26.13` | `250.0` / `244.1` | **96.24%** | [design](design/tablet-files-light.png) · [app](app/tablet-files-light.png) · [compare](compare/tablet-files-light.png) |
| `tablet-files` | Dark | `1280x800` | Tablet 3 · Explorer & Code Editor | `16.65` | `28.9` | `29.1` / `19.4` | **93.47%** | [design](design/tablet-files-dark.png) · [app](app/tablet-files-dark.png) · [compare](compare/tablet-files-dark.png) |
| `tablet-git` | Light | `1280x800` | Tablet 4 · Source Control & Diff Viewer | `27.03` | `36.46` | `221.4` / `243.5` | **89.4%** | [design](design/tablet-git-light.png) · [app](app/tablet-git-light.png) · [compare](compare/tablet-git-light.png) |
| `tablet-git` | Dark | `1280x800` | Tablet 4 · Source Control & Diff Viewer | `16.68` | `32.38` | `28.9` / `20.2` | **93.46%** | [design](design/tablet-git-dark.png) · [app](app/tablet-git-dark.png) · [compare](compare/tablet-git-dark.png) |
| `tablet-database` | Light | `1280x800` | Tablet 5 · Database Studio | `9.65` | `25.97` | `248.7` / `245.5` | **96.22%** | [design](design/tablet-database-light.png) · [app](app/tablet-database-light.png) · [compare](compare/tablet-database-light.png) |
| `tablet-database` | Dark | `1280x800` | Tablet 5 · Database Studio | `16.25` | `31.04` | `30.6` / `20.8` | **93.63%** | [design](design/tablet-database-dark.png) · [app](app/tablet-database-dark.png) · [compare](compare/tablet-database-dark.png) |
| `tablet-settings` | Light | `1280x800` | Tablet 6 · Settings List-Detail | `12.5` | `30.64` | `248.6` / `241.8` | **95.1%** | [design](design/tablet-settings-light.png) · [app](app/tablet-settings-light.png) · [compare](compare/tablet-settings-light.png) |
| `tablet-settings` | Dark | `1280x800` | Tablet 6 · Settings List-Detail | `17.25` | `33.7` | `30.8` / `23.3` | **93.24%** | [design](design/tablet-settings-dark.png) · [app](app/tablet-settings-dark.png) · [compare](compare/tablet-settings-dark.png) |
| `tablet-tools` | Light | `1280x800` | Tablet 7 · Port Forwarding & More Tools | `7.9` | `23.19` | `248.9` / `245.4` | **96.9%** | [design](design/tablet-tools-light.png) · [app](app/tablet-tools-light.png) · [compare](compare/tablet-tools-light.png) |
| `tablet-tools` | Dark | `1280x800` | Tablet 7 · Port Forwarding & More Tools | `15.95` | `31.24` | `29.9` / `23.8` | **93.75%** | [design](design/tablet-tools-dark.png) · [app](app/tablet-tools-dark.png) · [compare](compare/tablet-tools-dark.png) |

---

## 3. Intentional Differences Where the App Follows Web Logic Over Static Drawings (Requirement 17.2)

Per Requirement 17.2, wherever the static HTML design mockup (`designs/ppm-mobile-tablet-redesign/`) diverges from the live web application's state machines and server contracts (`src/web/**`, `src/server/**`, `core/state/**`), the Android app follows the web's real runtime logic:

1. **Shared `WorkspaceStore` & Live Server Layout Sync (`/api/project/:name/workspace`)**:
   - The HTML drawings show static tab counts (`1 of 4 tabs`, `4 of 5 tabs`). In the app, `PhoneShell` and `TabletShell` read and persist the project's live `PanelLayout` through `WorkspaceStore` and `WorkspaceCache` (`/api/project/:name/workspace`).
   - Tablet-only tab types (`git-graph`, `conflict-editor`, `db-structure`, `db-connection`, `db-impexp`, `extension`, `design`, `group`) are never dropped from `WorkspaceStore` when opened or synced onto a phone; `PhoneShell` routes them to `OpenOnTablet` (`"Opens on a tablet"` in `TabSwitcherSheet`) so switching back to a tablet preserves the exact multi-panel workspace (Requirements 3.2 & 3.3).

2. **Singleton Tab Resolution & Deterministic Tab IDs (`deriveTabId` / `TabUrl.kt`)**:
   - Following the web's `panel-utils.ts` and `use-url-sync.ts`, `settings` (`"settings"`) and `git-log` (`"git-log"`) are singleton tabs, while `editor:<path>`, `git-diff:<path>`, `sqlite:<path>`, and `chat:<sessionId>` derive deterministic IDs so tapping a file or deep-linking via `ppm://<host>/project/<name>/<type>/<id>` focuses the existing tab instead of duplicating it.

3. **Held Terminal WebSockets Across Navigation (`heldTabs`)**:
   - Both `PhoneShell` (`PhoneAreaHosts.kt`) and `TabletShell` (`TabletShell.kt` + `TerminalDock`) keep every open `terminal` tab mounted in the Compose tree (`alpha(0f)`, `zIndex(-1f)`) while the user views Chat, Files, Source Control, or Settings. This prevents `/ws/terminal` sockets from disconnecting and losing interactive shell state when switching screens.

4. **Live Provider, Model, Effort, Permission Mode & Account Chips**:
   - The design mockups render static labels (`Claude Sonnet 4.6 · High`, `Ask before edits`) inside the composer. The app binds the composer control strip (`PhoneChatScreen` and tablet `ChatFooter`) to the server's live `/api/settings`, `/api/providers`, and `/api/accounts` state (including account badge `D · Default`, effort level `Medium`/`High`, and permission mode `Ask`/`Bypass permissions`).

5. **Source Control Partial Staging & AI Commit Generation (`/api/project/:name/git/*`)**:
   - When a file (`status-bar.tsx`) has both staged and unstaged changes, `GitStatus` lists it in both `STAGED` (`part of the file`) and `CHANGES` (`rest of the file`) with live line-delta counts from `git status`/`git diff`.
   - Tapping `✨ AI` calls `POST /api/project/:name/git/commit-message` and populates the `Commit message…` field without auto-committing, matching the web's `git-status-panel.tsx` (Requirements 8.1 & 8.4).

6. **Database Read-Only on Phone vs Full Database Studio on Tablet**:
   - On phone (`phone-database`), `PhoneDatabaseScreen` enforces a strictly read-only workflow (`Read-only` badge, connection selector, table/row viewer, read-only SQL runner, cell detail bottom sheet, and `"Add or edit connections on a tablet"` callout; Requirements 10.1–10.5).
   - On tablet (`tablet-database`), `TabletShell` opens the full desktop-equivalent Database Studio (`+ Query`, `+ Table`, `Refresh`, `Save`, `+ Insert row`, `Clone row`, `Delete row`, `Edit cell`, `Set NULL`, `Revert row`, `Structure ↗`, `SQL ↗`; Requirements 14.1–14.5).

7. **Settings Presentation on Phone (6-Section Hub) vs Tablet (Floating List-Detail Window)**:
   - On phone (`phone-settings`), `PhoneSettingsScreen` groups settings into the 6 mobile sections (`General & Appearance`, `Servers & Accounts`, `AI & Providers`, `Extensions & MCP`, `Editor, Git & Terminal`, `Notifications & Cloud`) with `Open on a tablet` cards for heavy desktop editors (`Keybindings`, `Slash commands`, `Permissions`).
   - On tablet (`tablet-settings`), `Settings` opens in the web's list-detail floating window (`WindowLayer`) with its minimized/active pill in the bottom `WindowDock` status bar, while also supporting the redesigned `TabletSettingsScreen` list-detail layout (Requirements 11.1–11.5 & 15.1–15.3).

8. **Notification Feed & Graceful Fallback (`/api/notifications`)**:
   - `NotificationsSheet` (phone) and `NotificationBell` (tablet) query `/api/notifications` (`All`, `Unread`, `Attention`, `Mark all read`) and fall back to synthesizing unread session items from `/ws/global` (`events.unread`) if connected to an older server without `/api/notifications` (Requirement 4.3).

9. **Connect Screen Security & Offline Guards (`ConnectScreen.kt`)**:
   - `ConnectScreen` preserves the web login flow (`Unlock your workspace`) while enforcing `AddressSafety.needsPlainHttpWarning` before sending credentials over unencrypted `http://` to non-private/non-Tailscale addresses and `ConnectivityState` (`"This phone is offline"` / `"This tablet is offline"`) when Android reports no active network (Requirements 5.2 & 5.3).

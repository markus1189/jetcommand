# JetCommand (Android)

Native successor to `~/repos/projects/tools/bedjet-bluetooth-control.html`; protocol reference in `~/repos/projects/tools/docs/BEDJET_PROTOCOL.md`. Plan, decisions, status and the real-device checklist: `docs/ROADMAP.md` (update its status when a phase lands).

## Layout

| Layer | Where | Tested by |
|---|---|---|
| Codec, `BedJetSim` (device model) | `protocol/.../Codec.kt`, `BedJetSim.kt` | `FixtureTest` (recorded from the web app) |
| Transport contract | `protocol/.../Transport.kt` | — |
| `SimTransport`, `BedJetLink`, `BedJetDevice` | `protocol/` | `BedJetLinkTest`, virtual time |
| `GattTransport`, scanner, permissions | `app/.../ble/` | real device only |
| `NightRecorder`, `NightLogStore` | `protocol/.../NightLog.kt` | `NightRecorderTest` |
| `BedJetController` (owns the stack), `BedJetService`, Room night log, debug screen | `app/` | emulator via Demo BedJet |

`:protocol` stays free of Android so its tests run on the JVM.

## Toolchain

- Everything via `nix develop` (SDK, JDK 21, adb, node). Done means `./gradlew check assembleDebug` exits 0 (protocol tests, app unit tests, lint, APK); install with `./gradlew installDebug`.
- New files must be `git add`ed before `nix develop` sees flake changes.
- SDK in the Nix store is read-only: `compileSdk`/`buildToolsVersion` in `app/build.gradle.kts` must match the versions in `flake.nix`, or AGP tries to install and fails.
- New dependency versions go in `gradle/libs.versions.toml`, checked to exist on Maven first.

## Emulator

- `emu` (in the dev shell) boots a headless API 36 AVD named `jetcommand`, creating it on first run; run it in the background, cold boot takes ~30 s. Reuse a running one (`adb devices`).
- `./gradlew installDebug` installs on every attached device, the user's phone included; use `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
- Drive it with `adb`: `am start -n link.thesymbolic.jetcommand/.MainActivity`, `exec-out screencap -p`, `uiautomator dump` for tap coordinates, `input tap|text`.
- Its virtual Bluetooth never sees a BedJet. The "Demo BedJet" entry runs the full link/device stack on `SimTransport`, with fault buttons; `pm grant link.thesymbolic.jetcommand android.permission.BLUETOOTH_SCAN` (and `_CONNECT`) skips the permission dialog.
- The user's phone is reachable over wireless adb when connected (`adb devices`). Read a device setting before changing it for a test and restore it after; anything that writes to the BedJet needs the user's go, and it ends Off.

## Protocol

- `:protocol` is a pure-Kotlin port of the web app's `bedjet-codec` and `bedjet-sim` scripts; `BedJetSim` is the device only, connection state belongs to the transport.
- `FixtureTest` replays `fixtures.json`, recorded from the web app by `node scripts/gen-fixtures.mjs` (reads `../tools/bedjet-bluetooth-control.html`). After a protocol change on either side, regenerate and rerun `./gradlew :protocol:test`.
- Random cases miss boundaries: a behaviour worth pinning gets a hand-written case in the generator.
- Link tests: `SimTransport(sim, backgroundScope)` under `runTest` makes the sim tick on virtual time; pass `now = { currentTime }` to `BedJetLink`/`BedJetDevice`. Faults go through `inject(fault) { op -> … }`, `replyLatency`, `dropNotifications`, `loseLink()`, `chaos`.
- `BedJetLink` and `BedJetDevice` keep unlocked state: give them a single-threaded scope (Main or `limitedParallelism(1)`).

## BLE

- `app/.../ble/GattTransport` is the only `BluetoothGatt` user; minSdk 31 means every value-carrying GATT call and callback needs the API-33 overload plus the deprecated one, gated on `SDK_INT`.
- Lint `MissingPermission` is fatal: check the permission at the public entry point and suppress per method with the reason, never per file.

## Working here

- Commits: `area: subject` in lowercase (`protocol:`, `app:`, `ble:`, `docs:`).
- Parallel agents each get a worktree beside the repo (`git worktree add ../jetcommand-wt-<task> -b agent/<task>`); give agents disjoint files, and never two agents on the same screen.
- Notes in this file go under the matching heading, one fact per line; the deletion test applies.

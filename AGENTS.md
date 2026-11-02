# JetCommand (Android)

Native successor to `~/repos/projects/tools/bedjet-bluetooth-control.html`; protocol reference in `~/repos/projects/tools/docs/BEDJET_PROTOCOL.md`. Plan, decisions, status and the real-device checklist: `docs/ROADMAP.md` (update its status when a phase lands).

## Layout

| Layer | Where | Tested by |
|---|---|---|
| Codec, `BedJetSim` (device model) | `protocol/.../core/Codec.kt`, `shell/BedJetSim.kt` | `FixtureTest` (recorded from the web app), `StepCodecTest` (pinned fingerprint) |
| Transport contract, store interfaces | `protocol/.../ports/` | — |
| `SimTransport`, `BedJetLink`, `BedJetDevice` | `protocol/.../shell/` | `BedJetLinkTest`, virtual time |
| `StatusAssembler`, `reconnectDecision`, `linkStep` (the link's pure parts) | `protocol/.../core/` | `StatusAssemblerTest`, `ReconnectDecisionTest` |
| `ProgramLibrary`, `slotStatus` over a `Catalog` | `shell/ProgramLibrary.kt`, `core/Library.kt` | `LibraryTest`, `SlotStatusTest` |
| `UserCommand`, `plan()` (command policy) | `core/UserCommand.kt` | `PlanTest` |
| `CommandUri` (intent data, also in `shortcuts.xml`) | `app/` | `CommandUriTest` |
| `NightRecorder`, its state machine, `ProcessEvent` | `shell/NightRecorder.kt`, `core/NightLog.kt`, `core/ProcessEvent.kt` | `NightRecorderTest`, `ProcessEventTest` |
| `GattTransport`, scanner, permissions | `app/.../ble/` | real device only |
| Screen state and text: `now/NowState`, `ProgramsText`, `night/` | `app/` | `app/src/test` (JVM) |
| `BedJetController` (owns the stack), `BedJetService`, Room stores, composables | `app/` | emulator via Demo BedJet |

`:protocol` stays free of Android so its tests run on the JVM. Its `core/` (pure), `ports/` (interfaces) and `shell/` (effects) split is enforced by `ArchitectureTest`. Logic lives in pure functions over immutable values; classes hold one state value and run the effects, and composables take state, not view models.

## Toolchain

- Everything via `nix develop` (SDK, JDK 21, adb, node, sqlite3 for the pulled Room databases). Done means `./gradlew check assembleDebug` exits 0 (protocol tests, app unit tests, lint, APK); install with `./gradlew installDebug`.
- `check` also runs `spotlessCheck` (ktfmt, kotlinlang style), detekt (`detekt.yml`) and lint with Kotlin warnings as errors; run `./gradlew spotlessApply` first. The baselines (`*/lint-baseline.xml`, `*/detekt-baseline.xml`) hold pre-existing findings only: fix new ones, or suppress per site with the reason.
- New files must be `git add`ed before `nix develop` sees flake changes.
- SDK in the Nix store is read-only: `compileSdk`/`buildToolsVersion` in `app/build.gradle.kts` must match the versions in `flake.nix`, or AGP tries to install and fails.
- New dependency versions go in `gradle/libs.versions.toml`, checked to exist on Maven first.

## Emulator

- `emu` (in the dev shell) boots a headless API 36 AVD named `jetcommand`, creating it on first run; run it in the background, cold boot takes ~30 s. Reuse a running one (`adb devices`).
- `./gradlew installDebug` installs on every attached device, the user's phone included; use `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
- Drive it with `adb`: `am start -n link.thesymbolic.jetcommand/.MainActivity`, `exec-out screencap -p`, `uiautomator dump` for tap coordinates, `input tap|text`.
- Its virtual Bluetooth never sees a BedJet. The "Demo BedJet" entry runs the full link/device stack on `SimTransport`, with fault buttons; `pm grant link.thesymbolic.jetcommand android.permission.BLUETOOTH_SCAN` (and `_CONNECT`) skips the permission dialog.
- The user's phone is reachable over wireless adb at `192.168.178.69:<port>`; the port changes whenever wireless debugging restarts, so ask for it. `adb` exists only inside `nix develop`.
- Shortcuts: long-press the launcher icon in the app drawer; `am start` can't reach the non-exported `CommandActivity`.
- Faking a remembered device: add `device.address`/`device.name` to `shared_prefs/jetcommand.xml` via `run-as`, keeping `exit.seen`, or every past process exit is imported again.
- `pm grant` of the Bluetooth permissions skips the dialog that also asks for POST_NOTIFICATIONS; grant that too or the notification never shows.
- The user's phone: read a device setting before changing it for a test and restore it after; `batterystats --reset` and other state you can't restore need the user's go; anything that writes to the BedJet needs the user's go, and it ends Off.

## Protocol

- `:protocol` is a pure-Kotlin port of the web app's `bedjet-codec` and `bedjet-sim` scripts; `BedJetSim` is the device only, connection state belongs to the transport.
- `FixtureTest` replays `fixtures.json`, recorded from the web app by `node scripts/gen-fixtures.mjs` (reads `../tools/bedjet-bluetooth-control.html`). After a protocol change on either side, regenerate and rerun `./gradlew :protocol:test`.
- Random cases miss boundaries: a behaviour worth pinning gets a hand-written case in the generator.
- Link tests: `SimTransport(sim, backgroundScope)` under `runTest` makes the sim tick on virtual time; pass `now = { currentTime }` to `BedJetLink`/`BedJetDevice`. Faults go through `inject(fault) { op -> … }`, `replyLatency`, `dropNotifications`, `loseLink()`, `chaos`.
- A test collecting a `StateFlow` sees intermediate states only on `UnconfinedTestDispatcher`; a dispatched collector gets the conflated last value.
- `BedJetLink` and `BedJetDevice` keep unlocked state: give them a single-threaded scope (Main or `limitedParallelism(1)`).

## BLE

- `app/.../ble/GattTransport` is the only `BluetoothGatt` user; minSdk 31 means every value-carrying GATT call and callback needs the API-33 overload plus the deprecated one, gated on `SDK_INT`.
- Lint `MissingPermission` is fatal: check the permission at the public entry point and suppress per method with the reason, never per file.

## Working here

- Commits: `area: subject` in lowercase (`protocol:`, `app:`, `ble:`, `docs:`).
- Every commit builds: a `:protocol` change that breaks `app/` callers (a new `UserCommand` case) lands with them.
- Parallel agents each get a worktree beside the repo (`git worktree add ../jetcommand-wt-<task> -b agent/<task>`); give agents disjoint files, and never two agents on the same screen.
- `git stash` is shared by all worktrees, so agents in one checkpoint with WIP commits, never a stash.
- Before spawning parallel agents, commit the interface between their parts on main; both worktrees branch from that commit.
- Notes in this file go under the matching heading, one fact per line; the deletion test applies.

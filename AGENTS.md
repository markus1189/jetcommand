# JetCommand (Android)

Native successor to `~/repos/projects/tools/bedjet-bluetooth-control.html`; protocol reference in `~/repos/projects/tools/docs/BEDJET_PROTOCOL.md`.

- Toolchain only via `nix develop` (SDK, JDK 21, adb). Build: `./gradlew assembleDebug`; install: `./gradlew installDebug`.
- New files must be `git add`ed before `nix develop` sees flake changes.
- SDK in the Nix store is read-only: `compileSdk`/`buildToolsVersion` in `app/build.gradle.kts` must match the versions in `flake.nix`, or AGP tries to install and fails.

## Emulator

- `emu` (in the dev shell) boots a headless API 36 AVD named `jetcommand`, creating it on first run; run it in the background, cold boot takes ~30 s.
- Drive it with `adb`: `am start -n link.thesymbolic.jetcommand/.MainActivity`, `exec-out screencap -p`, `uiautomator dump` for tap coordinates, `input tap|text`.
- Its virtual Bluetooth controller never sees a real BedJet: test BLE against the in-app mock device or on a phone.

## Protocol

- `:protocol` is a pure-Kotlin port of the web app's `bedjet-codec` and `bedjet-sim` scripts; `BedJetSim` is the device only, connection state belongs to the transport.
- `FixtureTest` replays `fixtures.json`, recorded from the web app by `node scripts/gen-fixtures.mjs` (reads `../tools/bedjet-bluetooth-control.html`). After a protocol change on either side, regenerate and rerun `./gradlew :protocol:test`.
- Random cases miss boundaries: a behaviour worth pinning gets a hand-written case in the generator.
- Link tests: `SimTransport(sim, backgroundScope)` under `runTest` makes the sim tick on virtual time; pass `now = { currentTime }` to `BedJetLink`/`BedJetDevice`. Faults go through `inject(fault) { op -> … }`, `replyLatency`, `dropNotifications`, `loseLink()`, `chaos`.
- `BedJetLink` and `BedJetDevice` keep unlocked state: give them a single-threaded scope (Main or `limitedParallelism(1)`).

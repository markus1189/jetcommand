# Roadmap

Native successor to the JetCommand PWA (`~/repos/projects/tools/bedjet-bluetooth-control.html`), which stays the
fallback for desktop and iOS. Update the status column as work lands; decisions are settled unless new evidence
appears.

## Decisions

| Decision | Why |
|---|---|
| Native Kotlin + Compose, not Capacitor/TWA | The features worth having (overnight connection, alarms, morning review) run with no UI alive; a WebView app would need the protocol in Kotlin anyway, twice. |
| Protocol ported, not shared | `FixtureTest` replays behaviour recorded from the web app, so the two can't drift silently. |
| Mock seam at the transport, not the link | On Android our code does the GATT work (serialization, 133 retries, reconnects); a sim that replaced the link would leave it untested. |
| Demo BedJet is a runtime source, not a debug build | Same screen for the emulator, demos and the user without hardware. |
| minSdk 31 | BLUETOOTH_SCAN/CONNECT runtime permissions instead of location. |
| App id `link.thesymbolic.jetcommand` | Owned domain; changing it after install loses app data. |

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Repo, Nix toolchain, headless emulator | done |
| 2 | Protocol port + `BedJetSim`, cross-checked against the web app | done |
| 3 | `BedJetTransport` contract, `SimTransport`, `BedJetLink`/`BedJetDevice` | done |
| 4 | `GattTransport`, scanner, permissions | done, untested on hardware |
| 5 | Full-stack debug screen, Demo BedJet | done |
| 6 | Real-device test (checklist below) | **next** |
| 7 | Foreground service owning the link + night log (Room) | |
| 8 | Real UI: status/controls, program editor + library, night log + review | |
| 9 | Notification actions → exact-alarm program chaining → morning review (WorkManager) → CompanionDeviceManager auto-connect → QS tile/widget → Health Connect | |
| 10 | Import of the PWA's backup text format | |

## Known gaps

- Link gives up after 10 reconnect attempts (JS default); the overnight service needs to keep trying.
- `BedJetLink.replySource` is a plain var and the link doesn't expose the MTU; the debug screen works around both.
- Validation/verify errors carry only a message, not the JS read-back details.
- Optional spike: a second emulator as a BLE peripheral hosting `BedJetSim` via `BluetoothGattServer`, to test `GattTransport` without hardware (assumes emulators share netsim; unchecked).

## Real-device checklist

Record results here (date, phone, Android version) when run.

1. `./gradlew installDebug` over USB, grant both Bluetooth permissions.
2. Scan; the BedJet appears without "All devices" (if not: how is it advertised?).
3. Link reaches Ready; note MTU (expect > 23) and reply source (expect 2006).
4. Status updates ~1/s for 30 s; "extra" turns fresh now and then.
5. Every mode, fan ± and temp ±: the unit and the screen follow.
6. Read back name, firmware, memories, program names, slots 0–2; compare with the official app.
7. Sync clock: no notification appears.
8. Out of range or power-cycle: reconnect attempts, then Ready; note any status 133.
9. Rotate and background the app: the link stays Ready.
10. On Android 12 (API 31/32) if available: repeat 3–5 (deprecated-callback path).

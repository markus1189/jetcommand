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
| 4 | `GattTransport`, scanner, permissions | done, works on a real unit |
| 5 | Full-stack debug screen, Demo BedJet | done |
| 6 | Real-device test (checklist below) | done (API 31/32 path untested) |
| 7 | Foreground service owning the link + night log (Room) | **next** |
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

### 2026-10-22: Samsung SM-S948B, Android 17 (API 37), wireless adb, firmware V1.20

- 2–3: found as `BEDJET_V3` without "All devices", connects as `BEDJET-CD9A`; MTU 500; replies from 2006.
- 5: the user played with the controls: "seems to work".
- 6: name, firmware (`V1.20 (140) · V1.20 (412) · V1.20 (182)`), memories and all three slots read back (slot 1: 12 steps incl. clock-time steps).
- 4: resolved. The unit notifies ~4/s with 20-byte heads (byte 0 = 1) even at MTU 500; the remainder read returns the 11 missing bytes and merges every 2 s. "Stale" was the screen showing only the latest update; it now shows the extra's age (0–2 s sawtooth observed).
- 7: clock sync: no error, no notification after.
- 8 (Bluetooth off/on via `cmd bluetooth_manager`): lost with status 22, two attempts failed while off ("CONNECT could not be started"), attempt 3 Ready ~13 s after Bluetooth returned; no 133. Walking out of range not tried.
- 9: two rotations and 30 s in the background: link stayed Ready, no drop in the log; no crash in logcat.
- "Programs" = "Memories" names is the unit (`BEDJET_PROTOCOL.md:109`), not a bug.
- Open: 10 (no API 31/32 phone).
- Lesson: a scripted tap hit a mode button after read results shifted the layout. Keep commands that change the bed apart from reads.

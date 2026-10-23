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
| One `BedJetController` per process; the UI and `BedJetService` only observe it | One owner of the GATT connection whether or not an activity exists. |
| Night log in Room via KSP 2.3.x | KSP 2.3+ is versioned apart from Kotlin and builds with 2.4.20. |
| App id `link.thesymbolic.jetcommand` | Owned domain; changing it after install loses app data. |
| UI is a rethink, not a port of the PWA layout | The user isn't attached to it; concepts (gauge, timeline, templates, night log, review) carry over. |
| Night use: temp ± often, fan ± sometimes, from notification/lock screen | The user's night routine; the app stays one tap away for the rest. |
| Dark by default, no red tint (for now) | User's call. |
| Four places: Now, Programs, Nights, Settings (Diagnostics + Demo inside) | Agreed; bottom navigation. |
| UI built in small iterations with design rounds; program editor deferred | Write-vs-read separation and undo need to be felt, not specified; the editor is the least clear part. |
| Notification actions: Temp −, Temp +, Fan + (user's pick); Fan −, Stop, Disconnect only in the app | Three is the most a notification shows. |
| Notification is `MediaStyle` with compact actions but no MediaSession, on channel `controls` (DEFAULT importance, no sound) | Buttons visible collapsed and on the lock screen; no session keeps media/headset buttons untouched; silent (LOW) channels are hidden on lock screens. |
| Program library is the source of truth; the 3 slots are a read-only per-device cache; edits go library → push | The unit stores only name + steps, no id; one owner keeps conflicts resolvable. |
| Slot identity: push record `(device, slot) → program, rev, read-back fingerprint`, content match as fallback; the name counts | Detects every outside change (official app, rename); attributing foreign programs is impossible by construction. |
| No PWA compatibility; generic readable JSON for sharing programs maybe later | The user never used the PWA backup. |

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | Repo, Nix toolchain, headless emulator | done |
| 2 | Protocol port + `BedJetSim`, cross-checked against the web app | done |
| 3 | `BedJetTransport` contract, `SimTransport`, `BedJetLink`/`BedJetDevice` | done |
| 4 | `GattTransport`, scanner, permissions | done, works on a real unit |
| 5 | Full-stack debug screen, Demo BedJet | done |
| 6 | Real-device test (checklist below) | done (API 31/32 path untested) |
| 7 | Foreground service owning the link + night log (Room) | done on the emulator; overnight on a real unit open |
| 8 | Real UI: status/controls, program editor + library, night log + review | iteration 1 (night loop: Now screen, lock-screen notification controls, bottom nav, dark theme) on the user's phone 2026-10-22; night 1: service SIGKILLed at 22:55 as FGS (likely Samsung battery optimisation), night 2 pending; then a design round; nights tab on the emulator 2026-10-23; editor, library open |
| 9 | Notification actions → exact-alarm program chaining → morning review (WorkManager) → CompanionDeviceManager auto-connect → QS tile/widget → Health Connect | |
| 10 | ~~Import of the PWA's backup text format~~ | dropped 2026-10-23 |

## Next

1. Night 2 (battery now Unrestricted): did the service survive? Check `dumpsys activity exit-info link.thesymbolic.jetcommand` first (logcat rotates past a kill by morning), then the night log (`run-as link.thesymbolic.jetcommand`, `databases/night-log.db` plus `-wal`): `PROCESS` rows, drops, reconnects; reconnect gaps should now stay under ~30 s (wait capped at 10 s).
2. If the service died again: START_STICKY plus a restart path, or CompanionDeviceManager.
3. Design round on iteration 1 with the user's notes from the nights.
4. Nights screen landed (list, review, Vico timeline with scrubbing; design on https://claude.ai/artifact/Xe8NsmhAqKkYV9Zxv91CQo): check it against night 2's real data. Vico joins points across gaps unless `GapInterpolator` lifts the pen; there is no dual y-axis, so °C and fan are stacked panels.


## Program library (design 2026-10-23)

Protocol: `BEDJET_PROTOCOL.md:99-198`. Design canvas: https://claude.ai/artifact/MmXMAKknPjhZAist227cdp

1. Slots re-read in full on every Ready, in a low-priority lane behind user commands (the link queue needs one); offline the cache shows its age.
2. Room: `program` (id, currentRev, deletedAt tombstone), `program_revision` (immutable: name, steps as 5-byte wire blob, start, note, origin), `slot_state` (cache + push record + pending write). `fingerprint()` over the wire encoding lives in `:protocol`.
3. Write: validate → re-read slot and compare to cache → confirm overwrite → store pending intent → fragments, name, read-back verify → push record. A slot holding a program found nowhere else is saved to the library first. Writes are blocked while a program runs. A pending intent found on the next Ready resolves to done or "interrupted, retry".
4. Unseen content on a linked slot is saved as a non-current `outside` revision; the user picks take / keep / split off, never automatic. A slot matching a different entry re-links to it.
5. Run from the library: ranked slot picker, best preselected (already holds current → older rev → empty → in-sync linked, oldest push → orphan, auto-saved).
6. One step strip (width = duration, height = target °C, wait = stub; Now canvas board E, https://claude.ai/artifact/1rDbxtKAxLkZAxuWBTvwLj) for library rows, program detail and Now. Now needs the running slot's steps from the cache; the status gives only the step number, so a program started outside the app is matched by step count against the cached slots (ambiguous when two fit).

## Known gaps

- Service is `START_NOT_STICKY` and there is no boot receiver: if the process is killed overnight, the connection stays down until the app is opened (auto-connects to the remembered device then).
- Without BLUETOOTH_CONNECT the demo runs without the service (Android 14+ refuses a connectedDevice FGS).
- Night log keeps every event forever; no pruning yet.
- Background service costs ≈ 2 mAh/h (30 min measured on the phone, before change-only notification posts); the user charges the phone overnight, so night battery is not tracked.
- Status changes that happen while the link is down are stamped when the phone sees them after the reconnect.
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
11. With JetCommand connected, can the official app connect? (Slot refresh assumes not.)
12. Write commit point: drop the link after the last fragment, before the name; what does the slot hold? (User's go; ends Off.)
13. Overwrite the slot of a running program: what does the unit do? (User's go; ends Off.)

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

# BedJet V3 BLE Protocol (JetCommand reference)

What JetCommand implements, with sources. Sources:

- **OFF**: official Android app v1.0.6, decompiled in `markus1189/bedjet-re` (`decompiled/app/src/main/java/com/bedjet/bedjet/…`)
- **LULL**: `rkanjani/lull` `BedJetKit` (iOS, fixtures captured from a real unit, firmware V1.20)
- **ESP**: ESPHome `components/bedjet` (`bedjet_codec.h`)

Precedence: OFF wins every conflict. LULL and ESP only fill gaps OFF doesn't cover. JetCommand adds
checks OFF lacks (clock sync before start, read-back after save) where they don't contradict OFF.

## GATT

Base `0000XXXX-bed0-0080-aa55-4265644a6574`, service `1000`.

| Char | Use |
|---|---|
| `2000` | Status: notify; read returns the remainder of a partial status |
| `2001` | Device name (read, ASCII) |
| `2004` | All commands, including `0x40`/`0x41` (write with response) |
| `2005` | Bio reply (read). OFF reads replies here (`MainActivity.java:107,294`) |
| `2006` | Bio reply (read). LULL reads replies here (`BLELink.swift`) |

**Settled on hardware** (BedJet 3, firmware V1.20 (140)/(412)/(182), Chrome 155 on Android, probe 2026-10-21): both
hold the same reply, but a `2005` read stops at 22 bytes (ATT MTU 23 without long read) while `2006` returns all of it
(e.g. 50 bytes for `0x20`). Fragments (up to 43 bytes) and names (50 bytes) need `2006`. JetCommand reads `2006` first
and falls back to `2005`. OFF reading `2005` presumably works because Android's own stack does long reads (unchecked).

Write with response (`writeValueWithResponse`). 43-byte frames need a long write; Android/Chrome do it
automatically for writes with response. Never use write-without-response for `0x40` frames.

## Commands (write to `2004`)

| Bytes | Meaning |
|---|---|
| `01 bb` | Button press (table below) |
| `02 hh mm` | Set remaining runtime |
| `03 t` | Target temperature, half-°C |
| `07 f` | Fan step 0–19, speed = (f+1)×5 % |
| `08 hh mm` | Set unit clock (24 h) |
| `40 id …` | Bio write (see Biorhythm) |
| `41 id tag` | Bio query; reply read from `2005`/`2006` |

Buttons:

| Code | Meaning | Source |
|---|---|---|
| `01` off · `02` cool · `03` heat · `04` turbo · `05` dry · `06` ext. heat | Mode | OFF/ESP/LULL |
| `10`/`11` | Fan up/down | OFF `Constants.java` |
| `12`/`13` | Temp up/down (°C step) · `14`/`15` (°F step) | OFF |
| `20`–`22` | Recall memory M1–M3 | OFF/ESP |
| `28`–`2A` | Save current settings to M1–M3 | OFF |
| `46`/`47` | LED on/off | OFF `MenuFragment.java:328` ("Disable LEDs" → `47`), LULL |
| `48`/`49` | Mute/unmute beeps | OFF, LULL |
| `4C` | **Factory reset. Never send.** | OFF `MenuFragment.java:399` |
| `52` | Acknowledge notification | OFF/ESP |
| `80+slot` | Start biorhythm slot 0–2 | OFF `BiorhythmFragment.java:1153` |
| `83+slot` | Delete biorhythm slot 0–2 | OFF `BiorhythmFragment.java:858` |

## Status packet (notify on `2000`)

Indices include byte 0. OFF strips byte 0 before parsing, so its indices are one lower.

| Byte | Field |
|---|---|
| 0 | `1` = partial: read `2000` and append the result |
| 1 | `0x56` (V3 format) |
| 2 | Expected total length |
| 3 | `0x01` (status) |
| 4–6 | Remaining h, m, s |
| 7 / 8 | Actual / target temperature (half-°C) |
| 9 | Mode: 0 standby, 1 heat, 2 turbo, 3 ext. heat, 4 cool, 5 dry, 6 wait |
| 10 | Fan step 0–19 |
| 11–12 | Max runtime h, m for the current mode/temp/fan |
| 13 / 14 | Min / max target temperature for the current mode (half-°C); live, dry depends on ambient |
| 15–16 | Turbo time |
| 17 | Ambient temperature (half-°C) |
| 18 | Last shutdown reason (codes undocumented) |
| 22 | `0x02` = dual zone |
| 26 | Update phase |
| 27 | Flags: `0x20` conn test passed, `0x10` LEDs on, `0x04` units set up, `0x01` beeps muted |
| 28 | Biorhythm step (1-based, 0 = no program running) |
| 29 | Notification: 1 clean filter, 2 update available, 3 update failed, 4 program needs clock, 5 program step too long |

Notifications repeat until acknowledged with `01 52`. OFF acknowledges every notification immediately and shows
its text once per code until byte 29 returns to 0 (`MainFragment.java:370-386`). Reading the remainder at most every
2 s is enough (LULL).

Fixture (LULL `CodecTests.swift`, full packet after merge):
`01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 35 00 01 53`
→ standby, actual 46, target 79, fan step 19 (100 %), ambient 45, LED on, muted, step 0, notification 1.
The first 20 bytes alone must still parse (without bytes 27–29).

Fallback temperature ranges when bytes 13/14 are not available (OFF `Utils.getMinMaxTemp`, read from bytecode):
heat 45–80, turbo 86, ext. heat 38–67, cool 38–62, dry 40–62, standby/wait 80. LULL says dry 40–80; the editor code
says 62. These are the editor's limits, not the unit's: live bytes 13/14 on a real unit (V1.20) reported cool 38–52
and standby 20–80 (`bedjet-re/captures/2026-10-21-bedjet3-v1.20.md`).

## Bio queries (`41 id tag`)

Tag 1–255, wraps to 1. Reply: `id tag payload…`. The reply characteristic keeps stale contents until the unit answers:
accept only when the first two bytes match. Retry as OFF does (`MainActivity.requestWithRetry`/`doReadRetry`): read
the reply after each write ack; if it doesn't match, re-send the same request with the **same tag** every 400 ms.
JetCommand gives up after 8 attempts.

| `id` | Payload |
|---|---|
| `0x00` | Device name, NUL-terminated |
| `0x01` | Memory names, 3 × 16 bytes (on a V1.20 unit identical to the `0x04` names; the official app shows the same; cause open) |
| `0x04` | Biorhythm names, 3 × 16 bytes; slot whose first byte is `0x01` = never saved |
| `0x20` | Firmware versions: three NUL-padded 16-byte strings, e.g. `V1.20 (140)`, `V1.20 (412)`, `V1.20 (182)` |
| `7 + 4·slot + i` | Fragment `i` (0–3) of slot 0–2 |

Fragment payload: `count` (total steps in the program, 0–32), then up to 8 steps × 5 bytes. Read fragments until
`count` steps are collected.

Fixtures (LULL, full replies including `id tag`):
- Fragment: `07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 …` (43 bytes, zero padded) →
  4 steps: cool/fan 9/44/1h00, cool/13/49/3h00, cool/9/46/1h15, heat/4/47/1h00
- Names: `04 05 63 68 6f 74 65 20 6d 6f 74 65 7a 00 00 00 00 00 01 00 … 73 6c 65 65 65 65 70 00 …` →
  `["chote motez", null, "sleeeep"]`

## Biorhythm write (`40`)

- Step frame, 43 bytes: `40, 7+4·slot+i, count, 8 × step` zero padded. Every fragment repeats `count`. Send
  `ceil(count/8)` fragments, each after the previous write is acknowledged (OFF `writeNextBioDataBlock`).
- Step, 5 bytes: `mode, fan(0–19), temp(half-°C), hours | 0x80 if clock time, minutes`. Clock time = the step ends at
  that wall-clock time; otherwise the value is a duration. Example: cool, fan 14, temp 36, until 06:30 = `04 0e 24 86 1e`.
- Name frame, 19 bytes: `40, param, 0x10, ≤15 ASCII bytes, zero padded`. `param`: 0 device name, 1–3 memory names,
  4–6 biorhythm names (OFF `RenameType.java`, `MainActivity.setBedJetParam`). `0x10` is constant in OFF.
- Limits: 3 slots, 32 steps. Step length: the OFF editor allows exactly 12 h 00 (`BiorhythmAdapter.java:228-233`;
  `MAX_DURATION_IN_MINUTES = 720` is declared but unused); LULL says the unit refuses ≥ 12 h (unverified). JetCommand
  caps at 11 h 59 until a device test settles it.
- JetCommand order: fragments → name → read back and compare (LULL; OFF doesn't verify).

**Open:** whether the unit commits after the last fragment or after the name (OFF starts an unsaved program with
`01 80+slot` right after the last fragment ack, before the name is entered, `BiorhythmFragment.java:959-985`, which
suggests the fragments commit it); wait-step (mode 6) and clock-time
edge cases (midnight, first step).

## Program editor rules (OFF `BiorhythmAdapter.java`, `Utils.java`)

- **Clock-time** ("Run until") is offered only for wait, ext. heat and cool (`hasTimeMode`, BA:447); any position,
  including step 1. Hours 0–23. Choosing another mode clears the flag.
- **End time**: the app starts at the current phone time; duration steps add h:m, a clock-time step jumps to its
  HH:MM (wraps midnight). `checkStepTime` (U:303) tests "< 12 h after the previous end", but OFF only blanks the
  "Ends at" label when it fails: nothing reads the result, so OFF saves and starts such programs anyway and the unit
  refuses them at start (notification 5). Quirk: a target a few minutes earlier in the same hour passes the test.
  JetCommand enforces the rule itself and rejects these steps before saving (`bedjet-re/docs/OFFICIAL_APP.md`).
- **Wait**: temperature forced to 80; fan stored but disabled in the UI; shown as "----".
- **Turbo**: 1–10 min, temp 86.
- **Fan**: 5–100 % for all modes. Max step duration 12 h, except heat per the cut-off table.
- New-step default: wait, fan 19, 1 h, temp 80.

Suggested programs (OFF `BiorhythmFragment.java:423-506`), usable as templates (fan in %, temp in half-°C):

| Profile | Steps |
|---|---|
| Cold, 15–25 | turbo 100 % 5m; turbo 50 % 5m; wait 10m; cool 30 % 10m; heat 30 % 47 6h30 |
| Cold, 26+ | turbo 100 % 5m; turbo 50 % 3m; wait 10m; cool 30 % 10m; heat 30 % 49 6h00 |
| Neutral, 15–25 | turbo 100 % 5m; turbo 50 % 4m; wait 10m; cool 40 % 20m; cool 25 % 6h30 |
| Neutral, 26+ | turbo 100 % 5m; turbo 50 % 3m; wait 10m; cool 30 % 20m; cool 20 % 6h00 |
| Hot, 15–25 | turbo 100 % 3m; turbo 30 % 3m; wait 10m; cool 50 % 20m; cool 35 % 6h30 |
| Hot, 26+ | turbo 100 % 3m; turbo 30 % 3m; wait 10m; cool 45 % 20m; cool 30 % 6h00 |

Cool steps use temp 40. Options: "Sleep + Wake" appends heat 35 % 70 10m, heat 40 % 76 10m, heat 45 % 80 10m;
"Cloud Sheet" adds +10 % fan to every step (max 100 %).

Notification texts (OFF `MainFragment.java:370-386`): 4 "The specified sequence cannot be run because the clock is
not set"; 5 "…because it contains steps that would be too long running from the current time."

## Heat cut-off (OFF `Utils.getBioStepMaxTime`, `Constants.runtime_table`)

Applies only to mode heat with temp ≥ 60; otherwise the limit is 12 h. `row = runtime_table[fanStep]`,
`b = row[(temp-60) >> 1]`, hours = `((temp-60) odd ? b : b >> 4) & 15`. A step longer than that makes the unit refuse
the program (notification 5) or stop early.

```
rows 0-4:  cc cc cc cc cc cc cc c6 66 66 41
row 5:     cc cc cc cc cc cc c6 66 64 44 21
row 6:     cc cc cc cc cc cc c6 64 44 44 21
row 7:     cc cc cc cc cc cc c6 64 42 22 21
row 8:     cc cc cc cc cc cc c4 44 42 22 21
row 9:     cc cc cc cc cc cc c4 44 42 11 11
row 10:    cc cc cc cc cc c4 44 44 42 11 11
row 11:    cc cc cc cc cc 44 44 42 21 11 11
rows 12-13: cc cc cc cc c4 44 44 42 21 11 11
rows 14-19: cc cc cc cc 44 44 44 42 21 11 11
```

Checks (LULL tests): heat/80/fan 19 → 1 h; heat/75/fan 19 → 2 h; heat/80/fan 0 → 4 h; heat/81/fan 0 → 1 h;
heat/66/fan 19 → 12 h; cool → 12 h.

## Auto-off timer (`02 hh mm`; OFF `MainFragment.showTimer`, `PickerDialog`)

- Offered in every mode but standby and wait (`Utils.isIdleMode`), so also during a program step. There it sets the
  step's remaining time, shorter or longer than planned (observed); at its end the next step runs its own full length.
- Turbo: a 1–10 min picker, sent as `02 00 mm`.
- Other modes: hours 0 to `Utils.getMaxRunTime` (the heat cut-off above, else 12), minutes 0–59; picking the top
  hour forces minutes to 0, so the ceiling is exactly *max*:00. 0:00 is sent unchecked (effect unverified).
- OFF computes the ceiling from the table and never reads status bytes 11–12.

## Starting a program

Set the clock (`08 hh mm`, local time; OFF only does this in setup and from the menu), then `01 80+slot`. Watch
status for up to 4 s: byte 28 > 0 = started; byte 29 = 4 or 5 = refused → acknowledge with `01 52` and report.
Stop a running program with `01 01`.

## Connection

The unit accepts one connection and stops advertising while connected. Release it when the page is hidden so
another phone can connect. Web Bluetooth cannot advertise, so LULL's phone-to-phone hand-over is not possible here.

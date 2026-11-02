# JetCommand

An Android app for the BedJet 3 climate system, over Bluetooth LE.

It started as a web app in my [tools](https://github.com/markus1189/tools) collection. That one still works on desktop and iOS, but a browser tab can't hold a connection all night, so this is the native rewrite.

What it does:

- Controls mode, temperature, fan and timer, from the app or from three buttons on the lock-screen notification (Temp −, Temp +, Fan +)
- Keeps the connection in a foreground service overnight and reconnects after drops
- Logs each night (temperature, fan, every command) and shows it as a timeline the next morning
- Keeps a library of programs and syncs them with the three program slots on the unit
- Adds launcher shortcuts (Off, Program 1–3) that other apps, such as Samsung Routines, can trigger
- Includes a Demo BedJet, a simulated unit, so you can try everything without the hardware

Tested on one BedJet 3 (firmware V1.20) with a Samsung phone on Android 17. Needs Android 12 or later. Android 12 itself is untested.

## Building

Everything comes from the Nix flake:

```sh
nix develop
./gradlew check assembleDebug
./gradlew installDebug
```

Without Nix you need JDK 21 and the Android SDK versions listed in `flake.nix`.

The Bluetooth protocol lives in its own module, `protocol/`, as plain Kotlin with no Android dependency, so its tests run on the JVM. `docs/ROADMAP.md` has the design decisions and known gaps.

## Disclaimer

Not affiliated with or endorsed by BedJet LLC. BedJet is their trademark. This is an unofficial app: it writes to your unit, and you use it at your own risk.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).

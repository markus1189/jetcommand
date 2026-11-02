# JetCommand

An Android app for the BedJet 3 climate system, over Bluetooth LE.

What it does:

- Controls mode, temperature, fan and timer, from the app or from buttons on the lock-screen notification
- Keeps the connection in a foreground service overnight and reconnects after drops
- Logs each night (temperature, fan, every command) and shows it as a timeline the next morning
- Keeps a library of programs and syncs them with the three program slots on the unit
- Adds launcher shortcuts (Off, Program 1–3) that other apps, can trigger
- Includes a Demo BedJet, a simulated unit, so you can try everything without the hardware

Tested on one BedJet 3 (firmware V1.20) with a Samsung phone on Android 17. Needs Android 12 or later.

## Building

Everything comes from the Nix flake:

```sh
nix develop
./gradlew check assembleDebug
./gradlew installDebug
```

Without Nix you need JDK 21 and the Android SDK versions listed in `flake.nix`.

The Bluetooth protocol lives in its own module, `protocol/`, as plain Kotlin with no Android dependency, so its tests run on the JVM.

## Built with AI

Most of the code and docs were written by Claude (Anthropic) through Claude Code, with me setting the direction, making the design calls and testing on a real BedJet.

## Disclaimer

Not affiliated with or endorsed by BedJet LLC. BedJet is their trademark. This is an unofficial app: it writes to your unit, and you use it at your own risk.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).

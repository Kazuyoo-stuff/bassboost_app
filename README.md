# BassBoost

![Build](https://github.com/Kazuyoo-stuff/bassboost_app/actions/workflows/build.yml/badge.svg)

A minimal Android system service that boosts low frequencies on the **global output mix**.
No UI, no launcher icon, no background polling: it attaches audio effects once and is
configured entirely from a root shell.

## Features

- **Dynamics Processing** low-band boost (60-300 Hz) with a built-in limiter
- **Loudness Enhancer** for overall gain, useful at low volume
- **Built-in BassBoost** (optional, off by default in the recommended profile)
- Runtime configuration through intents, no app launch required
- Settings persist in a local database and survive reboots
- Starts on boot as a foreground service

## How it works

All effects are attached to audio session `0` (the global output mix) with maximum priority.

| Effect | Role |
|---|---|
| Dynamics Processing | 4-band pre-EQ: 0-60 Hz untouched, 60-150 Hz full boost, 150-300 Hz 60% of the boost, above 300 Hz untouched. A limiter (-3 dB, 20:1, attack 1 ms, release 80 ms) prevents clipping |
| Loudness Enhancer | Applies overall gain in millibels |
| BassBoost | Android's built-in effect (NXP on the tested device). Weak, and optional |

Sub-60 Hz content is deliberately not boosted: phone speakers cannot reproduce it, and
boosting it only triggers speaker protection and wastes headroom.

## Requirements

- Android 13+ (`minSdk 33`)
- Root (KernelSU or Magisk) to install as a privileged system app and to send commands
- JDK 17 and Gradle 8.5+ to build

## Build

### GitHub Actions (recommended)

Every push to `main` builds the APK. Download it from **Actions > latest run > Artifacts > `BassBoost-release`**.

### Local

```sh
# compileSdk 35, Android SDK required
gradle assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

The repository does not ship the Gradle wrapper, so use a local Gradle installation.
The unsigned APK installed successfully as a privileged app on the tested device.

## Configuration

Commands must be run from a root shell. Every change is applied immediately to the running
effects and stored in the database.

| Action (`dev.kazuyoo.bassboost.` + ...) | Range | Description |
|---|---|---|
| `SET_DP_ENABLED` | 0 / 1 | Dynamics Processing bass effect on/off |
| `SET_LOW_GAIN` | 0-15 (dB) | Bass gain for 60-300 Hz |
| `SET_LOUDNESS_ENABLED` | 0 / 1 | Loudness Enhancer on/off |
| `SET_LOUDNESS_TARGET` | 0 and up (mB) | Overall gain in **millibels**. 100 = 1 dB, 0 = no gain |
| `SET_BASS_ENABLED` | 0 / 1 | Built-in BassBoost on/off |
| `SET_BASS_STRENGTH` | 0-1000 | Built-in BassBoost strength |

Template:

```sh
am startservice \
  -n dev.kazuyoo.bassboost/.BassService \
  -a dev.kazuyoo.bassboost.SET_LOW_GAIN \
  --ei value 8
```

Defaults on first run: `dp_enabled=1`, `dp_gain=6`, `loudness_enabled=1`, `loudness_target=0`,
`bass_enabled=1`, `bass_strength=1000`. An existing database keeps its stored values.

### Database

```
/data/data/dev.kazuyoo.bassboost/databases/config.db
```

Table `settings` (`key TEXT`, `value TEXT`). Keys: `dp_enabled`, `dp_gain`, `bass_enabled`,
`bass_strength`, `loudness_enabled`, `loudness_target`.

## Profiles

A profile is a set of values applied together. Only **Smooth** has been tested
(Infinix X6725, built-in speaker). The others are untested starting points.

| Profile | `DP_ENABLED` | `LOW_GAIN` | `LOUDNESS_TARGET` | `BASS_ENABLED` | Character |
|---|---|---|---|---|---|
| Smooth (tested) | 1 | 8 | 150 | 0 | Noticeable bass, smooth, low volumes stay reasonably loud |
| Heavy bass | 1 | 9-10 | 150 | 0 | Stronger bass, may sound rough at high volume |
| Safe | 1 | 5 | 100 | 0 | Light boost, low load on the speaker |
| Off | 0 | - | 0 | 0 | Original sound |

### Apply a profile with one command

```sh
S=dev.kazuyoo.bassboost
send() { am startservice -n $S/.BassService -a $S.$1 --ei value $2 >/dev/null 2>&1; }
send SET_BASS_ENABLED 0
send SET_LOUDNESS_ENABLED 1
send SET_LOUDNESS_TARGET 150
send SET_LOW_GAIN 8
send SET_DP_ENABLED 1
```

Copy the snippet into a file, change the numbers, and you have your own profile.

### Tuning guide

Change **one value per test**, using the same song and the same volume.

| Symptom | Try |
|---|---|
| Not enough bass | Raise `LOW_GAIN` by one step |
| Harsh or distorted at high volume | Lower `LOUDNESS_TARGET` first, then `LOW_GAIN` |
| Low volumes not loud enough | Raise `LOUDNESS_TARGET` (100, 150, 200) |
| Overall volume drops with the effect on | Keep `BASS_ENABLED=0` |
| Speaker rattles | Lower `LOW_GAIN` and do not raise it again |

### System equalizer

If your device has a vendor equalizer (for example DTS on Transsion phones), it stacks with
this service. The Smooth profile was tested with: 100 Hz +3, 300 Hz +2, 1K -1, 3K 0, 10K 0.
Avoid maxing the vendor low bands and `LOW_GAIN` at the same time.

## Verify

```sh
# play audio first; the Enabled column should be "y"
dumpsys media.audio_flinger | grep -B10 "name: Dynamics Processing" | grep -A1 "Session State"

# service log (may be empty depending on the shell)
logcat -s BassService -d | tail -10
```

## Troubleshooting

| Problem | Cause / fix |
|---|---|
| Commands run but nothing changes | Use a root shell. Confirm the effect with the `dumpsys` command above |
| No `BassService` lines in logcat | Some shells cannot read app logs. Use `dumpsys` instead |
| Effect missing from `dumpsys` | The app is not running as a system app, or the module APK is outdated. Reinstall the module and reboot |
| `sqlite3: not found` | Not all ROMs ship it. It is only needed to inspect the database |

## Limitations and safety

- Results depend on the speaker, firmware and any vendor audio processing (limiters, smart amplifier protection). Values from the tested device will not necessarily suit yours.
- Large bass gains increase speaker cone excursion. Do not run high gains at maximum volume for long periods.
- The limiter parameters are fixed and cannot be changed at runtime.

## License

Apache License 2.0. See [LICENSE](LICENSE).

# dev.kazuyoo.bassboost

APK system minimal — no UI, no icon, background service only.
Set BassBoost + LoudnessEnhancer ke global output mix (session 0).

## Compile

```sh
# Butuh Android Studio / SDK dengan compileSdk 35
cd bassboost_app
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## Install sebagai system app (KernelSU module)

Buat struktur module:
```
META-INF/com/google/android/
  update-binary   (magisk busybox sh)
  updater-script  (#MAGISK)
module.prop
system/
  priv-app/
    BassBoost/
      BassBoost.apk
```

`module.prop`:
```
id=kazuyoo-bassboost
name=Kazuyoo BassBoost
version=v1.0
versionCode=1
author=kazuyoo
description=Global BassBoost daemon, no UI
```

Install lewat KernelSU Manager → reboot.

## Atur config via shell (tanpa buka app)

### Set bass strength (0-1000, default 1000)
```sh
am startservice \
  -n dev.kazuyoo.bassboost/.BassService \
  -a dev.kazuyoo.bassboost.SET_BASS_STRENGTH \
  --ei value 800
```

### Set loudness target dBFS (0 = max boost, default -6)
```sh
am startservice \
  -n dev.kazuyoo.bassboost/.BassService \
  -a dev.kazuyoo.bassboost.SET_LOUDNESS_TARGET \
  --ei value -3
```

### Enable/disable bass (1=on, 0=off)
```sh
am startservice \
  -n dev.kazuyoo.bassboost/.BassService \
  -a dev.kazuyoo.bassboost.SET_BASS_ENABLED \
  --ei value 0
```

### Enable/disable loudness (1=on, 0=off)
```sh
am startservice \
  -n dev.kazuyoo.bassboost/.BassService \
  -a dev.kazuyoo.bassboost.SET_LOUDNESS_ENABLED \
  --ei value 0
```

Semua perubahan langsung applied ke effect yang sedang jalan
dan disimpan ke DB — persist setelah reboot.

## Cek status
```sh
logcat -s BassService -d | tail -10
```

## DB location
```
/data/data/dev.kazuyoo.bassboost/databases/config.db
```
Table: `settings` (key TEXT, value TEXT)
Keys: `bass_enabled`, `bass_strength`, `loudness_enabled`, `loudness_target`

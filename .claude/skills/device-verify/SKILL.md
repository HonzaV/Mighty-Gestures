---
name: device-verify
description: Procedures for verifying Mighty Gestures on the Android emulator or a USB device — boot the AOSP emulator, install the debug build, inject motion sensor data with adb, read logcat, inspect sensor registrations, battery/Doze state, and take screenshots. Use when checking sensor-driven or user-visible behavior on a real runtime.
---

# Device / emulator verification

Tools live in `$ANDROID_HOME` (default `~/Android/Sdk`). If `adb` is not on `PATH`, use
`$ANDROID_HOME/platform-tools/adb` and `$ANDROID_HOME/emulator/emulator`. Store screenshots and logs in the
session scratchpad, never in the repo.

## 1. Get a device
```bash
adb devices                                   # already attached?
$ANDROID_HOME/emulator/emulator -list-avds    # expect mg_api35 (AOSP, no Google APIs)
$ANDROID_HOME/emulator/emulator -avd mg_api35 -no-snapshot-save -no-boot-anim &   # add -no-window for headless
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
```
If the AVD is missing, run `scripts/setup-android-sdk.sh --with-emulator`. Start the emulator in the
background and don't leave it running unless the caller wants it to be.

## 2. Install and launch
```bash
./gradlew installDebug
adb shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1
```
Read the `applicationId` from `app/build.gradle.kts` (debug builds may add a suffix).

## 3. Inject motion (emulator only)
Values are m/s² (acceleration) and rad/s (gyroscope), device axes: x right, y up, z out of the screen.
```bash
adb emu sensor set acceleration 0:9.81:0      # upright, portrait
adb emu sensor set acceleration 0:0:9.81      # flat on table, screen up
adb emu sensor set acceleration 0:0:-9.81     # flat, screen down (flip)
adb emu sensor set gyroscope 0:0:6.0          # fast twist around z
adb emu sensor status                          # list sensors
```
For dynamic gestures, send a timed sequence (e.g. a shake: alternate x between +15 and -15 every 80 ms,
6–10 times) from a small bash loop. Emulator timing is coarse: use it to confirm wiring (sensor → detector →
rule → action), not to tune thresholds — thresholds are tuned with JVM trace tests.

## 4. Observe
```bash
adb logcat -c && adb logcat -v time -s MightyGestures:* AndroidRuntime:E   # app logs + crashes
adb shell dumpsys sensorservice | grep -i -A5 <applicationId>              # active listeners, rates, latency
adb shell dumpsys activity services <applicationId>                         # foreground service state
adb exec-out screencap -p > "<scratch>/screen-<step>.png"                   # then view the image
```

## 5. Power & background behavior
```bash
adb shell dumpsys batterystats --reset
# ... exercise the app ...
adb shell dumpsys batterystats --charged <applicationId> | grep -iE 'wake|sensor|alarm'
adb shell dumpsys deviceidle force-idle      # enter Doze; verify behavior
adb shell dumpsys deviceidle unforce
adb shell input keyevent KEYCODE_SLEEP       # screen off; KEYCODE_WAKEUP to wake
adb shell am kill <applicationId>            # process death; check service/rules recover as specified
```
Emulator battery numbers are not representative — report them as wiring checks and request a real-device
measurement for battery claims.

## 6. UI checks
```bash
adb shell cmd uimode night yes                 # dark mode; reset: adb shell cmd uimode night no
adb shell settings put system font_scale 1.5   # large font; reset: adb shell settings put system font_scale 1.0
adb shell wm size 1080x2400                    # other screen size; reset: adb shell wm size reset
```

## 7. Clean up
Reset any settings you changed (font scale, night mode, wm size), stop the emulator if you started it
(`adb emu kill`), and report what was verified and how, with evidence paths.

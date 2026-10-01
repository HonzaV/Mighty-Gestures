# Sensors, background execution & battery

Always-on motion detection is the hardest constraint in this app. Every design choice here must be justified by
measurement. Verify API behavior against the official docs for the current `targetSdk` — these rules change
between Android versions.

## Platform facts that drive the architecture (minSdk 35)

- **Background sensor access.** Since Android 9, apps in the background do not receive events from
  continuous-reporting sensors (accelerometer, gyroscope). Always-on detection therefore needs a component the
  system treats as foreground: a **foreground service** (with a visible notification) or a bound system service
  such as an **AccessibilityService**. Which one (or both) is an ADR decision; each has privacy, UX and F-Droid
  review implications.
- **Foreground-service types.** Since Android 14 every FGS must declare `android:foregroundServiceType` and hold
  the matching `FOREGROUND_SERVICE_<TYPE>` permission. No type is a perfect fit for "listen to motion sensors";
  `specialUse` (with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation) is the usual candidate. Android 15 limits
  which FGS types may start from `BOOT_COMPLETED` and adds timeouts for some types — check the current list
  before choosing.
- **Sampling-rate cap.** Since Android 12, accelerometer / gyroscope / magnetometer are capped at ~200 Hz unless
  the app holds `HIGH_SAMPLING_RATE_SENSORS`. Gestures should not need it; justify in an ADR if one does.
- **Screen off / suspend.** With the screen off the application processor may suspend. Non-wake-up sensors keep
  events in a hardware FIFO (if the device has one) and drop them when it overflows; **wake-up** sensors wake the
  AP. Holding a partial wakelock to keep sampling is a battery disaster and needs an explicit ADR.
- **Doze & App Standby.** Doze defers alarms/jobs and restricts wakelocks; FGS keep running but the device can
  still suspend. Test behavior with `adb shell dumpsys deviceidle force-idle`.
- **Overlay + FGS (relevant for v3).** Since Android 15, an app holding `SYSTEM_ALERT_WINDOW` may only start a
  foreground service from the background while it has a visible overlay window.

## Design rules

1. **Sample as slowly as the gesture allows.** Start from `SENSOR_DELAY_GAME`-class rates (~50 Hz) or lower;
   go faster only with trace evidence that detection needs it.
2. **Batch.** Register with `registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs)` and use
   the largest report latency the gesture's UX tolerates, so the AP sleeps between batches.
3. **Prefer hardware / low-power sensors** when they express the gesture: significant-motion (one-shot,
   wake-up), step detector, the platform gravity / linear-acceleration / rotation-vector virtual sensors, or
   device-specific wake gestures. Cascade: a cheap sensor gates the expensive one.
4. **Register only what active rules need.** No enabled motion rule → no sensor listener, no FGS.
   Unregister on rule disable, and on conditions the user configured (e.g. "only when screen on").
5. **Process off the main thread** with no per-sample allocations (reuse buffers, primitive arrays, no boxing,
   no per-event `Flow` emissions in the hot path — batch into windows first).
6. **Debounce actions** and add a cool-down per rule so one physical movement fires one action.

## Measuring

Every change touching sensor registration, the service, or the detection hot path must report:

| What | How |
|---|---|
| Registered sensors and actual rates | `adb shell dumpsys sensorservice` (look for the app's package) |
| Service state | `adb shell dumpsys activity services <applicationId>` |
| Wakelocks / wakeups | `adb shell dumpsys batterystats --charged <applicationId>` after `adb shell dumpsys batterystats --reset` |
| Doze behavior | `adb shell dumpsys deviceidle force-idle`, then `unforce`; check the app still behaves as specified |
| Hot-path cost | JVM micro-benchmark of the detector on a long trace (samples/second, allocations) |

Record numbers (device, Android version, duration, screen on/off) in the PR. Regression without justification
blocks merge.

# Testing strategy

Pragmatic pyramid. The bulk of the confidence comes from fast JVM tests of pure-Kotlin gesture detection
running against recorded and synthetic sensor traces.

```
            ┌───────────────────────────┐
            │ Device / emulator (few)   │  connectedDebugAndroidTest, manual adb sensor injection
            ├───────────────────────────┤
            │ Compose UI tests (some)   │  key screens & flows, semantics-based
            ├───────────────────────────┤
            │ Robolectric (some)        │  Android glue: service wiring, permissions, actions
            ├───────────────────────────┤
            │ JVM unit tests (most)     │  detectors, rule engine, ViewModels, mappers
            └───────────────────────────┘
```

## Tools

- JUnit 4 (Robolectric and Compose UI testing require it).
- `kotlinx-coroutines-test` (`runTest`, `StandardTestDispatcher`, injected `TestScope`); Turbine for Flows.
- Assertions: Truth or `kotlin.test` — pick one per module and stay consistent.
- Prefer hand-written **fakes** over mocks. MockK only for Android/framework types that cannot be faked cheaply.
- Compose: `createComposeRule()`, find nodes by semantics (`testTag` only when no accessible label exists).

## Sensor-trace fixtures (the core of detector testing)

Location: `<module>/src/test/resources/traces/<gesture>/<case>.csv`

Format (header required, one sample per line, SI units — m/s² for acceleration, rad/s for gyroscope):

```
timestamp_ns,sensor,x,y,z
0,ACC,0.02,9.79,0.11
5000000,ACC,0.05,9.81,0.09
5000000,GYRO,0.001,-0.002,0.000
```

Every gesture detector must have:

| Category | Examples | Expectation |
|---|---|---|
| Positive | the gesture performed slowly / quickly, left / right hand, portrait / landscape | detected exactly once |
| Negative | walking, running, stairs, phone in pocket / bag, on a table, in a car, picking up the phone, typing | never detected |
| Robustness | 50 / 100 / 200 Hz sampling, timestamp jitter, dropped samples, sensor offset/bias, gravity in any orientation | same result as clean input |
| Timing | gesture at the very start/end of a trace, two gestures back-to-back, cool-down/debounce | defined behavior |

Rules:
- Negative traces matter more than positive ones: a false trigger (e.g. toggling the flashlight in a pocket)
  is a worse bug than a missed gesture. Track a **false-positive budget** per detector in its spec.
- Synthetic traces come from a generator in test code (deterministic seed). Real traces are recorded on devices
  with the in-app debug recorder (when it exists) and committed with a short note on the device and situation.
- Never tune thresholds to make one trace pass without re-running the whole corpus.
- Keep traces small (seconds, not minutes); long negative recordings can be split.

## Robolectric

Use for: service lifecycle and notification, broadcast receivers, permission checks, action implementations that
call framework APIs (torch, audio, DND). Configure `@Config(sdk = [35])` at minimum plus the target SDK.

## Device / emulator verification

```bash
$ANDROID_HOME/emulator/emulator -avd mg_api35 -no-snapshot-save &
adb wait-for-device
./gradlew installDebug
adb emu sensor set acceleration 0:9.81:0        # device upright
adb emu sensor set acceleration 0:0:-9.81       # face down (flip)
adb shell dumpsys sensorservice | grep -A3 <applicationId>   # who listens, at what rate
adb logcat -s MightyGestures:*                   # app log tag
adb exec-out screencap -p > /tmp/screen.png      # screenshot for UI checks
```

The emulator sets static values; for dynamic gestures write a small script that sends a sequence of
`adb emu sensor set` commands with sleeps, or rely on JVM trace tests. Real-device checks are required before
release for each new gesture (emulator sensor timing is not realistic).

Use the AOSP (no Google APIs) `mg_api37` AVD instead of `mg_api35` when a test depends on target/compile
SDK 37 behavior (e.g. ADR 0007 F9 task-hijacking rules, AC-A2) — set it up with
`scripts/setup-android-sdk.sh --with-emulator --api37` (opt-in, separate from `mg_api35`, large download).
See the `device-verify` skill for when to use which emulator and for the real-device fallback that AC-H2/AC-A2
also require.

## Naming & structure

- Test class mirrors the class under test: `FlipDetector` → `FlipDetectorTest`.
- Test names describe behavior: `` `detects flip when device turns face down within 600ms` ``.
- Arrange / act / assert, one behavior per test. No sleeps — use virtual time.

## Coverage

JaCoCo measures the debug unit tests (JVM + Robolectric). A global gate fails the build below **75 % line
coverage**: `./gradlew :app:jacocoCoverageVerification` (also part of `check` and CI; HTML report in
`app/build/reports/jacoco/jacocoTestReport/html/`). Never lower the threshold or widen the exclusions to make it
pass. The `domain` layer (detectors, rule engine) should stay above ~80 % line coverage; uncovered branches in
detectors need a reason.

# Mighty-Gestures
Android google free app for automating motion gestures. More incoming. 

## Tech stack

- Kotlin + Jetpack Compose with Material 3, Android 15+ (`minSdk 35`)
- No Google Play Services / Firebase / trackers — enforced by `scripts/check-no-gms.sh`
- Unit tests run on the JVM via Robolectric

## Building

Requires JDK 21+ and the Android SDK (`scripts/setup-android-sdk.sh`).

```sh
scripts/verify.sh                            # full verification: Google-free check, Spotless, detekt, lint, unit tests
./gradlew :app:assembleDebug                 # build debug APK
./gradlew :app:jacocoCoverageVerification    # tests + enforce 75 % line coverage
./gradlew spotlessApply                      # format
```

The coverage HTML report is written to `app/build/reports/jacoco/jacocoTestReport/html/`.

## CI

`.github/workflows/ci.yml` runs on every pull request and push to `main`: `scripts/verify.sh`, the coverage gate
(fails below 75 % line coverage) and the debug APK build.

Contributors and AI agents: see [`AGENTS.md`](AGENTS.md).

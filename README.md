# Mighty-Gestures
Android google free app for automating motion gestures. More incoming. 

## Tech stack

- Kotlin + Jetpack Compose with Material 3
- No Google Play Services / Firebase — enforced by `./gradlew :app:verifyNoGoogleServices`
- Unit tests run on the JVM via Robolectric

## Building

Requires JDK 17+ (JDK 21 recommended) and the Android SDK (API 35).

```sh
./gradlew :app:assembleDebug                 # build debug APK
./gradlew :app:testDebugUnitTest             # run unit tests
./gradlew :app:jacocoCoverageVerification    # tests + enforce 75 % line coverage
```

The coverage HTML report is written to `app/build/reports/jacoco/jacocoTestReport/html/`.

## CI

`.github/workflows/ci.yml` runs on every pull request: it checks for forbidden Google
dependencies, builds the debug APK, runs unit tests and fails if line coverage drops below 75 %.

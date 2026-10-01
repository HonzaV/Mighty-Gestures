# F-Droid, reproducible builds & dependency policy

F-Droid builds the app from source on its own servers. Anything non-free or non-reproducible blocks inclusion
or adds Anti-Features to the listing.

## Build configuration requirements

- Disable AGP's dependency metadata blob (F-Droid rejects it — it is encrypted with a Google key):
  ```kotlin
  android {
      dependenciesInfo {
          includeInApk = false
          includeInBundle = false
      }
  }
  ```
- No dynamic or `SNAPSHOT` versions; everything pinned in `gradle/libs.versions.toml`.
- Repositories: `google()` and `mavenCentral()` only. No JitPack or custom repos without an ADR.
- No build-time non-determinism: no timestamps, git hashes of dirty trees, random values, or machine paths in
  `BuildConfig`, resources or the manifest. `versionCode` / `versionName` are set explicitly in Gradle.
- No prebuilt binaries (`.jar`, `.aar`, `.so`) committed to the repo.
- Gradle wrapper is committed with `distributionSha256Sum` set.

## Dependency policy

A new dependency requires, in the spec or PR description:
1. Why the platform / AndroidX can't do it in reasonable code.
2. License (must be AGPL-3.0-compatible: Apache-2.0, MIT, BSD, LGPL, MPL-2.0, GPL-3.0…).
3. Transitive dependencies checked for GMS/Firebase/trackers: `scripts/check-no-gms.sh --classpath` (part of
   `scripts/verify.sh`; scans every configuration of every module); inspect details with
   `./gradlew :app:dependencyInsight --configuration <config> --dependency <group>`.
4. APK size impact for the release build.

## Metadata & releases

- Store metadata in Fastlane layout (F-Droid reads it from the repo):
  ```
  fastlane/metadata/android/en-US/
    title.txt  short_description.txt  full_description.txt
    images/icon.png  images/phoneScreenshots/1.png …
    changelogs/<versionCode>.txt
  ```
- Each release: bump `versionCode` (monotonic integer) and `versionName` (SemVer), add
  `changelogs/<versionCode>.txt` (≤ 500 chars), tag `vX.Y.Z` on `main`.
- Keep `CHANGELOG.md` (Keep a Changelog format) in sync with the Fastlane changelog.
- Before tagging: build the release APK twice from a clean checkout and compare (`diffoscope` or
  `apksigcopier compare`) to confirm reproducibility.

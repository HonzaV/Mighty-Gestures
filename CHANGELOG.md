# Changelog

All notable changes to this project are documented here. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Added
- AI agent harness: `AGENTS.md`, Claude Code agents/skills/hooks, engineering guides, ADR/spec templates,
  Android SDK setup and verification scripts.
- Android app skeleton (`cz.mightybities.mightygestures`, minSdk 35): Compose + Material 3 home screen with
  dynamic color, Robolectric tests, JaCoCo 75 % line-coverage gate, GitHub Actions CI.
- ADR 0002: application ID and package name.
- Build tooling: Gradle 9.8 (wrapper with checksum), AGP 9.4 (built-in Kotlin), Kotlin 2.4, compile/target SDK 37,
  Spotless + ktlint, detekt; CI runs `scripts/verify.sh` on JDK 21.
- Spec 0001 (motion gestures) and ADRs 0003–0009.
- Action layer for spec 0001 (not user-reachable until the gesture UI and accessibility host land): open app
  (incl. over the lock screen via the bouncer), flashlight, lock screen, an app-owned Do Not Disturb mode and
  sound mode. New manifest entries: `ACCESS_NOTIFICATION_POLICY` (needed only for the DND / sound-mode
  actions), `<queries>` for launchable apps, and a non-exported lock-screen trampoline activity.
- Motion detection core for spec 0001 (pure Kotlin; not user-reachable until the sensor adapter, rule engine,
  gesture UI and accessibility host land): gravity filter, segmenter, preprocessor, DTW template matcher with
  collision check, template validator, capture session (record/confirm) and the trace CSV format. It is tested
  against a seeded synthetic motion generator (test code only; no real human traces). Detection thresholds are
  **provisional**: they are the ADR 0008 initial values and unverified on real human motion (spec 0001 AC-R1).
  A detekt rule keeps `android.*`/`androidx.*` imports out of `domain`.

### Changed
- Builds and tests require JDK 21 (was JDK 17); bytecode still targets Java 17.
- Conventional Commits 1.0.0 now spelled out in `AGENTS.md` and required for PR titles too; PR template updated.
- `scripts/verify.sh` (full/`--device`) now runs `jacocoCoverageVerification` and prints the line-coverage %;
  CI's separate coverage-gate step was folded into its `scripts/verify.sh` call.

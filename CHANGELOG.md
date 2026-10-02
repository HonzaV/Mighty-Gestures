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

### Changed
- Builds and tests require JDK 21 (was JDK 17); bytecode still targets Java 17.
- Conventional Commits 1.0.0 now spelled out in `AGENTS.md` and required for PR titles too; PR template updated.
- `scripts/verify.sh` (full/`--device`) now runs `jacocoCoverageVerification` and prints the line-coverage %;
  CI's separate coverage-gate step was folded into its `scripts/verify.sh` call.

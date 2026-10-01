# ADR 0002 — Application ID and package name

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** maintainer

## Context
The application ID is permanent: F-Droid, installed devices and user backups key on it, and changing it after
the first release means a new app for every user. ADR 0001 listed it as an open decision. The initial project
skeleton used `io.github.honzav.mightygestures`, tied to a personal GitHub account.

## Options considered
### Option A — `io.github.honzav.mightygestures`
- Pros: no domain needed; F-Droid accepts `io.github.<user>` IDs.
- Cons: binds the app to one personal account.
### Option B — `cz.mightybities.mightygestures`
- Pros: project-owned namespace, independent of where the repository is hosted.
- Cons: the reverse-domain convention implies control of `mightybities.cz`; F-Droid does not require domain
  ownership, but whoever holds that domain could publish under the same prefix.

## Decision
We choose **`cz.mightybities.mightygestures`** as the `applicationId`, the Android `namespace` and the root
Kotlin package. The maintainer made this choice.

## Consequences
- Positive: the ID stays stable even if the repository moves.
- Negative / accepted trade-offs: none before the first release; after it, this ID can never change.
- Follow-ups: use the same ID in the F-Droid metadata (`fastlane/`) and in `dumpsys`/`logcat` examples in docs.

## References
- ADR 0001 — Project baseline

# Architecture Decision Records

One file per decision: `NNNN-short-slug.md`, created from `0000-template.md`. Never rewrite an accepted ADR —
supersede it with a new one and update the old one's status.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-project-baseline.md) | Project baseline | Accepted |

## Pending decisions (need an ADR before code depends on them)
- Module layout (single `app` module vs `core`/`domain`/`feature` modules)
- Dependency injection approach
- Persistence (DataStore vs Room) for rules and settings
- Application ID / package name
- Always-on sensing host: foreground service type (e.g. `specialUse`) and/or AccessibilityService
- Gesture-detection approach (thresholds + state machines vs other on-device methods) and trace format details

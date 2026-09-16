# core

Platform-adjacent infrastructure and shared model types, per
`docs/03-architecture/system-architecture.md` (F0.8) §15.

- `di/` — Hilt modules (FND-002, this task).
- `model/`, `common/` — shared domain-adjacent types and utilities (FND-004).
- `database/` — Room (FND-003).
- `datastore/` — Preferences DataStore (settings; later task).
- `location/`, `activityrecognition/` — Android platform gateways (`TRK-*`, `DET-001`).
- `notification/` — active-trip notification controller (`NOT-001`).

Each subpackage is created by the task that first needs it, not pre-scaffolded
here, to avoid empty structure with no owner.

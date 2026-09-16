# domain

Trip state machine, GPS processing and merge/split logic per
`docs/03-architecture/system-architecture.md` (F0.8) §4 and `ADR-013`
("Detector y processing desacoplados de tipos Android").

**Hard rule, enforced by `app/src/test/java/.../architecture/DomainBoundaryTest.kt`:**
no file under this package may import `android.*` or `androidx.*`. Android
inputs are converted to domain models by adapters in `core`/`tracking` before
reaching this layer.

Subpackages (`detection/`, `processing/`, `trip/`) are created by the tasks
that need them: `DET-002` onward, `PRC-001` onward, `EDT-001` onward.

`capability/` was added by `CAP-001` — not one of F0.8 §15's originally
named subpackages, but the natural home for `CapabilityResolver`, the first
real domain logic in this codebase (everything before it was pure data
shapes or infrastructure).

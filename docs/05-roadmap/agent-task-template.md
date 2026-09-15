# Agent Task Template — Moto Trip Tracker V2

Use this template for Codex/Claude Code implementation or review tasks after Phase 1 is authorized.

## Task

**ID:** `<EPIC-NNN>`  
**Title:** `<short bounded title>`  
**Type:** `implementation | test | research/spike | review | migration`  
**Status:** `Ready | Blocked | In progress | Review | Done`

## Objective

One concrete outcome. Avoid combining unrelated systems.

## Why this task exists

Link the task to the product/reliability risk it resolves.

## Source documents

- `<path>`
- `<path>`

## Requirements implemented

- `<FR/NFR ID>`

## ADR constraints

- `<ADR-XXX>`

## Dependencies / prerequisites

- `<task ID>`

## In scope

- ...

## Out of scope

- ...

## Expected files/packages

- ...

The agent may add a narrowly necessary file inside the same responsibility, but must not restructure unrelated packages/modules.

## Implementation constraints

- Preserve accepted ADRs.
- No unrelated dependency upgrades.
- No silent architecture changes.
- No network/backend/analytics unless explicitly in task scope.
- Preserve Raw Track/data lineage and invariants when applicable.

## Acceptance criteria

1. ...
2. ...

## Automated tests required

- ...

## Manual/device validation required

- `None` or explicit steps.

## Definition of Done

- Acceptance criteria pass.
- Required tests pass.
- Relevant regressions pass.
- Diagnostics/privacy/recovery implications handled.
- Documentation updated if contract changed.
- No unrelated changes.

## Expected agent response

Return:

1. summary;
2. files changed;
3. tests/checks run and results;
4. manual validation performed;
5. known limitations;
6. blockers/follow-ups;
7. any ADR/doc conflict discovered.

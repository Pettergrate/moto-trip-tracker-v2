# worker

WorkManager workers for deferrable, persistent processing only (`ADR-010`) —
trip post-processing, cache rebuilds, future backup/export. Never live
tracking. Populated starting with `PRC-001`'s processing worker.

# tracking

Owns the active `TripCapture` as an Android component (`ADR-004`): the
foreground service, its session coordinator, and the receivers that feed it
(activity transitions, boot). `service/`, `coordinator/`, `receiver/` are
created by `TRK-001` onward.

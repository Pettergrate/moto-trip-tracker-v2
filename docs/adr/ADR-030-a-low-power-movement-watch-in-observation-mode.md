# ADR-030 — A low-power movement watch (geofence), in observation mode: it records when a detector built on it would have started, and starts nothing

**Status:** Accepted
**Date:** 2026-10-02
**Project:** Moto Trip Tracker V2
**Phase:** Phase 1 — `DET-011` (owner-approved: "me gusta la recomendación" - first measure, then decide)

---

## Context

All three automatic rides of 2026-10-02 began recording late (phone database, `diagnostic_event` + `raw_track_point`):

| Ride | Android's label before | First usable fix | Recorded from |
|---|---|---|---|
| 19:54 (1.2 km) | `WALKING` | 17 m/s, already decelerating | mid-ride |
| 19:06 (10.2 km) | `WALKING` for 10 min | standstill, accelerating | after the first stop |
| 09:44 (13.8 km) | `STILL` | 25 m/s (89 km/h) | highway, the town section missing |

`ADR-007`'s trigger is Activity Recognition, and it only starts a candidate on `IN_VEHICLE` (or, since `ADR-026`, `ON_BICYCLE`). On this phone Android says `STILL`/`WALKING` for as long as the rider is slow or the phone is in a pocket, and flips only once he is at speed or after the first stop. `ADR-027` keeps everything seen once the service is running, but the service (and the GPS) only start when the label arrives; nothing earlier exists to keep. This is a limit of the passive trigger, not of any threshold.

The remedy must not cost the battery the owner asked to protect (`ADR-026`: he rejected an always-on location trigger). What is needed is something that notices the phone *leaving the place it was parked*, cheaply, independently of the label, and that survives Android killing the process (which `ADR-028` already had to handle).

## Decision

1. **A geofence EXIT is the candidate signal.** A single circular geofence (`MovementWatchProfile.radiusMeters`, 150 m) is kept around where the phone is parked, with only an EXIT transition. It is registered with Play Services through a `PendingIntent` to `MovementWatchReceiver` (like the Activity Recognition registration, so it works with the process dead). It runs on network/Wi-Fi/cell positions, never GPS.
   - Why not plain location updates: Android limits a background app to a few location updates per hour, so a trigger built on them would be nearly blind. Geofencing is the platform's mechanism for exactly this.
   - Why not the significant-motion sensor (the phone has one, `sns_smd`): its listener lives in the process, so keeping it alive would need a permanent foreground service and notification, and the phone's battery manager kills background processes.
2. **Observation mode: it records, it starts nothing.** An EXIT is logged (`MOVEMENT_WATCH_EXIT`) as the moment a detector built on it *would have* opened a start candidate. No candidate is opened, no service started, nothing about how rides are recorded changes. A later decision, made from the data below, turns it into a trigger (the code that would is `ActivityTransitionReceiver.maybeStartAutoDetection`'s).
3. **Where the circle is centred.** On the phone's position taken once when arming, by the cheapest means: an already known position at most two minutes old, otherwise one balanced-power (network) fix within eight seconds; a position older than two minutes, or reporting worse than 100 m, is refused (`STALE_FIX`, `POOR_FIX`) rather than centred on. The coordinates exist only for the hand-over from the position source to the geofence registration - never stored, logged or put in a diagnostic (`ADR-009`); the only copy that outlives it is Play Services'.
4. **When it is armed or re-centred** (`MovementWatch.ensureArmed`): at every app start, boot or change of Auto Tracking (`AutoTrackingDetection.sync`, so it follows `PERM-002`'s on/off exactly); when Activity Recognition reports `STILL` (the phone has settled somewhere new); when a capture finishes or an automatic candidate comes to nothing (the phone is where the ride ended); and straight after an EXIT. Re-centring because of a sync or because the circle was just left is throttled to once per two minutes (opening the app syncs it more than once in a second; riding would otherwise cost a position every ~150 m); a place change (`STILL`, a finished capture) is never throttled. It never arms while a capture is ACTIVE.
5. **"Off means off."** With Auto Tracking not listening, the geofence is removed (always asked of the platform, because it outlives the process), nothing is recorded, and an EXIT that arrives after the switch was turned off is dropped, not logged.
6. **Every outcome leaves a trace** - the lesson of the empty deliveries that silenced Activity Recognition for two weeks: `MOVEMENT_WATCH_ARMED` (reason: why; radius, accuracy and age of the position, its source), `MOVEMENT_WATCH_ARM_FAILED` (reason: `LOCATION_SERVICES_OFF`, `PRECISE_LOCATION_MISSING`, `BACKGROUND_LOCATION_MISSING`, `NO_FIX`, `STALE_FIX`, `POOR_FIX`, `REGISTRATION_FAILED`), `MOVEMENT_WATCH_EXIT`, `MOVEMENT_WATCH_DISARMED` and `MOVEMENT_WATCH_BROADCAST_EMPTY` (a delivery with no usable event, with its reason or Play Services error code). Only counts, milliseconds and metres - no coordinate.
7. **The `PendingIntent` is mutable** (Play Services adds the transition as an extra; an immutable one makes every delivery arrive empty), with its own request code.

## How this is measured (the whole point of this ADR)

For each ride, from the `MOVEMENT_WATCH_*` events and the capture:

- **How much earlier would it have started?** Lead = the capture's `startedAt` - the first `MOVEMENT_WATCH_EXIT` after an `ARMED` and before `AUTO_DETECTION_STARTED`. Positive means the watch noticed the phone leaving before recording began; multiplied by the ride's early speed (stored points) it is the distance that would have been recovered.
- **How often does it fire for no ride?** `MOVEMENT_WATCH_EXIT` with no capture within the next five minutes: walks, a phone carried around, network-position jitter. Each would have been a start candidate (GPS for up to 5 minutes).
- **What does it cost?** Android attributes the network-location work to this app's own uid. Baseline taken before the watch ever ran (2026-10-02 21:02, after installing, before the first launch), `dumpsys batterystats --charged`: time on battery 10 h 26 m 48 s; **UID `u0a485`: 34.2 mAh** (foreground 2.74, background 2.83, foreground service 5.97, cached 21.9), i.e. ~0.57 % of the 6,000 mAh battery; wake locks attributed to the app: `NetworkLocationScanner` 149 times / 2 m 11 s, `CollectionLib-SigCollector` 325 times / 5 m 4 s, `NetworkLocationLocator` 168 times / 12.7 s; device-wide Wi-Fi scan time 1 h 53 m (18 %). After a day or two, the same command, compared by rate (mAh per hour on battery) - the counter resets when the phone is charged - plus the number of watch events per day as the app-side cost.

## Rationale

- The data says the label is the limit; a signal that does not depend on the label is the only fix. A geofence EXIT is the cheapest such signal the platform offers and the only one that survives process death without a permanent notification.
- Measuring first costs a day or two and changes nothing about how the owner's rides are recorded; building the trigger first would add a battery cost and false starts nobody has measured. The decision after it is then made on three numbers (lead, false exits, cost) instead of a guess.
- Re-centring on `STILL` and on a finished capture follows the physical fact that matters - where the phone is parked - instead of a timer.

## Consequences

- A small, always-on background cost while Auto Tracking is on (the geofence and one position fix every time the phone is re-centred); the baseline above is what it is compared with. If the measurement shows it is not small, the watch is removed (it is one registration and one class) and nothing else changes.
- `MOVEMENT_WATCH_*` rows are added to the diagnostic table: a few dozen per day (an `ARMED` per place change, an `EXIT` per departure), bounded by `DIA-004`'s retention; none carries a coordinate (and a test pins that they survive the export scrubber intact).
- Play Services' geofence latency is not under this app's control (tens of seconds to a couple of minutes on Android 8+): the lead measured above is net of it.
- The geofence does not survive a reboot or an app update (Play Services documents it); `AutoTrackingDetection.sync` re-arms it from the same places that restore Activity Recognition.
- Nothing about starting or ending Trips changes in this version.

## Alternatives considered

- **Build it as an active trigger straight away.** Rejected by the owner's own approval of measuring first: it would spend battery and could start recordings on walks before anyone knows how often the watch is wrong.
- **Plain fused location updates with a distance filter.** Rejected: throttled to a few per hour in the background (see decision 1).
- **Significant-motion sensor.** Rejected (decision 1): needs a process that stays alive.
- **A longer start-candidate window opened on `WALKING`/`STILL` exits.** Rejected: it keeps the GPS on for every walk, which is the cost the owner declined, for rides that began after ten minutes of `WALKING`.
- **Do nothing; accept the lost start.** Remains the fallback if the measurement shows the watch is late or expensive.

## Reopen / supersede triggers

- The measurement: a lead too small to matter (Play Services' latency eats it), too many exits with no ride, or a battery cost that is not small - remove or retune (radius, re-centring rules).
- The measurement showing a clear lead at a bearable cost - the next ADR makes the EXIT start a candidate (and says how the candidate's window and the retained fixes of `ADR-027` apply).
- A different phone or Play Services version changing how geofence deliveries arrive (watch `MOVEMENT_WATCH_BROADCAST_EMPTY`).

## Traceability

- `ADR-007` (Activity Recognition as the passive trigger), `ADR-009` (no coordinates in diagnostics), `ADR-017` (nothing beyond a confirmed capture is stored), `ADR-018` (every number a placeholder, here chosen to be measured), `ADR-026` (the label trigger), `ADR-027` (what is kept once a candidate runs), `ADR-028` (the process can be killed)
- `PERM-002` (off means not listening); `F0.4` (platform); `trip-detection-spec.md` DP-001/008 (high-detail location only while validating or recording)
- Backlog `DET-011`

---

### Decision lifecycle

This ADR remains **Accepted** until explicitly superseded by a later ADR. Implementation tasks must not silently override it.

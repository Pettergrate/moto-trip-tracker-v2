package com.mototriptracker.app.navigation

/**
 * ADR-011: Home/History/Favorites are the bottom-nav tabs; Active Trip is
 * "un destino especial persistente, no una pestaña" (no bottom nav of its
 * own, its own back arrow instead - F0.9 §6/TRP-01's wireframe). Settings
 * is reachable from the app bar, not a tab (F0.9 §3.2).
 *
 * Plain objects, not `@Serializable`/`NavKey` - F0.8 §14 already establishes
 * "back stack no es fuente de verdad de un Trip activo": every screen here
 * re-derives its content from Room on composition, so the back stack itself
 * doesn't need to survive process death for the app to behave correctly on
 * reopen. Revisit only if a future task finds a real need for restored deep
 * back stacks.
 */
sealed interface Destination {
    data object Home : Destination
    data object History : Destination
    data object Favorites : Destination
    data object Settings : Destination
    data object ActiveTrip : Destination

    /** HIS-001/F0.9 §9: HIS-02, pushed from History with a specific Trip's ID - the first non-singleton, parameterized destination in this graph. */
    data class TripDetail(val tripId: String) : Destination

    /** EXP-002: internal-only, reached from Settings - not a tab, no user-facing entry point elsewhere (EXP-001 acceptance). */
    data object FieldTestHarness : Destination
}

/** The three ADR-011 bottom-nav tabs, in display order. */
val bottomNavDestinations: List<Destination> = listOf(Destination.Home, Destination.History, Destination.Favorites)

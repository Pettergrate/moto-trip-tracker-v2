package com.mototriptracker.app.experiment

/**
 * Seam (ADR-013) over where [PersistedHarnessState] lives on disk. At most
 * one harness session is ever active, so this is a single slot, not keyed
 * by session id.
 */
interface FieldTestHarnessStateStore {
    fun save(state: PersistedHarnessState)
    fun load(): PersistedHarnessState?
    fun clear()
}

package com.mototriptracker.app.experiment

/** In-memory [FieldTestHarnessStateStore] for tests. */
class FakeFieldTestHarnessStateStore : FieldTestHarnessStateStore {
    private var stored: PersistedHarnessState? = null

    override fun save(state: PersistedHarnessState) {
        stored = state
    }

    override fun load(): PersistedHarnessState? = stored

    override fun clear() {
        stored = null
    }

    /** Test setup helper: seed a persisted state as if a previous process had already saved one. */
    fun seed(state: PersistedHarnessState) {
        stored = state
    }
}

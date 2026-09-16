package com.mototriptracker.app.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * F0.12 §5.5: repositories, detector orchestration and workers take this
 * instead of referencing `Dispatchers.IO`/`Default`/`Main` directly, so
 * tests can swap in a single deterministic `TestDispatcher` for all three
 * (see the test-only `FakeDispatcherProvider`).
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

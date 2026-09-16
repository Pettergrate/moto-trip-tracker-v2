package com.mototriptracker.app.testing

import com.mototriptracker.app.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestDispatcher

/** All three dispatchers collapse onto one [TestDispatcher] for determinism. */
class FakeDispatcherProvider(dispatcher: TestDispatcher) : DispatcherProvider {
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}

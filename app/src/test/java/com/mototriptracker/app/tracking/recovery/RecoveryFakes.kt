package com.mototriptracker.app.tracking.recovery

internal class FakeExitReader(var exit: ProcessExit?) : ProcessExitReasonReader {
    override fun latestExit(): ProcessExit? = exit
}

internal class FakeHandledExitStore(var handled: Long = 0L, var bootCount: Int? = null) : HandledExitStore {
    override suspend fun lastHandledTimestamp(): Long = handled
    override suspend fun markHandled(timestampMillis: Long) {
        handled = timestampMillis
    }

    override suspend fun lastSeenBootCount(): Int? = bootCount
    override suspend fun setLastSeenBootCount(bootCount: Int) {
        this.bootCount = bootCount
    }
}

internal class FakeBootCountReader(var count: Int?) : BootCountReader {
    override fun bootCount(): Int? = count
}

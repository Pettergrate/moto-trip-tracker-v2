package com.mototriptracker.app.tracking.recovery

internal class FakeExitReader(var exit: ProcessExit?, var records: List<ProcessExitRecord> = emptyList()) : ProcessExitReasonReader {
    override fun latestExit(): ProcessExit? = exit
    override fun recentExits(): List<ProcessExitRecord> = records
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

    var recorded: Long = 0L
    override suspend fun lastRecordedExitTimestamp(): Long = recorded
    override suspend fun markExitRecorded(timestampMillis: Long) {
        recorded = timestampMillis
    }
}

internal class FakeBootCountReader(var count: Int?) : BootCountReader {
    override fun bootCount(): Int? = count
}

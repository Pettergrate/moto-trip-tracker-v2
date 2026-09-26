package com.mototriptracker.app.domain.persistence

/**
 * REC-006 / `reliability-recovery.md` §14.1-14.2: what is held in RAM while the database refuses
 * writes. Two promises, both explicit because the spec forbids silent loss:
 *
 * - **Bounded**: it never grows past [capacity]. When full, the *oldest* held item is dropped to
 *   make room and [droppedTotal] says so - the freshest evidence (where the rider is now) wins,
 *   and either policy costs exactly one contiguous stretch. The choice is stated here, not
 *   implied.
 * - **Ordered**: items leave in the order they went in ([peek]/[removeFirst]), so a recovery
 *   writes them back in the order they were received.
 *
 * No Android dependency (ADR-013). Not thread-safe: the owner serializes access.
 */
class BoundedWriteBuffer<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayDeque<T>()

    /** Items dropped for lack of room since this buffer was created - never reset, so a loss cannot be forgotten. */
    var droppedTotal: Int = 0
        private set

    /** The most items ever held at once (§14 "buffer high-water mark"). */
    var highWaterMark: Int = 0
        private set

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    /** Adds [item]; returns the item it displaced, or null when there was room. */
    fun offer(item: T): T? {
        var displaced: T? = null
        if (items.size == capacity) {
            displaced = items.removeFirst()
            droppedTotal++
        }
        items.addLast(item)
        if (items.size > highWaterMark) highWaterMark = items.size
        return displaced
    }

    fun peek(): T? = items.firstOrNull()

    /** A copy of what is held, oldest first - for the caller to describe a loss before discarding. */
    fun toList(): List<T> = items.toList()

    fun removeFirst(): T = items.removeFirst()

    /** Empties the buffer and returns how many items were discarded (the caller records that as loss). */
    fun discardAll(): Int {
        val n = items.size
        items.clear()
        return n
    }
}

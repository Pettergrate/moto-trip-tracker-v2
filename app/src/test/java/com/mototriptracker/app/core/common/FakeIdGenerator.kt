package com.mototriptracker.app.core.common

/** Deterministic, sequential IDs for tests/replay fixtures — never a real UUID. */
class FakeIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var counter = 0

    override fun newId(): String {
        counter += 1
        return "$prefix-$counter"
    }
}

package com.mototriptracker.app.core.common

import java.util.UUID
import javax.inject.Inject

class UuidIdGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

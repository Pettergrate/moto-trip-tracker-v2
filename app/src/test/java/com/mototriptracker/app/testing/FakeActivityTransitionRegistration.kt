package com.mototriptracker.app.testing

import com.mototriptracker.app.tracking.activityrecognition.ActivityTransitionRegistration

/** Records what was asked of Google's Activity Recognition registration, without Play Services. */
class FakeActivityTransitionRegistration : ActivityTransitionRegistration {
    val calls = mutableListOf<String>()

    /** True after the last call registered, false after it unregistered, `null` before any call. */
    val isRegistered: Boolean? get() = calls.lastOrNull()?.let { it == REGISTER }

    override fun register() {
        calls += REGISTER
    }

    override fun unregister() {
        calls += UNREGISTER
    }

    companion object {
        const val REGISTER = "register"
        const val UNREGISTER = "unregister"
    }
}

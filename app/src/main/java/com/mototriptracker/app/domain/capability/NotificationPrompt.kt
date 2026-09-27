package com.mototriptracker.app.domain.capability

/**
 * PERM-001 / `privacy-permissions.md` §19.2: after precise location, "notificación (si aplica)" - and §19.4, "no
 * spamear diálogos". Whether the first Start should offer the notification permission.
 *
 * Only Android 13+ has a runtime permission for notifications; below it nothing can be asked. It is offered once: if
 * it was already offered (whatever the answer), or notifications are already allowed, Start goes straight on - and a
 * person who said no still has the card that explains what is lost and offers to fix it (`PERM-003`).
 */
object NotificationPrompt {

    /** `Build.VERSION_CODES.TIRAMISU`; a plain number so `domain/` stays free of Android (ADR-013). */
    const val FIRST_SDK_WITH_RUNTIME_PERMISSION = 33

    fun shouldAsk(sdkInt: Int, notificationsEnabled: Boolean, alreadyAsked: Boolean): Boolean =
        sdkInt >= FIRST_SDK_WITH_RUNTIME_PERMISSION && !notificationsEnabled && !alreadyAsked
}

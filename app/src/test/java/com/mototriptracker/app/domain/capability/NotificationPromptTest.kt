package com.mototriptracker.app.domain.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PERM-001 / `privacy-permissions.md` §19.2 and §19.4: the notification prompt at the first Start, offered once and only where it exists. */
class NotificationPromptTest {

    @Test
    fun onAndroid13AndUpWithNotificationsOffAndNeverAskedItIsOffered() {
        assertTrue(NotificationPrompt.shouldAsk(sdkInt = 33, notificationsEnabled = false, alreadyAsked = false))
        assertTrue(NotificationPrompt.shouldAsk(sdkInt = 36, notificationsEnabled = false, alreadyAsked = false))
    }

    @Test
    fun beforeAndroid13ThereIsNoRuntimePermissionToAskFor() {
        assertFalse(NotificationPrompt.shouldAsk(sdkInt = 32, notificationsEnabled = false, alreadyAsked = false))
        assertFalse(NotificationPrompt.shouldAsk(sdkInt = 26, notificationsEnabled = false, alreadyAsked = false))
    }

    @Test
    fun itIsNeverOfferedTwiceWhateverTheAnswerWas() {
        assertFalse(NotificationPrompt.shouldAsk(sdkInt = 36, notificationsEnabled = false, alreadyAsked = true))
    }

    @Test
    fun withNotificationsAlreadyAllowedThereIsNothingToAsk() {
        assertFalse(NotificationPrompt.shouldAsk(sdkInt = 36, notificationsEnabled = true, alreadyAsked = false))
    }

    @Test
    fun theThresholdIsAndroid13() {
        assertEquals(33, NotificationPrompt.FIRST_SDK_WITH_RUNTIME_PERMISSION)
    }
}

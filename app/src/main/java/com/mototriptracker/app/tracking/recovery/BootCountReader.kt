package com.mototriptracker.app.tracking.recovery

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * REC-003: the *definitive* "did the device reboot" signal.
 * `Settings.Global.BOOT_COUNT` is incremented by the platform on every real
 * boot and by nothing else. `BOOT_COMPLETED` is **not** that signal: verified on
 * a real Android 16 device, the system also delivers it to an app that is
 * relaunched after a Force stop, with the phone up for 20+ minutes.
 */
interface BootCountReader {
    /** The current boot count, or `null` if the platform doesn't expose it. */
    fun bootCount(): Int?
}

class AndroidBootCountReader @Inject constructor(
    @ApplicationContext private val context: Context
) : BootCountReader {
    override fun bootCount(): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
}

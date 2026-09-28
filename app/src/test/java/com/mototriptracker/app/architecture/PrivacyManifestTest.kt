package com.mototriptracker.app.architecture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mototriptracker.app.BuildConfig
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PRV-001: what the *finished* app - the merged manifest, including whatever the libraries add - is allowed to ask for
 * and expose. `privacy-permissions.md` §5 is the approved list, §5.1 what needs a new requirement and a review first,
 * §12 the backup decision. A test fails on any drift, so that a dependency upgrade cannot quietly widen what the app
 * requests or exposes, and a change made on purpose has to touch the document and this test together.
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun packageInfo(flags: Int) = context.packageManager.getPackageInfo(context.packageName, flags)

    private val requestedPermissions: Set<String>
        get() = packageInfo(PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()

    /** `privacy-permissions.md` §5: the approved matrix. */
    private val approvedByTheDocument = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE"
    )

    /**
     * Not in the document's table but present in the merged manifest, each added by a library and reviewed (2026-09-27):
     * all "normal" permissions, granted at install, none of them dangerous or able to read location or personal data.
     */
    private val addedByLibrariesAndReviewed = mapOf(
        "android.permission.ACCESS_WIFI_STATE" to "MapLibre (org.maplibre.gl:android-sdk): reads whether Wi-Fi is on, for its tile networking",
        "android.permission.WAKE_LOCK" to "WorkManager (androidx.work:work-runtime): keeps the device awake while a background job runs",
        "com.mototriptracker.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to "androidx.core: the app's own signature-level permission that protects dynamically registered receivers"
    )

    @Test
    fun onlyTheApprovedPermissionsAreRequested() {
        val expected = approvedByTheDocument + addedByLibrariesAndReviewed.keys

        assertEquals(
            "The app requests a permission nobody reviewed (or stopped requesting one). Approved: privacy-permissions.md §5. " +
                "Added: ${requestedPermissions - expected}. Missing: ${expected - requestedPermissions}. " +
                "If it is on purpose, update the document first and then this test.",
            expected,
            requestedPermissions
        )
    }

    /** `privacy-permissions.md` §5.1: these need a new requirement and a review before they may appear. */
    @Test
    fun noPermissionTheDocumentRulesOutIsRequested() {
        val ruledOut = listOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_CONTACTS",
            "android.permission.READ_PHONE_STATE", "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
            "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
            "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.permission.QUERY_ALL_PACKAGES",
            "com.google.android.gms.permission.AD_ID"
        )

        assertEquals("ruled out by §5.1", emptyList<String>(), ruledOut.filter { it in requestedPermissions })
    }

    /** §12.2: the trip database holds precise locations and must not go to a cloud backup. */
    @Test
    fun androidBackupIsOff() {
        val flags = context.applicationInfo.flags

        assertEquals("android:allowBackup must stay false (privacy-permissions.md §12.2)", 0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    /**
     * Anything a component exports can be reached by other apps. Only what has to be: the launcher activity, and the
     * boot receiver (the system sends `BOOT_COMPLETED` to it). The transition receiver in particular must not be exported:
     * its PendingIntent is *mutable* (Play Services has to add the transition), which is only safe because the target is
     * explicit and not exported.
     */
    @Test
    fun onlyWhatMustBeReachableFromOutsideIsExported() {
        val info = packageInfo(
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
        )
        val exported = buildList {
            info.activities.orEmpty().filter { it.exported }.forEach { add(it.name) }
            info.receivers.orEmpty().filter { it.exported }.forEach { add(it.name) }
            info.services.orEmpty().filter { it.exported }.forEach { add(it.name) }
            info.providers.orEmpty().filter { it.exported }.forEach { add(it.name) }
        }.toSet()

        val ours = setOf("com.mototriptracker.app.MainActivity", "com.mototriptracker.app.tracking.receiver.BootReceiver")
        val libraries = protectedLibraryComponents.keys + if (BuildConfig.DEBUG) setOf(DEBUG_ONLY_PREVIEW_ACTIVITY) else emptySet()

        assertEquals("Exported components changed; each one is reachable by other apps and needs a reason.", ours + libraries, exported)
    }

    /**
     * Library components that are exported are acceptable only when a permission only the system can hold stands in front
     * of them. The one exception is Compose's preview activity, which exists only in debug builds (`debugImplementation`).
     */
    private val DEBUG_ONLY_PREVIEW_ACTIVITY = "androidx.compose.ui.tooling.PreviewActivity"

    private val protectedLibraryComponents = mapOf(
        "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
        "androidx.work.impl.background.systemjob.SystemJobService" to "android.permission.BIND_JOB_SERVICE"
    )

    @Test
    fun everyExportedLibraryComponentIsProtectedByASystemPermission() {
        val info = packageInfo(PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
        val guards = info.receivers.orEmpty().associate { it.name to it.permission } + info.services.orEmpty().associate { it.name to it.permission }

        protectedLibraryComponents.forEach { (name, permission) ->
            assertEquals("$name must be guarded by $permission", permission, guards[name])
        }
    }

    @Test
    fun theDebugOnlyPreviewActivityIsNotPartOfARelease() {
        // Present because ui-tooling is a debugImplementation dependency; a release build never contains it.
        val declaredDebugOnly = File("build.gradle.kts").readLines().any { it.contains("debugImplementation") && it.contains("ui.tooling") }

        assertTrue("PreviewActivity is exported without a permission: it may only come from a debugImplementation dependency", declaredDebugOnly)
    }

    @Test
    fun theTransitionReceiverAndTheFileProviderAreNotExported() {
        val info = packageInfo(PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)

        assertFalse(info.receivers.orEmpty().single { it.name.endsWith("ActivityTransitionReceiver") }.exported)
        assertTrue("the diagnostic FileProvider is granted per file, never exported", info.providers.orEmpty().none { it.exported })
    }
}

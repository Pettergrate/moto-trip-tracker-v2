package com.mototriptracker.app.feature.debug

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mototriptracker.app.diagnostics.export.DiagnosticExport

/**
 * DIA-003 / F0.13 §11.4: hands the package to the system share sheet and stops there. The person picks where it
 * goes (or dismisses it); the app never sends it anywhere. Read access is granted for this one file only, through
 * the `FileProvider` that exposes just the cache's diagnostics folder.
 */
object DiagnosticSharing {

    /** Must match the `<provider android:authorities>` in the manifest. */
    fun authority(context: Context) = "${context.packageName}.diagnostics"

    fun shareIntent(context: Context, export: DiagnosticExport): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), export.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Moto Trip Tracker diagnostic package")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share diagnostic package")
    }
}

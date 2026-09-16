package com.mototriptracker.app.experiment

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Writes into app-private storage
 * (`filesDir/field-tests/sessions/<sessionId>/`). F0.6 doesn't require this
 * to be user-shareable storage — that's an export/share concern for a later
 * task, same as DIA-001's diagnostic ZIP is separate from where
 * DiagnosticEvent rows live.
 */
class AndroidFieldTestDatasetWriter @Inject constructor(
    @param:ApplicationContext private val context: Context
) : FieldTestDatasetWriter {

    override fun writeSessionFile(sessionId: String, fileName: String, content: String) {
        val sessionDir = File(context.filesDir, "field-tests/sessions/$sessionId")
        sessionDir.mkdirs()
        File(sessionDir, fileName).writeText(content)
    }
}

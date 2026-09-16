package com.mototriptracker.app.experiment

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Proves the production writer actually reaches real file I/O (via
 * Robolectric's real `Context.filesDir`), not just that the interface
 * compiles — the same discipline as `TestDatabaseFactoryTest` for Room.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidFieldTestDatasetWriterTest {

    @Test
    fun writesFileUnderFieldTestsSessionsDirectory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val writer = AndroidFieldTestDatasetWriter(context)

        writer.writeSessionFile("FT-2026-003", "session.json", """{"sessionId":"FT-2026-003"}""")

        val expectedFile = File(context.filesDir, "field-tests/sessions/FT-2026-003/session.json")
        assertTrue(expectedFile.exists())
        assertEquals("""{"sessionId":"FT-2026-003"}""", expectedFile.readText())
    }
}

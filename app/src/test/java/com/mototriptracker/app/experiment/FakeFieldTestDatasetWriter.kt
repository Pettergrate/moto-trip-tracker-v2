package com.mototriptracker.app.experiment

/** In-memory writer for testing [FieldTestSessionExporter] without file I/O. */
class FakeFieldTestDatasetWriter : FieldTestDatasetWriter {
    private val files = mutableMapOf<String, String>()

    override fun writeSessionFile(sessionId: String, fileName: String, content: String) {
        files["$sessionId/$fileName"] = content
    }

    fun readFile(sessionId: String, fileName: String): String? = files["$sessionId/$fileName"]
}

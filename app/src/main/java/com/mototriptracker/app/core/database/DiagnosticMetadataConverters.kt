package com.mototriptracker.app.core.database

import androidx.room.TypeConverter
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Encodes DiagnosticEvent's small, allowlisted metadata map (F0.13 §4:
 * "mapa pequeño y allowlisted; sin datos sensibles arbitrarios") as a single
 * TEXT column — this is not a general-purpose JSON facility and must not be
 * reused to store arbitrary/large structures (that would be exactly the
 * "EAV genérico" F0.7 §2.9 rules out for core domain data).
 *
 * Uses plain `java.net.URLEncoder`/`URLDecoder` (JDK, not Android-specific)
 * rather than a JSON library, since no JSON dependency has been vetted/added
 * to this project yet and a handful of short key-value pairs doesn't need one.
 */
class DiagnosticMetadataConverters {

    @TypeConverter
    fun fromMetadataMap(map: Map<String, String>): String =
        map.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    @TypeConverter
    fun toMetadataMap(encoded: String): Map<String, String> {
        if (encoded.isEmpty()) return emptyMap()
        return encoded.split("&").associate { pair ->
            val (key, value) = pair.split("=", limit = 2)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}

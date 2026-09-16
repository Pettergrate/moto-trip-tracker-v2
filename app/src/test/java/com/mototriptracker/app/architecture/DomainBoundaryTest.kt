package com.mototriptracker.app.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces ADR-013 automatically: domain/ must stay compilable/testable
 * without the Android framework, so it can't quietly gain an android.*
 * or androidx.* import as features land in W1+.
 */
class DomainBoundaryTest {

    @Test
    fun `domain package does not import Android framework types`() {
        val domainRoot = File("src/main/java/com/mototriptracker/app/domain")
        assertTrue("Expected domain package at $domainRoot", domainRoot.isDirectory)

        val offendingImports = domainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import ") }
                    .filter { it.contains("android.") || it.contains("androidx.") }
                    .map { line -> "${file.path}: $line" }
            }
            .toList()

        assertTrue(
            "domain/ must not depend on Android framework types (ADR-013). Offending imports:\n" +
                offendingImports.joinToString("\n"),
            offendingImports.isEmpty()
        )
    }
}

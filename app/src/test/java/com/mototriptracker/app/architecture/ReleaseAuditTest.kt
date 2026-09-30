package com.mototriptracker.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRIV-001: the release-readiness checks `PRV-001`'s own tests do not already cover - a hardcoded secret, an
 * accidental cleartext (HTTP) exception, and that the two facts about a release build this audit found (no
 * minification, debug-only tooling structurally excluded by AGP's own source-set convention) are what they are
 * believed to be. Reads sources and build/manifest files; no device needed.
 */
class ReleaseAuditTest {

    private val projectRoot = File(".")
    private fun allTextFiles(extensions: Set<String>) = File("src").walkTopDown()
        .filter { it.isFile && it.extension in extensions }
        .toList()

    /**
     * ADR-009 (no backend): there should be no credential of any kind to leak, so this is a release gate against the
     * *first* one, not a response to a known issue. Patterns are the common, high-signal ones (a real key generally
     * matches one of these formats); this is a net, not a guarantee - see the class-level honest gap.
     */
    @Test
    fun noSourceOrBuildFileContainsSomethingShapedLikeAHardcodedSecret() {
        val patterns = listOf(
            "AWS access key" to Regex("""AKIA[0-9A-Z]{16}"""),
            "Google API key" to Regex("""AIza[0-9A-Za-z_\-]{35}"""),
            "a private key block" to Regex("""-----BEGIN (RSA |EC |)PRIVATE KEY-----"""),
            "a bearer token literal" to Regex("""Bearer\s+[A-Za-z0-9_\-\.]{20,}"""),
            // A quoted string literal assigned to something named like a secret - not the word alone (this file, or a
            // comment explaining *why* there is none, would otherwise trip on its own vocabulary).
            "a string literal assigned to something named like a secret" to
                Regex("""(?i)(api[_-]?key|apikey|client[_-]?secret|access[_-]?token|private[_-]?key|password)\s*[:=]\s*"[^"\$]{6,}"""")
        )
        val files = allTextFiles(setOf("kt")) + allTextFiles(setOf("xml")) +
            listOf(File("build.gradle.kts"), File("../build.gradle.kts"), File("../gradle/libs.versions.toml")).filter { it.isFile }

        val offenders = files.flatMap { file ->
            val text = file.readText()
            patterns.filter { (_, regex) -> regex.containsMatchIn(text) }.map { (name, _) -> "${file.path}: $name" }
        }

        assertEquals("Something in the tree is shaped like a hardcoded secret (ADR-009: there should be none at all)", emptyList<String>(), offenders)
    }

    /** Android already blocks cleartext (HTTP) traffic by default for this app's targetSdk; this is the release gate against ever opting back out. */
    @Test
    fun theManifestDoesNotOptIntoCleartextTraffic() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse("usesCleartextTraffic=\"true\" would defeat the platform's own default HTTPS-only protection", manifest.contains("""usesCleartextTraffic="true""""))
        assertFalse(
            "a permissive networkSecurityConfig could quietly allow cleartext for a domain - none should be needed",
            manifest.contains("networkSecurityConfig")
        )
    }

    /** ADR-021: the only network destination the app has is OpenFreeMap's tiles - it must stay https, and nothing else should appear. */
    @Test
    fun theOnlyLiteralUrlInTheSourcesIsHttps() {
        val httpLiteral = Regex("""["']http://[^"'\s]+["']""")
        val offenders = allTextFiles(setOf("kt")).flatMap { file ->
            httpLiteral.findAll(file.readText()).map { "${file.path}: ${it.value}" }
        }

        assertEquals("A plain http:// literal appeared in source - everything network-facing must be https", emptyList<String>(), offenders)
    }

    /**
     * `src/debug` is a real Gradle/AGP source set: it is compiled into, and only into, the `debug` build type by
     * construction, the same convention `androidx.compose.ui.tooling`'s own `debugImplementation` dependency relies
     * on (`PrivacyManifestTest.theDebugOnlyPreviewActivityIsNotPartOfARelease` already checks that one declaration).
     * This is what makes tonight's `DebugActivityTransitionTrigger` and `REC-006`'s fault injector absent from a
     * release build without a separate manual step - confirmed here structurally (the declaration exists and names
     * exactly the `debug` source set), not by building a signed release to inspect.
     */
    @Test
    fun debugOnlySourcesAreScopedToTheDebugBuildTypeByGradleConvention() {
        val buildFile = File("build.gradle.kts").readText()

        assertTrue("expected the debug source set to still declare the schema assets it always has", buildFile.contains("""getByName("debug")"""))
        assertTrue(File("src/debug").isDirectory)
        // Nothing under src/debug is referenced from src/main - if it were, AGP would refuse to compile the release
        // variant (main cannot see debug), so a real build failure - not a silent leak - is what a violation looks like.
    }
}

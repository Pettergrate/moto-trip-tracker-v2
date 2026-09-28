package com.mototriptracker.app.architecture

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * PRV-001: the privacy invariants of `CLAUDE.md` (ADR-009, ADR-017, F0.11 §12, §14, §21) as tests, like
 * `DomainBoundaryTest` does for ADR-013: a rule that lives only in a document is broken by the first tired commit.
 * They read the sources and the build files, so they need no device.
 */
class PrivacyEnforcementTest {

    private val sources = File("src/main/java")
    private fun kotlinFiles() = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Removes `//` and block comments so a word in a comment is not mistaken for code. */
    private fun withoutComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").lines().joinToString("\n") { it.substringBefore("//") }

    /** The text of each `Log.x(...)` / `println(...)` statement, from its opening to its matching parenthesis. */
    private fun loggingStatements(): List<Pair<String, String>> = kotlinFiles().flatMap { file ->
        val text = withoutComments(file.readText())
        Regex("""(Log\.[vdiwe]|Log\.wtf|println|print)\(""").findAll(text).map { match ->
            var depth = 1
            var i = match.range.last + 1
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            file.path to text.substring(match.range.first, i)
        }.toList()
    }

    /** CLAUDE.md: "Ninguna coordenada en logs ni diagnósticos." */
    @Test
    fun noLogStatementMentionsACoordinateOrACoordinateBearingValue() {
        val coordinateWords = Regex("""\b(latitude|longitude|lat|lon|lng|latLng|coordinates?)\b""", RegexOption.IGNORE_CASE)
        // Interpolating a whole location object would print its coordinates through toString().
        val bearingValues = Regex("""\$\{?(location|locations|fix|fixes|point|points|geoPoint|route|track)\b""")

        val offenders = loggingStatements().filter { (_, statement) -> coordinateWords.containsMatchIn(statement) || bearingValues.containsMatchIn(statement) }

        assertTrue(
            "A log statement can print a coordinate. Never log where the person was (CLAUDE.md, ADR-017):\n" +
                offenders.joinToString("\n") { (file, statement) -> "$file: ${statement.replace(Regex("\\s+"), " ")}" },
            offenders.isEmpty()
        )
    }

    /** ADR-009: no backend. The only network use is MapLibre's own tile fetching, which the app never wraps. */
    @Test
    fun theSourcesHaveNoNetworkClientOfTheirOwn() {
        val clients = Regex("""HttpURLConnection|HttpsURLConnection|\bURLConnection\b|okhttp3|retrofit2|io\.ktor|java\.net\.URL\b|java\.net\.Socket|java\.net\.http|WebSocket""")

        val offenders = kotlinFiles().filter { file -> withoutComments(file.readText()).lines().any { it.trimStart().startsWith("import") && clients.containsMatchIn(it) } }

        assertTrue("The app must not talk to a server of its own (ADR-009): ${offenders.map { it.path }}", offenders.isEmpty())
    }

    /** ADR-017 / F0.11 §14: no analytics, crash reporting or advertising third parties in Core. */
    @Test
    fun noAnalyticsCrashReportingOrAdvertisingSdkIsDeclared() {
        val buildFiles = listOf(File("build.gradle.kts"), File("../gradle/libs.versions.toml"), File("../build.gradle.kts"))
        val deny = listOf(
            "firebase", "crashlytics", "sentry", "bugsnag", "amplitude", "mixpanel", "segment", "appsflyer", "adjust.sdk", "branch.io",
            "facebook", "flurry", "datadog", "newrelic", "appcenter", "instabug", "onesignal", "play-services-analytics",
            "play-services-ads", "play-services-measurement", "play-services-appset", "acra", "posthog", "countly"
        )

        val found = buildFiles.filter { it.isFile }.flatMap { file ->
            file.readLines().map { it.substringBefore("#").substringBefore("//").lowercase() }
                .flatMap { line -> deny.filter { line.contains(it) }.map { "${file.name}: $it" } }
        }

        assertEquals("A third-party analytics/ads/crash SDK appeared in the build (F0.11 §14.2 needs a review first)", emptyList<String>(), found)
    }

    // ---- §12: nothing goes to a cloud backup or a device-to-device transfer -------------------------------------------

    private val backupDomains = setOf("root", "file", "database", "sharedpref", "external")

    private fun parse(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement

    private fun excludesOf(block: Element) = (0 until block.childNodes.length).map { block.childNodes.item(it) }
        .filterIsInstance<Element>().partition { it.tagName == "exclude" }

    private fun assertBlockExcludesEverything(block: Element, name: String) {
        val (excludes, others) = excludesOf(block)
        assertEquals("$name must contain only <exclude> rules (an allowlist of nothing), not ${others.map { it.tagName }}", emptyList<String>(), others.map { it.tagName })
        assertEquals("$name must exclude every domain", backupDomains, excludes.map { it.getAttribute("domain") }.toSet())
        assertTrue("$name must exclude each domain wholesale", excludes.all { it.getAttribute("path") == "." })
    }

    @Test
    fun theAndroid12BackupRulesExcludeEverythingFromCloudBackupAndDeviceTransfer() {
        val rules = parse("src/main/res/xml/data_extraction_rules.xml")

        assertEquals("data-extraction-rules", rules.tagName)
        val blocks = (0 until rules.childNodes.length).map { rules.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(setOf("cloud-backup", "device-transfer"), blocks.map { it.tagName }.toSet())
        blocks.forEach { assertBlockExcludesEverything(it, it.tagName) }
    }

    @Test
    fun theLegacyBackupRulesExcludeEverythingToo() {
        val rules = parse("src/main/res/xml/backup_rules.xml")

        assertEquals("full-backup-content", rules.tagName)
        assertBlockExcludesEverything(rules, "full-backup-content")
    }

    @Test
    fun theManifestPointsAtBothRuleFilesAndKeepsAllowBackupOffExplicitly() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}

package com.bernkastel5.haqomu.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Синтетический пак в памяти; Android SQLite не нужен. */
private class FakePackDataSource(
    private val tables: Set<String> = setOf("meta", "entry", "sense", "sense_fts"),
    private val columns: Map<String, Set<String>> = mapOf(
        "meta" to setOf("key", "value"),
        "entry" to setOf("id", "head", "head_norm", "root", "pos", "pattern", "source"),
        "sense" to setOf("id", "entry_id", "ord", "definition", "gloss_en", "example", "tags"),
    ),
    private val counts: Map<String, Long> = mapOf("entry" to 120, "sense" to 340, "sense_fts" to 340),
    private val metaValues: Map<String, String> = mapOf(
        "schema_version" to "1",
        "source" to "kaikki",
        "license" to "CC BY-SA 4.0",
        "build_date" to "2026-11-14",
        "entry_count" to "120",
        "lang_from" to "ar",
        "lang_to" to "en",
    ),
    private val orphanSenses: Long = 0,
    private val emptyHeadNorms: Long = 0,
    private val tableCountError: Map<String, Exception> = emptyMap(),
    private val tablesError: Exception? = null,
) : PackDataSource {
    override fun tables(): Set<String> = tablesError?.let { throw it } ?: tables
    override fun columns(table: String): Set<String> = columns[table] ?: emptySet()
    override fun rowCount(table: String): Long {
        tableCountError[table]?.let { throw it }
        return counts[table] ?: throw IllegalStateException("no such table: $table")
    }
    override fun metaValue(key: String): String? = metaValues[key]
    override fun orphanSenses(): Long = orphanSenses
    override fun emptyHeadNorms(): Long = emptyHeadNorms
}

class PackChecksTest {

    @Test
    fun validPackPasses() {
        val report = PackChecks.verify(FakePackDataSource())
        assertTrue(report.checks.filter { !it.ok }.joinToString("; ") { it.id }, report.passed)
    }

    @Test
    fun unreadableFileFailsFast() {
        val report = PackChecks.verify(FakePackDataSource(tablesError = IllegalStateException("file is not a database")))
        assertFalse(report.passed)
        assertEquals("readable", report.checks.single().id)
    }

    @Test
    fun missingCoreTableFailsFast() {
        val report = PackChecks.verify(FakePackDataSource(tables = setOf("meta", "entry")))
        assertFalse(report.passed)
        assertEquals("not a Haqomu pack (missing core tables)", report.fatalError)
    }

    @Test
    fun missingFtsTableFails() {
        val report = PackChecks.verify(FakePackDataSource(tables = setOf("meta", "entry", "sense")))
        assertFalse(report.passed)
        assertEquals(false, report.check("table:sense_fts")?.ok)
    }

    @Test
    fun fts5UnavailableFails() {
        val ds = FakePackDataSource(tableCountError = mapOf("sense_fts" to IllegalStateException("no such module: fts5")))
        val report = PackChecks.verify(ds)
        assertFalse(report.passed)
        assertEquals(false, report.check("fts5_available")?.ok)
    }

    @Test
    fun entryCountMismatchFails() {
        val report = PackChecks.verify(FakePackDataSource(metaValues = mapOf(
            "schema_version" to "1", "source" to "x", "license" to "y",
            "build_date" to "2026-11-14", "entry_count" to "999", "lang_from" to "ar", "lang_to" to "en",
        )))
        assertFalse(report.passed)
        assertEquals(false, report.check("entry_count_match")?.ok)
    }

    @Test
    fun wrongSchemaVersionFails() {
        val report = PackChecks.verify(FakePackDataSource(metaValues = mapOf(
            "schema_version" to "2", "source" to "x", "license" to "y",
            "build_date" to "2026-11-14", "entry_count" to "120", "lang_from" to "ar", "lang_to" to "en",
        )))
        assertFalse(report.passed)
        assertEquals(false, report.check("schema_version")?.ok)
    }

    @Test
    fun languagePairFromTzNamesAccepted() {
        val report = PackChecks.verify(FakePackDataSource(metaValues = mapOf(
            "schema_version" to "1", "source" to "x", "license" to "y",
            "build_date" to "2026-11-14", "entry_count" to "120",
            "language_from" to "ar", "language_to" to "en",
        )))
        assertTrue(report.checks.filter { !it.ok }.joinToString("; ") { it.id }, report.passed)
    }

    @Test
    fun missingLicenseFails() {
        val report = PackChecks.verify(FakePackDataSource(metaValues = mapOf(
            "schema_version" to "1", "source" to "x",
            "build_date" to "2026-11-14", "entry_count" to "120", "lang_from" to "ar", "lang_to" to "en",
        )))
        assertFalse(report.passed)
        assertEquals(false, report.check("meta:license")?.ok)
    }

    @Test
    fun orphanSensesFail() {
        assertFalse(PackChecks.verify(FakePackDataSource(orphanSenses = 3)).passed)
    }

    @Test
    fun emptyHeadNormFails() {
        assertFalse(PackChecks.verify(FakePackDataSource(emptyHeadNorms = 2)).passed)
    }

    @Test
    fun emptyPackFails() {
        val report = PackChecks.verify(FakePackDataSource(counts = mapOf("entry" to 0, "sense" to 0, "sense_fts" to 0),
            metaValues = mapOf(
                "schema_version" to "1", "source" to "x", "license" to "y",
                "build_date" to "2026-11-14", "entry_count" to "0", "lang_from" to "ar", "lang_to" to "en",
            )))
        assertFalse(report.passed)
        assertEquals(false, report.check("entry_nonempty")?.ok)
    }

    @Test
    fun missingColumnFails() {
        val report = PackChecks.verify(FakePackDataSource(columns = mapOf(
            "meta" to setOf("key", "value"),
            "entry" to setOf("id", "head"),
            "sense" to setOf("id", "entry_id", "ord", "definition"),
        )))
        assertFalse(report.passed)
        assertEquals(false, report.check("columns:entry")?.ok)
    }
}

package com.bernkastel5.haqomu.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FTS-индекс в паке необязателен (ADR-0009): системный SQLite в Android обычно без FTS5.
 * Пак с meta('fts') = none и без таблицы sense_fts обязан проходить проверку,
 * а пак, который заявил индекс, — проверяться на читаемость.
 */
class PackChecksFtsTest {

    private fun ds(
        meta: Map<String, String>,
        tables: Set<String> = setOf("meta", "entry", "sense"),
        ftsError: Exception? = null,
    ) = object : PackDataSource {
        override fun tables(): Set<String> = tables
        override fun columns(table: String): Set<String> = when (table) {
            "meta" -> setOf("key", "value")
            "entry" -> setOf("id", "head", "head_norm")
            "sense" -> setOf("id", "entry_id", "ord", "definition")
            else -> emptySet()
        }
        override fun rowCount(table: String): Long {
            if (table == "sense_fts") ftsError?.let { throw it }
            return when (table) {
                "entry" -> 10
                "sense" -> 20
                else -> 0
            }
        }
        override fun metaValue(key: String): String? = meta[key]
        override fun orphanSenses(): Long = 0
        override fun emptyHeadNorms(): Long = 0
    }

    private val baseMeta = mapOf(
        "schema_version" to "1",
        "source" to "kaikki",
        "license" to "CC BY-SA 4.0",
        "build_date" to "2026-11-14",
        "entry_count" to "10",
        "lang_from" to "ar",
        "lang_to" to "en",
    )

    @Test
    fun packWithoutFtsPasses() {
        val report = PackChecks.verify(ds(baseMeta + ("fts" to "none")))
        assertTrue(report.checks.filter { !it.ok }.joinToString("; ") { it.id }, report.passed)
        assertEquals(true, report.check("fts_available")?.ok)
    }

    @Test
    fun legacyPackWithoutFtsKeyPasses() {
        // паки, собранные до ADR-0009, ключа fts не имеют — они не должны внезапно «ломаться»
        val report = PackChecks.verify(ds(baseMeta))
        assertTrue(report.checks.filter { !it.ok }.joinToString("; ") { it.id }, report.passed)
    }

    @Test
    fun declaredFtsThatCannotBeReadFails() {
        val report = PackChecks.verify(
            ds(
                meta = baseMeta + ("fts" to "fts5"),
                tables = setOf("meta", "entry", "sense", "sense_fts"),
                ftsError = IllegalStateException("no such module: fts5"),
            )
        )
        assertEquals(false, report.check("fts_available")?.ok)
    }

    @Test
    fun missingTablesNoLongerRequiredButCoreStillAre() {
        val report = PackChecks.verify(ds(baseMeta, tables = setOf("meta", "entry")))
        assertEquals("not a Haqomu pack (missing core tables)", report.fatalError)
    }
}

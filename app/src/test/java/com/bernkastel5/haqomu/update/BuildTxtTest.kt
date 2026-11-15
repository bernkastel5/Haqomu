package com.bernkastel5.haqomu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор BUILD.txt из релиза — самая хрупкая часть проверки обновления: формат пишет
 * наш же скрипт публикации, но старые сборки писали его без версии. Тесты фиксируют
 * и текущий формат, и обратную совместимость.
 */
class BuildTxtTest {

    @Test
    fun parsesCurrentFormat() {
        val text = """
            Haqomu — тестовая сборка
            коммит: 98a2ad9
            version_code: 1234567
            version_name: 0.2.0
            размер: 80M
            sha256: 2b561f60aabb
        """.trimIndent()
        val meta = BuildTxt.parse(text)
        assertEquals(1234567, meta.versionCode)
        assertEquals("0.2.0", meta.versionName)
        assertEquals("98a2ad9", meta.commit)
        assertEquals(80L * 1024 * 1024, meta.sizeBytes)
        assertEquals("2b561f60aabb", meta.sha256)
    }

    @Test
    fun oldBuildWithoutVersionGivesNullCode() {
        val text = "Haqomu — тестовая сборка\nкоммит: c5c8ba2\nразмер: 80M\n"
        val meta = BuildTxt.parse(text)
        assertNull(meta.versionCode)
        assertNull(meta.versionName)
        assertEquals("c5c8ba2", meta.commit)
    }

    @Test
    fun englishKeysAccepted() {
        val meta = BuildTxt.parse("commit: abc1234\nversion_code: 7\nversion_name: 0.3.0\nsize: 1.5G\n")
        assertEquals(7, meta.versionCode)
        assertEquals("0.3.0", meta.versionName)
        assertEquals("abc1234", meta.commit)
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), meta.sizeBytes)
    }

    @Test
    fun garbageVersionIsIgnored() {
        assertNull(BuildTxt.parse("version_code: не число\n").versionCode)
    }

    @Test
    fun emptyTextIsSafe() {
        val meta = BuildTxt.parse("")
        assertNull(meta.versionCode)
        assertNull(meta.commit)
        assertNull(meta.sizeBytes)
    }

    @Test
    fun sizeUnitsParsed() {
        assertEquals(83883534L, BuildTxt.parseSize("83883534"))
        assertEquals(1024L, BuildTxt.parseSize("1K"))
        assertEquals(12L * 1024 * 1024, BuildTxt.parseSize("12 MB"))
        assertNull(BuildTxt.parseSize("много"))
    }

    @Test
    fun newerBuildMeansUpdate() {
        assertTrue(BuildTxt.isNewer(installedCode = 10, availableCode = 11))
    }

    @Test
    fun sameOrOlderOrUnknownIsNotAnUpdate() {
        assertFalse(BuildTxt.isNewer(10, 10))
        assertFalse(BuildTxt.isNewer(10, 9))
        assertFalse(BuildTxt.isNewer(10, null))
    }
}

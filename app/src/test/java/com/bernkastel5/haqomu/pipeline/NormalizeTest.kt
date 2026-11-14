package com.bernkastel5.haqomu.pipeline

import com.bernkastel5.haqomu.morph.ArabicNormalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ф4: нормализация — ключ пака (nk), сравнение голов (lemmaKey), корень (normRoot). */
class NormalizeTest {

    @Test
    fun nkStripsDiacriticsAndUnifies() {
        assertEquals("ذهب", ArabicNormalize.nk("ذَهَبَ"))
        assertEquals("اكل", ArabicNormalize.nk("أَكَل"))
        assertEquals("ايمان", ArabicNormalize.nk("إيـمان"))
        assertEquals("ي", ArabicNormalize.nk("ى"))
        assertEquals("ه", ArabicNormalize.nk("ة"))
        assertEquals("و", ArabicNormalize.nk("ؤ"))
    }

    @Test
    fun nkStripsNonArabicAndNormalizesNfc() {
        assertEquals("", ArabicNormalize.nk("hello"))
        assertEquals("ذهب", ArabicNormalize.nk("ذهب123"))
        // декомпозированная хамза (NFC) должна склеиться до унификации
        assertEquals("ا", ArabicNormalize.nk("أ"))
    }

    @Test
    fun lemmaKeyStripsArticleAndConjunctions() {
        assertEquals("بيت", ArabicNormalize.lemmaKey("البيت"))
        assertEquals("بيت", ArabicNormalize.lemmaKey("والبيت"))
        assertEquals("بيت", ArabicNormalize.lemmaKey("بالبيت"))
        // короткие слова не разрезаются
        assertEquals("الي", ArabicNormalize.lemmaKey("الى")) // ى→ي нормализуется
        assertEquals("فال", ArabicNormalize.lemmaKey("فال"))
    }

    @Test
    fun normRootDropsHamzaAndShortRoots() {
        assertEquals("ذه ب".replace(" ", ""), ArabicNormalize.normRoot("ذ ه ب"))
        assertEquals("قرو", ArabicNormalize.normRoot("ق ر ء و"))
        assertEquals("", ArabicNormalize.normRoot("ء"))
        assertEquals("", ArabicNormalize.normRoot(""))
    }

    @Test
    fun nkIsStableOnPackHeads() {
        // сборщики паков пишут head_norm той же схемой — запрос из приложения обязан совпадать
        assertFalse(ArabicNormalize.nk("Hans Wehr").isNotEmpty())
        assertTrue(ArabicNormalize.nk("قَرَأَ").isNotEmpty())
    }
}

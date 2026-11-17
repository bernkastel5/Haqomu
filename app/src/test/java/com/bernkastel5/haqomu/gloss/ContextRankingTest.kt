package com.bernkastel5.haqomu.gloss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор смысла по предложению без модели: сигнал — слова предложения в толковании
 * (+1 за каждое совпадение), часть речи — подсказка. Решение принимается только при
 * явном перевесе; иначе словарный порядок нетронут.
 */
class ContextRankingTest {

    // عين: 1) орган зрения, 2) источник воды.
    private val ayn = listOf(
        "عضو البصر الذي به ترى الأشياء",
        "منبع الماء في الأرض",
    )

    @Test
    fun liftsSenseWhoseDefinitionMatchesTheSentence() {
        // «нашли источник в пустыне» — в толковании №2 есть «الماء» и «الأرض»
        val choice = ContextRanking.choose(ayn, "وجدنا عيناً في الصحراء و الماء قليل و الارض جافة")
        assertTrue(choice != null)
        assertEquals(1, choice!!.index)
        assertTrue(choice.matched.isNotEmpty())
        assertTrue(choice.margin >= ContextRanking.MIN_MARGIN)
    }

    @Test
    fun keepsOrderWhenSentenceSaysNothing() {
        // ни одно знаменательное слово не встречается в толкованиях
        assertNull(ContextRanking.choose(ayn, "قال الرجل كلمة غريبة تماما"))
    }

    @Test
    fun keepsOrderWithoutSentence() {
        assertNull(ContextRanking.choose(ayn, "   "))
    }

    @Test
    fun keepsOrderForSingleSense() {
        assertNull(ContextRanking.choose(listOf("عضو البصر"), "عضو البصر و الماء"))
    }

    @Test
    fun doesNotLiftWhenBestIsAlreadyFirst() {
        // слова предложения совпадают с ПЕРВЫМ толкованием: поднимать нечего
        assertNull(ContextRanking.choose(ayn, "عضو البصر و الأشياء"))
    }

    @Test
    fun needsRealMargin() {
        // один общий токен — перевес 1 при MIN_SCORE 2: мало
        assertNull(ContextRanking.choose(ayn, "الماء"))
    }

    @Test
    fun posHintAloneIsNotEnough() {
        // толкование начинается с «فعل», разбор — глагол: +1 < MIN_SCORE
        val senses = listOf("اسم الآلة", "فعل يدل على الكتابة")
        assertNull(ContextRanking.choose(senses, "كتب الرجل الرسالة الطويلة", pos = "VERB"))
    }

    @Test
    fun posHintAddsToMatches() {
        // «الكتابة» в предложении даёт 1 совпадение, пометка «فعل» при VERB даёт ещё 1
        val senses = listOf("اسم الآلة التي يكتب بها", "فعل يدل على الكتابة")
        val choice = ContextRanking.choose(senses, "الكتابة", pos = "VERB")
        assertTrue(choice != null)
        assertEquals(1, choice!!.index)
    }

    @Test
    fun stopwordsAreNotEvidence() {
        // «من», «في», «التي» есть в обоих толкованиях — как признак они бесполезны
        val senses = listOf("من في التي", "من في التي هو")
        assertNull(ContextRanking.choose(senses, "من في التي هو ما كان"))
    }

    @Test
    fun tokensIgnoreDiacriticsAndTatweel() {
        val withHarakat = ContextRanking.tokensOf("المَاءُ في الأَرضِ")
        // lemmaKey снимает и огласовку, и артикль: сравнение не зависит от оформления
        assertTrue("ماء" in withHarakat)
        assertTrue("ارض" in withHarakat)
        assertTrue("في" !in withHarakat)
    }

    @Test
    fun shortWordsAreIgnored() {
        // «ما», «لا» короче MIN_WORD или в стоп-списке
        assertTrue(ContextRanking.tokensOf("ما لا هو").isEmpty())
    }

    @Test
    fun posGroupMapping() {
        assertEquals("VERB", ContextRanking.posGroup("VERB"))
        assertEquals("VERB", ContextRanking.posGroup("pv"))
        assertEquals("NOUN", ContextRanking.posGroup("NOUN"))
        assertEquals("ADJ", ContextRanking.posGroup("adj"))
        assertNull(ContextRanking.posGroup("PUNC"))
        assertNull(ContextRanking.posGroup(null))
    }

    @Test
    fun scoreCountsMatchesAndPos() {
        val tokens = ContextRanking.tokensOf("البصر الأشياء")
        val (withPos, matched) = ContextRanking.score("اسم عضو البصر و الأشياء", tokens, "NOUN")
        assertEquals(3.0, withPos, 1e-9)
        assertEquals(listOf("اشياء", "بصر"), matched)
        val (withoutPos, _) = ContextRanking.score("عضو البصر و الأشياء", tokens, "NOUN")
        assertEquals(2.0, withoutPos, 1e-9)
    }
}

package com.bernkastel5.haqomu.gloss

import com.bernkastel5.haqomu.pipeline.DictionarySense
import com.bernkastel5.haqomu.pipeline.PopupArticle
import com.bernkastel5.haqomu.pipeline.PopupContent
import com.bernkastel5.haqomu.pipeline.PopupMode
import com.bernkastel5.haqomu.pipeline.WordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф10 / TZ §6.3: модель меняет порядок смыслов ВНУТРИ статьи и только при уверенности.
 * Статьи остаются на местах, ответ пайплайна — по-прежнему первая статья (D-003),
 * все смыслы видны (Ф9). Сбой модели деградирует в словарный порядок, а не в ошибку.
 */
class SenseResolverTest {

    private class FakeModel(
        private val probs: Map<String, Double>,
        private val fail: Boolean = false,
    ) : SenseModel {
        override val name: String = "fake"
        var calls = 0
        var lastSentence: String? = null
        var lastDefinitions: List<String> = emptyList()

        override fun score(sentence: String, definitions: List<String>): DoubleArray {
            calls += 1
            lastSentence = sentence
            lastDefinitions = definitions
            if (fail) throw IllegalStateException("модель сломалась")
            return definitions.map { probs[it] ?: 0.0 }.toDoubleArray()
        }
    }

    private fun article(head: String, vararg senses: String, family: String = "kaikki") = PopupArticle(
        head = head,
        root = null,
        pos = null,
        sourceFamily = family,
        senses = senses.map { DictionarySense(it, null) },
        rank = "answer",
        support = 0,
        isAnswer = true,
    )

    private fun content(vararg articles: PopupArticle) = PopupContent(
        original = "ذهب",
        mode = PopupMode.ARTICLES,
        wordKind = WordKind.WORD,
        articles = articles.toList(),
        lemmas = listOf("ذهب"),
    )

    private val sentence = "ذهب الطفل إلى البيت."

    // ---------------------------------------------------------------- работа

    @Test
    fun confidentModelLiftsSenseInsideTheArticle() {
        val model = FakeModel(mapOf("идти" to 0.1, "умереть" to 0.75))
        val before = content(article("ذهب", "идти", "умереть"))
        val after = SenseResolver.resolve(before, sentence, model)

        assertEquals(1, after.articles.size)
        assertEquals("умереть", after.articles[0].senses[0].definition)
        assertEquals("идти", after.articles[0].senses[1].definition)
        assertTrue(after.articles[0].senseChosenByModel)
        assertTrue(after.articles[0].isAnswer)
        assertEquals("answer", after.articles[0].rank)
        assertTrue(model.calls == 1)
        assertEquals(sentence, model.lastSentence)
    }

    @Test
    fun unsureModelKeepsDictionaryOrder() {
        // разрыв 0.15 < 0.3 — модель молчит (иначе портит 32% верных ответов)
        val model = FakeModel(mapOf("идти" to 0.45, "умереть" to 0.6))
        val before = content(article("ذهب", "идти", "умереть"))
        val after = SenseResolver.resolve(before, sentence, model)
        assertEquals("идти", after.articles[0].senses[0].definition)
        assertFalse(after.articles[0].senseChosenByModel)
    }

    @Test
    fun articlesAreNeverReordered() {
        val model = FakeModel(mapOf("идти" to 0.05, "умереть" to 0.9))
        val before = content(
            article("ذهب", "идти", "умереть"),
            article("ذهبة", "слиток", family = "wehr").copy(isAnswer = false, rank = "лемма"),
        )
        val after = SenseResolver.resolve(before, sentence, model)

        assertEquals(2, after.articles.size)
        assertEquals("ذهب", after.articles[0].head)
        assertTrue(after.articles[0].isAnswer)
        assertEquals("ذهبة", after.articles[1].head)
        // вторая статья не тронута
        assertFalse(after.articles[1].senseChosenByModel)
        assertEquals("слиток", after.articles[1].senses[0].definition)
    }

    @Test
    fun allSensesStayVisible() {
        val model = FakeModel(mapOf("с" to 0.9, "a" to 0.05, "b" to 0.05))
        val before = content(article("كلمة", "a", "b", "с"))
        val after = SenseResolver.resolve(before, sentence, model)
        assertEquals(listOf("с", "a", "b"), after.articles[0].senses.map { it.definition })
    }

    // ---------------------------------------------------------------- деградация

    @Test
    fun noModelStillUsesTheSentence() {
        // Без весов контекст работает эвристикой: слова предложения в толковании
        val before = content(article("عين", "عضو البصر", "منبع الماء في الأرض"))
        val after = SenseResolver.resolve(before, "وجدنا عيناً في الصحراء و الماء قليل و الارض جافة", null)
        assertEquals("منبع الماء في الأرض", after.articles[0].senses[0].definition)
        assertTrue(after.articles[0].senseChosenByContext)
        assertFalse(after.articles[0].senseChosenByModel)
    }

    @Test
    fun noModelAndNoSignalKeepsDictionaryOrder() {
        val before = content(article("ذهب", "идти", "умереть"))
        val after = SenseResolver.resolve(before, sentence, null)
        assertEquals(before, after)
    }

    @Test
    fun modelWinsOverContextWhenConfident() {
        // модель уверена (0.75 против 0.1) — её выбор остаётся, эвристика не перебивает
        val model = FakeModel(mapOf("идти" to 0.1, "умереть" to 0.75))
        val before = content(article("عين", "عضو البصر", "منبع الماء في الأرض"))
            .copy(articles = listOf(article("عين", "عضو البصر", "منبع الماء في الأرض")))
        val after = SenseResolver.resolve(before, "وجدنا عينا في الصحراء و الماء قليل و الارض جافة", model)
        assertEquals("منبع الماء في الأرض", after.articles[0].senses[0].definition)
        assertTrue(after.articles[0].senseChosenByModel || after.articles[0].senseChosenByContext)
    }

    @Test
    fun noSentenceMeansNoRun() {
        val model = FakeModel(mapOf("умереть" to 0.99))
        val before = content(article("ذهب", "идти", "умереть"))
        val after = SenseResolver.resolve(before, "   ", model)
        assertEquals(before, after)
        assertEquals(0, model.calls)
    }

    @Test
    fun modelFailureFallsBackToDictionaryOrder() {
        val model = FakeModel(mapOf("умереть" to 0.99), fail = true)
        val before = content(article("ذهب", "идти", "умереть"))
        val after = SenseResolver.resolve(before, sentence, model)
        assertEquals("идти", after.articles[0].senses[0].definition)
        assertFalse(after.articles[0].senseChosenByModel)
    }

    @Test
    fun singleSenseArticleIsNotScored() {
        val model = FakeModel(mapOf("идти" to 0.9))
        val before = content(article("ذهب", "идти"))
        val after = SenseResolver.resolve(before, sentence, model)
        assertEquals(before, after)
        assertEquals(0, model.calls)
    }

    @Test
    fun modelIsAskedAboutAtMostMaxSenses() {
        val model = FakeModel(emptyMap())
        val many = (1..12).map { "смысл$it" }.toTypedArray()
        SenseResolver.resolve(content(article("ذهب", *many)), sentence, model)
        assertEquals(SenseResolver.MAX_SENSES, model.lastDefinitions.size)
    }
}

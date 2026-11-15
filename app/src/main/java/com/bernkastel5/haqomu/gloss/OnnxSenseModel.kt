package com.bernkastel5.haqomu.gloss

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * Модель разрешения смыслов на ONNX Runtime (Ф10, Ф13) — единственное место, где
 * приложение зависит от ONNX.
 *
 * Модель — классификатор BERT-типа на паре «предложение [SEP] толкование».
 * Считаем вероятности по одному смыслу за прогон: батчи для 1–6 смыслов экономят
 * миллисекунды, но требуют аккуратной сборки attention-mask — сложность не окупается
 * на фоне 700 мс p95 (TZ §6.5).
 *
 * Всё, что может пойти не так с данными (битый файл, чужие имена входов, выход не тех
 * размеров), превращается в [IllegalStateException] с понятным текстом: вызывающий
 * ([com.bernkastel5.haqomu.gloss.SenseResolver]) в этом случае молча возвращается
 * к словарному порядку, попап не падает.
 */
class OnnxSenseModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: BertTokenizer,
    private val meta: BundleMeta,
) : SenseModel {

    override val name: String get() = meta.model

    private val inputNames: List<String> = session.inputNames.toList()

    init {
        require(session.outputNames.isNotEmpty()) { "у модели нет выходов" }
        require(inputNames.contains(meta.inputIds)) {
            "у модели нет входа '${meta.inputIds}': есть $inputNames"
        }
    }

    /**
     * Прогон по каждому толкованию. Результат — вероятность «подходит».
     * Пустое предложение или пустой список толкований дают пустой массив:
     * без предложения модель не запускается вообще (TZ §3.2).
     */
    override fun score(sentence: String, definitions: List<String>): DoubleArray {
        val text = sentence.trim()
        if (text.isEmpty() || definitions.isEmpty()) return DoubleArray(0)
        val out = DoubleArray(definitions.size)
        for ((index, definition) in definitions.withIndex()) {
            val clean = definition.trim()
            out[index] = if (clean.isEmpty()) 0.0 else scoreOne(text, clean)
        }
        return out
    }

    private fun scoreOne(sentence: String, definition: String): Double {
        val encoded = tokenizer.encodePair(sentence, definition, meta.maxLength)
        val shape = longArrayOf(1, encoded.length.toLong())
        val values = encoded.inputIds
        val types = encoded.tokenTypeIds
        val mask = LongArray(encoded.length) { 1L }

        OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape).use { ids ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { attention ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape).use { typesTensor ->
                    val inputs = HashMap<String, OnnxTensor>(3)
                    inputs[meta.inputIds] = ids
                    if (inputNames.contains(meta.attentionMask)) inputs[meta.attentionMask] = attention
                    if (inputNames.contains(meta.tokenTypeIds)) inputs[meta.tokenTypeIds] = typesTensor
                    session.run(inputs).use { result ->
                        // Выход один (логиты); берём по индексу, чтобы не зависеть
                        // от имени, которое задал экспортёр.
                        val tensor = result.get(0)
                            ?: throw IllegalStateException("модель не вернула выход")
                        val logits = readLogits(tensor.value)
                        return SenseRanking.positiveProbability(logits)
                    }
                }
            }
        }
    }

    /** Выход модели: [1, labels] или [1, 1]; приводим к массиву чисел одного примера. */
    private fun readLogits(value: Any?): DoubleArray = when (value) {
        is Array<*> -> {
            val first = value.firstOrNull()
            when (first) {
                is FloatArray -> DoubleArray(first.size) { first[it].toDouble() }
                is DoubleArray -> first
                is Array<*> -> {
                    val row = first.firstOrNull()
                    when (row) {
                        is FloatArray -> DoubleArray(row.size) { row[it].toDouble() }
                        is DoubleArray -> row
                        else -> throw IllegalStateException("непонятный выход модели")
                    }
                }
                else -> throw IllegalStateException("непонятный выход модели")
            }
        }
        is FloatArray -> DoubleArray(value.size) { value[it].toDouble() }
        is DoubleArray -> value
        else -> throw IllegalStateException("непонятный выход модели")
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
            // закрытие сессии не должно ломать выход из попапа
        }
        try {
            env.close()
        } catch (_: Exception) {
            // см. выше
        }
    }

    companion object {
        /**
         * Загрузка установленного бандла: папка с `model.onnx` и `vocab.txt`
         * (распаковка и проверка — [ModelBundle], установка — [com.bernkastel5.haqomu.gloss.ModelStore]).
         */
        fun open(dir: File, meta: BundleMeta): OnnxSenseModel {
            val modelFile = File(dir, ModelBundle.FILE_MODEL)
            val vocabFile = File(dir, ModelBundle.FILE_VOCAB)
            if (!modelFile.isFile || modelFile.length() == 0L) {
                throw IllegalStateException("файл модели не найден")
            }
            if (!vocabFile.isFile || vocabFile.length() == 0L) {
                throw IllegalStateException("словарь токенизатора не найден")
            }
            val vocabText = vocabFile.readText(Charsets.UTF_8)
            ModelBundle.checkVocab(vocabText)?.let { throw IllegalStateException(it.reason) }
            val tokenizer = BertTokenizer.fromVocabText(
                text = vocabText,
                lowercase = meta.lowercase,
                stripAccents = meta.stripAccents,
            )
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // Один поток: попап короткий, а лишние потоки греют телефон без выигрыша
                // на 192 токенах (TZ §6.5: попап с моделью p95 < 700 мс).
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = try {
                env.createSession(modelFile.absolutePath, options)
            } catch (e: Exception) {
                options.close()
                throw IllegalStateException("модель не загрузилась: ${e.message ?: e.javaClass.simpleName}")
            }
            return OnnxSenseModel(env, session, tokenizer, meta)
        }
    }
}

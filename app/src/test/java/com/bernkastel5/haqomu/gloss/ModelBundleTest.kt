package com.bernkastel5.haqomu.gloss

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ф13: проверка бандла модели до установки. Битый, чужой или неполный бандл
 * отклоняется с понятной причиной; ничего не распаковывается и не грузится в память.
 */
class ModelBundleTest {

    private val vocab = buildString {
        appendLine("[PAD]")
        appendLine("[UNK]")
        appendLine("[CLS]")
        appendLine("[SEP]")
        repeat(120) { appendLine("tok$it") }
    }

    private fun manifest(
        model: String = "ArabGlossBERT int8",
        labels: Int = 2,
        maxLength: Int = 192,
        threshold: Double = 0.3,
        version: Int = 1,
    ): String = """
        {
          "model": "$model",
          "version": $version,
          "license": "CC BY-SA 4.0",
          "source": "SinaLab/ArabGlossBERT",
          "size_bytes": 141557760,
          "max_length": $maxLength,
          "lowercase": false,
          "strip_accents": true,
          "labels": $labels,
          "threshold": $threshold
        }
    """.trimIndent()

    private fun zipOf(files: Map<String, ByteArray>): File {
        val f = File.createTempFile("bundle", ".zip")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, bytes) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return f
    }

    // ---------------------------------------------------------------- манифест

    @Test
    fun manifestIsParsedWithDefaults() {
        val parsed = ModelBundle.parseManifest("""{"model":"ArabGlossBERT int8"}""")
        assertTrue(parsed is BundleCheck.Ok)
        val meta = (parsed as BundleCheck.Ok).meta
        assertEquals("ArabGlossBERT int8", meta.model)
        assertEquals(2, meta.labels)
        assertEquals(192, meta.maxLength)
        assertEquals(0.3, meta.threshold, 0.0)
        assertEquals("CC BY-SA 4.0", meta.license)
    }

    @Test
    fun manifestRejectsWrongShapes() {
        fun reason(json: String): String =
            (ModelBundle.parseManifest(json) as BundleCheck.Failed).reason

        assertTrue(reason("""{"version":1}""").contains("имени"))
        assertTrue(reason(manifest(labels = 3)).contains("2 класса"))
        assertTrue(reason(manifest(maxLength = 4096)).contains("maxLength"))
        assertTrue(reason(manifest(threshold = 1.5)).contains("порог"))
        assertTrue(reason(manifest(version = 0)).contains("версия"))
        assertTrue(reason("""{"model": "x", }""").isNotEmpty())
        assertTrue(reason("не json").isNotEmpty())
    }

    @Test
    fun manifestIgnoresUnknownAndNestedFields() {
        val parsed = ModelBundle.parseManifest(
            """{"model":"m","extra":{"a":[1,2,{"b":"}"}]},"labels":2,"note":"ok"}"""
        )
        assertTrue(parsed is BundleCheck.Ok)
    }

    // ---------------------------------------------------------------- состав

    @Test
    fun entriesRequireAllThreeFiles() {
        assertNull(ModelBundle.checkEntries(mapOf("model.onnx" to 100L, "vocab.txt" to 100L, "model.json" to 10L)))
        assertTrue(ModelBundle.checkEntries(mapOf("model.onnx" to 100L, "model.json" to 10L))!!.reason.contains("vocab.txt"))
        assertTrue(ModelBundle.checkEntries(emptyMap())!!.reason.contains("model.onnx"))
        assertTrue(
            ModelBundle.checkEntries(mapOf("model.onnx" to 0L, "vocab.txt" to 1L, "model.json" to 1L))!!
                .reason.contains("пустой")
        )
    }

    @Test
    fun entriesRejectBomb() {
        val huge = mapOf(
            "model.onnx" to ModelBundle.MAX_UNCOMPRESSED_BYTES,
            "vocab.txt" to 1L,
            "model.json" to 1L,
        )
        assertNotNull(ModelBundle.checkEntries(huge))
    }

    @Test
    fun vocabMustHaveSpecialTokens() {
        assertNull(ModelBundle.checkVocab(vocab))
        assertTrue(ModelBundle.checkVocab("")!!.reason.isNotEmpty())
        assertTrue(ModelBundle.checkVocab("[PAD]\n[UNK]\n[CLS]\n")!!.reason.contains("[SEP]"))
        assertTrue(ModelBundle.checkVocab("[PAD]\n[UNK]\n[CLS]\n[SEP]\n")!!.reason.contains("короткий"))
    }

    // ---------------------------------------------------------------- архив

    @Test
    fun goodZipPasses() {
        val zip = zipOf(
            mapOf(
                ModelBundle.FILE_MODEL to ByteArray(2048) { 1 },
                ModelBundle.FILE_VOCAB to vocab.toByteArray(),
                ModelBundle.FILE_MANIFEST to manifest().toByteArray(),
            )
        )
        val check = ModelBundle.checkZip(zip)
        assertTrue(check is BundleCheck.Ok)
        assertEquals("ArabGlossBERT int8", (check as BundleCheck.Ok).meta.model)
    }

    @Test
    fun zipWithoutModelIsRejected() {
        val zip = zipOf(
            mapOf(
                ModelBundle.FILE_VOCAB to vocab.toByteArray(),
                ModelBundle.FILE_MANIFEST to manifest().toByteArray(),
            )
        )
        val check = ModelBundle.checkZip(zip)
        assertTrue(check is BundleCheck.Failed)
        assertTrue((check as BundleCheck.Failed).reason.contains("model.onnx"))
    }

    @Test
    fun zipWithBrokenManifestIsRejected() {
        val zip = zipOf(
            mapOf(
                ModelBundle.FILE_MODEL to ByteArray(2048),
                ModelBundle.FILE_VOCAB to vocab.toByteArray(),
                ModelBundle.FILE_MANIFEST to "{oops".toByteArray(),
            )
        )
        assertTrue(ModelBundle.checkZip(zip) is BundleCheck.Failed)
    }

    @Test
    fun notAZipIsRejected() {
        val f = File.createTempFile("notzip", ".zip")
        f.deleteOnExit()
        f.writeText("это не архив")
        val check = ModelBundle.checkZip(f)
        assertTrue(check is BundleCheck.Failed)
    }
}

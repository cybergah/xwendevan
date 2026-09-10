package com.xwendevan.reader.debugtools

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.xwendevan.reader.EspeakData
import com.xwendevan.reader.doc.DocumentLoader
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.tts.TtsEngine
import com.xwendevan.reader.tts.VoiceRepository
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Yalnızca hata ayıklama derlemesinde bulunur.
 * adb ile çağrılır:
 *   adb shell am start -n com.xwendevan.reader/.debugtools.SelfTestActivity \
 *       --es text "Merhaba" --es doc /sdcard/deneme.txt
 */
class SelfTestActivity : Activity() {

    private val tag = "ZDTEST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text")
            ?: "Merhaba, bu bir deneme cümlesidir. Sesli okuma testi yapılıyor."
        val docPath = intent.getStringExtra("doc")
        val voiceHint = intent.getStringExtra("voice")

        if (intent.getStringExtra("mode") == "engine") {
            runEngineTest(text)
            return
        }

        Thread {
            if (intent.getStringExtra("mode") == "ocr") runOcrTest(text) else runTest(text, docPath, voiceHint)
            finish()
        }.start()
    }

    /** Sistemin TTS çatısı üzerinden kendi motorumuzu sınar. */
    private fun runEngineTest(text: String) {
        val out = java.io.File(getExternalFilesDir(null), "engine.wav")
        runCatching { out.delete() }
        var engine: android.speech.tts.TextToSpeech? = null
        val started = SystemClock.elapsedRealtime()

        engine = android.speech.tts.TextToSpeech(this, { status ->
            Log.i(tag, "motor baglandi: status=$status (${SystemClock.elapsedRealtime() - started} ms)")
            if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                Log.e(tag, "SONUC: MOTOR BAGLANMADI"); finish(); return@TextToSpeech
            }
            Log.i(tag, "sesler: " + engine?.voices?.joinToString { it.name + "@" + it.locale })
            val lang = intent.getStringExtra("lang") ?: "tur"
            val result = engine?.setLanguage(java.util.Locale(lang, "TUR"))
            Log.i(tag, "setLanguage($lang) = $result")

            engine?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    Log.i(tag, "wav: ${out.absolutePath} (${out.length()} B) ${SystemClock.elapsedRealtime() - started} ms")
                    Log.i(tag, if (out.length() > 1000) "SONUC: BASARILI" else "SONUC: BOS SES")
                    engine?.shutdown()
                    finish()
                }

                @Deprecated("deprecated")
                override fun onError(utteranceId: String?) {
                    Log.e(tag, "SONUC: MOTOR HATASI")
                    engine?.shutdown()
                    finish()
                }
            })

            val params = android.os.Bundle()
            val code = engine?.synthesizeToFile(text, params, out, "selftest")
            Log.i(tag, "synthesizeToFile = $code")
        }, "com.xwendevan.reader")
    }

    /** OCR yolunu sınar: metin bir görüntüye çizilir, Tesseract geri okur. */
    private fun runOcrTest(text: String) {
        try {
            val width = 1400
            val height = 500
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 54f
                isAntiAlias = true
            }
            var y = 120f
            for (line in text.split("|")) {
                canvas.drawText(line.trim(), 60f, y, paint)
                y += 90f
            }

            val t0 = SystemClock.elapsedRealtime()
            val paragraphs = com.xwendevan.reader.doc.Ocr.run(
                ctx = this,
                key = "selftest",
                pageCount = 1,
                render = { bitmap }
            ) { page, lines -> Log.i(tag, "ocr sayfa $page: ${lines.size} paragraf") }

            Log.i(tag, "OCR ${SystemClock.elapsedRealtime() - t0} ms, ${paragraphs.size} paragraf")
            paragraphs.forEach { Log.i(tag, "  ocr: $it") }
            Log.i(tag, if (paragraphs.isEmpty()) "SONUC: OCR BOS" else "SONUC: BASARILI")
        } catch (t: Throwable) {
            Log.e(tag, "SONUC: OCR HATASI", t)
        }
    }

    private fun runTest(text: String, docPath: String?, voiceHint: String?) {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val dataDir = EspeakData.ensure(this)
            Log.i(tag, "espeak-ng-data: ${dataDir.absolutePath} (${dataDir.listFiles()?.size} dosya) ${SystemClock.elapsedRealtime() - t0} ms")

            val voices = VoiceRepository.listVoices(this)
            Log.i(tag, "bulunan ses sayisi: ${voices.size}")
            voices.forEach {
                Log.i(tag, "  ses: ${it.displayName} | ${it.language} | espeak=${it.espeakVoice} | sr=${it.sampleRate} | spk=${it.numSpeakers} | tokens=${it.tokensFile.name} (${it.tokensFile.length()} B)")
            }
            val voice = voices.firstOrNull { v -> voiceHint == null || v.modelFile.name.contains(voiceHint, true) }
            if (voice == null) {
                Log.e(tag, "SONUC: ses modeli yok")
                return
            }

            val tLoad = SystemClock.elapsedRealtime()
            VoiceRepository.prepare(this, voice)
            TtsEngine.load(this, voice)
            Log.i(tag, "model yuklendi: ${voice.displayName} (${SystemClock.elapsedRealtime() - tLoad} ms), sampleRate=${TtsEngine.sampleRate}, konusmaci=${TtsEngine.tts?.numSpeakers()}")

            val tts = TtsEngine.tts!!
            val samples = ArrayList<FloatArray>()
            var firstChunkMs = -1L
            val tGen = SystemClock.elapsedRealtime()
            tts.generateWithCallback(text, 0, 1.0f) { chunk ->
                if (firstChunkMs < 0) firstChunkMs = SystemClock.elapsedRealtime() - tGen
                samples.add(chunk.copyOf())
                1
            }
            val totalMs = SystemClock.elapsedRealtime() - tGen
            val frames = samples.sumOf { it.size }
            val seconds = frames.toFloat() / TtsEngine.sampleRate
            var peak = 0f
            for (c in samples) for (v in c) { val a = kotlin.math.abs(v); if (a > peak) peak = a }
            Log.i(tag, "sentez: parca=${samples.size} kare=$frames ses=${"%.2f".format(seconds)} sn ilkParca=$firstChunkMs ms toplam=$totalMs ms tepe=${"%.3f".format(peak)}")
            Log.i(tag, "gercekZamanKati=${"%.2f".format(seconds * 1000 / totalMs.coerceAtLeast(1))}x")

            val out = File(getExternalFilesDir(null), "selftest.wav")
            writeWav(out, samples, TtsEngine.sampleRate)
            Log.i(tag, "wav yazildi: ${out.absolutePath} (${out.length()} B)")

            if (docPath != null) testDocument(docPath)

            Log.i(tag, "SONUC: BASARILI")
        } catch (t: Throwable) {
            Log.e(tag, "SONUC: HATA ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun testDocument(path: String) {
        val uri = Uri.fromFile(File(path))
        val model = DocumentModel(File(path).name, path, DocumentModel.Kind.TEXT)
        val t0 = SystemClock.elapsedRealtime()
        var firstBatchMs = -1L
        DocumentLoader.loadText(this, uri, object : DocumentLoader.Sink {
            override fun onBatch(paragraphs: List<com.xwendevan.reader.doc.ParsedParagraph>) {
                if (firstBatchMs < 0) firstBatchMs = SystemClock.elapsedRealtime() - t0
                model.addBatch(paragraphs)
            }

            override fun onProgress(parsed: Int, total: Int) {}
            override fun onDone() = model.markComplete()
            override fun onError(message: String) {
                Log.e(tag, "belge hatasi: $message")
            }
        })
        Log.i(tag, "belge: ${model.paragraphCount} paragraf, ${model.sentenceCount} cumle, ilkParti=$firstBatchMs ms, toplam=${SystemClock.elapsedRealtime() - t0} ms")
        for (i in 0 until minOf(4, model.sentenceCount)) {
            Log.i(tag, "  cumle[$i]: ${model.sentenceAt(i)?.text?.take(90)}")
        }
    }

    private fun writeWav(file: File, chunks: List<FloatArray>, sampleRate: Int) {
        val frames = chunks.sumOf { it.size }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + frames * 2)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(1.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2.toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray())
            header.putInt(frames * 2)
            raf.write(header.array())

            val body = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (c in chunks) for (v in c) {
                val s = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                body.putShort(s)
            }
            raf.write(body.array())
        }
    }
}

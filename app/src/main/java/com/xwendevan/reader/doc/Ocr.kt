package com.xwendevan.reader.doc

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Taranmış (metin katmanı olmayan) PDF'ler için çevrimdışı OCR.
 * Tesseract ve Türkçe + Kurmancî veri dosyaları APK'nın içinde gelir.
 */
object Ocr {

    private const val TAG = "Ocr"
    private const val LANGUAGES = "tur+kmr"
    private const val MARKER = ".ready_v1"

    /** Sayfa görüntülerini okurken kullanılan genişlik (piksel). */
    const val RENDER_WIDTH = 1700

    /** OCR verisi hazır mı; ilk kullanımda APK'dan açılır. */
    private fun dataDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "tesseract")
        val tessdata = File(dir, "tessdata")
        val marker = File(dir, MARKER)
        if (marker.exists()) return dir

        tessdata.mkdirs()
        for (name in ctx.assets.list("tessdata").orEmpty()) {
            ctx.assets.open("tessdata/$name").use { input ->
                File(tessdata, name).outputStream().use { out -> input.copyTo(out, 128 * 1024) }
            }
        }
        marker.writeText("ok")
        return dir
    }

    /** Bir belgenin OCR çıktısı; tekrar tanımaya gerek kalmasın diye saklanır. */
    private fun cacheFile(ctx: Context, key: String): File =
        File(File(ctx.filesDir, "ocr").apply { mkdirs() }, "${key.hashCode()}.txt")

    fun cached(ctx: Context, key: String): List<String>? {
        val f = cacheFile(ctx, key)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            f.readText(Charsets.UTF_8).split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun saveCache(ctx: Context, key: String, paragraphs: List<String>) {
        runCatching { cacheFile(ctx, key).writeText(paragraphs.joinToString("\n\n"), Charsets.UTF_8) }
    }

    /**
     * Sayfaları sırayla tanır. [render] sayfayı görüntüye çevirir,
     * [onPage] her sayfanın paragraflarını verir, [cancelled] true dönerse durur.
     */
    fun run(
        ctx: Context,
        key: String,
        pageCount: Int,
        render: (Int) -> Bitmap?,
        cancelled: () -> Boolean = { false },
        onPage: (Int, List<String>) -> Unit
    ): List<String> {
        val api = TessBaseAPI()
        val all = ArrayList<String>()
        try {
            if (!api.init(dataDir(ctx).absolutePath, LANGUAGES)) {
                throw IllegalStateException("OCR başlatılamadı")
            }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

            for (page in 0 until pageCount) {
                if (cancelled()) break
                val bitmap = render(page) ?: continue
                api.setImage(bitmap)
                val text = runCatching { api.utF8Text }.getOrNull().orEmpty()
                api.clear()
                if (!bitmap.isRecycled) bitmap.recycle()

                val paragraphs = TextFix.normalize(text)
                    .split(Regex("\n[ \t]*\n+"))
                    .map { it.replace(Regex("[ \t]*\n[ \t]*"), " ").replace(Regex("\\s+"), " ").trim() }
                    .filter { it.length > 1 }

                if (paragraphs.isNotEmpty()) all.addAll(paragraphs)
                onPage(page, paragraphs)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OCR hatası", t)
            throw t
        } finally {
            runCatching { api.recycle() }
        }

        if (all.isNotEmpty() && !cancelled()) saveCache(ctx, key, all)
        return all
    }
}

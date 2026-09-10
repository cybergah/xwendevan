package com.xwendevan.reader.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File

/**
 * PDF'i olduğu gibi gösterebilmek için sayfaları görüntüye dönüştürür.
 * PdfRenderer aynı anda tek sayfa açabildiğinden tüm erişim tek kilit altında.
 */
class PdfSource private constructor(
    val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    val pageCount: Int = renderer.pageCount

    /** Sayfa boyutları (nokta / 1-72 inç). */
    val pageSizes: List<FloatArray> = ArrayList<FloatArray>(pageCount).also { list ->
        for (i in 0 until pageCount) {
            synchronized(lock) {
                renderer.openPage(i).use { p ->
                    list.add(floatArrayOf(p.width.toFloat(), p.height.toFloat()))
                }
            }
        }
    }

    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 5).coerceAtLeast(16 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(key(index, widthPx))

    fun render(index: Int, widthPx: Int): Bitmap? {
        if (index < 0 || index >= pageCount || widthPx <= 0) return null
        cache.get(key(index, widthPx))?.let { return it }
        val size = pageSizes.getOrNull(index) ?: return null
        val heightPx = (widthPx * size[1] / size[0]).toInt().coerceAtLeast(1)
        val bmp = runCatching {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        bmp.eraseColor(Color.WHITE)
        synchronized(lock) {
            runCatching {
                renderer.openPage(index).use { page ->
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }.onFailure {
                bmp.recycle()
                return null
            }
        }
        cache.put(key(index, widthPx), bmp)
        return bmp
    }

    /** OCR için tek seferlik çizim: önbelleği kirletmez. */
    fun renderOnce(index: Int, widthPx: Int): Bitmap? {
        if (index < 0 || index >= pageCount || widthPx <= 0) return null
        val size = pageSizes.getOrNull(index) ?: return null
        val heightPx = (widthPx * size[1] / size[0]).toInt().coerceAtLeast(1)
        val bmp = runCatching {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        bmp.eraseColor(Color.WHITE)
        synchronized(lock) {
            runCatching {
                renderer.openPage(index).use { page ->
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }.onFailure {
                bmp.recycle()
                return null
            }
        }
        return bmp
    }

    private fun key(index: Int, width: Int) = "$index@$width"

    override fun close() {
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        private val lock = Any()

        /** İçeriği önbelleğe kopyalar (PdfRenderer ve PDFBox aynı dosyayı kullanır). */
        fun open(ctx: Context, uri: Uri, key: String): PdfSource {
            val dir = File(ctx.cacheDir, "docs").apply { mkdirs() }
            val cached = File(dir, "${key.hashCode()}.pdf")
            if (!cached.exists() || cached.length() == 0L) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    cached.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                } ?: throw IllegalStateException("Dosya açılamadı")
            }
            val pfd = ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            return PdfSource(cached, pfd, renderer)
        }
    }
}

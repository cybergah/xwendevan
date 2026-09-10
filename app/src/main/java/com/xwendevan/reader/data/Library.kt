package com.xwendevan.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Kitaplıktaki bir belge. */
data class BookEntry(
    val uri: String,
    var name: String,
    var kind: String,              // pdf | docx | txt
    var addedAt: Long,
    var lastOpenedAt: Long,
    var sentenceIndex: Int = 0,
    var totalSentences: Int = 0,
    var pageCount: Int = 0,
    var thumb: String? = null,
    var favorite: Boolean = false,
    var openCount: Int = 0,
    var listenSeconds: Long = 0
) {
    val progress: Int
        get() = if (totalSentences <= 1) 0 else
            ((sentenceIndex + 1) * 100 / totalSentences).coerceIn(0, 100)

    /** Yarım kalmış: başlanmış ama bitmemiş. */
    val inProgress: Boolean get() = progress in 1..97

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        put("kind", kind)
        put("addedAt", addedAt)
        put("lastOpenedAt", lastOpenedAt)
        put("sentenceIndex", sentenceIndex)
        put("totalSentences", totalSentences)
        put("pageCount", pageCount)
        put("thumb", thumb ?: "")
        put("favorite", favorite)
        put("openCount", openCount)
        put("listenSeconds", listenSeconds)
    }

    companion object {
        fun fromJson(o: JSONObject) = BookEntry(
            uri = o.getString("uri"),
            name = o.optString("name"),
            kind = o.optString("kind", "txt"),
            addedAt = o.optLong("addedAt"),
            lastOpenedAt = o.optLong("lastOpenedAt"),
            sentenceIndex = o.optInt("sentenceIndex"),
            totalSentences = o.optInt("totalSentences"),
            pageCount = o.optInt("pageCount"),
            thumb = o.optString("thumb").takeIf { it.isNotBlank() },
            favorite = o.optBoolean("favorite", false),
            openCount = o.optInt("openCount", 0),
            listenSeconds = o.optLong("listenSeconds", 0)
        )
    }
}

/** Ana ekranda listelenen belge kitaplığı; JSON olarak saklanır. */
object Library {

    private const val FILE = "library.json"
    private var cache: MutableList<BookEntry>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun all(ctx: Context): List<BookEntry> = load(ctx).sortedByDescending { it.lastOpenedAt }

    @Synchronized
    fun find(ctx: Context, uri: String): BookEntry? = load(ctx).firstOrNull { it.uri == uri }

    @Synchronized
    fun upsert(ctx: Context, entry: BookEntry) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.uri == entry.uri }
        if (i >= 0) list[i] = entry else list.add(entry)
        save(ctx, list)
    }

    @Synchronized
    fun touch(ctx: Context, uri: String, name: String, kind: String): BookEntry {
        val list = load(ctx)
        val existing = list.firstOrNull { it.uri == uri }
        val now = System.currentTimeMillis()
        val entry = existing?.also {
            it.lastOpenedAt = now
            it.name = name
        } ?: BookEntry(uri, name, kind, now, now).also { list.add(it) }
        save(ctx, list)
        return entry
    }

    @Synchronized
    fun updateProgress(ctx: Context, uri: String, index: Int, total: Int, pages: Int = -1) {
        val list = load(ctx)
        val e = list.firstOrNull { it.uri == uri } ?: return
        e.sentenceIndex = index
        if (total > 0) e.totalSentences = total
        if (pages > 0) e.pageCount = pages
        e.lastOpenedAt = System.currentTimeMillis()
        save(ctx, list)
    }

    /** Belge okuma ekranında açıldı: okunma sayacı. */
    @Synchronized
    fun markOpened(ctx: Context, uri: String) {
        val list = load(ctx)
        val e = list.firstOrNull { it.uri == uri } ?: return
        e.openCount += 1
        e.lastOpenedAt = System.currentTimeMillis()
        save(ctx, list)
    }

    /** Sesli okuma süresini biriktirir. */
    @Synchronized
    fun addListenTime(ctx: Context, uri: String, seconds: Long) {
        if (seconds <= 0) return
        val list = load(ctx)
        val e = list.firstOrNull { it.uri == uri } ?: return
        e.listenSeconds += seconds
        save(ctx, list)
        Stats.add(ctx, seconds)
    }

    @Synchronized
    fun setFavorite(ctx: Context, uri: String, value: Boolean) {
        val list = load(ctx)
        val e = list.firstOrNull { it.uri == uri } ?: return
        e.favorite = value
        save(ctx, list)
    }

    /** Yarım kalanlar — en son açılan başta. */
    fun continuing(ctx: Context): List<BookEntry> =
        all(ctx).filter { it.inProgress }

    fun favorites(ctx: Context): List<BookEntry> =
        all(ctx).filter { it.favorite }

    /**
     * En çok okunanlar: dinleme süresi ve açılma sayısı birlikte puanlanır,
     * hiç dinlenmemiş belgeler listeye girmez.
     */
    fun mostRead(ctx: Context): List<BookEntry> =
        all(ctx).filter { it.listenSeconds >= 30 || it.openCount >= 2 }
            .sortedByDescending { it.listenSeconds + it.openCount * 60L }

    @Synchronized
    fun remove(ctx: Context, uri: String) {
        val list = load(ctx)
        list.firstOrNull { it.uri == uri }?.thumb?.let { runCatching { File(it).delete() } }
        list.removeAll { it.uri == uri }
        save(ctx, list)
        Bookmarks.removeDocument(ctx, uri)
    }

    @Synchronized
    fun setThumb(ctx: Context, uri: String, path: String) {
        val list = load(ctx)
        list.firstOrNull { it.uri == uri }?.thumb = path
        save(ctx, list)
    }

    private fun load(ctx: Context): MutableList<BookEntry> {
        cache?.let { return it }
        val list = ArrayList<BookEntry>()
        val f = file(ctx)
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) list.add(BookEntry.fromJson(arr.getJSONObject(i)))
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<BookEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(ctx).writeText(arr.toString(), Charsets.UTF_8) }
    }

    /** PDF'in ilk sayfasından küçük bir kapak görüntüsü üretir. */
    fun makeThumb(ctx: Context, uri: Uri, key: String): String? {
        return runCatching {
            val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: return null
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val w = 240
                        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
                        val out = File(dir, "${key.hashCode()}.png")
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        bmp.recycle()
                        out.absolutePath
                    }
                }
            }
        }.getOrNull()
    }
}

package com.xwendevan.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bir belgedeki işaretli cümle. */
data class Bookmark(
    val uri: String,
    val sentence: Int,
    val text: String,
    val page: Int,
    val at: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("sentence", sentence)
        put("text", text)
        put("page", page)
        put("at", at)
    }

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            uri = o.optString("uri"),
            sentence = o.optInt("sentence"),
            text = o.optString("text"),
            page = o.optInt("page", -1),
            at = o.optLong("at")
        )
    }
}

/** Yer imleri; belge bazında saklanır. */
object Bookmarks {

    private const val FILE = "bookmarks.json"
    private var cache: MutableList<Bookmark>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun forDocument(ctx: Context, uri: String): List<Bookmark> =
        load(ctx).filter { it.uri == uri }.sortedBy { it.sentence }

    @Synchronized
    fun add(ctx: Context, mark: Bookmark): Boolean {
        val list = load(ctx)
        if (list.any { it.uri == mark.uri && it.sentence == mark.sentence }) return false
        list.add(mark)
        save(ctx, list)
        return true
    }

    @Synchronized
    fun remove(ctx: Context, uri: String, sentence: Int) {
        val list = load(ctx)
        list.removeAll { it.uri == uri && it.sentence == sentence }
        save(ctx, list)
    }

    @Synchronized
    fun removeDocument(ctx: Context, uri: String) {
        val list = load(ctx)
        list.removeAll { it.uri == uri }
        save(ctx, list)
    }

    private fun load(ctx: Context): MutableList<Bookmark> {
        cache?.let { return it }
        val list = ArrayList<Bookmark>()
        val f = file(ctx)
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) list.add(Bookmark.fromJson(arr.getJSONObject(i)))
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(ctx).writeText(arr.toString(), Charsets.UTF_8) }
    }
}

package com.xwendevan.reader.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Günlük dinleme süresi kaydı: { "2026-09-10": 1834, … } saniye cinsinden. */
object Stats {

    private const val FILE = "stats.json"
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun add(ctx: Context, seconds: Long) {
        if (seconds <= 0) return
        val json = read(ctx)
        val key = dayFormat.format(Date())
        json.put(key, json.optLong(key, 0) + seconds)
        prune(json)
        runCatching { file(ctx).writeText(json.toString(), Charsets.UTF_8) }
    }

    fun today(ctx: Context): Long = read(ctx).optLong(dayFormat.format(Date()), 0)

    /** Son 7 gün (bugün dahil). */
    fun week(ctx: Context): Long = lastDays(ctx, 7).sumOf { it.second }

    fun total(ctx: Context): Long {
        val json = read(ctx)
        var sum = 0L
        val keys = json.keys()
        while (keys.hasNext()) sum += json.optLong(keys.next(), 0)
        return sum
    }

    /** En eskiden bugüne doğru (gün adı, saniye) çiftleri. */
    fun lastDays(ctx: Context, days: Int): List<Pair<Date, Long>> {
        val json = read(ctx)
        val out = ArrayList<Pair<Date, Long>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        repeat(days) {
            val date = cal.time
            out.add(date to json.optLong(dayFormat.format(date), 0))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /** En çok dinlenen belgeler. */
    fun topBooks(ctx: Context, limit: Int = 5): List<BookEntry> =
        Library.all(ctx).filter { it.listenSeconds > 0 }
            .sortedByDescending { it.listenSeconds }
            .take(limit)

    @Synchronized
    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    private fun read(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.exists()) return JSONObject()
        return runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    /** Bir yıldan eski günleri at. */
    private fun prune(json: JSONObject) {
        if (json.length() <= 400) return
        val cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, -1)
        val limit = dayFormat.format(cal.time)
        val stale = ArrayList<String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k < limit) stale.add(k)
        }
        stale.forEach { json.remove(it) }
    }
}

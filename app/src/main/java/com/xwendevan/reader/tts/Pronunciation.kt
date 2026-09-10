package com.xwendevan.reader.tts

import android.content.Context
import com.xwendevan.reader.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bir okunuş düzeltmesi: [from] geçtiğinde [to] okunur. */
data class PronRule(
    val from: String,
    val to: String,
    val wholeWord: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("from", from)
        put("to", to)
        put("whole", wholeWord)
    }

    companion object {
        fun fromJson(o: JSONObject) = PronRule(
            from = o.optString("from"),
            to = o.optString("to"),
            wholeWord = o.optBoolean("whole", true)
        )
    }
}

/**
 * Telaffuz sözlüğü: kısaltmalar ve özel adlar seslendirilmeden önce
 * kullanıcının yazdığı karşılıklarla değiştirilir. Metnin kendisi değişmez,
 * yalnızca modele giden kopya düzeltilir.
 */
object Pronunciation {

    private const val FILE = "pronunciation.json"
    private const val SEEDED = "pron_seeded"

    private var cache: List<PronRule>? = null
    private var compiled: List<Pair<Regex, String>> = emptyList()

    /** İlk kurulumda gelen örnek kurallar. */
    private val DEFAULTS = listOf(
        PronRule("vb.", "ve benzeri"),
        PronRule("vs.", "vesaire"),
        PronRule("örn.", "örneğin"),
        PronRule("Dr.", "Doktor"),
        PronRule("Prof.", "Profesör"),
        PronRule("TBMM", "Te Be Em Me"),
        PronRule("TL", "Türk lirası")
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun rules(ctx: Context): List<PronRule> {
        cache?.let { return it }
        val f = file(ctx)
        val list = ArrayList<PronRule>()
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) list.add(PronRule.fromJson(arr.getJSONObject(i)))
            }
        } else if (!seeded(ctx)) {
            list.addAll(DEFAULTS)
            markSeeded(ctx)
            write(ctx, list)
        }
        cache = list
        compile(list)
        return list
    }

    @Synchronized
    fun save(ctx: Context, list: List<PronRule>) {
        write(ctx, list)
        cache = list
        compile(list)
    }

    fun add(ctx: Context, rule: PronRule) {
        val list = rules(ctx).filterNot { it.from.equals(rule.from, ignoreCase = false) } + rule
        save(ctx, list)
    }

    fun remove(ctx: Context, from: String) {
        save(ctx, rules(ctx).filterNot { it.from == from })
    }

    /** Seslendirilecek metni düzeltir. Sentez iş parçacığından çağrılır. */
    fun apply(text: String): String {
        if (compiled.isEmpty()) {
            // henüz yüklenmediyse bir kez oku
            runCatching { rules(App.instance) }
            if (compiled.isEmpty()) return text
        }
        var out = text
        for ((regex, replacement) in compiled) out = regex.replace(out, replacement)
        return out
    }

    // ------------------------------------------------------------------

    private fun compile(list: List<PronRule>) {
        compiled = list.filter { it.from.isNotBlank() }.map { rule ->
            val quoted = Regex.escape(rule.from)
            // Kelime sınırı: harf/rakam bitişik değilse. Kısaltmalarda nokta da sayılır.
            val pattern = if (rule.wholeWord) "(?<![\\p{L}\\p{N}])$quoted(?![\\p{L}\\p{N}])" else quoted
            Regex(pattern, RegexOption.IGNORE_CASE) to Regex.escapeReplacement(rule.to)
        }
    }

    private fun write(ctx: Context, list: List<PronRule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(ctx).writeText(arr.toString(), Charsets.UTF_8) }
    }

    private fun seeded(ctx: Context): Boolean =
        ctx.getSharedPreferences("xwendevan", Context.MODE_PRIVATE).getBoolean(SEEDED, false)

    private fun markSeeded(ctx: Context) =
        ctx.getSharedPreferences("xwendevan", Context.MODE_PRIVATE).edit()
            .putBoolean(SEEDED, true).apply()
}

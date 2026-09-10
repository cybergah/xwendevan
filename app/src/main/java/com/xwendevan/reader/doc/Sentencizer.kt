package com.xwendevan.reader.doc

/**
 * Metni seslendirmeye uygun cümlelere böler.
 * Amaç: çok kısa parçalar (kesik prozodi) ile çok uzun parçalar (geç başlayan ses)
 * arasında denge kurmak.
 */
object Sentencizer {

    private const val MAX_LEN = 280
    private const val SOFT_MIN = 24

    /** Yaygın kısaltmalar: sonrasındaki nokta cümleyi bitirmez. */
    private val ABBREVIATIONS = setOf(
        "dr", "doç", "prof", "av", "sn", "bkz", "vb", "vs", "yy", "no", "tel", "cad", "sok",
        "mah", "apt", "bl", "st", "mr", "mrs", "ms", "jr", "sr", "etc", "vol", "fig",
        "hz", "alb", "gen", "yrd", "öğr", "gör", "arş", "bşk", "mad", "krş", "çev", "haz",
        "ed", "eds", "pp", "op", "cit", "min", "max", "km", "cm", "mm", "kg", "gr", "lt"
    )

    private const val QUOTE_TAIL = "\"'”’)]»"

    fun split(text: String): List<IntRange> {
        val raw = ArrayList<IntRange>()
        if (text.isBlank()) return raw

        var segStart = 0
        var i = 0
        val n = text.length

        while (i < n) {
            val c = text[i]
            val isBreaker = c == '.' || c == '!' || c == '?' || c == '…' || c == '؟' || c == '۔'
            if (!isBreaker) {
                i++
                continue
            }
            // Ardışık noktalama işaretlerini yut ("...", "?!")
            var j = i
            while (j + 1 < n && (text[j + 1] == '.' || text[j + 1] == '!' || text[j + 1] == '?' || text[j + 1] == '…')) j++
            // Kapanış tırnak / parantez cümleye dahil
            while (j + 1 < n && QUOTE_TAIL.indexOf(text[j + 1]) >= 0) j++

            val atEnd = j + 1 >= n
            val nextIsSpace = !atEnd && text[j + 1].isWhitespace()

            if ((atEnd || nextIsSpace) && !isFalseStop(text, i)) {
                if (j - segStart + 1 >= 2) raw.add(segStart..j)
                segStart = j + 1
                while (segStart < n && text[segStart].isWhitespace()) segStart++
                i = segStart
            } else {
                i = j + 1
            }
        }
        if (segStart < n && text.substring(segStart).isNotBlank()) raw.add(segStart..(n - 1))

        // Uzun parçaları böl
        val balanced = ArrayList<IntRange>()
        for (r in raw) balanced.addAll(splitLong(text, r))

        // Çok kısa parçaları bir sonrakine yapıştır
        val merged = ArrayList<IntRange>()
        var pending: IntRange? = null
        for (r in balanced) {
            val p = pending
            if (p != null) {
                val combined = p.first..r.last
                pending = null
                if (combined.last - combined.first + 1 <= MAX_LEN) {
                    merged.add(combined)
                    continue
                }
                merged.add(p)
            }
            if (r.last - r.first + 1 < 4) pending = r else merged.add(r)
        }
        pending?.let { merged.add(it) }
        return if (merged.isEmpty()) balanced else merged
    }

    private fun splitLong(text: String, range: IntRange): List<IntRange> {
        if (range.last - range.first + 1 <= MAX_LEN) return listOf(range)

        val out = ArrayList<IntRange>()
        var start = range.first
        while (range.last - start + 1 > MAX_LEN) {
            var cut = -1
            for (ch in charArrayOf(';', ':', '،', '—', ',')) {
                cut = lastIndexOfIn(text, ch, start + SOFT_MIN, start + MAX_LEN)
                if (cut > 0) break
            }
            if (cut <= 0) cut = lastIndexOfIn(text, ' ', start + SOFT_MIN, start + MAX_LEN)
            if (cut <= 0) cut = minOf(start + MAX_LEN - 1, range.last)
            out.add(start..cut)
            start = cut + 1
            while (start <= range.last && text[start].isWhitespace()) start++
        }
        if (start <= range.last) out.add(start..range.last)
        return out
    }

    private fun lastIndexOfIn(text: String, ch: Char, from: Int, to: Int): Int {
        var i = minOf(to, text.length - 1)
        while (i >= from && i >= 0) {
            if (text[i] == ch) return i
            i--
        }
        return -1
    }

    /** "Dr.", "3.14", "1. Bölüm", "M. Kemal" gibi durumlarda cümle bitmez. */
    private fun isFalseStop(text: String, dotIndex: Int): Boolean {
        if (text[dotIndex] != '.') return false

        if (dotIndex > 0 && dotIndex + 1 < text.length &&
            text[dotIndex - 1].isDigit() && text[dotIndex + 1].isDigit()
        ) return true

        var s = dotIndex - 1
        while (s >= 0 && (text[s].isLetter() || text[s].isDigit())) s--
        val word = text.substring(s + 1, dotIndex)
        if (word.isEmpty()) return false

        if (word.length == 1 && word[0].isLetter()) return true
        if (word.all { it.isDigit() } && word.length <= 2) return true

        return ABBREVIATIONS.contains(word.lowercase())
    }
}

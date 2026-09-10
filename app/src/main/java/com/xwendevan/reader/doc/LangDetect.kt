package com.xwendevan.reader.doc

/**
 * Belgenin dilini kaba ama ucuz bir puanlamayla kestirir: Kurmancî'ye özgü
 * harfler/kelimeler ile Türkçe'ye özgü olanlar sayılır. Amaç mükemmel bir
 * sınıflandırma değil, doğru ses modelini kendiliğinden seçebilmek.
 */
object LangDetect {

    private val KU_WORDS = setOf(
        "û", "di", "bi", "ku", "ji", "li", "ev", "ew", "ez", "tu", "em", "hûn", "wan",
        "heye", "hene", "dike", "dibe", "kir", "bû", "were", "gelek", "her", "wek",
        "jî", "ne", "bo", "der", "xwe", "min", "te", "wî", "wê", "nav", "roj", "zimanê"
    )

    private val TR_WORDS = setOf(
        "ve", "bir", "bu", "için", "ile", "olarak", "çok", "daha", "gibi", "kadar",
        "sonra", "önce", "ancak", "ama", "veya", "her", "olan", "olduğu", "değil",
        "yok", "var", "şey", "kişi", "yıl", "gün", "dil", "büyük"
    )

    /** @return "tr" · "ku" · "ar" · "" (bilinmiyor) */
    fun detect(text: String): String {
        if (text.isBlank()) return ""
        val sample = text.take(4000)

        var arabic = 0
        var latin = 0
        for (ch in sample) {
            when {
                ch in '؀'..'ۿ' -> arabic++
                ch.isLetter() -> latin++
            }
        }
        if (arabic > latin && arabic > 20) return "ar"
        if (latin < 20) return ""

        var ku = 0
        var tr = 0

        // Harf ipuçları: ê î û yalnız Kurmancî'de, ğ ı ş(sık) Türkçe'de
        for (ch in sample) {
            when (ch) {
                'ê', 'Ê', 'î', 'Î', 'û', 'Û' -> ku += 2
                'ğ', 'Ğ', 'ı', 'İ' -> tr += 2
                'ö', 'ü', 'Ö', 'Ü' -> tr += 1
            }
        }

        // Kelime ipuçları
        val words = sample.lowercase().split(Regex("[^\\p{L}]+"))
        for (w in words) {
            if (w.length > 12) continue
            if (w in KU_WORDS) ku += 3
            if (w in TR_WORDS) tr += 3
        }

        val diff = ku - tr
        return when {
            diff > 12 -> "ku"
            diff < -12 -> "tr"
            ku > tr -> "ku"
            tr > ku -> "tr"
            else -> ""
        }
    }
}

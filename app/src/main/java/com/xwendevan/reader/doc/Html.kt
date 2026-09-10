package com.xwendevan.reader.doc

/** (X)HTML parçalarını düz metne çevirir; EPUB bölümleri bunun üzerinden okunur. */
object Html {

    private val SCRIPT = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val HEAD = Regex("(?is)<head[^>]*>.*?</head>")
    private val BLOCK_END = Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)\\s*>")
    private val BREAK = Regex("(?i)<br\\s*/?>|<hr\\s*/?>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…",
        "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "shy" to "", "copy" to "©", "reg" to "®"
    )

    /** Blok etiketlerini satır sonuna çevirip metni paragraflara böler. */
    fun toParagraphs(html: String): List<String> {
        var text = HEAD.replace(html, " ")
        text = SCRIPT.replace(text, " ")
        text = BLOCK_END.replace(text, "\n")
        text = BREAK.replace(text, "\n")
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        text = TextFix.normalize(text)

        return text.split('\n')
            .map { it.replace(Regex("[ \t ]+"), " ").trim() }
            .filter { it.isNotEmpty() }
    }

    fun decodeEntities(input: String): String = ENTITY.replace(input) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value

            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value

            else -> NAMED[body.lowercase()] ?: m.value
        }
    }
}

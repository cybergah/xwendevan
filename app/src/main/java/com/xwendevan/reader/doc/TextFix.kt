package com.xwendevan.reader.doc

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Metin bütünlüğü: doğru karakter kodlamasını seçer ve PDF/Word kaynaklı
 * bağ harflerini (ﬁ, ﬂ …) ile görünmez karakterleri düzeltir.
 * Harfler asla değiştirilmez; yalnızca aynı anlama gelen karşılıkları yazılır.
 */
object TextFix {

    /** Tek bir görüntüde birleşmiş harfler; okunurken ayrılmalı. */
    private val LIGATURES: Map<Char, String> = mapOf(
        0xFB00 to "ff", 0xFB01 to "fi", 0xFB02 to "fl",
        0xFB03 to "ffi", 0xFB04 to "ffl", 0xFB05 to "st", 0xFB06 to "st",
        0x0132 to "IJ", 0x0133 to "ij", 0x0152 to "OE", 0x0153 to "oe"
    ).mapKeys { it.key.toChar() }

    /** Metinden atilacak gorunmez karakterler (yumusak tire, sifir genislikli ...). */
    private val INVISIBLE: Set<Char> =
        setOf(0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF).map { it.toChar() }.toSet()

    /** Bosluk yerine gecen karakterler. */
    private val SPACES: Set<Char> =
        (listOf(0x00A0, 0x1680, 0x202F, 0x205F, 0x3000) + (0x2000..0x200A).toList())
            .map { it.toChar() }.toSet()

    fun expand(ch: Char): String? = LIGATURES[ch]

    fun isInvisible(ch: Char): Boolean = INVISIBLE.contains(ch)

    fun isSpaceLike(ch: Char): Boolean = SPACES.contains(ch)

    fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            when {
                isInvisible(ch) -> {}
                isSpaceLike(ch) -> out.append(' ')
                else -> {
                    val ligature = LIGATURES[ch]
                    if (ligature != null && ligature.length > 1) out.append(ligature) else out.append(ch)
                }
            }
        }
        return out.toString()
    }

    /**
     * Baytları en uygun kodlamayla çözer.
     * Önce BOM, sonra katı UTF-8; olmazsa Türkçe/Kürtçe/Arapça alfabelerine
     * göre puanlanan eski kodlamalar denenir.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

        strictUtf8(bytes)?.let { return it }

        val candidates = listOf("windows-1254", "windows-1256", "ISO-8859-9", "windows-1252", "ISO-8859-1")
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (name in candidates) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val text = String(bytes, charset)
            val score = score(text)
            if (score > bestScore) {
                bestScore = score
                best = text
            }
        }
        return best ?: String(bytes, Charsets.UTF_8)
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /** Beklenen harfler artı, bozuk/görüntülenemeyen karakterler eksi puan. */
    private fun score(text: String): Int {
        var points = 0
        for (ch in text) {
            when {
                ch == '�' -> points -= 20
                ch.code in 0x80..0x9F -> points -= 10          // C1 kontrol bölgesi
                ch in "çğıöşüÇĞİÖŞÜ" -> points += 3            // Türkçe
                ch in "êîûŞşÊÎÛ" -> points += 3                // Kurmancî
                ch.code in 0x0600..0x06FF -> points += 3       // Arapça
                ch.isLetter() || ch.isWhitespace() || ch.isDigit() -> points += 1
                ch.isISOControl() -> points -= 5
            }
        }
        return points
    }
}

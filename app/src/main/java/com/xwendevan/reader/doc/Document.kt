package com.xwendevan.reader.doc

import android.graphics.RectF

/** Bir paragraf içindeki cümle. PDF'te ayrıca sayfa ve konum kutuları taşır. */
data class Sentence(
    val index: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val page: Int = -1,
    val boxes: List<RectF> = emptyList()
)

/**
 * Okunan belge. Ayrıştırma sürerken paragraf/cümleler eklenmeye devam eder,
 * bu yüzden listelere senkronize erişilir.
 */
class DocumentModel(val title: String, val key: String, val kind: Kind) {

    enum class Kind { TEXT, PDF }

    private val _paragraphs = ArrayList<String>()
    private val _sentences = ArrayList<Sentence>()
    private val _paraToSentences = HashMap<Int, MutableList<Int>>()
    private val _pageToSentences = HashMap<Int, MutableList<Int>>()

    @Volatile
    var isComplete: Boolean = false
        private set

    @Volatile
    var totalUnits: Int = 0

    @Volatile
    var parsedUnits: Int = 0

    val paragraphCount: Int get() = synchronized(this) { _paragraphs.size }
    val sentenceCount: Int get() = synchronized(this) { _sentences.size }

    fun paragraphAt(i: Int): String = synchronized(this) {
        if (i in _paragraphs.indices) _paragraphs[i] else ""
    }

    fun sentenceAt(i: Int): Sentence? = synchronized(this) {
        if (i in _sentences.indices) _sentences[i] else null
    }

    fun sentencesOfParagraph(p: Int): List<Sentence> = synchronized(this) {
        _paraToSentences[p]?.map { _sentences[it] } ?: emptyList()
    }

    fun sentencesOfPage(page: Int): List<Sentence> = synchronized(this) {
        _pageToSentences[page]?.map { _sentences[it] } ?: emptyList()
    }

    /** Ayrıştırıcıdan gelen paragrafları ekler. */
    fun addBatch(list: List<ParsedParagraph>): Int = synchronized(this) {
        var added = 0
        for (p in list) {
            val text = p.text.trim()
            if (text.isEmpty()) continue
            // trim() başta boşluk kırptıysa kutuları da kaydır
            val offset = p.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val boxes = p.boxes?.let { it.subList(offset.coerceAtMost(it.size), it.size) }
            added += addOne(text, p.page, boxes)
        }
        added
    }

    /** Düz metin belgeler için basit paragraf ekleme. */
    fun addParagraphs(newParagraphs: List<String>): Int =
        addBatch(newParagraphs.map { ParsedParagraph(it) })

    private fun addOne(text: String, page: Int, charBoxes: List<RectF?>?): Int {
        val p = _paragraphs.size
        _paragraphs.add(text)
        val ids = ArrayList<Int>()
        var added = 0

        val ranges = Sentencizer.split(text).ifEmpty { listOf(0..(text.length - 1)) }
        for (range in ranges) {
            val body = text.substring(range.first, range.last + 1).trim()
            if (body.isEmpty()) continue
            val boxes = charBoxes?.let { mergeBoxes(it, range.first, range.last + 1) } ?: emptyList()
            val s = Sentence(_sentences.size, p, range.first, range.last + 1, body, page, boxes)
            _sentences.add(s)
            ids.add(s.index)
            if (page >= 0) _pageToSentences.getOrPut(page) { ArrayList() }.add(s.index)
            added++
        }
        _paraToSentences[p] = ids
        return added
    }

    /** Cümle karakterlerinin kutularını satır satır birleştirir. */
    private fun mergeBoxes(charBoxes: List<RectF?>, from: Int, to: Int): List<RectF> {
        val out = ArrayList<RectF>()
        var current: RectF? = null
        var lineCenter = 0f
        for (i in from until minOf(to, charBoxes.size)) {
            val b = charBoxes[i] ?: continue
            val center = (b.top + b.bottom) / 2f
            val height = (b.bottom - b.top).coerceAtLeast(1f)
            val cur = current
            if (cur == null) {
                current = RectF(b)
                lineCenter = center
            } else if (kotlin.math.abs(center - lineCenter) > height * 0.7f || b.left < cur.left - height * 2f) {
                out.add(cur)
                current = RectF(b)
                lineCenter = center
            } else {
                cur.union(b)
                lineCenter = (lineCenter + center) / 2f
            }
        }
        current?.let { out.add(it) }
        return out
    }

    fun markComplete() {
        isComplete = true
    }

    fun charsFrom(sentenceIndex: Int): Long = synchronized(this) {
        var n = 0L
        for (i in sentenceIndex.coerceAtLeast(0) until _sentences.size) n += _sentences[i].text.length
        n
    }

    /** Verilen sayfadaki ilk cümlenin sırası. */
    fun firstSentenceOfPage(page: Int): Int = synchronized(this) {
        _pageToSentences[page]?.minOrNull() ?: -1
    }
}

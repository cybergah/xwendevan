package com.xwendevan.reader.doc

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * PDF metnini sayfa sayfa, her karakterin sayfa üzerindeki konumu ile birlikte çıkarır.
 * Konumlar sayesinde okunan cümle, sayfanın kendi görüntüsü üzerinde işaretlenebilir.
 */
object PdfTextExtractor {

    private class Cell(val ch: Char, val box: RectF?)

    fun extract(file: File, sink: DocumentLoader.Sink) {
        val document = PDDocument.load(file)
        try {
            val pages = document.numberOfPages
            sink.onProgress(0, pages)
            for (page in 1..pages) {
                val cells = runCatching { collect(document, page) }.getOrDefault(emptyList())
                val paragraphs = reflow(cells, page - 1)
                if (paragraphs.isNotEmpty()) sink.onBatch(paragraphs)
                sink.onProgress(page, pages)
            }
            sink.onDone()
        } finally {
            runCatching { document.close() }
        }
    }

    private fun collect(document: PDDocument, page: Int): List<Cell> {
        val cells = ArrayList<Cell>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (i in text.indices) {
                    val tp = textPositions.getOrNull(
                        if (textPositions.isEmpty()) -1 else minOf(i, textPositions.size - 1)
                    )
                    val box = tp?.let {
                        val top = it.yDirAdj - it.heightDir
                        RectF(it.xDirAdj, top, it.xDirAdj + it.widthDirAdj, it.yDirAdj)
                    }
                    val ch = text[i]
                    when {
                        TextFix.isInvisible(ch) -> {}
                        TextFix.isSpaceLike(ch) -> cells.add(Cell(' ', box))
                        else -> {
                            val expanded = TextFix.expand(ch)
                            if (expanded != null) {
                                // Bag harfi ayrilir; her parca ayni kutuyu paylasir
                                for (c in expanded) cells.add(Cell(c, box))
                            } else {
                                cells.add(Cell(ch, box))
                            }
                        }
                    }
                }
            }

            override fun writeLineSeparator() {
                cells.add(Cell('\n', null))
            }

            override fun writeWordSeparator() {
                cells.add(Cell(' ', null))
            }

            override fun writeParagraphSeparator() {
                cells.add(Cell('\n', null))
                cells.add(Cell('\n', null))
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = page
        stripper.endPage = page
        stripper.getText(document)
        return cells
    }

    /** Satırları paragraflara birleştirir; kutular metinle hizalı kalır. */
    private fun reflow(cells: List<Cell>, pageIndex: Int): List<ParsedParagraph> {
        if (cells.isEmpty()) return emptyList()

        // Satırlara böl
        val lines = ArrayList<List<Cell>>()
        var current = ArrayList<Cell>()
        for (c in cells) {
            if (c.ch == '\n') {
                lines.add(current); current = ArrayList()
            } else {
                current.add(c)
            }
        }
        lines.add(current)

        val trimmed = lines.map { line ->
            var s = 0
            var e = line.size
            while (s < e && line[s].ch.isWhitespace()) s++
            while (e > s && line[e - 1].ch.isWhitespace()) e--
            line.subList(s, e)
        }

        val meaningful = trimmed.filter { it.isNotEmpty() }
        val avgLen = if (meaningful.isEmpty()) 0 else meaningful.sumOf { it.size } / meaningful.size

        val out = ArrayList<ParsedParagraph>()
        val buf = ArrayList<Cell>()

        fun flush() {
            while (buf.isNotEmpty() && buf.last().ch.isWhitespace()) buf.removeAt(buf.size - 1)
            if (buf.isEmpty()) return
            val text = StringBuilder(buf.size)
            val boxes = ArrayList<RectF?>(buf.size)
            for (c in buf) {
                text.append(c.ch)
                boxes.add(c.box)
            }
            out.add(ParsedParagraph(text.toString(), pageIndex, boxes))
            buf.clear()
        }

        for (line in trimmed) {
            if (line.isEmpty()) {
                flush(); continue
            }
            val str = line.joinToString("") { it.ch.toString() }

            // Yalnızca sayfa numarası olan satırlar
            if (str.length <= 6 && str.all { it.isDigit() || it == '-' || it == '.' || it == '|' }) continue

            // Başlık gibi duran satır kendi paragrafı olsun
            if (isHeading(str, avgLen)) {
                flush()
                buf.addAll(line)
                flush()
                continue
            }

            if (buf.isNotEmpty()) {
                val last = buf.last()
                if (last.ch == '-' || last.ch == SOFT_HYPHEN) {
                    buf.removeAt(buf.size - 1)         // heceleme tiresi
                } else if (!last.ch.isWhitespace()) {
                    buf.add(Cell(' ', null))
                }
            }
            for (c in line) {
                if (c.ch.isWhitespace() && buf.isNotEmpty() && buf.last().ch == ' ') continue
                buf.add(if (c.ch == NBSP) Cell(' ', c.box) else c)
            }

            val lastChar = str.last()
            val endsSentence = lastChar == '.' || lastChar == '!' || lastChar == '?' ||
                lastChar == ':' || lastChar == '…'
            if (avgLen > 0 && str.length < avgLen * 0.62 && endsSentence) flush()
            else if (avgLen > 0 && str.length < avgLen * 0.45) flush()
        }
        flush()
        return out
    }

    private fun isHeading(line: String, avgLen: Int): Boolean {
        if (line.length > 70) return false
        if (avgLen > 0 && line.length > avgLen * 0.75) return false
        val last = line.last()
        if (last == '.' || last == ',' || last == ';' || last == ':' || last == '?' || last == '!') return false
        val letters = line.filter { it.isLetter() }
        if (letters.length < 3) return false
        return letters.count { it.isUpperCase() }.toFloat() / letters.length > 0.7f
    }

    private val NBSP = 160.toChar()
    private val SOFT_HYPHEN = 173.toChar()
}

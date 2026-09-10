package com.xwendevan.reader.doc

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Ayrıştırılmış bir paragraf. PDF'te sayfa ve karakter kutuları da doludur. */
class ParsedParagraph(
    val text: String,
    val page: Int = -1,
    val boxes: List<RectF?>? = null
)

/**
 * DOCX / TXT belgelerini parça parça metne çevirir. PDF için [PdfTextExtractor] kullanılır.
 * Her parça hazır olunca [Sink.onBatch] çağrılır; böylece okuma, belge tamamlanmadan başlar.
 */
object DocumentLoader {

    enum class Kind { PDF, DOCX, EPUB, TXT, LEGACY_DOC }

    interface Sink {
        fun onBatch(paragraphs: List<ParsedParagraph>)
        fun onProgress(parsed: Int, total: Int)
        fun onDone()
        fun onError(message: String)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "").name
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: (uri.lastPathSegment ?: "Belge")
    }

    fun kindOf(ctx: Context, uri: Uri): Kind {
        val name = displayName(ctx, uri).lowercase()
        val type = runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: ""
        return when {
            name.endsWith(".pdf") || type == "application/pdf" -> Kind.PDF
            name.endsWith(".docx") || type.contains("wordprocessingml") -> Kind.DOCX
            name.endsWith(".epub") || type == "application/epub+zip" -> Kind.EPUB
            name.endsWith(".doc") || type == "application/msword" -> Kind.LEGACY_DOC
            else -> Kind.TXT
        }
    }

    /** Uzun sürer; arka planda çağırın. DOCX, EPUB ve TXT için. */
    fun loadText(ctx: Context, uri: Uri, sink: Sink) {
        try {
            when (kindOf(ctx, uri)) {
                Kind.DOCX -> loadDocx(ctx, uri, sink)
                Kind.EPUB -> loadEpub(ctx, uri, sink)
                Kind.LEGACY_DOC -> sink.onError("doc")
                else -> loadPlain(ctx, uri, sink)
            }
        } catch (t: Throwable) {
            sink.onError(t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- EPUB

    /**
     * EPUB = zip. İçindeki OPF dosyası bölümlerin okunma sırasını (spine)
     * verir; her bölüm XHTML olarak çözülüp paragraflara ayrılır.
     */
    private fun loadEpub(ctx: Context, uri: Uri, sink: Sink) {
        val temp = File.createTempFile("book", ".epub", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
            } ?: throw IllegalStateException("Dosya açılamadı")

            ZipFile(temp).use { zip ->
                // Bazı araçlar zip içinde "\" ayırıcı kullanıyor; hepsini "/"e çeviriyoruz
                val entries = HashMap<String, java.util.zip.ZipEntry>()
                for (entry in zip.entries()) {
                    entries[entry.name.replace('\\', '/').lowercase()] = entry
                }

                val opfPath = findOpfPath(zip, entries) ?: throw IllegalStateException("EPUB içeriği bulunamadı")
                val opf = readEntry(zip, entries, opfPath) ?: throw IllegalStateException("EPUB içeriği okunamadı")
                val baseDir = opfPath.substringBeforeLast('/', "")

                val chapters = spineHrefs(opf).map { href ->
                    if (baseDir.isEmpty()) normalizePath(href) else normalizePath("$baseDir/$href")
                }
                if (chapters.isEmpty()) throw IllegalStateException("EPUB bölümleri bulunamadı")

                var index = 0
                for (path in chapters) {
                    val html = readEntry(zip, entries, path)
                    index++
                    if (html.isNullOrBlank()) {
                        sink.onProgress(index, chapters.size)
                        continue
                    }
                    val paragraphs = Html.toParagraphs(html).map { ParsedParagraph(it) }
                    if (paragraphs.isNotEmpty()) sink.onBatch(paragraphs)
                    sink.onProgress(index, chapters.size)
                }
            }
            sink.onDone()
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun findOpfPath(zip: ZipFile, entries: Map<String, java.util.zip.ZipEntry>): String? {
        val container = readEntry(zip, entries, "META-INF/container.xml")
        val fromContainer = container?.let {
            Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        }
        if (!fromContainer.isNullOrBlank()) return fromContainer.replace('\\', '/')
        // Bozuk container.xml: ilk .opf dosyasını dene
        return entries.keys.firstOrNull { it.endsWith(".opf") }
    }

    /** manifest (id → href) ile spine sırasını birleştirir. */
    private fun spineHrefs(opf: String): List<String> {
        val manifest = HashMap<String, String>()
        for (m in Regex("(?is)<item\\s([^>]*)/?>").findAll(opf)) {
            val attrs = m.groupValues[1]
            val id = Regex("id\\s*=\\s*\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: continue
            val href = Regex("href\\s*=\\s*\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: continue
            manifest[id] = href
        }
        val order = ArrayList<String>()
        val spine = Regex("(?is)<spine[^>]*>(.*?)</spine>").find(opf)?.groupValues?.get(1) ?: ""
        for (m in Regex("(?is)<itemref\\s([^>]*)/?>").findAll(spine)) {
            val idref = Regex("idref\\s*=\\s*\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1) ?: continue
            manifest[idref]?.let { order.add(it) }
        }
        // spine yoksa manifest sırasındaki xhtml dosyaları
        if (order.isEmpty()) {
            manifest.values.filterTo(order) {
                it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true)
            }
        }
        return order.map { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    private fun readEntry(
        zip: ZipFile,
        entries: Map<String, java.util.zip.ZipEntry>,
        path: String
    ): String? {
        val key = path.replace('\\', '/').lowercase()
        val entry = entries[key]
        // Yol tutmadıysa dosya adına göre dene (iç içe klasörlerde işe yarıyor)
            ?: entries.entries.firstOrNull { it.key.endsWith("/" + key.substringAfterLast('/')) }?.value
            ?: return null
        return runCatching {
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /** "a/b/../c.xhtml" → "a/c.xhtml" */
    private fun normalizePath(path: String): String {
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- TXT

    private fun loadPlain(ctx: Context, uri: Uri, sink: Sink) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Dosya açılamadı")
        val text = TextFix.normalize(TextFix.decode(bytes))
        val paragraphs = text.split(Regex("\r?\n[ \t]*\r?\n+"))
            .map { block ->
                block.replace(Regex("\r?\n"), " ").replace(Regex("[ \t]+"), " ").trim()
            }
            .filter { it.isNotBlank() }
            .map { ParsedParagraph(it) }

        var i = 0
        while (i < paragraphs.size) {
            val end = minOf(i + 200, paragraphs.size)
            sink.onBatch(paragraphs.subList(i, end))
            sink.onProgress(end, paragraphs.size)
            i = end
        }
        sink.onDone()
    }

    // ---------------------------------------------------------------- DOCX

    private fun loadDocx(ctx: Context, uri: Uri, sink: Sink) {
        val stream: InputStream = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Dosya açılamadı")

        var handled = false
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    parseWordXml(zip, sink)
                    handled = true
                    break
                }
                zip.closeEntry()
            }
        }
        if (!handled) throw IllegalStateException("Geçerli bir .docx dosyası değil")
        sink.onDone()
    }

    private fun parseWordXml(input: InputStream, sink: Sink) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")

        val batch = ArrayList<ParsedParagraph>()
        val current = StringBuilder()
        var inText = false
        var count = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> inText = true
                    "w:tab", "w:br", "w:cr" -> current.append(' ')
                }

                XmlPullParser.TEXT -> if (inText) current.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    "w:p" -> {
                        val s = TextFix.normalize(current.toString())
                            .replace(Regex("[ \t]+"), " ").trim()
                        current.setLength(0)
                        if (s.isNotEmpty()) {
                            batch.add(ParsedParagraph(s))
                            count++
                            if (batch.size >= 60) {
                                sink.onBatch(ArrayList(batch))
                                batch.clear()
                                sink.onProgress(count, 0)
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (batch.isNotEmpty()) sink.onBatch(batch)
        sink.onProgress(count, count)
    }
}

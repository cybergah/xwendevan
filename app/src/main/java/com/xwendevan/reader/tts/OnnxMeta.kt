package com.xwendevan.reader.tts

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Piper (rhasspy/piper1) modelleri ONNX üstverisi taşımaz; sherpa-onnx ise
 * `sample_rate`, `n_speakers`, `language`, `comment` gibi alanları zorunlu kılar
 * ve bulamazsa süreci sonlandırır.
 *
 * Burada modelin sonuna eksik `metadata_props` kayıtları eklenir. ModelProto'da
 * bu alan (14) zaten en sonda serileştirilir; protobuf tekrarlı alanları sıradan
 * bağımsız birleştirdiği için ekleme yapmak modeli bozmaz, Piper ile de uyumlu kalır.
 */
object OnnxMeta {

    private const val TAG = "OnnxMeta"
    private const val FIELD_METADATA = 14
    private const val FIELD_GRAPH = 7

    /** Model dosyasının üst düzey metadata_props kayıtlarını okur. */
    fun read(file: File): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            while (raf.filePointer < length) {
                val tag = readVarint(raf) ?: break
                val field = (tag ushr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> readVarint(raf) ?: return result
                    1 -> raf.seek(raf.filePointer + 8)
                    5 -> raf.seek(raf.filePointer + 4)
                    2 -> {
                        val len = readVarint(raf) ?: return result
                        if (field == FIELD_METADATA) {
                            val bytes = ByteArray(len.toInt())
                            raf.readFully(bytes)
                            parseEntry(bytes)?.let { result[it.first] = it.second }
                        } else {
                            raf.seek(raf.filePointer + len)
                        }
                    }

                    else -> return result
                }
            }
        }
        return result
    }

    /**
     * sherpa-onnx için gereken üstveriyi tamamlar. Zaten varsa dosyaya dokunmaz.
     * @return üstveri eklenip eklenmediği
     */
    fun ensurePiperMetadata(model: File, config: File): Boolean {
        val existing = runCatching { read(model) }.getOrElse {
            Log.w(TAG, "üstveri okunamadı: ${it.message}")
            emptyMap()
        }

        val json = JSONObject(config.readText(Charsets.UTF_8))
        val audio = json.optJSONObject("audio")
        val espeak = json.optJSONObject("espeak")
        val languageObj = json.optJSONObject("language")

        val wanted = LinkedHashMap<String, String>()
        wanted["model_type"] = "vits"
        wanted["comment"] = "piper"
        wanted["language"] = languageObj?.optString("name_english")?.takeIf { it.isNotBlank() }
            ?: languageObj?.optString("code")?.takeIf { it.isNotBlank() }
            ?: espeak?.optString("voice") ?: "unknown"
        wanted["voice"] = espeak?.optString("voice")?.takeIf { it.isNotBlank() } ?: "en-us"
        wanted["has_espeak"] = "1"
        wanted["n_speakers"] = json.optInt("num_speakers", 1).coerceAtLeast(1).toString()
        wanted["sample_rate"] = (audio?.optInt("sample_rate", 22050) ?: 22050).toString()

        val missing = wanted.filterKeys { !existing.containsKey(it) }
        if (missing.isEmpty()) return false

        if (!model.canWrite()) {
            throw IllegalStateException("Model dosyasına yazılamıyor: ${model.name}")
        }

        val payload = ByteArrayOutputStream()
        for ((k, v) in missing) payload.write(encodeEntry(k, v))

        RandomAccessFile(model, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(payload.toByteArray())
        }
        Log.i(TAG, "üstveri eklendi (${missing.keys}) -> ${model.name}")
        return true
    }

    // ------------------------------------------------------------ protobuf

    private fun readVarint(raf: RandomAccessFile): Long? {
        var shift = 0
        var result = 0L
        while (shift < 64) {
            val b = raf.read()
            if (b < 0) return null
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    private fun parseEntry(bytes: ByteArray): Pair<String, String>? {
        var i = 0
        var key: String? = null
        var value: String? = null
        while (i < bytes.size) {
            var shift = 0
            var tag = 0L
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                i++
                tag = tag or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val field = (tag ushr 3).toInt()
            when ((tag and 7L).toInt()) {
                2 -> {
                    var s2 = 0
                    var len = 0
                    while (i < bytes.size) {
                        val b = bytes[i].toInt() and 0xFF
                        i++
                        len = len or ((b and 0x7F) shl s2)
                        if (b and 0x80 == 0) break
                        s2 += 7
                    }
                    if (i + len > bytes.size) return null
                    val s = String(bytes, i, len, Charsets.UTF_8)
                    i += len
                    if (field == 1) key = s else if (field == 2) value = s
                }

                0 -> while (i < bytes.size && (bytes[i].toInt() and 0x80) != 0) i++
                else -> return null
            }
        }
        return if (key != null) key to (value ?: "") else null
    }

    /** metadata_props kaydı: alan 14, StringStringEntryProto{key=1, value=2} */
    private fun encodeEntry(key: String, value: String): ByteArray {
        val inner = ByteArrayOutputStream()
        val kb = key.toByteArray(Charsets.UTF_8)
        val vb = value.toByteArray(Charsets.UTF_8)
        inner.write(0x0A)                       // alan 1, uzunluk sınırlı
        writeVarint(inner, kb.size.toLong())
        inner.write(kb)
        inner.write(0x12)                       // alan 2, uzunluk sınırlı
        writeVarint(inner, vb.size.toLong())
        inner.write(vb)

        val out = ByteArrayOutputStream()
        writeVarint(out, ((FIELD_METADATA shl 3) or 2).toLong())
        val body = inner.toByteArray()
        writeVarint(out, body.size.toLong())
        out.write(body)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, valueIn: Long) {
        var value = valueIn
        while (true) {
            if (value and 0x7FL.inv() == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }
}

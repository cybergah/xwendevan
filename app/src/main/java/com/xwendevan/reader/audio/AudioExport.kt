package com.xwendevan.reader.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.tts.TtsEngine
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Yazılan metni seslendirip MP3 olarak "İndirilenler/Xwendevan" klasörüne yazar. */
object AudioExport {

    private const val BITRATE_KBPS = 96
    private const val FOLDER = "Xwendevan"

    data class Result(val uri: Uri, val displayName: String, val bytes: Long, val seconds: Float)

    /** Tek dosya. */
    fun toMp3(
        ctx: Context,
        doc: DocumentModel,
        speakerId: Int,
        speed: Float,
        title: String,
        onProgress: (Int, Int) -> Unit
    ): Result = toMp3Parts(ctx, doc, speakerId, speed, title, 0) { done, total, _ ->
        onProgress(done, total)
    }.first()

    /**
     * Belgeyi MP3'e çevirir. [partMinutes] > 0 ise ses yaklaşık o uzunlukta
     * parçalara bölünür (uzun kitaplar için); 0 ise tek dosya yazılır.
     *
     * @param onProgress (biten cümle, toplam cümle, kaçıncı parça)
     * @param cancelled  true dönerse işlem bırakılır ve yarım dosyalar silinir
     */
    fun toMp3Parts(
        ctx: Context,
        doc: DocumentModel,
        speakerId: Int,
        speed: Float,
        title: String,
        partMinutes: Int,
        cancelled: () -> Boolean = { false },
        onProgress: (Int, Int, Int) -> Unit
    ): List<Result> {
        val tts = TtsEngine.tts ?: throw IllegalStateException("Ses modeli yüklü değil")
        val sampleRate = TtsEngine.sampleRate
        val total = doc.sentenceCount
        if (total == 0) throw IllegalStateException("Okunacak metin yok")

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val base = cleanTitle(title).ifEmpty { "Xwendevan" }
        val partFrames = partMinutes.toLong() * 60L * sampleRate
        val done = ArrayList<Result>()

        var part = 1
        var target: Target? = null
        var writer: Mp3Writer? = null
        var frames = 0L

        fun openPart() {
            val name = if (partMinutes > 0) "$base-$stamp-%02d.mp3".format(part)
            else "$base-$stamp.mp3"
            val t = Target.create(ctx, name)
            target = t
            writer = Mp3Writer(sampleRate, BITRATE_KBPS, t.stream)
            frames = 0
        }

        fun closePart() {
            val t = target ?: return
            writer?.finish()
            runCatching { t.stream.close() }
            done.add(
                Result(
                    uri = t.publish(ctx),
                    displayName = t.name,
                    bytes = t.size(ctx),
                    seconds = frames.toFloat() / sampleRate
                )
            )
            target = null
            writer = null
        }

        try {
            openPart()
            for (i in 0 until total) {
                if (cancelled()) throw InterruptedException("iptal")
                val raw = doc.sentenceAt(i)?.text?.takeIf { it.isNotBlank() } ?: continue
                val text = com.xwendevan.reader.tts.Pronunciation.apply(raw)
                tts.generateWithCallback(text, speakerId, speed) { samples ->
                    frames += samples.size
                    writer?.write(samples)
                    if (cancelled()) 0 else 1
                }
                onProgress(i + 1, total, part)

                // Parça sınırı: cümle bitiminde bölünür, kelime ortasında değil
                if (partFrames > 0 && frames >= partFrames && i < total - 1) {
                    closePart()
                    part++
                    openPart()
                }
            }
            closePart()
        } catch (t: Throwable) {
            runCatching { writer?.close() }
            target?.discard(ctx)
            done.forEach { runCatching { ctx.contentResolver.delete(it.uri, null, null) } }
            throw t
        }

        return done
    }

    private fun cleanTitle(title: String): String = title.trim()
        .substringBeforeLast('.')
        .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .take(40)
        .trim()

    // ------------------------------------------------------------------

    /** Android 10 öncesi ve sonrası için ortak yazma hedefi. */
    private class Target(
        val stream: OutputStream,
        val name: String,
        private val pendingUri: Uri?,
        private val file: File?
    ) {
        fun publish(ctx: Context): Uri {
            if (pendingUri != null) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(pendingUri, values, null, null)
                return pendingUri
            }
            val f = file!!
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("audio/mpeg"), null)
            return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        }

        fun discard(ctx: Context) {
            runCatching { stream.close() }
            val uri = pendingUri
            if (uri != null) runCatching { ctx.contentResolver.delete(uri, null, null) }
            else runCatching { file?.delete() }
        }

        fun size(ctx: Context): Long {
            if (file != null) return file.length()
            return runCatching {
                ctx.contentResolver.openFileDescriptor(pendingUri!!, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
        }

        companion object {
            fun create(ctx: Context, name: String): Target {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("İndirilenler klasörüne yazılamadı")
                    val out = ctx.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Dosya açılamadı")
                    return Target(out, name, uri, null)
                }

                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                )
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("İndirilenler klasörü oluşturulamadı")
                }
                val f = File(dir, name)
                return Target(f.outputStream(), name, null, f)
            }
        }
    }
}

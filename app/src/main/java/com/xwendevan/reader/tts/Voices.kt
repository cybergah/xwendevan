package com.xwendevan.reader.tts

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Uygulamaya gömülü bir Piper (VITS/ONNX) ses modeli. */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    val modelFile: File,
    val configFile: File,
    val tokensFile: File,
    val language: String,
    val espeakVoice: String,
    val sampleRate: Int,
    val numSpeakers: Int,
    val speakerNames: List<String>,
    val noiseScale: Float,
    val noiseScaleW: Float,
    val lengthScale: Float,
    val sizeBytes: Long
) {
    /** Model APK'dan kullanıma açıldı mı. */
    val isPrepared: Boolean get() = modelFile.exists() && modelFile.length() > 0

    val flag: String
        get() = when {
            language.startsWith("tr") -> "TR"
            language.startsWith("ku") -> "KU"
            language.startsWith("de") -> "DE"
            language.startsWith("en") -> "EN"
            language.startsWith("ar") -> "AR"
            language.startsWith("fa") -> "FA"
            else -> language.take(2).uppercase()
        }
}

/**
 * Ses modelleri APK'nın `assets/voices` klasöründe gelir; klasör seçme veya
 * indirme yoktur. Model ilk kez kullanılacağı zaman uygulamanın kendi belleğine
 * açılır, sherpa-onnx'in beklediği tokens.txt de oradan üretilir.
 */
object VoiceRepository {

    private const val TAG = "VoiceRepository"
    private const val ASSET_DIR = "voices"

    /** Gömülü ses modelleri; listedeki sıra kullanıcıya gösterilen sıradır. */
    private val CATALOG = listOf(
        BuiltIn("ku_berfin_renas", "Berfin & Renas"),
        BuiltIn("tr_yelda", "Yelda"),
        BuiltIn("tr_fahrettin", "Fahrettin"),
        BuiltIn("tr_murat", "Murat")
    )

    private data class BuiltIn(val id: String, val displayName: String)

    fun internalDir(ctx: Context): File = File(ctx.filesDir, "voices").apply { mkdirs() }

    fun listVoices(ctx: Context): List<VoiceInfo> =
        CATALOG.mapNotNull { entry ->
            runCatching { info(ctx, entry) }.onFailure {
                Log.w(TAG, "ses okunamadı: ${entry.id}", it)
            }.getOrNull()
        }

    fun byId(ctx: Context, id: String?): VoiceInfo? =
        if (id == null) null else listVoices(ctx).firstOrNull { it.id == id }

    /** Uygulama diline en yakın ses. */
    fun defaultVoice(ctx: Context, voices: List<VoiceInfo> = listVoices(ctx)): VoiceInfo? {
        val tag = com.xwendevan.reader.Settings.language.ifBlank {
            ctx.resources.configuration.locales[0]?.language ?: "tr"
        }
        return voices.firstOrNull { it.language.startsWith(tag) } ?: voices.firstOrNull()
    }

    // ------------------------------------------------------------ hazırlama

    /**
     * Modeli APK'dan uygulama belleğine açar (bir kez) ve tokens.txt'yi üretir.
     * Zaten hazırsa dosyaya dokunmaz.
     */
    fun prepare(ctx: Context, voice: VoiceInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): VoiceInfo {
        voice.modelFile.parentFile?.mkdirs()

        if (!voice.configFile.exists() || voice.configFile.length() == 0L) {
            copyAsset(ctx, "$ASSET_DIR/${voice.id}.onnx.json", voice.configFile) { _, _ -> }
        }
        if (!voice.isPrepared) {
            val tmp = File(voice.modelFile.absolutePath + ".part")
            copyAsset(ctx, "$ASSET_DIR/${voice.id}.onnx", tmp, onProgress)
            if (!tmp.renameTo(voice.modelFile)) {
                tmp.delete()
                throw IllegalStateException("Model açılamadı: ${voice.id}")
            }
        }
        ensureTokens(voice)
        return voice
    }

    private fun copyAsset(ctx: Context, assetPath: String, target: File, onProgress: (Long, Long) -> Unit) {
        val total = assetSize(ctx, assetPath)
        ctx.assets.open(assetPath).use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    onProgress(copied, total)
                }
            }
        }
    }

    private fun assetSize(ctx: Context, assetPath: String): Long =
        runCatching { ctx.assets.openFd(assetPath).use { it.length } }.getOrDefault(0L)

    // ------------------------------------------------------------ okuma

    private fun info(ctx: Context, entry: BuiltIn): VoiceInfo {
        val dir = File(internalDir(ctx), entry.id)
        val model = File(dir, "${entry.id}.onnx")
        val config = File(dir, "${entry.id}.onnx.json")

        val json = JSONObject(readAssetText(ctx, "$ASSET_DIR/${entry.id}.onnx.json"))
        val audio = json.optJSONObject("audio")
        val espeak = json.optJSONObject("espeak")
        val inference = json.optJSONObject("inference")
        val lang = json.optJSONObject("language")?.optString("code")
            ?: espeak?.optString("voice") ?: "tr"

        val numSpeakers = json.optInt("num_speakers", 1).coerceAtLeast(1)
        val names = speakerNames(json, numSpeakers)

        return VoiceInfo(
            id = entry.id,
            displayName = entry.displayName,
            modelFile = model,
            configFile = config,
            tokensFile = File(dir, "${entry.id}.tokens.txt"),
            language = lang,
            espeakVoice = espeak?.optString("voice") ?: "tr",
            sampleRate = audio?.optInt("sample_rate", 22050) ?: 22050,
            numSpeakers = numSpeakers,
            speakerNames = names,
            noiseScale = (inference?.optDouble("noise_scale", 0.667) ?: 0.667).toFloat(),
            noiseScaleW = (inference?.optDouble("noise_w", 0.8) ?: 0.8).toFloat(),
            lengthScale = (inference?.optDouble("length_scale", 1.0) ?: 1.0).toFloat(),
            sizeBytes = assetSize(ctx, "$ASSET_DIR/${entry.id}.onnx")
        )
    }

    private fun speakerNames(json: JSONObject, numSpeakers: Int): List<String> {
        val speakerMap = json.optJSONObject("speaker_id_map")
        val names = ArrayList<String>()
        if (speakerMap != null && speakerMap.length() > 0) {
            val tmp = arrayOfNulls<String>(numSpeakers)
            val keys = speakerMap.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id = speakerMap.optInt(k, -1)
                if (id in 0 until numSpeakers) tmp[id] = k
            }
            for (i in 0 until numSpeakers) names.add(tmp[i] ?: "Ses ${i + 1}")
        } else {
            for (i in 0 until numSpeakers) names.add("Ses ${i + 1}")
        }
        return names
    }

    private fun readAssetText(ctx: Context, path: String): String =
        ctx.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    /** sherpa-onnx tokens.txt dosyasını .onnx.json içindeki phoneme_id_map'ten üretir. */
    private fun ensureTokens(voice: VoiceInfo) {
        val target = voice.tokensFile
        if (target.exists() && target.length() > 0) return

        val json = JSONObject(voice.configFile.readText(Charsets.UTF_8))
        val map = json.optJSONObject("phoneme_id_map")
            ?: throw IllegalStateException("phoneme_id_map bulunamadı: ${voice.configFile.name}")

        val pairs = ArrayList<Pair<String, Int>>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val symbol = keys.next()
            val ids = map.optJSONArray(symbol) ?: continue
            if (ids.length() > 0) pairs.add(symbol to ids.optInt(0))
        }
        pairs.sortBy { it.second }

        val sb = StringBuilder()
        for ((symbol, id) in pairs) {
            if (symbol == "\n" || symbol == "\r") continue
            sb.append(symbol).append(' ').append(id).append('\n')
        }
        target.writeText(sb.toString(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------ temizlik

    /** Eski sürümlerden kalan, içe aktarılmış modelleri siler. */
    fun cleanupLegacy(ctx: Context) {
        val known = CATALOG.map { it.id }.toSet()
        val root = internalDir(ctx)
        val children = root.listFiles() ?: return
        for (f in children) {
            if (f.name in known) continue
            runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                .onSuccess { Log.i(TAG, "eski ses silindi: ${f.name}") }
        }
    }
}

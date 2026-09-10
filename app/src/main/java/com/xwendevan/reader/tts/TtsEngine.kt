package com.xwendevan.reader.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.xwendevan.reader.EspeakData

/** Piper (VITS) modelini sherpa-onnx üzerinden yükler. */
object TtsEngine {

    private const val TAG = "TtsEngine"

    @Volatile
    var tts: OfflineTts? = null
        private set

    @Volatile
    var voice: VoiceInfo? = null
        private set

    val isReady: Boolean get() = tts != null

    val sampleRate: Int get() = tts?.sampleRate() ?: (voice?.sampleRate ?: 22050)

    @Synchronized
    fun load(ctx: Context, info: VoiceInfo) {
        release()
        val dataDir = EspeakData.ensure(ctx)

        // Piper modelleri ONNX üstverisi taşımaz; sherpa-onnx eksik alanda süreci
        // sonlandırdığı için önce tamamlıyoruz.
        OnnxMeta.ensurePiperMetadata(info.modelFile, info.configFile)
        val meta = OnnxMeta.read(info.modelFile)
        val required = listOf("sample_rate", "n_speakers", "language", "comment")
        val missing = required.filter { !meta.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Model üstverisi eksik: ${missing.joinToString()}")
        }

        val vits = OfflineTtsVitsModelConfig(
            model = info.modelFile.absolutePath,
            lexicon = "",
            tokens = info.tokensFile.absolutePath,
            dataDir = dataDir.absolutePath,
            dictDir = "",
            noiseScale = info.noiseScale,
            noiseScaleW = info.noiseScaleW,
            lengthScale = info.lengthScale
        )
        val modelConfig = OfflineTtsModelConfig(
            vits = vits,
            numThreads = suggestedThreads(),
            debug = false,
            provider = "cpu"
        )
        val config = OfflineTtsConfig(model = modelConfig, maxNumSentences = 1)

        Log.i(TAG, "Model yükleniyor: ${info.modelFile.name}")
        tts = OfflineTts(config = config)
        voice = info
    }

    @Synchronized
    fun release() {
        runCatching { tts?.release() }
        tts = null
        voice = null
    }

    private fun suggestedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 4)
    }
}

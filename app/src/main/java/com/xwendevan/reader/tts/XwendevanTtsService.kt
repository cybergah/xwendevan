package com.xwendevan.reader.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log

/**
 * Xwendevan'ı sistemin konuşma motoru yapar: Ayarlar → Erişilebilirlik →
 * Metin okuma çıktısı listesinden seçilince Kurmancî ve Türkçe sesler
 * TalkBack'te, tarayıcıda, her uygulamada kullanılabilir.
 *
 * Kendi işleminde çalışır (`:tts`), böylece okuyucunun yüklediği modelle
 * çakışmaz.
 */
class XwendevanTtsService : TextToSpeechService() {

    private val tag = "XwendevanTts"
    private var currentLanguage = arrayOf("tur", "TUR", "")

    @Volatile
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        // Sistem ilk sorguyu yapmadan önce hazır bir dil bulunsun
        onLoadLanguage(currentLanguage[0], currentLanguage[1], currentLanguage[2])
    }

    override fun onDestroy() {
        TtsEngine.release()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voice = voiceFor(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (!variant.isNullOrBlank()) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        return if (matchesCountry(voice.language, country)) TextToSpeech.LANG_COUNTRY_AVAILABLE
        else TextToSpeech.LANG_AVAILABLE
    }

    /** Sistem listesine çıkan sesler; her gömülü model bir "voice". */
    override fun onGetVoices(): MutableList<Voice> {
        val out = ArrayList<Voice>()
        for (voice in voices()) {
            out.add(
                Voice(
                    voiceName(voice),
                    localeOf(voice),
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    false,
                    emptySet()
                )
            )
        }
        return out
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        voiceFor(lang)?.let { voiceName(it) }

    override fun onIsValidVoiceName(name: String?): Int =
        if (voices().any { voiceName(it) == name }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int {
        val voice = voices().firstOrNull { voiceName(it) == name } ?: return TextToSpeech.ERROR
        return if (load(voice)) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val voice = voiceFor(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (!load(voice)) return TextToSpeech.LANG_NOT_SUPPORTED
        currentLanguage = arrayOf(iso3(voice.language), country.orEmpty().ifEmpty { "TUR" }, "")
        return onIsLanguageAvailable(lang, country, variant)
    }

    /** Modeli (gerekiyorsa APK'dan açıp) yükler. */
    private fun load(voice: VoiceInfo): Boolean {
        if (TtsEngine.voice?.id == voice.id) return true
        val result = runCatching {
            VoiceRepository.prepare(this, voice)
            TtsEngine.load(this, voice)
        }
        if (result.isFailure) Log.e(tag, "model yüklenemedi", result.exceptionOrNull())
        return result.isSuccess
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val text = Pronunciation.apply(request.charSequenceText?.toString() ?: "")
        if (text.isBlank()) {
            callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        if (onLoadLanguage(request.language, request.country, request.variant) ==
            TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            callback.error()
            return
        }

        val tts = TtsEngine.tts
        if (tts == null) {
            callback.error()
            return
        }

        val sampleRate = TtsEngine.sampleRate
        val speed = (request.speechRate / 100f).coerceIn(0.5f, 2.5f)
        val speaker = 0

        callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        val maxBytes = callback.maxBufferSize.coerceAtLeast(1024)

        val result = runCatching {
            tts.generateWithCallback(text, speaker, speed) { samples ->
                if (stopRequested) return@generateWithCallback 0
                writePcm(callback, samples, maxBytes)
                if (stopRequested) 0 else 1
            }
        }

        if (result.isFailure) {
            Log.e(tag, "sentez hatası", result.exceptionOrNull())
            callback.error()
            return
        }
        callback.done()
    }

    /** float (-1..1) → 16 bit PCM; sistem tamponunu aşmayacak parçalar hâlinde. */
    private fun writePcm(callback: SynthesisCallback, samples: FloatArray, maxBytes: Int) {
        val chunkSamples = (maxBytes / 2).coerceAtLeast(256)
        var offset = 0
        while (offset < samples.size && !stopRequested) {
            val n = minOf(chunkSamples, samples.size - offset)
            val buffer = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = (samples[offset + i].coerceIn(-1f, 1f) * 32767f).toInt()
                buffer[i * 2] = (v and 0xFF).toByte()
                buffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            callback.audioAvailable(buffer, 0, buffer.size)
            offset += n
        }
    }

    // ------------------------------------------------------------------

    /** Ses listesi her sorguda yeniden okunmasın (sistem yüzlerce kez sorar). */
    private var cachedVoices: List<VoiceInfo>? = null

    private fun voices(): List<VoiceInfo> =
        cachedVoices ?: VoiceRepository.listVoices(this).also { cachedVoices = it }

    private fun voiceFor(lang: String?): VoiceInfo? {
        val code = lang?.lowercase().orEmpty()
        val wanted = when {
            code.startsWith("kur") || code.startsWith("kmr") || code.startsWith("ku") -> "ku"
            code.startsWith("tur") || code.startsWith("tr") -> "tr"
            else -> return null
        }
        return voices().firstOrNull { it.language.startsWith(wanted) }
    }

    private fun voiceName(voice: VoiceInfo): String = "xwendevan-${voice.id}"

    private fun localeOf(voice: VoiceInfo): java.util.Locale =
        if (voice.language.startsWith("ku")) java.util.Locale("ku", "TR")
        else java.util.Locale("tr", "TR")

    private fun matchesCountry(voiceLanguage: String, country: String?): Boolean {
        if (country.isNullOrBlank()) return true
        val voiceCountry = voiceLanguage.substringAfter('_', "").uppercase()
        return voiceCountry.isEmpty() || country.uppercase().startsWith(voiceCountry.take(2))
    }

    private fun iso3(voiceLanguage: String): String =
        if (voiceLanguage.startsWith("ku")) "kur" else "tur"
}

package com.xwendevan.reader.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.xwendevan.reader.tts.VoiceRepository

/**
 * Sistem, konuşma motorunu listelemeden önce "ses verisi var mı" diye sorar.
 * Sesler APK'nın içinde geldiği için yanıt her zaman "var".
 */
class TtsCheckActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = ArrayList<String>()
        for (voice in VoiceRepository.listVoices(this)) {
            val iso3 = if (voice.language.startsWith("ku")) "kur" else "tur"
            val entry = "$iso3-TUR"
            if (entry !in available) available.add(entry)
        }

        val result = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        }
        setResult(
            if (available.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
            else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            result
        )
        finish()
    }
}

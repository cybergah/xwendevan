package com.xwendevan.reader.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.xwendevan.reader.R
import com.xwendevan.reader.Settings
import com.xwendevan.reader.player.ReaderPlayer
import com.xwendevan.reader.tts.TtsEngine
import com.xwendevan.reader.tts.VoiceInfo
import com.xwendevan.reader.tts.VoiceRepository

/** Uygulamaya gömülü ses modellerinin seçim paneli. */
object VoiceSheet {

    private val ui = Handler(Looper.getMainLooper())

    fun show(activity: Activity, onVoiceChanged: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.sheet_voices, null)
        val list = view.findViewById<RecyclerView>(R.id.voiceList)
        val note = view.findViewById<TextView>(R.id.voiceNote)
        list.layoutManager = LinearLayoutManager(activity)
        note.setText(R.string.voices_builtin_note)

        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)

        val adapter = VoiceAdapter(emptyList(), TtsEngine.voice?.id) { picked ->
            dialog.dismiss()
            load(activity, picked, onVoiceChanged)
        }
        list.adapter = adapter
        dialog.show()

        Thread {
            val voices = VoiceRepository.listVoices(activity)
            ui.post { adapter.update(voices, TtsEngine.voice?.id) }
        }.start()
    }

    /** Modeli hazırlar, yükler ve okumayı bulunduğu cümleden sürdürür. */
    fun load(activity: Activity, voice: VoiceInfo, onDone: () -> Unit) {
        val wasPlaying = ReaderPlayer.isPlaying
        val index = ReaderPlayer.currentIndex
        val document = ReaderPlayer.document
        ReaderPlayer.pause()

        val progress = AlertDialog.Builder(activity)
            .setMessage(if (voice.isPrepared) R.string.loading_voice else R.string.preparing_voice)
            .setCancelable(false)
            .show()

        Thread {
            val result = runCatching {
                VoiceRepository.prepare(activity, voice) { copied, total ->
                    if (total > 0) {
                        val pct = (copied * 100 / total).toInt()
                        ui.post { progress.setMessage(activity.getString(R.string.preparing_voice_pct, pct)) }
                    }
                }
                ui.post { progress.setMessage(activity.getString(R.string.loading_voice)) }
                TtsEngine.load(activity, voice)
            }
            ui.post {
                runCatching { progress.dismiss() }
                result.onSuccess {
                    Settings.voiceId = voice.id
                    val speaker = Settings.speakerFor(voice.id).coerceIn(0, voice.numSpeakers - 1)
                    ReaderPlayer.stop()
                    if (document != null) ReaderPlayer.setDocument(document, index)
                    ReaderPlayer.setSpeaker(speaker)
                    onDone()
                    if (wasPlaying) ReaderPlayer.play()
                }.onFailure {
                    AlertDialog.Builder(activity)
                        .setMessage(activity.getString(R.string.voice_error, it.message ?: ""))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    onDone()
                }
            }
        }.start()
    }

    /** Kayıtlı (yoksa dile uygun) ses modelini arka planda hazırlayıp yükler. */
    fun loadSaved(ctx: Context, onDone: (VoiceInfo?) -> Unit) {
        Thread {
            runCatching { com.xwendevan.reader.EspeakData.ensure(ctx) }
            runCatching { VoiceRepository.cleanupLegacy(ctx) }
            val voices = VoiceRepository.listVoices(ctx)
            val pick = voices.firstOrNull { it.id == Settings.voiceId }
                ?: VoiceRepository.defaultVoice(ctx, voices)
            var loaded: VoiceInfo? = null
            if (pick != null) {
                val ok = runCatching {
                    VoiceRepository.prepare(ctx, pick)
                    TtsEngine.load(ctx, pick)
                }.isSuccess
                if (ok) {
                    Settings.voiceId = pick.id
                    ReaderPlayer.setSpeaker(Settings.speakerFor(pick.id).coerceIn(0, pick.numSpeakers - 1))
                    loaded = pick
                }
            }
            ui.post { onDone(loaded) }
        }.start()
    }
}

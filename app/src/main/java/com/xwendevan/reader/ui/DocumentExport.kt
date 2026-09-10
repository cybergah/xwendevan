package com.xwendevan.reader.ui

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.xwendevan.reader.R
import com.xwendevan.reader.audio.AudioExport
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.player.ReaderPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Açık belgenin tamamını MP3'e çevirir. Uzun kitaplarda parçalara bölünür;
 * işlem sırasında okuma durur (sentez motoru tek).
 */
object DocumentExport {

    private val ui = Handler(Looper.getMainLooper())

    fun start(activity: Activity, doc: DocumentModel, partMinutes: Int) {
        ReaderPlayer.pause()
        val speaker = ReaderPlayer.speakerId
        val speed = ReaderPlayer.speed
        val cancelled = AtomicBoolean(false)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.menu_export_mp3)
            .setMessage(activity.getString(R.string.export_progress, 0, doc.sentenceCount, 1))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()

        Thread {
            val result = runCatching {
                AudioExport.toMp3Parts(
                    activity, doc, speaker, speed, doc.title, partMinutes,
                    cancelled = { cancelled.get() }
                ) { done, total, part ->
                    ui.post {
                        dialog.setMessage(activity.getString(R.string.export_progress, done, total, part))
                    }
                }
            }
            ui.post {
                runCatching { dialog.dismiss() }
                result.onSuccess { parts -> showDone(activity, parts) }
                    .onFailure {
                        if (cancelled.get()) {
                            Toast.makeText(activity, R.string.export_cancelled, Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(activity)
                                .setMessage(activity.getString(R.string.save_failed, it.message ?: ""))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
            }
        }.start()
    }

    private fun showDone(activity: Activity, parts: List<AudioExport.Result>) {
        if (parts.isEmpty()) return
        val totalSeconds = parts.sumOf { it.seconds.toDouble() }.toInt()
        val totalMb = parts.sumOf { it.bytes } / 1024f / 1024f
        val message = activity.getString(
            R.string.export_done,
            parts.size,
            totalSeconds / 60,
            String.format("%.1f", totalMb)
        ) + "\n\n" + parts.joinToString("\n") { it.displayName }

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.menu_export_mp3)
            .setMessage(message)
            .setNegativeButton(android.R.string.ok, null)

        if (parts.size == 1) {
            builder.setPositiveButton(R.string.open) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(parts[0].uri, "audio/mpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { activity.startActivity(intent) }
                    .onFailure { Toast.makeText(activity, R.string.no_player_app, Toast.LENGTH_LONG).show() }
            }
        }
        builder.show()
    }
}

package com.xwendevan.reader.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xwendevan.reader.App
import com.xwendevan.reader.R
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Uyku zamanlayıcısı: süre dolunca ses üç saniyede kısılır ve okuma durur.
 * Süre yerine "paragraf bitince" veya "belge bitince" de seçilebilir.
 */
object SleepTimer : ReaderPlayer.Listener {

    const val OFF = 0
    const val MINUTES = 1
    const val END_OF_PARAGRAPH = 2
    const val END_OF_DOCUMENT = 3

    private val ui = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var mode: Int = OFF
        private set

    private var endAt = 0L
    private var stopParagraph = -1
    private var attached = false
    private val expire = Runnable { fadeOutAndPause(6) }

    val isActive: Boolean get() = mode != OFF

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Kalan süre; dakika kipinde değilse -1. */
    fun remainingSeconds(): Int {
        if (mode != MINUTES) return -1
        return ((endAt - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)
    }

    fun setMinutes(minutes: Int) {
        attach()
        mode = MINUTES
        endAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        ui.removeCallbacks(expire)
        ui.postDelayed(expire, minutes * 60_000L)
        changed()
    }

    fun setEndOfParagraph() {
        attach()
        mode = END_OF_PARAGRAPH
        stopParagraph = currentParagraph()
        ui.removeCallbacks(expire)
        changed()
    }

    fun setEndOfDocument() {
        attach()
        mode = END_OF_DOCUMENT
        ui.removeCallbacks(expire)
        changed()
    }

    fun cancel() {
        mode = OFF
        endAt = 0
        stopParagraph = -1
        ui.removeCallbacks(expire)
        ReaderPlayer.setVolume(1f)
        changed()
    }

    /** Ayarlar / menü satırında gösterilecek özet. */
    fun label(ctx: Context): String = when (mode) {
        MINUTES -> {
            val s = remainingSeconds()
            ctx.getString(R.string.sleep_left, s / 60, s % 60)
        }

        END_OF_PARAGRAPH -> ctx.getString(R.string.sleep_end_paragraph)
        END_OF_DOCUMENT -> ctx.getString(R.string.sleep_end_document)
        else -> ctx.getString(R.string.sleep_off)
    }

    // ------------------------------------------------------------------

    private fun attach() {
        if (attached) return
        ReaderPlayer.addListener(this)
        attached = true
    }

    private fun currentParagraph(): Int =
        ReaderPlayer.document?.sentenceAt(ReaderPlayer.currentIndex)?.paragraph ?: -1

    override fun onSentenceChanged(index: Int) {
        if (mode != END_OF_PARAGRAPH) return
        val p = ReaderPlayer.document?.sentenceAt(index)?.paragraph ?: return
        if (stopParagraph >= 0 && p != stopParagraph) ui.post { fadeOutAndPause(3) }
    }

    override fun onFinished() {
        if (mode == END_OF_DOCUMENT || mode == END_OF_PARAGRAPH) ui.post { finish() }
    }

    /** Sesi kademeli kısıp durdurur; ani kesilme rahatsız edici oluyor. */
    private fun fadeOutAndPause(steps: Int) {
        if (!ReaderPlayer.isPlaying) {
            finish(); return
        }
        var step = steps
        val tick = object : Runnable {
            override fun run() {
                if (step <= 0) {
                    finish(); return
                }
                ReaderPlayer.setVolume(step / steps.toFloat())
                step--
                ui.postDelayed(this, 500)
            }
        }
        ui.post(tick)
    }

    private fun finish() {
        ReaderPlayer.pause()
        ReaderPlayer.setVolume(1f)
        runCatching { PlayerService.stopService(App.instance) }
        cancel()
    }

    private fun changed() = listeners.forEach { runCatching { it() } }
}

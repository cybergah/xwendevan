package com.xwendevan.reader.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.tts.TtsEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canlı seslendirme hattı.
 *
 * - Sentez iş parçacığı: cümleleri sırayla modele verir, üretilen ses parçalarını kuyruğa koyar.
 *   Kuyruk dolduğunda kendiliğinden bekler (ileriye doğru sınırlı ön-üretim).
 * - Çalma iş parçacığı: kuyruktaki parçaları AudioTrack'e yazar.
 * - İzleme iş parçacığı: AudioTrack'in çaldığı kare sayısına bakarak o an duyulan cümleyi bulur;
 *   vurgulama bu sayede sesle senkron olur.
 */
object ReaderPlayer {

    private const val TAG = "ReaderPlayer"
    private const val NO_SEEK = Int.MIN_VALUE

    interface Listener {
        fun onSentenceChanged(index: Int) {}
        fun onStateChanged(playing: Boolean, preparing: Boolean) {}
        fun onBufferChanged(readyAhead: Int) {}
        fun onFinished() {}
        fun onError(message: String) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    // ------------------------------------------------------------------ durum

    @Volatile
    var document: DocumentModel? = null
        private set

    @Volatile
    var speed: Float = 1.0f
        private set

    @Volatile
    var speakerId: Int = 0
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var isPreparing: Boolean = false
        private set

    @Volatile
    var currentIndex: Int = 0
        private set

    /** Sesi hazırlanmış ama henüz çalınmamış cümle sayısı. */
    @Volatile
    var readyAhead: Int = 0
        private set

    // -------------------------------------------------------------- iç değişkenler

    private class Chunk(val gen: Int, val sentence: Int, val samples: FloatArray)
    private class Mark(val frame: Long, val sentence: Int)

    private val queue = ArrayBlockingQueue<Chunk>(64)
    private val marks = ArrayDeque<Mark>()
    private val generation = AtomicInteger(0)
    private val pendingSeek = AtomicInteger(NO_SEEK)

    @Volatile private var synthIndex = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var framesWritten = 0L
    @Volatile private var lastHeadRaw = 0L
    @Volatile private var headBase = 0L
    @Volatile private var synthDone = false          // tüm cümleler sentezlendi
    @Volatile private var volumeScale = 1.0f

    // Kalan süre tahmini için ölçüm
    @Volatile private var measuredChars = 0L
    @Volatile private var measuredFrames = 0L

    private var synthThread: Thread? = null
    private var playThread: Thread? = null
    private var watchThread: Thread? = null

    // ------------------------------------------------------------------ genel API

    fun setDocument(doc: DocumentModel, startIndex: Int) {
        stop()
        document = doc
        currentIndex = startIndex
        synthIndex = startIndex
        notifySentence(startIndex)
    }

    fun setSpeed(value: Float) {
        val v = value.coerceIn(0.5f, 2.5f)
        if (kotlin.math.abs(v - speed) < 0.01f) return
        speed = v
        // Hız anında duyulsun: içinde bulunulan cümleyi yeni hızla yeniden üret
        if (document != null) seekTo(currentIndex, keepPlaying = isPlaying)
    }

    fun setSpeaker(id: Int) {
        if (id == speakerId) return
        speakerId = id
        if (document != null) seekTo(currentIndex, keepPlaying = isPlaying)
    }

    fun setVolume(scale: Float) {
        volumeScale = scale.coerceIn(0f, 1f)
        runCatching { track?.setVolume(volumeScale) }
    }

    fun play() {
        val doc = document ?: return
        if (!TtsEngine.isReady) {
            notifyError("Önce bir ses modeli seçin")
            return
        }
        if (doc.sentenceCount == 0 && doc.isComplete) return

        ensureThreads()
        isPlaying = true
        isPreparing = queue.isEmpty()
        runCatching { track?.play() }
        notifyState()
    }

    fun pause() {
        isPlaying = false
        isPreparing = false
        runCatching { track?.pause() }
        notifyState()
    }

    fun toggle() = if (isPlaying) pause() else play()

    fun next() = seekTo(currentIndex + 1, keepPlaying = isPlaying)

    fun previous() {
        // Cümlenin başındaysak bir öncekine, değilsek başa dön
        seekTo(currentIndex - 1, keepPlaying = isPlaying)
    }

    fun skipParagraphs(delta: Int) {
        val doc = document ?: return
        val cur = doc.sentenceAt(currentIndex) ?: return
        val targetPara = (cur.paragraph + delta).coerceIn(0, (doc.paragraphCount - 1).coerceAtLeast(0))
        val target = doc.sentencesOfParagraph(targetPara).firstOrNull()?.index ?: return
        seekTo(target, keepPlaying = isPlaying)
    }

    fun seekTo(index: Int, keepPlaying: Boolean = isPlaying) {
        val doc = document ?: return
        val max = (doc.sentenceCount - 1).coerceAtLeast(0)
        val target = index.coerceIn(0, max)

        generation.incrementAndGet()
        queue.clear()
        synthIndex = target
        currentIndex = target
        synthDone = false
        pendingSeek.set(target)
        notifySentence(target)

        if (keepPlaying) {
            ensureThreads()
            isPlaying = true
            isPreparing = true
        }
        notifyState()
    }

    fun stop() {
        running = false
        isPlaying = false
        isPreparing = false
        generation.incrementAndGet()
        queue.clear()
        synthThread?.interrupt(); playThread?.interrupt(); watchThread?.interrupt()
        synthThread = null; playThread = null; watchThread = null
        runCatching {
            track?.pause(); track?.flush(); track?.release()
        }
        track = null
        resetPosition()
        notifyState()
    }

    /** Kalan okuma süresi (saniye). Ölçüm yoksa 0. */
    fun remainingSeconds(): Int {
        val doc = document ?: return 0
        val chars = doc.charsFrom(currentIndex)
        val c = measuredChars
        val f = measuredFrames
        if (c < 200 || f <= 0) return 0
        val sr = TtsEngine.sampleRate.coerceAtLeast(1)
        return ((chars.toDouble() * f / c) / sr).toInt()
    }

    // ------------------------------------------------------------------ iç işleyiş

    private fun resetPosition() {
        synchronized(marks) { marks.clear() }
        framesWritten = 0
        lastHeadRaw = 0
        headBase = 0
    }

    private fun ensureThreads() {
        if (running && synthThread != null && playThread != null) return
        running = true
        synthDone = false

        if (track == null) createTrack()

        if (synthThread == null) {
            synthThread = Thread({ synthLoop() }, "zd-synth").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
        if (playThread == null) {
            playThread = Thread({ playLoop() }, "zd-play").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
        if (watchThread == null) {
            watchThread = Thread({ watchLoop() }, "zd-watch").apply { start() }
        }
    }

    private fun createTrack() {
        val sr = TtsEngine.sampleRate
        val minBytes = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        // ~0,4 saniyelik tampon: gecikme düşük kalsın, vurgulama sesle örtüşsün
        val bufBytes = maxOf(minBytes, (sr * 4 * 0.4f).toInt())

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volumeScale)
        track = t
        resetPosition()
    }

    private fun synthLoop() {
        while (running) {
            try {
                val doc = document
                if (doc == null || !isPlaying) {
                    Thread.sleep(60); continue
                }
                val gen = generation.get()
                val idx = synthIndex

                if (idx >= doc.sentenceCount) {
                    if (doc.isComplete) {
                        synthDone = true
                        Thread.sleep(120)
                    } else {
                        Thread.sleep(80)      // ayrıştırma sürüyor, yeni cümle bekleniyor
                    }
                    continue
                }
                synthDone = false

                val sentence = doc.sentenceAt(idx) ?: continue
                val text = com.xwendevan.reader.tts.Pronunciation.apply(sentence.text)
                if (text.isBlank()) {
                    synthIndex = idx + 1; continue
                }

                val tts = TtsEngine.tts
                if (tts == null) {
                    Thread.sleep(200); continue
                }

                var producedFrames = 0L
                runCatching {
                    tts.generateWithCallback(text, speakerId, speed) { samples ->
                        if (generation.get() != gen || !running) return@generateWithCallback 0
                        producedFrames += samples.size
                        if (!offerChunk(Chunk(gen, idx, samples.copyOf()), gen)) 0 else 1
                    }
                }.onFailure {
                    Log.e(TAG, "Sentez hatası", it)
                }

                if (generation.get() == gen) {
                    measuredChars += text.length
                    measuredFrames += producedFrames
                    synthIndex = idx + 1
                }
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.e(TAG, "synthLoop", t)
                try { Thread.sleep(150) } catch (e: InterruptedException) { return }
            }
        }
    }

    /** Kuyruk doluysa bekler; sürüm değişirse vazgeçer. */
    private fun offerChunk(chunk: Chunk, gen: Int): Boolean {
        while (running && generation.get() == gen) {
            if (queue.offer(chunk, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun playLoop() {
        var pending: Chunk? = null
        while (running) {
            try {
                val t = track
                if (t == null) {
                    Thread.sleep(50); continue
                }

                // Bekleyen atlama isteği: tamponu boşalt, konumu sıfırla
                val seek = pendingSeek.getAndSet(NO_SEEK)
                if (seek != NO_SEEK) {
                    pending = null
                    runCatching {
                        t.pause(); t.flush()
                    }
                    resetPosition()
                    if (isPlaying) runCatching { t.play() }
                }

                val chunk = pending ?: queue.poll(80, java.util.concurrent.TimeUnit.MILLISECONDS)
                pending = null
                if (chunk == null) continue
                if (chunk.gen != generation.get()) continue

                if (isPlaying && t.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { t.play() }

                synchronized(marks) {
                    marks.addLast(Mark(framesWritten, chunk.sentence))
                    while (marks.size > 512) marks.removeFirst()
                }

                var offset = 0
                val n = chunk.samples.size
                while (offset < n && running && chunk.gen == generation.get()) {
                    if (pendingSeek.get() != NO_SEEK) break
                    val written = t.write(
                        chunk.samples, offset, n - offset, AudioTrack.WRITE_NON_BLOCKING
                    )
                    if (written > 0) {
                        offset += written
                        framesWritten += written
                        isPreparing = false
                    } else {
                        Thread.sleep(10)
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.e(TAG, "playLoop", t)
                try { Thread.sleep(80) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun watchLoop() {
        var lastNotified = -1
        var lastAhead = -1
        while (running) {
            try {
                Thread.sleep(60)
                val t = track ?: continue
                val head = playedFrames(t)

                var audible = -1
                synchronized(marks) {
                    val it = marks.iterator()
                    while (it.hasNext()) {
                        val m = it.next()
                        if (m.frame <= head) audible = m.sentence else break
                    }
                    // Geride kalan işaretleri temizle
                    while (marks.size > 2 && marks.first().frame < head - TtsEngine.sampleRate * 4) {
                        marks.removeFirst()
                    }
                }

                if (audible >= 0 && audible != currentIndex) {
                    currentIndex = audible
                }
                if (currentIndex != lastNotified) {
                    lastNotified = currentIndex
                    notifySentence(currentIndex)
                }

                val ahead = (synthIndex - currentIndex - 1).coerceAtLeast(0)
                if (ahead != lastAhead) {
                    lastAhead = ahead
                    readyAhead = ahead
                    listeners.forEach { runCatching { it.onBufferChanged(ahead) } }
                }

                // Belge bitti mi?
                val doc = document
                if (isPlaying && doc != null && doc.isComplete && synthDone &&
                    queue.isEmpty() && head >= framesWritten && framesWritten > 0
                ) {
                    isPlaying = false
                    runCatching { track?.pause() }
                    notifyState()
                    listeners.forEach { runCatching { it.onFinished() } }
                }
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.e(TAG, "watchLoop", t)
            }
        }
    }

    /** AudioTrack 32 bit sayaç kullanır; taşmayı hesaba katarak toplam kare sayısını verir. */
    private fun playedFrames(t: AudioTrack): Long {
        val raw = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (raw < lastHeadRaw) headBase += 0x100000000L
        lastHeadRaw = raw
        return headBase + raw
    }

    // ------------------------------------------------------------------ bildirim

    private fun notifySentence(index: Int) {
        listeners.forEach { runCatching { it.onSentenceChanged(index) } }
    }

    private fun notifyState() {
        listeners.forEach { runCatching { it.onStateChanged(isPlaying, isPreparing) } }
    }

    private fun notifyError(msg: String) {
        listeners.forEach { runCatching { it.onError(msg) } }
    }
}

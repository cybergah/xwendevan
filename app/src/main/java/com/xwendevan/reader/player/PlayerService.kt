package com.xwendevan.reader.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.xwendevan.reader.R
import com.xwendevan.reader.ui.HomeActivity

/** Ekran kapalıyken de okumayı sürdüren ön plan servisi. */
class PlayerService : Service(), ReaderPlayer.Listener {

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var lastNotify = 0L
    private var hasFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> ReaderPlayer.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> ReaderPlayer.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ReaderPlayer.setVolume(0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> ReaderPlayer.setVolume(1.0f)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()

        session = MediaSessionCompat(this, "Xwendevan").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = requestFocusAndPlay()
                override fun onPause() = ReaderPlayer.pause()
                override fun onSkipToNext() = ReaderPlayer.next()
                override fun onSkipToPrevious() = ReaderPlayer.previous()
                override fun onStop() {
                    ReaderPlayer.pause()
                    stopSelf()
                }
            })
            isActive = true
        }
        ReaderPlayer.addListener(this)
        startForegroundCompat(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> if (ReaderPlayer.isPlaying) ReaderPlayer.pause() else requestFocusAndPlay()
            ACTION_PLAY -> requestFocusAndPlay()
            ACTION_PAUSE -> ReaderPlayer.pause()
            ACTION_NEXT -> ReaderPlayer.next()
            ACTION_PREV -> ReaderPlayer.previous()
            ACTION_STOP -> {
                ReaderPlayer.pause()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        MediaButtonReceiver.handleIntent(session, intent)
        pushNotification(force = true)
        return START_STICKY
    }

    override fun onDestroy() {
        ReaderPlayer.removeListener(this)
        abandonFocus()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Uygulama listeden kaldırılsa da okuma sürsün
        super.onTaskRemoved(rootIntent)
    }

    // ------------------------------------------------------------ ses odağı

    private fun requestFocusAndPlay() {
        if (!hasFocus) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        ReaderPlayer.setVolume(1.0f)
        ReaderPlayer.play()
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        hasFocus = false
    }

    // ------------------------------------------------------------ bildirim

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_playback_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun action(icon: Int, title: String, act: String): NotificationCompat.Action {
        val intent = Intent(this, PlayerService::class.java).setAction(act)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return NotificationCompat.Action(icon, title, PendingIntent.getService(this, act.hashCode(), intent, flags))
    }

    private fun buildNotification(): Notification {
        val doc = ReaderPlayer.document
        val sentence = doc?.sentenceAt(ReaderPlayer.currentIndex)?.text ?: getString(R.string.notif_idle)
        val title = doc?.title ?: getString(R.string.app_name)

        val contentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            contentFlags
        )

        val playing = ReaderPlayer.isPlaying
        val total = doc?.sentenceCount ?: 0

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sentence.take(120))
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, total.toLong())
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f, SystemClock.elapsedRealtime()
                )
                .build()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(sentence.take(140))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, getString(R.string.prev), ACTION_PREV))
            .addAction(
                if (playing) action(R.drawable.ic_pause, getString(R.string.pause), ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_play, getString(R.string.play), ACTION_PLAY_PAUSE)
            )
            .addAction(action(R.drawable.ic_next, getString(R.string.next), ACTION_NEXT))
            .addAction(action(R.drawable.ic_stop, getString(R.string.stop), ACTION_STOP))
            .build()
    }

    private fun pushNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 900) return
        lastNotify = now
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    // ------------------------------------------------------------ oynatıcı olayları

    override fun onSentenceChanged(index: Int) = pushNotification()

    override fun onStateChanged(playing: Boolean, preparing: Boolean) = pushNotification(force = true)

    override fun onFinished() {
        abandonFocus()
        pushNotification(force = true)
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIF_ID = 42

        const val ACTION_PLAY = "com.xwendevan.reader.PLAY"
        const val ACTION_PAUSE = "com.xwendevan.reader.PAUSE"
        const val ACTION_PLAY_PAUSE = "com.xwendevan.reader.PLAY_PAUSE"
        const val ACTION_NEXT = "com.xwendevan.reader.NEXT"
        const val ACTION_PREV = "com.xwendevan.reader.PREV"
        const val ACTION_STOP = "com.xwendevan.reader.STOP"

        fun send(ctx: Context, action: String) {
            val intent = Intent(ctx, PlayerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stopService(ctx: Context) {
            ctx.stopService(Intent(ctx, PlayerService::class.java))
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, id, notification, type)
    }
}

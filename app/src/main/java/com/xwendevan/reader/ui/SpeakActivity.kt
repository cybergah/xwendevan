package com.xwendevan.reader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.xwendevan.reader.R
import com.xwendevan.reader.Settings
import com.xwendevan.reader.audio.AudioExport
import com.xwendevan.reader.databinding.ActivitySpeakBinding
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.player.PlayerService
import com.xwendevan.reader.player.ReaderPlayer
import com.xwendevan.reader.tts.TtsEngine

/** Kendi yazdığınız metni okutma ve MP3 olarak kaydetme ekranı. */
class SpeakActivity : AppCompatActivity(), ReaderPlayer.Listener {

    private lateinit var b: ActivitySpeakBinding
    private val ui = Handler(Looper.getMainLooper())

    /** ReaderPlayer'a verilen metnin son hali; değişirse belge yeniden kurulur. */
    private var builtText: String? = null
    private var exporting = false

    private val writePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startExport()
            else Toast.makeText(this, R.string.save_needs_permission, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySpeakBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        savedInstanceState?.getString(STATE_TEXT)?.let { b.input.setText(it) }
        if (b.input.text.isNullOrBlank()) b.input.setText(Settings.speakDraft)
        // Paylaş → Xwendevan ya da "seçili metni okut" ile gelen metin
        incomingText(intent)?.let { b.input.setText(it) }

        b.pasteButton.setOnClickListener { pasteFromClipboard() }

        b.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateStatus()
        })

        b.voiceButton.setOnClickListener { VoiceSheet.show(this) { refreshVoiceBar() } }
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnStop.setOnClickListener { stopPlayback() }
        b.btnSave.setOnClickListener { save() }

        val speed = (Math.round(Settings.speed / 0.05f) * 0.05f).coerceIn(0.6f, 2.0f)
        b.speedSlider.value = speed
        b.speedValue.text = getString(R.string.speed_label, speed)
        b.speedSlider.addOnChangeListener { _, value, fromUser ->
            b.speedValue.text = getString(R.string.speed_label, value)
            if (fromUser) {
                Settings.speed = value
                ReaderPlayer.setSpeed(value)
            }
        }

        VoiceSheet.loadSaved(this) { refreshVoiceBar() }
        refreshVoiceBar()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        ReaderPlayer.addListener(this)
        updateControls()
    }

    override fun onStop() {
        super.onStop()
        ReaderPlayer.removeListener(this)
        Settings.speakDraft = b.input.text?.toString() ?: ""
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TEXT, b.input.text?.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingText(intent)?.let {
            b.input.setText(it)
            builtText = null
        }
    }

    /** Paylaşılan ya da seçilip gönderilen metin. */
    private fun incomingText(intent: Intent?): String? {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)?.toString()

            else -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val current = currentText()
        b.input.setText(if (current.isEmpty()) text else "$current\n\n$text")
        b.input.setSelection(b.input.text?.length ?: 0)
        builtText = null
    }

    // ------------------------------------------------------------------ okuma

    private fun currentText(): String = b.input.text?.toString()?.trim().orEmpty()

    /** Yazılan metni cümlelere bölüp oynatıcıya verir. */
    private fun ensureDocument(): DocumentModel? {
        val text = currentText()
        if (text.isEmpty()) return null

        val existing = ReaderPlayer.document
        if (existing != null && builtText == text) return existing

        val doc = DocumentModel(
            title = getString(R.string.menu_speak),
            key = "speak:" + text.hashCode(),
            kind = DocumentModel.Kind.TEXT
        )
        doc.addParagraphs(text.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s*\\n\\s*"), " ") })
        doc.markComplete()
        doc.totalUnits = doc.paragraphCount
        doc.parsedUnits = doc.paragraphCount
        if (doc.sentenceCount == 0) return null

        builtText = text
        ReaderPlayer.setDocument(doc, 0)
        ReaderPlayer.setSpeed(b.speedSlider.value)
        return doc
    }

    private fun togglePlay() {
        if (ReaderPlayer.isPlaying) {
            PlayerService.send(this, PlayerService.ACTION_PAUSE)
            return
        }
        if (TtsEngine.voice == null) {
            Toast.makeText(this, R.string.no_voice, Toast.LENGTH_LONG).show()
            return
        }
        if (ensureDocument() == null) {
            Toast.makeText(this, R.string.speak_empty, Toast.LENGTH_SHORT).show()
            return
        }
        PlayerService.send(this, PlayerService.ACTION_PLAY)
        updateControls()
    }

    private fun stopPlayback() {
        ReaderPlayer.stop()
        PlayerService.stopService(this)
        updateControls()
        updateStatus()
    }

    // ------------------------------------------------------------------ MP3

    private fun save() {
        if (exporting) return
        if (TtsEngine.voice == null) {
            Toast.makeText(this, R.string.no_voice, Toast.LENGTH_LONG).show()
            return
        }
        if (currentText().isEmpty()) {
            Toast.makeText(this, R.string.speak_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startExport()
    }

    private fun startExport() {
        // Sentez tek motoru kullanır; kaydederken okuma sürmemeli.
        ReaderPlayer.pause()
        val doc = ensureDocument() ?: run {
            Toast.makeText(this, R.string.speak_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val speaker = ReaderPlayer.speakerId
        val speed = b.speedSlider.value
        val title = currentText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.saving_mp3_pct, 0))
            .setCancelable(false)
            .show()

        exporting = true
        Thread {
            val result = runCatching {
                AudioExport.toMp3(this, doc, speaker, speed, title) { done, total ->
                    val pct = if (total > 0) done * 100 / total else 0
                    ui.post { progress.setMessage(getString(R.string.saving_mp3_pct, pct)) }
                }
            }
            ui.post {
                exporting = false
                runCatching { progress.dismiss() }
                result.onSuccess { showSaved(it) }
                    .onFailure {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.save_failed, it.message ?: ""))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
            }
        }.start()
    }

    private fun showSaved(result: AudioExport.Result) {
        val mb = result.bytes / 1024f / 1024f
        val message = getString(
            R.string.saved_mp3,
            result.displayName,
            formatDuration(result.seconds.toInt()),
            String.format("%.1f", mb)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.save_mp3)
            .setMessage(message)
            .setNeutralButton(R.string.share) { _, _ -> share(result.uri) }
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.open) { _, _ -> open(result.uri) }
            .show()
    }

    private fun open(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.no_player_app, Toast.LENGTH_LONG).show() }
    }

    private fun share(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.share))) }
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds >= 60 -> getString(R.string.dur_m, seconds / 60) + " " + getString(R.string.dur_s, seconds % 60)
        else -> getString(R.string.dur_s, seconds)
    }

    // ------------------------------------------------------------------ arayüz

    private fun refreshVoiceBar() {
        val voice = TtsEngine.voice
        b.voiceButton.text = voice?.let { "${it.flag} · ${it.displayName}" } ?: getString(R.string.pick_voice)

        b.speakerGroup.removeAllViews()
        if (voice != null && voice.numSpeakers > 1) {
            b.speakerGroup.visibility = View.VISIBLE
            voice.speakerNames.forEachIndexed { index, name ->
                val button = MaterialButton(
                    this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = name
                    isCheckable = true
                    id = View.generateViewId()
                    tag = index
                    minWidth = 0
                    minimumWidth = 0
                    insetTop = 0
                    insetBottom = 0
                }
                b.speakerGroup.addView(button)
                if (index == ReaderPlayer.speakerId) b.speakerGroup.check(button.id)
            }
            b.speakerGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val index = group.findViewById<MaterialButton>(checkedId)?.tag as? Int
                    ?: return@addOnButtonCheckedListener
                if (index == ReaderPlayer.speakerId) return@addOnButtonCheckedListener
                ReaderPlayer.setSpeaker(index)
                voice.let { Settings.setSpeakerFor(it.id, index) }
            }
        } else {
            b.speakerGroup.visibility = View.GONE
        }
        updateControls()
    }

    private fun updateControls() {
        val playing = ReaderPlayer.isPlaying
        b.btnPlay.setText(if (playing) R.string.pause else R.string.speak_play)
        b.btnPlay.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnSave.isEnabled = !exporting
    }

    private fun updateStatus() {
        val doc = ReaderPlayer.document
        val text = currentText()
        b.status.text = when {
            text.isEmpty() -> getString(R.string.speak_note)
            doc == null || builtText != text -> getString(R.string.speak_chars, text.length)
            else -> getString(
                R.string.status_line,
                (ReaderPlayer.currentIndex + 1).coerceAtMost(doc.sentenceCount),
                doc.sentenceCount,
                if (doc.sentenceCount > 0) (ReaderPlayer.currentIndex + 1) * 100 / doc.sentenceCount else 0
            )
        }
    }

    // ------------------------------------------------------------------ oynatıcı olayları

    override fun onSentenceChanged(index: Int) {
        ui.post { updateStatus() }
    }

    override fun onStateChanged(playing: Boolean, preparing: Boolean) {
        ui.post {
            updateControls()
            updateStatus()
        }
    }

    override fun onFinished() {
        ui.post {
            updateControls()
            updateStatus()
        }
    }

    override fun onError(message: String) {
        ui.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val STATE_TEXT = "speak_text"
    }
}

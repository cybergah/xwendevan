package com.xwendevan.reader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xwendevan.reader.BuildConfig
import com.xwendevan.reader.R
import com.xwendevan.reader.databinding.ActivityAboutBinding
import com.xwendevan.reader.tts.VoiceRepository

/** Bilgilendirme ekranı. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.version.text = getString(R.string.version_x, BuildConfig.VERSION_NAME)

        val voices = VoiceRepository.listVoices(this)
        b.voiceList.text = voices.joinToString("\n") { v ->
            // Konuşmacılar zaten ses adında geçiyorsa tekrar yazma
            val extra = v.speakerNames.filterNot { v.displayName.contains(it, ignoreCase = true) }
            val speakers = if (v.numSpeakers > 1 && extra.isNotEmpty()) " · " + extra.joinToString(", ") else ""
            "${v.flag} · ${v.displayName}$speakers · ${v.sizeBytes / (1024 * 1024)} MB"
        } + "\n" + getString(R.string.about_voices_note)

        val open = { openCybergah() }
        b.cybergahCard.setOnClickListener { open() }
        b.cybergahUrl.setOnClickListener { open() }
    }

    private fun openCybergah() {
        val url = getString(R.string.cybergah_url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }
}

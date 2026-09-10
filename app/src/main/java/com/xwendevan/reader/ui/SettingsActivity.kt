package com.xwendevan.reader.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xwendevan.reader.R
import com.xwendevan.reader.Settings
import com.xwendevan.reader.data.Stats
import com.xwendevan.reader.databinding.ActivitySettingsBinding
import com.xwendevan.reader.player.ReaderPlayer
import com.xwendevan.reader.player.SleepTimer
import com.xwendevan.reader.tts.Pronunciation
import com.xwendevan.reader.tts.TtsEngine

/** Ayarlar ekranı. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.rowTheme.setOnClickListener { pickTheme() }
        b.rowLanguage.setOnClickListener { pickLanguage() }
        b.rowFont.setOnClickListener { pickFont() }
        b.rowHighlight.setOnClickListener { pickHighlight() }
        b.rowVoice.setOnClickListener { VoiceSheet.show(this) { render() } }
        b.rowSpeaker.setOnClickListener { pickSpeaker() }
        b.rowPronunciation.setOnClickListener {
            startActivity(Intent(this, PronunciationActivity::class.java))
        }
        b.rowSleep.setOnClickListener { SleepDialog.show(this) { render() } }
        b.rowStats.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        b.rowAutoVoice.setOnClickListener { b.switchAutoVoice.toggle() }
        b.switchAutoVoice.setOnCheckedChangeListener { _, checked -> Settings.autoVoice = checked }
        b.rowAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }

        b.switchAutoScroll.setOnCheckedChangeListener { _, checked -> Settings.autoScroll = checked }
        b.switchKeepScreen.setOnCheckedChangeListener { _, checked -> Settings.keepScreenOn = checked }

        b.speedSlider.addOnChangeListener { _, value, fromUser ->
            b.speedValue.text = getString(R.string.speed_label, value)
            if (fromUser) {
                Settings.speed = value
                ReaderPlayer.setSpeed(value)
            }
        }

        render()
    }

    private fun render() {
        b.valueTheme.text = getString(
            when (Settings.theme) {
                Settings.THEME_LIGHT -> R.string.theme_light
                Settings.THEME_DARK -> R.string.theme_dark
                else -> R.string.theme_system
            }
        )
        b.valueLanguage.text = languageLabel(Settings.language)
        b.valueFont.text = "${Settings.fontSize} sp"
        b.valueHighlight.text = getString(
            when (Settings.highlightColor) {
                1 -> R.string.color_yellow
                2 -> R.string.color_green
                3 -> R.string.color_pink
                else -> R.string.color_blue
            }
        )

        val voice = TtsEngine.voice
        b.valueVoice.text = voice?.let { "${it.flag} ${it.displayName}" } ?: getString(R.string.no_voice_short)
        if (voice != null && voice.numSpeakers > 1) {
            b.rowSpeaker.isEnabled = true
            b.valueSpeaker.text = voice.speakerNames.getOrNull(ReaderPlayer.speakerId) ?: "-"
        } else {
            b.rowSpeaker.isEnabled = false
            b.valueSpeaker.text = getString(R.string.single_speaker)
        }

        b.switchAutoScroll.isChecked = Settings.autoScroll
        b.switchKeepScreen.isChecked = Settings.keepScreenOn
        b.switchAutoVoice.isChecked = Settings.autoVoice

        b.valuePronunciation.text = getString(R.string.pron_count, Pronunciation.rules(this).size)
        b.valueSleep.text = SleepTimer.label(this)
        b.valueStats.text = getString(R.string.stats_summary, (Stats.week(this) / 60).toInt())

        val speed = (Math.round(Settings.speed / 0.05f) * 0.05f).coerceIn(0.6f, 2.0f)
        b.speedSlider.value = speed
        b.speedValue.text = getString(R.string.speed_label, speed)
    }

    private fun pickTheme() {
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, Settings.theme) { d, which ->
                Settings.theme = which
                d.dismiss()
                render()
            }
            .show()
    }

    private fun pickLanguage() {
        val tags = listOf("", "tr", "ku", "en", "ar")
        val labels = tags.map { languageLabel(it) }.toTypedArray()
        val current = tags.indexOf(Settings.language).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, current) { d, which ->
                Settings.language = tags[which]
                d.dismiss()
                recreate()
            }
            .show()
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "tr" -> "Türkçe"
        "ku" -> "Kurmancî"
        "en" -> "English"
        "ar" -> "العربية"
        else -> getString(R.string.language_system)
    }

    private fun pickFont() {
        val sizes = (14..30 step 2).toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_font)
            .setSingleChoiceItems(
                sizes.map { "$it sp" }.toTypedArray(),
                sizes.indexOf(Settings.fontSize).coerceAtLeast(0)
            ) { d, which ->
                Settings.fontSize = sizes[which]
                d.dismiss()
                render()
            }
            .show()
    }

    private fun pickHighlight() {
        val labels = arrayOf(
            getString(R.string.color_blue),
            getString(R.string.color_yellow),
            getString(R.string.color_green),
            getString(R.string.color_pink)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.highlight_color)
            .setSingleChoiceItems(labels, Settings.highlightColor) { d, which ->
                Settings.highlightColor = which
                d.dismiss()
                render()
            }
            .show()
    }

    private fun pickSpeaker() {
        val voice = TtsEngine.voice ?: return
        if (voice.numSpeakers <= 1) return
        AlertDialog.Builder(this)
            .setTitle(R.string.speaker)
            .setSingleChoiceItems(
                voice.speakerNames.toTypedArray(),
                ReaderPlayer.speakerId.coerceIn(0, voice.numSpeakers - 1)
            ) { d, which ->
                ReaderPlayer.setSpeaker(which)
                Settings.setSpeakerFor(voice.id, which)
                d.dismiss()
                render()
            }
            .show()
    }
}

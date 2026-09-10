package com.xwendevan.reader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        PDFBoxResourceLoader.init(this)
        AppCompatDelegate.setDefaultNightMode(Settings.themeMode(this))
        Settings.applyLanguage(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/** Tüm kullanıcı ayarları. */
object Settings {

    private const val NAME = "xwendevan"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // --- görünüm ---

    var theme: Int
        get() = sp(App.instance).getInt("theme", THEME_SYSTEM)
        set(v) {
            sp(App.instance).edit().putInt("theme", v).apply()
            AppCompatDelegate.setDefaultNightMode(themeMode(App.instance))
        }

    fun themeMode(ctx: Context): Int = when (sp(ctx).getInt("theme", THEME_SYSTEM)) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** "" = sistem dili */
    var language: String
        get() = sp(App.instance).getString("language", "") ?: ""
        set(v) {
            sp(App.instance).edit().putString("language", v).apply()
            applyLanguage(App.instance)
        }

    fun applyLanguage(ctx: Context) {
        val tag = sp(ctx).getString("language", "") ?: ""
        val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    var fontSize: Int
        get() = sp(App.instance).getInt("font_size", 18)
        set(v) = sp(App.instance).edit().putInt("font_size", v).apply()

    // --- okuma ---

    var speed: Float
        get() = sp(App.instance).getFloat("speed", 1.0f)
        set(v) = sp(App.instance).edit().putFloat("speed", v).apply()

    var autoScroll: Boolean
        get() = sp(App.instance).getBoolean("auto_scroll", true)
        set(v) = sp(App.instance).edit().putBoolean("auto_scroll", v).apply()

    var keepScreenOn: Boolean
        get() = sp(App.instance).getBoolean("keep_screen_on", false)
        set(v) = sp(App.instance).edit().putBoolean("keep_screen_on", v).apply()

    /** 0 = mavi, 1 = sarı, 2 = yeşil, 3 = pembe */
    var highlightColor: Int
        get() = sp(App.instance).getInt("highlight", 0)
        set(v) = sp(App.instance).edit().putInt("highlight", v).apply()

    /** Belgenin dilini kestirip sesi kendiliğinden seç. */
    var autoVoice: Boolean
        get() = sp(App.instance).getBoolean("auto_voice", true)
        set(v) = sp(App.instance).edit().putBoolean("auto_voice", v).apply()

    /** "Metin okut" ekranında yazılan son metin. */
    var speakDraft: String
        get() = sp(App.instance).getString("speak_draft", "") ?: ""
        set(v) = sp(App.instance).edit().putString("speak_draft", v).apply()

    var hintShown: Boolean
        get() = sp(App.instance).getBoolean("hint_shown", false)
        set(v) = sp(App.instance).edit().putBoolean("hint_shown", v).apply()

    // --- ses ---

    var voiceId: String?
        get() = sp(App.instance).getString("voice", null)
        set(v) = sp(App.instance).edit().putString("voice", v).apply()

    var speakerId: Int
        get() = sp(App.instance).getInt("speaker", 0)
        set(v) = sp(App.instance).edit().putInt("speaker", v).apply()

    fun speakerFor(voiceId: String): Int = sp(App.instance).getInt("spk_" + voiceId.hashCode(), 0)

    fun setSpeakerFor(voiceId: String, id: Int) =
        sp(App.instance).edit().putInt("spk_" + voiceId.hashCode(), id).apply()
}

/** APK içindeki espeak-ng verisini bir kez dahili belleğe açar. */
object EspeakData {
    private const val MARKER = ".extracted_v1"

    fun dir(ctx: Context): File = File(ctx.filesDir, "espeak-ng-data")

    fun ensure(ctx: Context): File {
        val target = dir(ctx)
        val marker = File(target, MARKER)
        if (marker.exists()) return target
        target.deleteRecursively()
        target.mkdirs()
        copyAssetDir(ctx, "espeak-ng-data", target)
        marker.writeText("ok")
        return target
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, outDir: File) {
        val children = ctx.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            outDir.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                outDir.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }
        outDir.mkdirs()
        for (child in children) copyAssetDir(ctx, "$assetPath/$child", File(outDir, child))
    }
}

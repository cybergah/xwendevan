package com.xwendevan.reader.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.xwendevan.reader.databinding.ActivitySplashBinding

/**
 * Açılış ekranı: logo belirir, altında ses dalgası çubukları oynar,
 * en altta Cybergah künyesi. Ekrana dokunulursa hemen geçilir.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var b: ActivitySplashBinding
    private val ui = Handler(Looper.getMainLooper())
    private val barAnimators = ArrayList<ValueAnimator>()
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.root.setOnClickListener { openHome() }
        playIntro()
        ui.postDelayed({ openHome() }, TOTAL_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        barAnimators.forEach { it.cancel() }
    }

    private fun playIntro() {
        // logo: küçükten yerine oturur
        b.logo.alpha = 0f
        b.logo.scaleX = 0.72f
        b.logo.scaleY = 0.72f
        b.logo.rotation = -8f
        b.logo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
            .setDuration(520)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()

        fadeUp(b.appName, 260)
        fadeUp(b.tagline, 380)
        fadeUp(b.credit, 700)

        // ses dalgası: her çubuk kendi temposunda
        b.bars.alpha = 0f
        b.bars.animate().alpha(1f).setStartDelay(420).setDuration(300).start()
        startBars()
    }

    private fun fadeUp(view: View, delay: Long) {
        view.alpha = 0f
        view.translationY = 24f
        view.animate()
            .alpha(if (view === b.credit || view === b.tagline) 0.75f else 1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(420)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun startBars() {
        val group: LinearLayout = b.bars
        val peaks = floatArrayOf(1f, 0.55f, 0.9f, 0.4f, 0.75f)
        val periods = longArrayOf(520, 380, 460, 320, 420)
        for (i in 0 until group.childCount) {
            val bar = group.getChildAt(i)
            val animator = ValueAnimator.ofFloat(0.25f, peaks[i % peaks.size]).apply {
                duration = periods[i % periods.size]
                startDelay = i * 70L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { bar.scaleY = it.animatedValue as Float }
            }
            barAnimators.add(animator)
            animator.start()
        }
    }

    private fun openHome() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private companion object {
        const val TOTAL_MS = 1900L
    }
}

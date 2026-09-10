package com.xwendevan.reader.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xwendevan.reader.R
import com.xwendevan.reader.data.Stats
import com.xwendevan.reader.databinding.ActivityStatsBinding
import java.text.SimpleDateFormat
import java.util.Locale

/** Dinleme istatistikleri: bugün, bu hafta, toplam ve son 7 gün. */
class StatsActivity : AppCompatActivity() {

    private lateinit var b: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.reset.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.stats_reset_q)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.stats_reset) { _, _ ->
                    Stats.clear(this)
                    render()
                }
                .show()
        }

        render()
    }

    private fun render() {
        b.valueToday.text = duration(Stats.today(this))
        b.valueWeek.text = duration(Stats.week(this))
        b.valueTotal.text = duration(Stats.total(this))

        drawWeek()
        drawTopBooks()
    }

    /** Son yedi günün basit sütun grafiği. */
    private fun drawWeek() {
        val days = Stats.lastDays(this, 7)
        val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(60L)
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

        b.chart.removeAllViews()
        for ((date, seconds) in days) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }

            val wholeMinutes = seconds / 60
            val minutes = TextView(this).apply {
                text = if (wholeMinutes > 0) wholeMinutes.toString() else ""
                textSize = 11f
                alpha = 0.7f
                gravity = Gravity.CENTER
            }

            val heightPx = (dp(96) * seconds / max.toFloat()).toInt().coerceAtLeast(if (seconds > 0) dp(4) else dp(2))
            val bar = View(this).apply {
                setBackgroundResource(R.drawable.bar_bg)
                alpha = if (seconds > 0) 1f else 0.18f
                layoutParams = LinearLayout.LayoutParams(dp(18), heightPx).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                }
            }

            val label = TextView(this).apply {
                text = dayFormat.format(date)
                textSize = 11f
                alpha = 0.7f
                gravity = Gravity.CENTER
            }

            column.addView(minutes)
            column.addView(bar)
            column.addView(label)
            b.chart.addView(column)
        }
    }

    private fun drawTopBooks() {
        val books = Stats.topBooks(this, 5)
        b.topBooks.removeAllViews()
        b.topTitle.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE

        for (book in books) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val name = TextView(this).apply {
                text = book.name.substringBeforeLast('.')
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val time = TextView(this).apply {
                text = duration(book.listenSeconds)
                setTextColor(getColor(R.color.cybergah_red))
            }
            row.addView(name)
            row.addView(time)
            b.topBooks.addView(row)
        }
    }

    private fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> getString(R.string.dur_hm, h.toInt(), m.toInt())
            m > 0 -> getString(R.string.dur_m, m.toInt())
            else -> getString(R.string.dur_s, seconds.toInt())
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

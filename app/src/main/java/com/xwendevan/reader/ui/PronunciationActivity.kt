package com.xwendevan.reader.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xwendevan.reader.R
import com.xwendevan.reader.databinding.ActivityPronunciationBinding
import com.xwendevan.reader.tts.PronRule
import com.xwendevan.reader.tts.Pronunciation

/** Telaffuz sözlüğü: kısaltma ve özel adların nasıl okunacağı. */
class PronunciationActivity : AppCompatActivity() {

    private lateinit var b: ActivityPronunciationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPronunciationBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.addButton.setOnClickListener { edit(null) }
        render()
    }

    private fun render() {
        val rules = Pronunciation.rules(this)
        b.empty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        b.list.removeAllViews()

        for (rule in rules) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setOnClickListener { edit(rule) }
            }

            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = rule.from
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            })
            texts.addView(TextView(this).apply {
                text = getString(R.string.pron_reads_as, rule.to)
                alpha = 0.75f
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })

            val delete = ImageView(this).apply {
                setImageResource(R.drawable.ic_delete)
                contentDescription = getString(R.string.delete)
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener {
                    Pronunciation.remove(this@PronunciationActivity, rule.from)
                    render()
                }
            }

            row.addView(texts)
            row.addView(delete)
            b.list.addView(row)
        }
    }

    /** [existing] null ise yeni kural. */
    private fun edit(existing: PronRule?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val from = EditText(this).apply {
            hint = getString(R.string.pron_from_hint)
            setSingleLine()
            setText(existing?.from ?: "")
        }
        val to = EditText(this).apply {
            hint = getString(R.string.pron_to_hint)
            setSingleLine()
            setText(existing?.to ?: "")
        }
        container.addView(from)
        container.addView(to)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.pron_add else R.string.pron_edit)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val f = from.text?.toString()?.trim().orEmpty()
                val t = to.text?.toString()?.trim().orEmpty()
                if (f.isEmpty() || t.isEmpty()) return@setPositiveButton
                if (existing != null && existing.from != f) Pronunciation.remove(this, existing.from)
                Pronunciation.add(this, PronRule(f, t))
                render()
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

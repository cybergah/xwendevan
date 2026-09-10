package com.xwendevan.reader.ui

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.xwendevan.reader.R
import com.xwendevan.reader.player.SleepTimer

/** Uyku zamanlayıcısı seçimi. */
object SleepDialog {

    private val MINUTES = intArrayOf(15, 30, 45, 60)

    fun show(activity: Activity, onChanged: () -> Unit = {}) {
        val labels = ArrayList<String>()
        MINUTES.forEach { labels.add(activity.getString(R.string.sleep_minutes, it)) }
        labels.add(activity.getString(R.string.sleep_end_paragraph))
        labels.add(activity.getString(R.string.sleep_end_document))
        if (SleepTimer.isActive) labels.add(activity.getString(R.string.sleep_cancel))

        val title = if (SleepTimer.isActive)
            activity.getString(R.string.sleep_active, SleepTimer.label(activity))
        else activity.getString(R.string.menu_sleep)

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < MINUTES.size -> {
                        SleepTimer.setMinutes(MINUTES[which])
                        toast(activity, activity.getString(R.string.sleep_set, MINUTES[which]))
                    }

                    which == MINUTES.size -> {
                        SleepTimer.setEndOfParagraph()
                        toast(activity, activity.getString(R.string.sleep_end_paragraph))
                    }

                    which == MINUTES.size + 1 -> {
                        SleepTimer.setEndOfDocument()
                        toast(activity, activity.getString(R.string.sleep_end_document))
                    }

                    else -> {
                        SleepTimer.cancel()
                        toast(activity, activity.getString(R.string.sleep_off))
                    }
                }
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(activity: Activity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}

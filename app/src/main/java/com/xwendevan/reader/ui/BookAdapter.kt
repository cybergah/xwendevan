package com.xwendevan.reader.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xwendevan.reader.R
import com.xwendevan.reader.data.BookEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kitaplık kartları. [compact] = ana ekrandaki yatay raflarda kullanılan
 * dar kart; false ise "tüm belgeler" ızgarasındaki büyük kart.
 */
class BookAdapter(
    private val onClick: (BookEntry) -> Unit,
    private val onLongClick: (BookEntry) -> Unit,
    private val compact: Boolean = false
) : RecyclerView.Adapter<BookAdapter.VH>() {

    private var items: List<BookEntry> = emptyList()

    fun submit(list: List<BookEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(
            if (compact) R.layout.item_book_small else R.layout.item_book, parent, false
        )
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val badge: TextView = v.findViewById(R.id.kindBadge)
        private val title: TextView = v.findViewById(R.id.title)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
        private val favMark: ImageView = v.findViewById(R.id.favMark)

        fun bind(entry: BookEntry) {
            val ctx = itemView.context
            title.text = entry.name.substringBeforeLast('.')
            badge.text = entry.kind.uppercase()
            favMark.visibility = if (entry.favorite) View.VISIBLE else View.GONE

            meta.text = if (compact) {
                when {
                    entry.progress > 0 -> ctx.getString(R.string.percent_read, entry.progress)
                    entry.pageCount > 0 -> ctx.getString(R.string.pages_n, entry.pageCount)
                    else -> ctx.getString(R.string.not_started)
                }
            } else {
                val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    .format(Date(entry.lastOpenedAt))
                val pages = if (entry.pageCount > 0)
                    ctx.getString(R.string.pages_n, entry.pageCount) + " · " else ""
                "$pages$date"
            }

            progress.progress = entry.progress
            progress.visibility = if (entry.progress > 0) View.VISIBLE else View.INVISIBLE

            val thumb = entry.thumb
            if (thumb != null && File(thumb).exists()) {
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
                cover.setPadding(0, 0, 0, 0)
                cover.setImageBitmap(BitmapFactory.decodeFile(thumb))
            } else {
                cover.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = ((if (compact) 30 else 48) * itemView.resources.displayMetrics.density).toInt()
                cover.setPadding(pad, pad, pad, pad)
                cover.setImageResource(
                    when (entry.kind) {
                        "pdf" -> R.drawable.ic_doc_pdf
                        "docx" -> R.drawable.ic_doc_word
                        else -> R.drawable.ic_doc_text
                    }
                )
            }

            itemView.setOnClickListener { onClick(entry) }
            itemView.setOnLongClickListener { onLongClick(entry); true }
        }
    }
}

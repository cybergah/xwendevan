package com.xwendevan.reader.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xwendevan.reader.R
import com.xwendevan.reader.doc.DocumentModel

/**
 * Metin belgelerini paragraf paragraf gösterir ve okunan cümleyi vurgular.
 * Dokunuşlar [onTap] ile üst katmana iletilir (tek dokunuş: çubukları göster/gizle,
 * üç dokunuş: oradan okumaya devam).
 */
class ParagraphAdapter(
    private val onTap: (sentenceIndex: Int) -> Unit
) : RecyclerView.Adapter<ParagraphAdapter.VH>() {

    private var doc: DocumentModel? = null
    private var visibleCount = 0
    private var currentSentence = -1
    private var currentParagraph = -1

    var textSizeSp: Float = 18f
        set(value) {
            field = value
            notifyItemRangeChanged(0, visibleCount)
        }

    var highlightColor: Int = 0x552979FF
        set(value) {
            field = value
            notifyItemRangeChanged(0, visibleCount)
        }

    fun attach(document: DocumentModel?) {
        doc = document
        visibleCount = document?.paragraphCount ?: 0
        currentSentence = -1
        currentParagraph = -1
        notifyDataSetChanged()
    }

    fun syncCount() {
        val total = doc?.paragraphCount ?: 0
        if (total > visibleCount) {
            val start = visibleCount
            visibleCount = total
            notifyItemRangeInserted(start, total - start)
        } else if (total < visibleCount) {
            visibleCount = total
            notifyDataSetChanged()
        }
    }

    fun paragraphOf(sentenceIndex: Int): Int = doc?.sentenceAt(sentenceIndex)?.paragraph ?: -1

    fun setCurrentSentence(index: Int) {
        if (index == currentSentence) return
        val d = doc ?: return
        val newPara = d.sentenceAt(index)?.paragraph ?: -1
        val oldPara = currentParagraph
        currentSentence = index
        currentParagraph = newPara
        if (oldPara >= 0 && oldPara < visibleCount) notifyItemChanged(oldPara, PAYLOAD)
        if (newPara >= 0 && newPara < visibleCount && newPara != oldPara) notifyItemChanged(newPara, PAYLOAD)
    }

    override fun getItemCount(): Int = visibleCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_paragraph, parent, false)
        return VH(v as TextView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD)) holder.bind(position)
        else super.onBindViewHolder(holder, position, payloads)
    }

    inner class VH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {

        private var downX = 0f
        private var downY = 0f

        init {
            tv.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x; downY = event.y
                    }

                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(event.x - downX) < 24 && kotlin.math.abs(event.y - downY) < 24) {
                            onTap(sentenceAt(event.x, event.y))
                        }
                    }
                }
                true
            }
        }

        private fun sentenceAt(x: Float, y: Float): Int {
            val d = doc ?: return -1
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return -1
            val layout = tv.layout ?: return -1
            val line = layout.getLineForVertical((y - tv.paddingTop).toInt().coerceAtLeast(0))
            val offset = layout.getOffsetForHorizontal(line, x - tv.paddingLeft)
            val sentences = d.sentencesOfParagraph(pos)
            val hit = sentences.firstOrNull { offset >= it.start && offset < it.end }
                ?: sentences.lastOrNull()
            return hit?.index ?: -1
        }

        fun bind(position: Int) {
            val d = doc
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            if (d == null) {
                tv.text = ""
                return
            }
            val text = d.paragraphAt(position)
            val sentence = if (position == currentParagraph) d.sentenceAt(currentSentence) else null
            if (sentence == null || sentence.paragraph != position) {
                tv.text = text
                return
            }
            val span = SpannableString(text)
            val start = sentence.start.coerceIn(0, text.length)
            val end = sentence.end.coerceIn(start, text.length)
            span.setSpan(BackgroundColorSpan(highlightColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = span
        }
    }

    companion object {
        private const val PAYLOAD = "hl"
    }
}

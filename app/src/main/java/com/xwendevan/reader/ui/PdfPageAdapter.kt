package com.xwendevan.reader.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.doc.PdfSource
import java.util.concurrent.Executors

/** PDF sayfalarını sırayla gösterir; okunan cümleyi sayfa üzerinde işaretler. */
class PdfPageAdapter(
    private val source: PdfSource,
    private val document: DocumentModel,
    private val onTap: (page: Int, xPt: Float, yPt: Float) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.VH>() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private var currentSentence = -1
    private var currentPage = -1

    var highlightColor: Int = 0x552979FF
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD)
        }

    fun setCurrentSentence(index: Int) {
        if (index == currentSentence) return
        currentSentence = index
        val newPage = document.sentenceAt(index)?.page ?: -1
        val old = currentPage
        currentPage = newPage
        if (old >= 0) notifyItemChanged(old, PAYLOAD)
        if (newPage >= 0 && newPage != old) notifyItemChanged(newPage, PAYLOAD)
    }

    fun pageOfSentence(index: Int): Int = document.sentenceAt(index)?.page ?: -1

    override fun getItemCount(): Int = source.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val container = FrameLayout(parent.context)
        val page = PdfPageView(parent.context)
        container.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.setPadding(0, 0, 0, PAGE_GAP)
        return VH(container, page)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD)) holder.applyHighlight(position)
        else holder.bind(position)
    }

    override fun onViewRecycled(holder: VH) {
        holder.token++
        holder.page.bitmap = null
    }

    fun shutdown() = io.shutdownNow()

    inner class VH(container: View, val page: PdfPageView) : RecyclerView.ViewHolder(container) {

        var token = 0
        private var downX = 0f
        private var downY = 0f

        init {
            page.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    downX = event.x; downY = event.y
                } else if (event.action == MotionEvent.ACTION_UP) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION &&
                        kotlin.math.abs(event.x - downX) < 24 && kotlin.math.abs(event.y - downY) < 24
                    ) {
                        val pt = (v as PdfPageView).toPagePoint(event.x, event.y)
                        onTap(pos, pt[0], pt[1])
                    }
                }
                true
            }
        }

        fun bind(position: Int) {
            val size = source.pageSizes.getOrNull(position) ?: floatArrayOf(595f, 842f)
            page.pageWidthPt = size[0]
            page.pageHeightPt = size[1]
            page.requestLayout()
            applyHighlight(position)

            val width = page.width.takeIf { it > 0 } ?: itemView.resources.displayMetrics.widthPixels
            val cached: Bitmap? = source.cached(position, width)
            if (cached != null) {
                page.bitmap = cached
                return
            }
            page.bitmap = null
            token++
            val my = token
            io.execute {
                val bmp = source.render(position, width)
                ui.post { if (my == token && bmp != null) page.bitmap = bmp }
            }
        }

        fun applyHighlight(position: Int) {
            page.highlightColor = highlightColor
            page.highlightBoxes =
                if (position == currentPage) document.sentenceAt(currentSentence)?.boxes ?: emptyList()
                else emptyList()
        }
    }

    companion object {
        private const val PAYLOAD = "hl"
        private const val PAGE_GAP = 18
    }
}

package com.xwendevan.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Bir PDF sayfasını olduğu gibi çizer ve okunan cümleyi sayfanın üzerinde işaretler.
 * Kutular sayfa noktası (1/72 inç) biriminde verilir, görünüm ölçeğine çevrilir.
 */
class PdfPageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dst = RectF()

    var pageWidthPt: Float = 595f
    var pageHeightPt: Float = 842f

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var highlightBoxes: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var highlightColor: Int = 0x552979FF
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (pageWidthPt > 0) (width * pageHeightPt / pageWidthPt).toInt() else width
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        dst.set(0f, 0f, w, h)

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        } else {
            canvas.drawRect(dst, placeholderPaint)
        }

        if (highlightBoxes.isEmpty()) return
        val sx = w / pageWidthPt
        val sy = h / pageHeightPt
        highlightPaint.color = highlightColor
        for (box in highlightBoxes) {
            val r = RectF(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy)
            r.inset(-2f, -2f)
            canvas.drawRoundRect(r, 6f, 6f, highlightPaint)
        }
    }

    /** Görünüm koordinatını sayfa noktasına çevirir. */
    fun toPagePoint(x: Float, y: Float): FloatArray = floatArrayOf(
        if (width > 0) x * pageWidthPt / width else 0f,
        if (height > 0) y * pageHeightPt / height else 0f
    )
}

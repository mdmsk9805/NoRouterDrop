package dev.msk.norouterdrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A round progress ring with the percent in the middle. */
class RingProgressView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var percent: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    private val density = resources.displayMetrics.density
    private val stroke = 12f * density
    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFD9E6E3.toInt()) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF00796B.toInt()); strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFF222222.toInt()); textAlign = Paint.Align.CENTER; textSize = 32f * density; isFakeBoldText = true }
    private val box = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val size = (160f * density).toInt()
        setMeasuredDimension(resolveSize(size, w), resolveSize(size, h))
    }

    override fun onDraw(c: Canvas) {
        val pad = stroke / 2 + 2 * density
        val d = minOf(width, height).toFloat()
        val left = (width - d) / 2; val top = (height - d) / 2
        box.set(left + pad, top + pad, left + d - pad, top + d - pad)
        c.drawOval(box, track)
        c.drawArc(box, -90f, 360f * percent / 100f, false, arc)
        val y = height / 2f - (text.descent() + text.ascent()) / 2
        c.drawText("$percent%", width / 2f, y, text)
    }
}

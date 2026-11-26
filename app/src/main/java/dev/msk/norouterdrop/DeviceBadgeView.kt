package dev.msk.norouterdrop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator

/** The big round device badge on the Receive screen: a filled disc with the app's three-node mark and two soft rings that pulse outwards. */
class DeviceBadgeView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var animationsOn = true
        set(v) { field = v; updateAnimator() }

    private var phase = 0f
    private var animator: ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun attr(a: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(a, tv, true)) tv.data else fallback
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimator() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }

    private fun updateAnimator() {
        animator?.cancel(); animator = null
        if (animationsOn && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val half = minOf(width, height) / 2f
        val primary = attr(androidx.appcompat.R.attr.colorPrimary, 0xFF00796B.toInt())
        val onPrimary = attr(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
        val r0 = half * 0.58f
        // two rings, half a cycle apart; each grows from the disc edge to the view edge and fades out
        for (k in 0..1) {
            val p = if (animationsOn) (phase + k * 0.5f) % 1f else 0.25f + k * 0.35f
            ring.color = primary
            ring.alpha = ((1f - p) * 150).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, r0 + (half - r0 - 2 * d) * p, ring)
        }
        disc.color = primary
        c.drawCircle(cx, cy, r0, disc)
        // mark: triangle of three nodes
        val s = r0 * 0.5f
        val ax = cx - s; val ay = cy - s * 0.62f
        val bx = cx + s; val by = ay
        val tx = cx; val ty = cy + s * 0.95f
        line.color = onPrimary; line.strokeWidth = r0 * 0.07f
        c.drawLine(ax, ay, bx, by, line); c.drawLine(ax, ay, tx, ty, line); c.drawLine(bx, by, tx, ty, line)
        dot.color = onPrimary
        val nr = r0 * 0.15f
        c.drawCircle(ax, ay, nr, dot); c.drawCircle(bx, by, nr, dot); c.drawCircle(tx, ty, nr, dot)
    }
}

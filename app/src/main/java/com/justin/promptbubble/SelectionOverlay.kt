package com.justin.promptbubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen dimmed layer. Drag a finger to draw a box; lift to confirm.
 * Reports the box in screen pixels (raw coordinates), or null if you just tapped (cancel).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SelectionOverlay(
    ctx: Context,
    private val hint: String,
    private val onDone: (Rect?) -> Unit,
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0xFF9D9DFF.toInt()
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE202028.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15f * d; textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(textPaint).apply { textSize = 12f * d; color = 0xBBFFFFFF.toInt() }

    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
    private var dragging = false
    private var finished = false
    private val loc = IntArray(2)

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        val w = width.toFloat(); val h = height.toFloat()
        if (!dragging) {
            c.drawRect(0f, 0f, w, h, dim)
            val cy = h * 0.14f
            val pw = max(textPaint.measureText(hint), subPaint.measureText(CANCEL_HINT)) + 40 * d
            c.drawRoundRect(RectF(w / 2 - pw / 2, cy - 30 * d, w / 2 + pw / 2, cy + 28 * d), 20 * d, 20 * d, pill)
            c.drawText(hint, w / 2, cy - 2 * d, textPaint)
            c.drawText(CANCEL_HINT, w / 2, cy + 17 * d, subPaint)
            return
        }
        val l = min(sx, ex) - loc[0]; val t = min(sy, ey) - loc[1]
        val r = max(sx, ex) - loc[0]; val b = max(sy, ey) - loc[1]
        // Dim everything except the selected box.
        c.drawRect(0f, 0f, w, t, dim)
        c.drawRect(0f, b, w, h, dim)
        c.drawRect(0f, t, l, b, dim)
        c.drawRect(r, t, w, b, dim)
        c.drawRect(l, t, r, b, border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.rawX; sy = e.rawY; ex = sx; ey = sy; dragging = true; invalidate()
            }
            MotionEvent.ACTION_MOVE -> { ex = e.rawX; ey = e.rawY; invalidate() }
            MotionEvent.ACTION_UP -> {
                ex = e.rawX; ey = e.rawY
                val rect = Rect(min(sx, ex).toInt(), min(sy, ey).toInt(), max(sx, ex).toInt(), max(sy, ey).toInt())
                val tooSmall = rect.width() < 24 * d || rect.height() < 24 * d
                finish(if (tooSmall) null else rect)
            }
            MotionEvent.ACTION_CANCEL -> finish(null)
        }
        return true
    }

    private fun finish(r: Rect?) {
        if (finished) return
        finished = true
        onDone(r)
    }

    private companion object {
        const val CANCEL_HINT = "Tap anywhere to cancel"
    }
}

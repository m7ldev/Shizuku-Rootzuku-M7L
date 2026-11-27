package moe.shizuku.manager.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import moe.shizuku.manager.R
import java.util.Random

/**
 * A faint rain of characters, drawn behind the content of a screen. It never takes touches
 * and stops drawing while it is not visible.
 */
class MatrixRainView(context: Context) : View(context) {

    private class Column(var y: Float, var speed: Float, var length: Int, var chars: CharArray, var active: Boolean)

    companion object {
        private const val SYMBOLS = "0123456789ABCDEF#$%&*+<>/=カキクケコサシスセソ"
        private const val FRAME_DELAY = 55L

        /** How strong the brightest character is, 0-255. The rain must stay in the background. */
        private const val MAX_ALPHA = 70
    }

    private val random = Random()
    private val density = resources.displayMetrics.density
    private val step = 17 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12 * density
        color = context.getColor(R.color.neon_green)
    }
    private var columns: Array<Column> = emptyArray()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun randomChars(length: Int): CharArray {
        return CharArray(length) { SYMBOLS[random.nextInt(SYMBOLS.length)] }
    }

    private fun reset(column: Column, height: Int, anywhere: Boolean) {
        column.length = 5 + random.nextInt(9)
        column.chars = randomChars(column.length)
        column.speed = (1.2f + random.nextFloat() * 2.4f) * density
        // Only some columns rain at a time, a full screen of characters would be too much
        column.active = random.nextInt(100) < 45
        column.y = if (anywhere) random.nextInt(height.coerceAtLeast(1)).toFloat() else -random.nextInt(400) * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val count = (w / (22 * density)).toInt().coerceAtLeast(1)
        columns = Array(count) { Column(0f, 0f, 0, CharArray(0), false).also { reset(it, h, true) } }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = height
        val spacing = width.toFloat() / columns.size.coerceAtLeast(1)
        for ((index, column) in columns.withIndex()) {
            if (column.active) {
                val x = index * spacing + spacing / 3
                for (i in 0 until column.length) {
                    val y = column.y - i * step
                    if (y < 0 || y > height) continue
                    // The head of a column is the brightest, the tail fades out
                    paint.alpha = MAX_ALPHA * (column.length - i) / column.length
                    canvas.drawText(column.chars, i, 1, x, y, paint)
                }
            }
            column.y += column.speed
            if (column.y - column.length * step > height) {
                reset(column, height, false)
            } else if (random.nextInt(40) == 0 && column.length > 0) {
                // A character changes now and then
                column.chars[random.nextInt(column.length)] = SYMBOLS[random.nextInt(SYMBOLS.length)]
            }
        }
        if (isShown) {
            postInvalidateDelayed(FRAME_DELAY)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
}

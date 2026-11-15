package vn.poseguide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Hình nét viền nhỏ của một dáng mẫu, dùng trong thẻ gợi ý. */
class PoseThumbView(context: Context, private val template: PoseTemplate) : View(context) {

    private var art: PoseLineArt.Rendered? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        art?.bitmap?.recycle()
        art = if (w > 0 && h > 0) {
            val stroke = resources.displayMetrics.density * 1.2f
            PoseLineArt.render(template, h * 0.9f, false, stroke)
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = art ?: return
        // Thu nhỏ cho vừa ô (dáng dang tay có thể rộng hơn ô), đặt giữa
        val k = minOf(1f, width / a.bitmap.width.toFloat(), height / a.bitmap.height.toFloat())
        val w = a.bitmap.width * k
        val h = a.bitmap.height * k
        val x = (width - w) / 2f
        val y = (height - h) / 2f
        canvas.drawBitmap(a.bitmap, null, android.graphics.RectF(x, y, x + w, y + h), paint)
    }
}

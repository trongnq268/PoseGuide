package vn.poseguide

import android.media.Image
import kotlin.math.abs

/**
 * Thống kê nhanh của một khung hình, theo 3 cột dọc (trái / giữa / phải) của ảnh ĐÃ XOAY THẲNG
 * (chưa lật gương). Chỉ đọc kênh độ sáng Y, lấy mẫu thưa nên rất nhẹ.
 *
 * brightness: độ sáng trung bình 0..255.  texture: mức chi tiết (chênh lệch giữa các điểm lân cận).
 */
data class FrameStats(val brightness: FloatArray, val texture: FloatArray) {

    val meanBrightness get() = brightness.average().toFloat()

    /** Nền ít chi tiết: 2 cột "sạch" nhất đều rất trơn. */
    val isPlain get() = texture.sorted().take(2).average() < PLAIN_TEXTURE

    /** -1: sáng từ trái, +1: sáng từ phải, 0: đều. */
    val lightSide: Int
        get() {
            val d = brightness[2] - brightness[0]
            return when {
                d > SIDE_LIGHT_DIFF -> 1
                d < -SIDE_LIGHT_DIFF -> -1
                else -> 0
            }
        }

    /** Lật trái ↔ phải (dùng cho camera trước – màn hình hiển thị như gương). */
    fun mirrored() = FrameStats(brightness.reversedArray(), texture.reversedArray())

    fun describe(): String {
        val parts = ArrayList<String>()
        if (isPlain) parts += "nền tối giản"
        when (lightSide) {
            -1 -> parts += "ánh sáng ngang từ trái"
            1 -> parts += "ánh sáng ngang từ phải"
        }
        if (meanBrightness < DARK) parts += "thiếu sáng"
        return parts.joinToString(" · ")
    }

    override fun equals(other: Any?) = other is FrameStats &&
        brightness.contentEquals(other.brightness) && texture.contentEquals(other.texture)

    override fun hashCode() = 31 * brightness.contentHashCode() + texture.contentHashCode()

    companion object {
        const val PLAIN_TEXTURE = 5f
        const val SIDE_LIGHT_DIFF = 28f
        const val DARK = 55f
        private const val STEP = 8

        /** Tính từ ảnh YUV_420_888 của CameraX. rotation: số độ cần xoay để ảnh thẳng đứng. */
        fun compute(image: Image, rotation: Int): FrameStats {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = image.width
            val h = image.height

            val bSum = FloatArray(3)
            val tSum = FloatArray(3)
            val n = IntArray(3)

            var y = 0
            while (y < h) {
                var x = 0
                while (x + STEP < w) {
                    val idx = y * rowStride + x * pixStride
                    val v = buf.get(idx).toInt() and 0xFF
                    val vr = buf.get(idx + STEP * pixStride).toInt() and 0xFF
                    // Vị trí ngang (0..1) của điểm này trên ảnh đã xoay thẳng
                    val ux = when (rotation) {
                        90 -> 1f - y.toFloat() / h
                        180 -> 1f - x.toFloat() / w
                        270 -> y.toFloat() / h
                        else -> x.toFloat() / w
                    }
                    val col = (ux * 3).toInt().coerceIn(0, 2)
                    bSum[col] += v
                    tSum[col] += abs(v - vr)
                    n[col]++
                    x += STEP
                }
                y += STEP
            }
            val b = FloatArray(3) { if (n[it] > 0) bSum[it] / n[it] else 0f }
            val t = FloatArray(3) { if (n[it] > 0) tSum[it] / n[it] else 0f }
            return FrameStats(b, t)
        }
    }
}

package vn.poseguide

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

data class Guidance(val score: Float?, val hint: String)

/**
 * Lớp vẽ đè lên camera: lưới 1/3, thước cân bằng, BÓNG NGƯỜI TRẮNG MỜ (dáng mẫu)
 * và khung xương người thật (mảnh, màu vàng) để so khớp.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var template: PoseTemplate? = null
        set(value) { field = value; invalidate() }
    var onRight = true
        set(value) { field = value; invalidate() }
    var rollDeg = 0f
        set(value) { field = value; invalidate() }

    private var matched = false
    private var live: Map<Int, PointF> = emptyMap()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255); strokeWidth = 2f
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 213, 79); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }

    init {
        // BlurMaskFilter (viền nhoè) cần vẽ bằng phần mềm
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun silhouetteHeight() = height * 0.56f

    /** Đổi toạ độ dáng mẫu sang toạ độ màn hình. */
    private fun tplToView(p: PointF): PointF {
        val h = silhouetteHeight()
        val feetY = height * 0.80f
        val top = feetY - 0.96f * h
        val cx = width * (if (onRight) 2f / 3f else 1f / 3f)
        return PointF(cx + p.x * h, top + p.y * h)
    }

    /** Nhận khung xương người thật, trả về điểm khớp + lời nhắc. */
    fun updatePose(pose: Pose?, imgW: Int, imgH: Int): Guidance {
        val tpl = template
        live = if (pose == null || width == 0 || imgW == 0) emptyMap() else mapToView(pose, imgW, imgH)
        val result = computeGuidance(tpl)
        matched = (result.score ?: 0f) >= MATCH_THRESHOLD
        invalidate()
        return result
    }

    private fun mapToView(pose: Pose, imgW: Int, imgH: Int): Map<Int, PointF> {
        // PreviewView mặc định FILL_CENTER: phóng to cho đầy màn hình rồi cắt giữa
        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width - imgW * scale) / 2f
        val offY = (height - imgH * scale) / 2f
        val out = HashMap<Int, PointF>()
        for (lm in pose.allPoseLandmarks) {
            if (lm.landmarkType in USED && lm.inFrameLikelihood > 0.5f) {
                out[lm.landmarkType] = PointF(lm.position.x * scale + offX, lm.position.y * scale + offY)
            }
        }
        return out
    }

    private fun computeGuidance(tpl: PoseTemplate?): Guidance {
        if (tpl == null) return Guidance(null, "")
        if (live.isEmpty()) return Guidance(null, "Hướng camera về phía người được chụp")

        val nose = live[PoseLandmark.NOSE]
        val la = live[PoseLandmark.LEFT_ANKLE]
        val ra = live[PoseLandmark.RIGHT_ANKLE]
        val lh = live[PoseLandmark.LEFT_HIP]
        val rh = live[PoseLandmark.RIGHT_HIP]
        if (nose == null || (la == null && ra == null) || lh == null || rh == null) {
            return Guidance(null, "Đưa TOÀN THÂN người mẫu vào khung hình")
        }

        // 1) Kích thước: người thật so với bóng mờ
        val ankleY = listOfNotNull(la?.y, ra?.y).average().toFloat()
        val liveHeight = ankleY - nose.y
        val tplNose = tplToView(tpl.points.getValue(PoseLandmark.NOSE))
        val tplAnkleY = tplToView(tpl.points.getValue(PoseLandmark.LEFT_ANKLE)).y
        val tplHeight = tplAnkleY - tplNose.y
        val ratio = liveHeight / tplHeight

        // 2) Vị trí ngang
        val liveCx = (lh.x + rh.x) / 2f
        val tplCx = width * (if (onRight) 2f / 3f else 1f / 3f)
        val dx = liveCx - tplCx

        // 3) Góc tay chân
        var sum = 0f
        var n = 0
        var worstDiff = 0f
        var worstName = ""
        for (seg in SEGMENTS) {
            val a = live[seg.from] ?: continue
            val b = live[seg.to] ?: continue
            val ta = tpl.points[seg.from] ?: continue
            val tb = tpl.points[seg.to] ?: continue
            val diff = angleDiff(angle(a, b), angle(ta, tb))
            sum += (1f - diff / 70f).coerceIn(0f, 1f)
            n++
            if (diff > worstDiff) { worstDiff = diff; worstName = seg.name }
        }
        if (n < 5) return Guidance(null, "Đưa toàn thân người mẫu vào khung hình")
        var score = sum / n
        // Trừ điểm nếu đứng sai chỗ / sai cỡ
        if (abs(dx) > width * 0.12f) score *= 0.85f
        if (ratio < 0.75f || ratio > 1.3f) score *= 0.85f

        val hint = when {
            ratio < 0.75f -> "Người chụp tiến lại gần hơn"
            ratio > 1.3f -> "Người chụp lùi ra xa hơn"
            dx > width * 0.12f -> "Người mẫu dịch sang TRÁI khung hình"
            dx < -width * 0.12f -> "Người mẫu dịch sang PHẢI khung hình"
            score >= MATCH_THRESHOLD -> "Tuyệt! Giữ nguyên dáng và chụp"
            worstDiff > 20f -> "Chỉnh $worstName theo bóng mờ"
            else -> "Gần đúng rồi, chỉnh nhẹ theo bóng mờ"
        }
        return Guidance(score, hint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Lưới 1/3
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, gridPaint)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, gridPaint)
        }

        // Thước cân bằng (đường chân trời)
        val level = abs(rollDeg) < 1.5f
        levelPaint.color = if (level) Color.argb(230, 76, 217, 100) else Color.argb(200, 255, 255, 255)
        canvas.save()
        canvas.rotate(rollDeg, w / 2f, h / 2f)
        canvas.drawLine(w / 2f - w * 0.15f, h / 2f, w / 2f + w * 0.15f, h / 2f, levelPaint)
        canvas.restore()
        levelPaint.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(w / 2f - w * 0.2f, h / 2f, w / 2f - w * 0.17f, h / 2f, levelPaint)
        canvas.drawLine(w / 2f + w * 0.17f, h / 2f, w / 2f + w * 0.2f, h / 2f, levelPaint)

        template?.let { drawGhost(canvas, it) }
        drawLive(canvas)
    }

    /** Vẽ bóng người trắng mờ từ các điểm khớp mẫu. */
    private fun drawGhost(canvas: Canvas, tpl: PoseTemplate) {
        val sh = silhouetteHeight()
        val color = if (matched) Color.argb(130, 76, 217, 100) else Color.argb(115, 255, 255, 255)
        val blur = BlurMaskFilter(sh * 0.012f, BlurMaskFilter.Blur.NORMAL)
        ghostStroke.color = color; ghostStroke.maskFilter = blur
        ghostFill.color = color; ghostFill.maskFilter = blur

        fun p(type: Int) = tplToView(tpl.points.getValue(type))
        val nose = p(PoseLandmark.NOSE)
        val ls = p(PoseLandmark.LEFT_SHOULDER); val rs = p(PoseLandmark.RIGHT_SHOULDER)
        val lhip = p(PoseLandmark.LEFT_HIP); val rhip = p(PoseLandmark.RIGHT_HIP)

        // Thân
        val torso = Path().apply {
            moveTo(ls.x, ls.y); lineTo(rs.x, rs.y); lineTo(rhip.x, rhip.y); lineTo(lhip.x, lhip.y); close()
        }
        canvas.drawPath(torso, ghostFill)
        ghostStroke.strokeWidth = sh * 0.06f
        canvas.drawPath(torso, ghostStroke)

        // Cổ + đầu
        val neck = PointF((ls.x + rs.x) / 2f, (ls.y + rs.y) / 2f)
        canvas.drawLine(neck.x, neck.y, nose.x, nose.y, ghostStroke)
        canvas.drawCircle(nose.x, nose.y - sh * 0.005f, sh * 0.062f, ghostFill)

        // Tay (mảnh hơn) và chân (dày hơn)
        for (seg in SEGMENTS) {
            ghostStroke.strokeWidth = sh * (if (seg.isLeg) 0.075f else 0.055f)
            val a = p(seg.from); val b = p(seg.to)
            canvas.drawLine(a.x, a.y, b.x, b.y, ghostStroke)
        }
    }

    private fun drawLive(canvas: Canvas) {
        if (live.isEmpty()) return
        val pairs = SEGMENTS.map { it.from to it.to } + listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
        )
        for ((f, t) in pairs) {
            val a = live[f] ?: continue
            val b = live[t] ?: continue
            canvas.drawLine(a.x, a.y, b.x, b.y, livePaint)
        }
        for (pt in live.values) canvas.drawCircle(pt.x, pt.y, 7f, livePaint)
    }

    private data class Segment(val from: Int, val to: Int, val name: String, val isLeg: Boolean)

    companion object {
        const val MATCH_THRESHOLD = 0.85f

        private val SEGMENTS = listOf(
            Segment(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, "tay trái", false),
            Segment(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, "cẳng tay trái", false),
            Segment(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, "tay phải", false),
            Segment(PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, "cẳng tay phải", false),
            Segment(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, "đùi trái", true),
            Segment(PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE, "chân trái", true),
            Segment(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, "đùi phải", true),
            Segment(PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE, "chân phải", true),
        )

        private val USED = setOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
        )

        private fun angle(a: PointF, b: PointF): Float =
            Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

        private fun angleDiff(a: Float, b: Float): Float {
            var d = abs(a - b) % 360f
            if (d > 180f) d = 360f - d
            return d
        }
    }
}

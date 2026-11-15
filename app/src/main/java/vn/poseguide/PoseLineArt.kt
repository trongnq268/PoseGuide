package vn.poseguide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Vẽ dáng mẫu thành NÉT VIỀN (line-art) kiểu phác thảo: dựng hình người từ khung xương
 * (đầu, cổ, thân, tay, chân, bàn tay, bàn chân) rồi chỉ vẽ đường viền.
 * Các mảng được vẽ từ sau ra trước; mảng phía trước che nét của mảng phía sau
 * (vd. tay khoanh trước ngực vẫn thấy rõ).
 */
object PoseLineArt {

    /** Ảnh nét vẽ (nền trong suốt, nét trắng) + vị trí góc trên‑trái so với gốc mẫu (giữa thân, đỉnh đầu). */
    class Rendered(val bitmap: Bitmap, val left: Float, val top: Float)

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * @param s số pixel cho 1 đơn vị mẫu (≈ chiều cao người)
     * @param mirror lật ngang (camera trước)
     */
    fun render(tpl: PoseTemplate, s: Float, mirror: Boolean, stroke: Float): Rendered {
        val parts = parts(tpl, s, mirror)
        val bounds = RectF()
        val tmp = RectF()
        parts.forEachIndexed { i, p ->
            p.computeBounds(tmp, true)
            if (i == 0) bounds.set(tmp) else bounds.union(tmp)
        }
        val pad = stroke * 2f
        val w = ceil(bounds.width() + pad * 2).toInt().coerceAtLeast(1)
        val h = ceil(bounds.height() + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(pad - bounds.left, pad - bounds.top)
        strokePaint.strokeWidth = stroke
        for (p in parts) {
            c.drawPath(p, clearPaint)   // xoá nét của mảng phía sau bị che
            c.drawPath(p, strokePaint)  // vẽ viền mảng này
        }
        return Rendered(bmp, bounds.left - pad, bounds.top - pad)
    }

    /** Các mảng cơ thể theo thứ tự từ sau ra trước. */
    private fun parts(tpl: PoseTemplate, s: Float, mirror: Boolean): List<Path> {
        fun p(type: Int): PointF {
            val q = tpl.points.getValue(type)
            return PointF((if (mirror) -q.x else q.x) * s, q.y * s)
        }
        val nose = p(PoseLandmark.NOSE)
        val ls = p(PoseLandmark.LEFT_SHOULDER); val rs = p(PoseLandmark.RIGHT_SHOULDER)
        val le = p(PoseLandmark.LEFT_ELBOW); val re = p(PoseLandmark.RIGHT_ELBOW)
        val lw = p(PoseLandmark.LEFT_WRIST); val rw = p(PoseLandmark.RIGHT_WRIST)
        val lh = p(PoseLandmark.LEFT_HIP); val rh = p(PoseLandmark.RIGHT_HIP)
        val lk = p(PoseLandmark.LEFT_KNEE); val rk = p(PoseLandmark.RIGHT_KNEE)
        val la = p(PoseLandmark.LEFT_ANKLE); val ra = p(PoseLandmark.RIGHT_ANKLE)
        val centerX = (lh.x + rh.x) / 2f

        return listOf(
            leg(rh, rk, ra, centerX, s),
            leg(lh, lk, la, centerX, s),
            torso(ls, rs, lh, rh, nose, s),
            head(nose, s),
            arm(rs, re, rw, s),
            arm(ls, le, lw, s),
        )
    }

    private fun torso(ls: PointF, rs: PointF, lh: PointF, rh: PointF, nose: PointF, s: Float): Path {
        // Bên trái / phải theo màn hình
        val (sL, sR) = if (ls.x < rs.x) ls to rs else rs to ls
        val (hL, hR) = if (lh.x < rh.x) lh to rh else rh to lh
        val neck = PointF((ls.x + rs.x) / 2f, (ls.y + rs.y) / 2f)

        // Chân cổ → vai dốc → eo thon → hông tròn → đáy → (đối xứng) → chân cổ
        val neckL = PointF(neck.x - .03f * s, neck.y - .025f * s)
        val neckR = PointF(neck.x + .03f * s, neck.y - .025f * s)
        val tipL = PointF(sL.x - .022f * s, sL.y + .012f * s)
        val tipR = PointF(sR.x + .022f * s, sR.y + .012f * s)
        val hipL = PointF(hL.x - .045f * s, hL.y + .01f * s)
        val hipR = PointF(hR.x + .045f * s, hR.y + .01f * s)
        val waistL = PointF((tipL.x + hipL.x) / 2f + .03f * s, (tipL.y + hipL.y) / 2f)
        val waistR = PointF((tipR.x + hipR.x) / 2f - .03f * s, (tipR.y + hipR.y) / 2f)

        val body = Path().apply {
            moveTo(neckL.x, neckL.y)
            quadTo(sL.x + .02f * s, sL.y - .02f * s, tipL.x, tipL.y)
            quadTo(waistL.x, waistL.y, hipL.x, hipL.y)
            quadTo((hipL.x + hipR.x) / 2f, (hipL.y + hipR.y) / 2f + .05f * s, hipR.x, hipR.y)
            quadTo(waistR.x, waistR.y, tipR.x, tipR.y)
            quadTo(sR.x - .02f * s, sR.y - .02f * s, neckR.x, neckR.y)
            close()
        }
        // Cổ
        val neckTop = PointF((neck.x + nose.x) / 2f, (neck.y + nose.y) / 2f)
        body.op(capsule(neck, neckTop, .027f * s, .025f * s), Path.Op.UNION)
        return body
    }

    private fun head(nose: PointF, s: Float): Path = Path().apply {
        val cy = nose.y - .006f * s
        addOval(RectF(nose.x - .054f * s, cy - .066f * s, nose.x + .054f * s, cy + .062f * s), Path.Direction.CW)
    }

    private fun arm(sh: PointF, el: PointF, wr: PointF, s: Float): Path {
        val path = capsule(sh, el, .030f * s, .024f * s)
        path.op(capsule(el, wr, .024f * s, .018f * s), Path.Op.UNION)
        // Bàn tay: kéo dài theo hướng cẳng tay
        val len = max(hypot(wr.x - el.x, wr.y - el.y), 1e-3f)
        val tip = PointF(wr.x + (wr.x - el.x) / len * .035f * s, wr.y + (wr.y - el.y) / len * .035f * s)
        path.op(capsule(wr, tip, .019f * s, .014f * s), Path.Op.UNION)
        return path
    }

    private fun leg(hip: PointF, knee: PointF, ankle: PointF, centerX: Float, s: Float): Path {
        val path = capsule(hip, knee, .048f * s, .032f * s)
        path.op(capsule(knee, ankle, .032f * s, .021f * s), Path.Op.UNION)
        // Bàn chân hướng ra ngoài và hơi chúc xuống
        val side = if (ankle.x >= centerX) 1f else -1f
        val toe = PointF(ankle.x + side * .03f * s, ankle.y + .028f * s)
        path.op(capsule(ankle, toe, .021f * s, .016f * s), Path.Op.UNION)
        return path
    }

    private fun circle(c: PointF, r: Float) = Path().apply { addCircle(c.x, c.y, r, Path.Direction.CW) }

    /** Hình con nhộng thuôn từ bán kính ra (đầu a) tới rb (đầu b). */
    private fun capsule(a: PointF, b: PointF, ra: Float, rb: Float): Path {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-3f) return circle(a, max(ra, rb))
        val nx = -dy / len
        val ny = dx / len
        val path = Path().apply {
            moveTo(a.x + nx * ra, a.y + ny * ra)
            lineTo(b.x + nx * rb, b.y + ny * rb)
            lineTo(b.x - nx * rb, b.y - ny * rb)
            lineTo(a.x - nx * ra, a.y - ny * ra)
            close()
        }
        path.op(circle(a, ra), Path.Op.UNION)
        path.op(circle(b, rb), Path.Op.UNION)
        return path
    }
}

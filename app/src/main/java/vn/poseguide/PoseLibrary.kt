package vn.poseguide

import android.graphics.PointF
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * Một dáng mẫu: các điểm khớp trong hệ toạ độ chuẩn hoá.
 * x: lệch ngang so với trục giữa cơ thể (đơn vị = chiều cao người), y: 0 = đỉnh đầu, ~1 = bàn chân.
 * Người mẫu nhìn về phía máy ảnh, nên bên TRÁI của người mẫu nằm ở x dương (bên phải màn hình).
 */
data class PoseTemplate(
    val name: String,
    val tip: String,
    val points: Map<Int, PointF>,
)

enum class Scene(val vi: String, val silhouetteOnRight: Boolean) {
    BEACH("Biển / sông nước", true),
    NATURE("Thiên nhiên / cây cỏ", true),
    CITY("Phố / kiến trúc", false),
    CAFE("Quán cà phê / trong nhà", false),
    GENERAL("Cảnh chung", true),
}

object PoseLibrary {

    /** Thứ tự 13 số cặp (x, y): mũi, vai T, khuỷu T, cổ tay T, vai P, khuỷu P, cổ tay P, hông T, gối T, cổ chân T, hông P, gối P, cổ chân P */
    private val ORDER = intArrayOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE,
    )

    private fun tpl(name: String, tip: String, vararg v: Float): PoseTemplate {
        require(v.size == ORDER.size * 2)
        val map = HashMap<Int, PointF>()
        ORDER.forEachIndexed { i, type -> map[type] = PointF(v[i * 2], v[i * 2 + 1]) }
        return PoseTemplate(name, tip, map)
    }

    private val RELAX = tpl(
        "Đứng thả lỏng", "Dồn trọng tâm vào một chân, thả lỏng vai, cười nhẹ",
        0f, .06f, .11f, .18f, .14f, .35f, .15f, .50f, -.11f, .18f, -.14f, .35f, -.15f, .50f,
        .07f, .52f, .07f, .74f, .07f, .96f, -.07f, .52f, -.08f, .74f, -.10f, .96f,
    )
    private val HIP = tpl(
        "Chống hông", "Một tay chống hông, khuỷu tay mở rộng, nghiêng đầu nhẹ",
        0f, .06f, .11f, .18f, .24f, .32f, .08f, .48f, -.11f, .18f, -.14f, .35f, -.15f, .50f,
        .07f, .52f, .05f, .74f, .04f, .96f, -.07f, .52f, -.09f, .74f, -.13f, .96f,
    )
    private val WAVE = tpl(
        "Vẫy tay", "Giơ một tay cao vẫy, cười tươi tự nhiên",
        0f, .06f, .11f, .18f, .14f, .35f, .15f, .50f, -.11f, .18f, -.22f, .12f, -.25f, -.03f,
        .07f, .52f, .07f, .74f, .07f, .96f, -.07f, .52f, -.08f, .74f, -.10f, .96f,
    )
    private val OPEN = tpl(
        "Dang tay đón gió", "Dang rộng hai tay, ngẩng mặt nhẹ, hít thở thật sâu",
        0f, .06f, .11f, .18f, .26f, .15f, .41f, .10f, -.11f, .18f, -.26f, .15f, -.41f, .10f,
        .07f, .52f, .09f, .74f, .11f, .96f, -.07f, .52f, -.09f, .74f, -.11f, .96f,
    )
    private val WALK = tpl(
        "Bước đi tự nhiên", "Bước chậm về phía trước, vung tay nhẹ, nhìn xa",
        0f, .06f, .11f, .18f, .15f, .34f, .20f, .47f, -.11f, .18f, -.12f, .35f, -.07f, .49f,
        .07f, .52f, .10f, .73f, .14f, .96f, -.07f, .52f, -.05f, .75f, -.09f, .95f,
    )
    private val HEAD = tpl(
        "Hai tay sau đầu", "Đan hai tay sau gáy, mở rộng khuỷu tay, thư thái",
        0f, .06f, .11f, .18f, .24f, .07f, .05f, .03f, -.11f, .18f, -.24f, .07f, -.05f, .03f,
        .07f, .52f, .07f, .74f, .07f, .96f, -.07f, .52f, -.08f, .74f, -.10f, .96f,
    )
    private val HAIR = tpl(
        "Một tay vén tóc", "Một tay chạm tóc, bắt chéo chân nhẹ, nhìn nghiêng",
        0f, .06f, .11f, .18f, .22f, .10f, .07f, .02f, -.11f, .18f, -.14f, .35f, -.15f, .50f,
        .07f, .52f, .01f, .74f, -.03f, .96f, -.07f, .52f, -.07f, .74f, -.07f, .96f,
    )
    private val SIT = tpl(
        "Ngồi thư giãn", "Ngồi thẳng lưng, hai tay đặt nhẹ trên đùi hoặc cầm ly",
        0f, .10f, .12f, .26f, .16f, .45f, .07f, .61f, -.12f, .26f, -.16f, .45f, -.07f, .61f,
        .08f, .62f, .11f, .70f, .10f, .96f, -.08f, .62f, -.11f, .70f, -.10f, .96f,
    )
    private val JUMP = tpl(
        "Nhảy lên", "Bật nhảy, hai tay giơ cao chữ V – nên bật Tự chụp",
        0f, .06f, .11f, .18f, .20f, .07f, .28f, -.07f, -.11f, .18f, -.20f, .07f, -.28f, -.07f,
        .07f, .52f, .11f, .72f, .07f, .90f, -.07f, .52f, -.11f, .72f, -.07f, .90f,
    )

    fun forScene(scene: Scene): List<PoseTemplate> = when (scene) {
        Scene.BEACH -> listOf(OPEN, WALK, HAIR, JUMP, WAVE)
        Scene.NATURE -> listOf(WALK, HAIR, HEAD, OPEN, RELAX)
        Scene.CITY -> listOf(HIP, WALK, RELAX, HAIR)
        Scene.CAFE -> listOf(SIT, HAIR, RELAX, HIP)
        Scene.GENERAL -> listOf(RELAX, HIP, WALK, HAIR, WAVE, HEAD, OPEN, JUMP, SIT)
    }
}

/** Đổi nhãn ML Kit (tiếng Anh) thành loại cảnh. */
object SceneClassifier {
    private val keywords = mapOf(
        Scene.BEACH to setOf("beach", "sea", "lake", "river", "sand", "surfboard", "waterfall", "boat", "pier", "swimwear", "water"),
        Scene.NATURE to setOf("tree", "flower", "plant", "garden", "grass", "mountain", "forest", "field", "leaf", "petal", "park", "sky", "cloud", "rock"),
        Scene.CITY to setOf("building", "skyscraper", "bridge", "road", "car", "vehicle", "bicycle", "motorcycle", "tower", "city", "street", "monument", "temple", "church"),
        Scene.CAFE to setOf("chair", "table", "couch", "cup", "coffee", "restaurant", "desk", "food", "drink", "bar", "room", "bed", "shelf", "curtain", "cushion", "lamp"),
    )

    fun classify(labels: List<ImageLabel>): Scene {
        val scores = HashMap<Scene, Float>()
        for (label in labels) {
            val text = label.text.lowercase()
            for ((scene, words) in keywords) {
                if (words.any { text.contains(it) }) {
                    scores[scene] = (scores[scene] ?: 0f) + label.confidence
                }
            }
        }
        return scores.maxByOrNull { it.value }?.key ?: Scene.GENERAL
    }
}

package vn.poseguide

import android.graphics.PointF
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.pose.PoseLandmark

/** Góc máy gợi ý cho từng dáng. tiltDeg: độ ngửa camera so với phương ngang (+ = ngửa lên, − = chúc xuống). */
enum class CameraAngle(val vi: String, val tiltDeg: Float, val howTo: String) {
    LOW("Góc thấp", 12f, "Hạ máy ngang hông, ngửa lên ~12° để kéo dài chân"),
    EYE("Ngang tầm ngực", 0f, "Giữ máy ngang tầm ngực người mẫu, thẳng đứng"),
    HIGH("Góc cao", -15f, "Giơ máy cao hơn đầu, chúc xuống ~15°"),
}

/** Vật thể xung quanh có thể dùng để tạo dáng tương tác. */
enum class Prop(val vi: String, val keywords: Set<String>) {
    SEAT("ghế", setOf("chair", "couch", "bench", "sofa", "stool")),
    STAIRS("bậc thang", setOf("stair", "step")),
    RAIL("lan can", setOf("handrail", "railing", "fence", "balcony")),
    WINDOW("cửa sổ", setOf("window", "curtain")),
    TREE("cây", setOf("tree", "plant", "flower")),
    WALL("tường", setOf("wall", "brick")),
}

/**
 * Một dáng mẫu: các điểm khớp trong hệ toạ độ chuẩn hoá.
 * x: lệch ngang so với trục giữa cơ thể (đơn vị = chiều cao người), y: 0 = đỉnh đầu, ~1 = bàn chân.
 * Người mẫu nhìn về phía máy ảnh, nên bên TRÁI của người mẫu nằm ở x dương (bên phải màn hình).
 */
data class PoseTemplate(
    val name: String,
    val tip: String,
    val points: Map<Int, PointF>,
    val angle: CameraAngle,
    val props: Set<Prop> = emptySet(),
)

enum class Scene(val vi: String, val silhouetteOnRight: Boolean) {
    BEACH("Biển / sông nước", true),
    NATURE("Thiên nhiên / cây cỏ", true),
    CITY("Phố / kiến trúc", false),
    CAFE("Quán cà phê / trong nhà", false),
    STAIRS("Cầu thang / bậc", false),
    WALL("Tường trơn / tối giản", true),
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

    private fun tpl(
        name: String, tip: String, angle: CameraAngle, props: Set<Prop>, vararg v: Float,
    ): PoseTemplate {
        require(v.size == ORDER.size * 2)
        val map = HashMap<Int, PointF>()
        ORDER.forEachIndexed { i, type -> map[type] = PointF(v[i * 2], v[i * 2 + 1]) }
        return PoseTemplate(name, tip, map, angle, props)
    }

    private val RELAX = tpl(
        "Đứng thả lỏng", "Dồn trọng tâm vào một chân, thả lỏng vai, cười nhẹ", CameraAngle.LOW, emptySet(),
        0f, .06f, .11f, .18f, .14f, .35f, .15f, .50f, -.11f, .18f, -.14f, .35f, -.15f, .50f,
        .07f, .52f, .07f, .74f, .07f, .96f, -.07f, .52f, -.08f, .74f, -.10f, .96f,
    )
    private val HIP = tpl(
        "Chống hông", "Một tay chống hông, khuỷu tay mở rộng, nghiêng đầu nhẹ", CameraAngle.LOW, emptySet(),
        0f, .06f, .11f, .18f, .24f, .32f, .08f, .48f, -.11f, .18f, -.14f, .35f, -.15f, .50f,
        .07f, .52f, .05f, .74f, .04f, .96f, -.07f, .52f, -.09f, .74f, -.13f, .96f,
    )
    private val WAVE = tpl(
        "Vẫy tay", "Giơ một tay cao vẫy, cười tươi tự nhiên", CameraAngle.EYE, emptySet(),
        0f, .06f, .11f, .18f, .14f, .35f, .15f, .50f, -.11f, .18f, -.22f, .12f, -.25f, -.03f,
        .07f, .52f, .07f, .74f, .07f, .96f, -.07f, .52f, -.08f, .74f, -.10f, .96f,
    )
    private val OPEN = tpl(
        "Dang tay đón gió", "Dang rộng hai tay, ngẩng mặt nhẹ, hít thở thật sâu", CameraAngle.LOW, emptySet(),
        0f, .06f, .11f, .18f, .26f, .15f, .41f, .10f, -.11f, .18f, -.26f, .15f, -.41f, .10f,
        .07f, .52f, .09f, .74f, .11f, .96f, -.07f, .52f, -.09f, .74f, -.11f, .96f,
    )
    private val WALK = tpl(
        "Bước đi tự nhiên", "Bước chậm về phía trước, vung tay nhẹ, nhìn xa", CameraAngle.LOW, emptySet(),
        0f, .06f, .11f, .18f, .15f, .34f, .20f, .47f, -.11f, .18f, -.12f, .35f, -.07f, .49f,
        .07f, .52f, .10f, .73f, .14f, .96f, -.07f, .52f, -.05f, .75f, -.09f, .95f,
    )
    private val HEAD = tpl(
        "Hai tay sau đầu", "Đan hai tay sau gáy, mở rộng khuỷu tay, thư thái", CameraAngle.EYE, emptySet(),
        0f, .06f, .11f, .18f, .24f, .07f, .05f, .03f, -.11f, .18f, -.24f, .07f, -.05f, .03f,
        .07f, .52f, .07f, .74f, .07f, .96f, -.07f, .52f, -.08f, .74f, -.10f, .96f,
    )
    private val HAIR = tpl(
        "Một tay vén tóc", "Một tay chạm tóc, bắt chéo chân nhẹ, nhìn nghiêng", CameraAngle.EYE, emptySet(),
        0f, .06f, .11f, .18f, .22f, .10f, .07f, .02f, -.11f, .18f, -.14f, .35f, -.15f, .50f,
        .07f, .52f, .01f, .74f, -.03f, .96f, -.07f, .52f, -.07f, .74f, -.07f, .96f,
    )
    private val SIT = tpl(
        "Ngồi thư giãn", "Ngồi thẳng lưng, hai tay đặt nhẹ trên đùi hoặc cầm ly", CameraAngle.EYE, setOf(Prop.SEAT),
        0f, .10f, .12f, .26f, .16f, .45f, .07f, .61f, -.12f, .26f, -.16f, .45f, -.07f, .61f,
        .08f, .62f, .11f, .70f, .10f, .96f, -.08f, .62f, -.11f, .70f, -.10f, .96f,
    )
    private val JUMP = tpl(
        "Nhảy lên", "Bật nhảy, hai tay giơ cao chữ V – nên bật Tự chụp", CameraAngle.LOW, emptySet(),
        0f, .06f, .11f, .18f, .20f, .07f, .28f, -.07f, -.11f, .18f, -.20f, .07f, -.28f, -.07f,
        .07f, .52f, .11f, .72f, .07f, .90f, -.07f, .52f, -.11f, .72f, -.07f, .90f,
    )
    private val LEAN = tpl(
        "Tựa lưng nhìn nghiêng", "Tựa lưng vào tường, bắt chéo chân, quay mặt nhìn ra xa", CameraAngle.EYE, setOf(Prop.WALL),
        0f, .06f, .11f, .18f, .15f, .34f, .09f, .48f, -.11f, .18f, -.15f, .34f, -.09f, .48f,
        .07f, .52f, -.01f, .73f, -.09f, .94f, -.07f, .52f, -.08f, .74f, -.06f, .96f,
    )
    private val STEP_SIT = tpl(
        "Ngồi bậc thang", "Ngồi trên bậc, một chân co lên bậc trên, một chân duỗi xuống", CameraAngle.HIGH, setOf(Prop.STAIRS, Prop.SEAT),
        0f, .12f, .12f, .27f, .17f, .42f, .14f, .55f, -.12f, .27f, -.17f, .44f, -.12f, .60f,
        .08f, .63f, .14f, .56f, .13f, .80f, -.08f, .63f, -.16f, .74f, -.20f, .96f,
    )
    private val RAIL = tpl(
        "Tựa lan can", "Một tay đặt lên lan can, người hơi nghiêng, chân bắt chéo", CameraAngle.LOW, setOf(Prop.RAIL, Prop.STAIRS),
        0f, .06f, .11f, .18f, .14f, .35f, .15f, .50f, -.11f, .18f, -.22f, .32f, -.32f, .42f,
        .07f, .52f, .01f, .74f, -.04f, .96f, -.07f, .52f, -.08f, .74f, -.08f, .96f,
    )
    private val WINDOW = tpl(
        "Nhìn ra cửa sổ", "Đứng cạnh cửa sổ, hai tay ôm nhẹ trước ngực, nhìn ra ánh sáng", CameraAngle.EYE, setOf(Prop.WINDOW),
        0f, .06f, .11f, .18f, .15f, .33f, -.02f, .30f, -.11f, .18f, -.15f, .33f, .02f, .32f,
        .07f, .52f, .06f, .74f, .06f, .96f, -.07f, .52f, -.09f, .74f, -.12f, .96f,
    )
    private val TREE = tpl(
        "Vịn cây ngẩng nhìn", "Một tay vịn thân cây ngang vai, người nghiêng ra, ngẩng nhìn lên", CameraAngle.LOW, setOf(Prop.TREE),
        0f, .06f, .11f, .18f, .14f, .35f, .15f, .50f, -.11f, .18f, -.24f, .20f, -.36f, .18f,
        .07f, .52f, .09f, .74f, .12f, .96f, -.07f, .52f, -.07f, .74f, -.06f, .96f,
    )

    private fun base(scene: Scene): List<PoseTemplate> = when (scene) {
        Scene.BEACH -> listOf(OPEN, WALK, HAIR, JUMP, WAVE)
        Scene.NATURE -> listOf(WALK, TREE, HAIR, HEAD, OPEN, RELAX)
        Scene.CITY -> listOf(HIP, WALK, RAIL, RELAX, HAIR)
        Scene.CAFE -> listOf(SIT, WINDOW, HAIR, RELAX, HIP)
        Scene.STAIRS -> listOf(STEP_SIT, RAIL, WALK, LEAN, HIP)
        Scene.WALL -> listOf(LEAN, HIP, HAIR, HEAD, RELAX)
        Scene.GENERAL -> listOf(RELAX, HIP, WALK, HAIR, WAVE, HEAD, OPEN, JUMP, SIT)
    }

    private val ALL = listOf(RELAX, HIP, WAVE, OPEN, WALK, HEAD, HAIR, SIT, JUMP, LEAN, STEP_SIT, RAIL, WINDOW, TREE)

    /**
     * Đề xuất tối đa [max] dáng: dáng tương tác với vật thể đang thấy được ưu tiên trước,
     * sau đó là các dáng hợp với cảnh.
     */
    fun suggest(scene: Scene, props: Set<Prop>, max: Int = 5): List<PoseTemplate> {
        val interactive = ALL.filter { t -> t.props.any { it in props } }
        return (interactive + base(scene)).distinct().take(max)
    }
}

/** Đổi nhãn ML Kit (tiếng Anh) + thống kê khung hình thành loại cảnh và vật thể. */
object SceneClassifier {
    private val keywords = mapOf(
        Scene.BEACH to setOf("beach", "sea", "lake", "river", "sand", "surfboard", "waterfall", "boat", "pier", "swimwear", "water"),
        Scene.NATURE to setOf("tree", "flower", "plant", "garden", "grass", "mountain", "forest", "field", "leaf", "petal", "park", "sky", "cloud", "rock"),
        Scene.CITY to setOf("building", "skyscraper", "bridge", "road", "car", "vehicle", "bicycle", "motorcycle", "tower", "city", "street", "monument", "temple", "church"),
        Scene.CAFE to setOf("chair", "table", "couch", "cup", "coffee", "restaurant", "desk", "food", "drink", "bar", "room", "bed", "shelf", "curtain", "cushion", "lamp"),
        Scene.STAIRS to setOf("stair", "step", "handrail"),
    )

    fun classify(labels: List<ImageLabel>, stats: FrameStats?): Scene {
        val scores = HashMap<Scene, Float>()
        for (label in labels) {
            val text = label.text.lowercase()
            for ((scene, words) in keywords) {
                if (words.any { text.contains(it) }) {
                    // Cầu thang là bối cảnh rất cụ thể, ưu tiên hơn khi thấy
                    val w = if (scene == Scene.STAIRS) 1.5f else 1f
                    scores[scene] = (scores[scene] ?: 0f) + label.confidence * w
                }
            }
        }
        val byLabel = scores.maxByOrNull { it.value }?.key ?: Scene.GENERAL
        // Nền ít chi tiết (tường trơn) – nhận ra từ độ "nhiễu" của ảnh, không cần nhãn
        if (stats != null && stats.isPlain && byLabel in setOf(Scene.GENERAL, Scene.CAFE, Scene.CITY)) {
            return Scene.WALL
        }
        return byLabel
    }

    fun detectProps(labels: List<ImageLabel>): Set<Prop> {
        val out = HashSet<Prop>()
        for (label in labels) {
            val text = label.text.lowercase()
            for (p in Prop.values()) if (p.keywords.any { text.contains(it) }) out += p
        }
        return out
    }
}

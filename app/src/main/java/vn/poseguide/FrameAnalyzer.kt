package vn.poseguide

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/** Kết quả phân tích một khung hình. labels/stats chỉ có khoảng mỗi 500 ms, các khung khác là null. */
class FrameResult(
    val pose: Pose?,
    val imageWidth: Int,
    val imageHeight: Int,
    val labels: List<ImageLabel>?,
    val stats: FrameStats?,
)

/**
 * Phân tích từng khung hình camera, hoàn toàn trên máy:
 *  - nhận diện khung xương người (mỗi khung hình)
 *  - nhận diện cảnh/vật thể + đo ánh sáng, độ chi tiết nền (mỗi 500 ms cho nhẹ máy)
 * Kết quả trả về trên luồng chính (main thread).
 */
class FrameAnalyzer(
    private val onResult: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    private var lastSceneTime = 0L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        val upright = rotation == 90 || rotation == 270
        val w = if (upright) proxy.height else proxy.width
        val h = if (upright) proxy.width else proxy.height

        val poseTask: Task<Pose> = poseDetector.process(input)
        val now = SystemClock.elapsedRealtime()
        val sceneFrame = now - lastSceneTime >= SCENE_INTERVAL_MS
        if (sceneFrame) lastSceneTime = now
        val labelTask: Task<List<ImageLabel>>? = if (sceneFrame) labeler.process(input) else null
        val stats: FrameStats? = if (sceneFrame) safeStats(media, rotation) else null

        val tasks = listOfNotNull<Task<*>>(poseTask, labelTask)
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            val pose = if (poseTask.isSuccessful) poseTask.result else null
            val labels = labelTask?.let { t -> if (t.isSuccessful) t.result else null }
            try {
                onResult(FrameResult(pose, w, h, labels, stats))
            } finally {
                proxy.close()
            }
        }
    }

    private fun safeStats(media: android.media.Image, rotation: Int): FrameStats? =
        try {
            FrameStats.compute(media, rotation)
        } catch (e: Exception) {
            null
        }

    fun close() {
        poseDetector.close()
        labeler.close()
    }

    companion object {
        const val SCENE_INTERVAL_MS = 500L
    }
}

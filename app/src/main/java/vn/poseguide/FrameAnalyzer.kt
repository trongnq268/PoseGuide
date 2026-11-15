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

/**
 * Phân tích từng khung hình camera, hoàn toàn trên máy:
 *  - nhận diện khung xương người (mỗi khung hình)
 *  - nhận diện cảnh (khoảng 1 lần/giây cho nhẹ máy)
 * Kết quả trả về trên luồng chính (main thread).
 */
class FrameAnalyzer(
    private val onResult: (pose: Pose?, imageWidth: Int, imageHeight: Int, labels: List<ImageLabel>?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    private var lastLabelTime = 0L

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
        val labelTask: Task<List<ImageLabel>>? =
            if (now - lastLabelTime > 1000) {
                lastLabelTime = now
                labeler.process(input)
            } else null

        val tasks = listOfNotNull<Task<*>>(poseTask, labelTask)
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            val pose = if (poseTask.isSuccessful) poseTask.result else null
            val labels = labelTask?.let { if (it.isSuccessful) it.result else null }
            try {
                onResult(pose, w, h, labels)
            } finally {
                proxy.close()
            }
        }
    }

    fun close() {
        poseDetector.close()
        labeler.close()
    }
}

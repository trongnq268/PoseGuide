package vn.poseguide

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.pose.Pose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var sceneText: TextView
    private lateinit var hintText: TextView
    private lateinit var tipText: TextView
    private lateinit var poseName: TextView
    private lateinit var scoreText: TextView
    private lateinit var autoBtn: Button

    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: FrameAnalyzer? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var sensorManager: SensorManager

    private var scene = Scene.GENERAL
    private var sceneLocked = false
    private var pendingScene: Scene? = null
    private var pendingCount = 0
    private var poses = PoseLibrary.forScene(scene)
    private var poseIndex = 0

    private var autoCapture = false
    private var goodSince = 0L
    private var lastShot = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Cần quyền Camera để dùng app", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        sceneText = findViewById(R.id.sceneText)
        hintText = findViewById(R.id.hintText)
        tipText = findViewById(R.id.tipText)
        poseName = findViewById(R.id.poseName)
        scoreText = findViewById(R.id.scoreText)
        autoBtn = findViewById(R.id.autoBtn)

        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        findViewById<Button>(R.id.prevBtn).setOnClickListener { changePose(-1) }
        findViewById<Button>(R.id.nextBtn).setOnClickListener { changePose(+1) }
        findViewById<Button>(R.id.flipBtn).setOnClickListener { overlay.onRight = !overlay.onRight }
        findViewById<Button>(R.id.shutterBtn).setOnClickListener { takePhoto() }
        autoBtn.setOnClickListener {
            autoCapture = !autoCapture
            autoBtn.text = if (autoCapture) "Tự chụp: BẬT" else "Tự chụp: TẮT"
        }
        // Chạm vào tên cảnh để tự chọn cảnh (khoá lại, không tự đổi nữa)
        sceneText.setOnClickListener {
            val all = Scene.values()
            sceneLocked = true
            setScene(all[(scene.ordinal + 1) % all.size])
        }

        setScene(Scene.GENERAL)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val ratio43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(ratio43)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val a = FrameAnalyzer(::onFrame)
            analyzer = a
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, a) }

            val capture = ImageCapture.Builder()
                .setResolutionSelector(ratio43)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Gọi trên luồng chính sau mỗi khung hình được phân tích. */
    private fun onFrame(pose: Pose?, imgW: Int, imgH: Int, labels: List<ImageLabel>?) {
        if (labels != null && !sceneLocked) updateScene(SceneClassifier.classify(labels))

        val g = overlay.updatePose(pose, imgW, imgH)
        hintText.text = g.hint
        scoreText.text = g.score?.let { "Khớp ${(it * 100).toInt()}%" } ?: ""

        // Tự chụp khi khớp dáng liên tục 0,8 giây
        val now = SystemClock.elapsedRealtime()
        if ((g.score ?: 0f) >= OverlayView.MATCH_THRESHOLD) {
            if (goodSince == 0L) goodSince = now
            if (autoCapture && now - goodSince > 800 && now - lastShot > 3000) takePhoto()
        } else {
            goodSince = 0L
        }
    }

    private fun updateScene(detected: Scene) {
        if (detected == scene) { pendingScene = null; pendingCount = 0; return }
        if (detected == pendingScene) pendingCount++ else { pendingScene = detected; pendingCount = 1 }
        // Chỉ đổi cảnh khi thấy ổn định 2 lần liên tiếp, tránh nhảy lung tung
        if (pendingCount >= 2) setScene(detected)
    }

    private fun setScene(s: Scene) {
        scene = s
        pendingScene = null
        pendingCount = 0
        poses = PoseLibrary.forScene(s)
        poseIndex = 0
        overlay.onRight = s.silhouetteOnRight
        sceneText.text = "Cảnh: ${s.vi}" + if (sceneLocked) "  🔒" else ""
        showPose()
    }

    private fun changePose(delta: Int) {
        poseIndex = (poseIndex + delta + poses.size) % poses.size
        showPose()
    }

    private fun showPose() {
        val p = poses[poseIndex]
        overlay.template = p
        poseName.text = "${p.name}  (${poseIndex + 1}/${poses.size})"
        tipText.text = p.tip
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        lastShot = SystemClock.elapsedRealtime()
        val name = "PoseGuide_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoseGuide")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Đã lưu ảnh vào Pictures/PoseGuide", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Lỗi chụp: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // Thước cân bằng dùng cảm biến trọng lực
    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        overlay.rollDeg = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        analyzer?.close()
        cameraExecutor.shutdown()
    }
}

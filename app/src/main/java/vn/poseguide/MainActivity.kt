package vn.poseguide

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var sceneText: TextView
    private lateinit var hintText: TextView
    private lateinit var angleText: TextView
    private lateinit var tipText: TextView
    private lateinit var scoreText: TextView
    private lateinit var cardRow: LinearLayout
    private lateinit var autoBtn: Button

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analyzer: FrameAnalyzer
    private var imageCapture: ImageCapture? = null
    private lateinit var sensorManager: SensorManager
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Bối cảnh
    private var scene = Scene.GENERAL
    private var sceneLocked = false
    private var pendingScene: Scene? = null
    private var pendingCount = 0
    private val recentProps = ArrayDeque<Set<Prop>>()
    private var props: Set<Prop> = emptySet()
    private var lastStats: FrameStats? = null

    // Vị trí đứng: tự chọn phía nền "sạch" hơn, trừ khi người dùng tự đổi bên
    private var sideManual = false
    private var pendingSide: Boolean? = null
    private var pendingSideCount = 0

    // Dáng gợi ý
    private var suggestions: List<PoseTemplate> = emptyList()
    private lateinit var current: PoseTemplate

    // Góc máy (độ ngửa camera, đã lọc nhiễu)
    private var tiltDeg = 0f
    private var tiltInit = false

    // Tự chụp
    private var autoCapture = false
    private var goodSince = 0L
    private var lostSince = 0L
    private var countdownStart = 0L
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
        angleText = findViewById(R.id.angleText)
        tipText = findViewById(R.id.tipText)
        scoreText = findViewById(R.id.scoreText)
        cardRow = findViewById(R.id.cardRow)
        autoBtn = findViewById(R.id.autoBtn)

        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = FrameAnalyzer(::onFrame)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        findViewById<Button>(R.id.flipBtn).setOnClickListener {
            sideManual = true
            overlay.onRight = !overlay.onRight
        }
        findViewById<Button>(R.id.shutterBtn).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.cameraBtn).setOnClickListener { switchCamera() }
        autoBtn.setOnClickListener {
            autoCapture = !autoCapture
            if (!autoCapture) cancelCountdown()
            autoBtn.text = if (autoCapture) "Tự chụp: BẬT" else "Tự chụp: TẮT"
        }
        // Chạm vào dòng cảnh để tự chọn cảnh (khoá lại, không tự đổi nữa)
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

    // ---------------------------------------------------------------- Camera

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

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            val capture = ImageCapture.Builder()
                .setResolutionSelector(ratio43)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis, capture)
            } catch (e: Exception) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isFront() = lensFacing == CameraSelector.LENS_FACING_FRONT

    private fun switchCamera() {
        val provider = try { ProcessCameraProvider.getInstance(this).get() } catch (e: Exception) { return }
        val next = if (isFront()) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val available = try {
            provider.hasCamera(CameraSelector.Builder().requireLensFacing(next).build())
        } catch (e: Exception) {
            false
        }
        if (!available) {
            Toast.makeText(this, "Máy không có camera này", Toast.LENGTH_SHORT).show()
            return
        }
        lensFacing = next
        overlay.mirror = isFront()
        tiltInit = false
        cancelCountdown()
        startCamera()
    }

    // ---------------------------------------------------------------- Phân tích khung hình

    /** Gọi trên luồng chính sau mỗi khung hình được phân tích. */
    private fun onFrame(r: FrameResult) {
        val stats = r.stats?.let { if (isFront()) it.mirrored() else it }
        if (stats != null) lastStats = stats

        if (r.labels != null) {
            updateProps(SceneClassifier.detectProps(r.labels))
            if (!sceneLocked) updateScene(SceneClassifier.classify(r.labels, lastStats))
            updateSceneText()
        }

        val personVisible = r.pose?.allPoseLandmarks?.isNotEmpty() == true
        if (stats != null && !personVisible) maybeAutoSide(stats)

        val g = overlay.updatePose(r.pose, r.imageWidth, r.imageHeight)
        scoreText.text = g.score?.let { "${(it * 100).toInt()}%" } ?: ""
        handleAutoCapture(g)
    }

    private fun updateProps(seen: Set<Prop>) {
        // Gộp vật thể thấy trong 3 lần quét gần nhất (~1,5 giây) cho ổn định
        recentProps.addLast(seen)
        while (recentProps.size > 3) recentProps.removeFirst()
        val merged = recentProps.flatten().toSet()
        if (merged != props) {
            props = merged
            refreshSuggestions()
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
        if (!sideManual) overlay.onRight = s.silhouetteOnRight
        refreshSuggestions()
        updateSceneText()
    }

    private fun updateSceneText() {
        val line1 = "Cảnh: ${scene.vi}" + if (sceneLocked) "  🔒" else ""
        val extras = ArrayList<String>()
        if (props.isNotEmpty()) extras += "Thấy: " + props.joinToString(", ") { it.vi }
        lastStats?.describe()?.takeIf { it.isNotEmpty() }?.let { extras += it }
        sceneText.text = if (extras.isEmpty()) line1 else line1 + "\n" + extras.joinToString(" · ")
    }

    /** Đặt bóng mờ ở phía nền ít chi tiết hơn (chủ thể nổi bật hơn). Chỉ chạy khi chưa có người trong khung. */
    private fun maybeAutoSide(stats: FrameStats) {
        if (sideManual || countdownStart > 0) return
        val left = stats.texture[0]
        val right = stats.texture[2]
        val want: Boolean? = when {
            right * 1.4f < left -> true
            left * 1.4f < right -> false
            else -> null
        }
        if (want == null || want == overlay.onRight) { pendingSide = null; pendingSideCount = 0; return }
        if (want == pendingSide) pendingSideCount++ else { pendingSide = want; pendingSideCount = 1 }
        if (pendingSideCount >= 3) {
            overlay.onRight = want
            pendingSide = null
            pendingSideCount = 0
        }
    }

    // ---------------------------------------------------------------- Thẻ gợi ý

    private fun refreshSuggestions() {
        if (countdownStart > 0) return
        val next = PoseLibrary.suggest(scene, props)
        if (next == suggestions) return
        suggestions = next
        if (!::current.isInitialized || current !in next) current = next.first()
        buildCards()
        showPose()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).roundToInt()

    private fun buildCards() {
        cardRow.removeAllViews()
        for (t in suggestions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                tag = t
                setOnClickListener { select(t) }
            }
            card.addView(PoseThumbView(this, t), LinearLayout.LayoutParams(dp(44), dp(56)))
            card.addView(TextView(this).apply {
                text = t.name
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT))
            card.addView(TextView(this).apply {
                text = t.angle.vi
                setTextColor(Color.parseColor("#FFC107"))
                textSize = 10f
                gravity = Gravity.CENTER
            })
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            cardRow.addView(card, lp)
        }
        highlightCards()
    }

    private fun highlightCards() {
        for (i in 0 until cardRow.childCount) {
            val card = cardRow.getChildAt(i)
            val selected = card.tag == current
            card.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (selected) Color.argb(90, 76, 217, 100) else Color.argb(60, 255, 255, 255))
                setStroke(dp(2), if (selected) Color.rgb(76, 217, 100) else Color.TRANSPARENT)
            }
        }
    }

    private fun select(t: PoseTemplate) {
        current = t
        cancelCountdown()
        showPose()
        highlightCards()
    }

    private fun showPose() {
        overlay.template = current
        overlay.targetTilt = current.angle.tiltDeg
        tipText.text = current.tip + "\n📷 " + current.angle.howTo
        updateAngleText()
    }

    // ---------------------------------------------------------------- Góc máy

    private fun tiltError() = tiltDeg - current.angle.tiltDeg

    private fun tiltOk() = abs(tiltError()) <= OverlayView.TILT_TOLERANCE

    private fun updateAngleText() {
        if (!::current.isInitialized) return
        val err = tiltError()
        val name = current.angle.vi
        angleText.text = when {
            !tiltInit -> ""
            tiltOk() -> "✓ $name: góc máy chuẩn"
            err < 0 -> "$name: NGỬA máy lên thêm ${(-err).roundToInt()}°"
            else -> "$name: CHÚC máy xuống thêm ${err.roundToInt()}°"
        }
        angleText.setTextColor(if (tiltOk()) Color.rgb(76, 217, 100) else Color.parseColor("#FFC107"))
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        cancelCountdown()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        overlay.rollDeg = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()

        // Độ ngửa của camera sau so với phương ngang: màn hình hướng lên trời (gz > 0) = camera chúc xuống
        var elevation = Math.toDegrees(atan2(-gz.toDouble(), hypot(gx.toDouble(), gy.toDouble()))).toFloat()
        if (isFront()) elevation = -elevation
        tiltDeg = if (tiltInit) tiltDeg * 0.8f + elevation * 0.2f else elevation
        tiltInit = true
        overlay.tiltDeg = tiltDeg
        updateAngleText()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- Tự chụp

    /**
     * Khớp dáng + đúng góc máy liên tục [HOLD_MS] → đếm ngược 3‑2‑1 → chụp.
     * Đang đếm mà người mẫu lệch dáng quá [LOST_MS] thì huỷ.
     */
    private fun handleAutoCapture(g: Guidance) {
        val now = SystemClock.elapsedRealtime()
        val score = g.score ?: 0f
        val ready = score >= OverlayView.MATCH_THRESHOLD && tiltOk()
        if (ready) { if (goodSince == 0L) goodSince = now } else goodSince = 0L

        if (countdownStart > 0) {
            if (score < OverlayView.MATCH_THRESHOLD - 0.1f) {
                if (lostSince == 0L) lostSince = now
                if (now - lostSince > LOST_MS) {
                    cancelCountdown()
                    hintText.text = "Đã huỷ đếm ngược – tạo lại dáng nhé"
                    return
                }
            } else {
                lostSince = 0L
            }
            val elapsed = now - countdownStart
            if (elapsed >= COUNTDOWN_MS) {
                cancelCountdown()
                takePhoto()
            } else {
                overlay.countdown = 3 - (elapsed / 1000).toInt()
                hintText.text = "Giữ nguyên dáng!"
            }
            return
        }

        hintText.text = when {
            g.score != null && score >= OverlayView.MATCH_THRESHOLD && !tiltOk() -> "Dáng chuẩn! Chỉnh góc máy theo thước bên phải"
            else -> g.hint
        }
        if (autoCapture && ready && now - goodSince >= HOLD_MS && now - lastShot > 3000) {
            countdownStart = now
            lostSince = 0L
            overlay.countdown = 3
        }
    }

    private fun cancelCountdown() {
        countdownStart = 0L
        lostSince = 0L
        goodSince = 0L
        overlay.countdown = null
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

    override fun onDestroy() {
        super.onDestroy()
        analyzer.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val HOLD_MS = 1200L
        private const val COUNTDOWN_MS = 3000L
        private const val LOST_MS = 600L
    }
}

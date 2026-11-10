package io.github.deganegane.looilab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Where the nearest face is, in the camera's (= the robot's) view: x -1 left … 1 right, y -1 up … 1 down. */
data class SeenFace(val x: Float, val y: Float, val size: Float, val at: Long)

/**
 * The front camera: on-device face detection (ML Kit, no internet), small JPEG frames for
 * Gemini when Stevo is allowed to see, and full photos saved to Pictures/Stevo.
 */
class CameraEyes(private val context: Context) {
    private val _face = MutableStateFlow<SeenFace?>(null)
    val face: StateFlow<SeenFace?> = _face.asStateFlow()

    /** Set while Stevo may see: receives a small JPEG about every [frameEveryMs]. */
    @Volatile var onFrame: ((ByteArray) -> Unit)? = null
    private val frameEveryMs = 1_500L
    private var lastFrame = 0L

    private val exec = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.12f)
            .build()
    )
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    var running = false
        private set

    fun start(owner: LifecycleOwner) {
        if (running) return
        running = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get(); provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec) { img -> analyze(img) }
            val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            capture = cap
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis, cap)
            }.onFailure { running = false }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        capture = null
        _face.value = null
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(img: ImageProxy) {
        val media = img.image ?: run { img.close(); return }
        val rot = img.imageInfo.rotationDegrees
        val upright = if (rot == 90 || rot == 270) img.height to img.width else img.width to img.height

        val now = System.currentTimeMillis()
        val sink = onFrame
        if (sink != null && now - lastFrame >= frameEveryMs) {
            lastFrame = now
            runCatching { sink(jpeg(img.toBitmap(), rot, 640, 60)) }
        }

        detector.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener { faces ->
                val f = faces.maxByOrNull { it.boundingBox.width() }
                if (f != null) {
                    val (w, h) = upright
                    val b = f.boundingBox
                    _face.value = SeenFace(
                        x = (b.exactCenterX() / w) * 2 - 1,
                        y = (b.exactCenterY() / h) * 2 - 1,
                        size = b.width() / w.toFloat(),
                        at = System.currentTimeMillis(),
                    )
                }
            }
            .addOnCompleteListener { img.close() }
    }

    private fun jpeg(src: Bitmap, rotation: Int, maxSide: Int, quality: Int): ByteArray {
        val scale = maxSide / maxOf(src.width, src.height).toFloat()
        val m = Matrix().apply { postRotate(rotation.toFloat()); if (scale < 1f) postScale(scale, scale) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
    }

    /** Saves a full photo to Pictures/Stevo; [done] gets the file name, or null on failure. */
    fun takePhoto(done: (String?) -> Unit) {
        val cap = capture ?: return done(null)
        val name = "Stevo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Stevo")
        }
        val out = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        cap.takePicture(out, exec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) = done(name)
            override fun onError(e: ImageCaptureException) = done(null)
        })
    }
}

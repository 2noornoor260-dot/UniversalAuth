package ax.nd.faceunlock.backend

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.megvii.facepp.sdk.UnlockEncryptor
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class SmartFaceEngine(context: Context) {

    companion object {
        private const val TAG = "SmartFaceEngine"
        private const val MAX_TEMPLATES = 18
        private const val DB_FILE_NAME = "smart_face_templates_v4.enc"
        const val PREFS_NAME = "smart_face_prefs"
        private const val KEY_CACHED_ROTATION = "cached_camera_rotation"

        // مفاتيح تجربة الاستخدام الذكية
        const val KEY_REQUIRE_EYES = "req_eyes_open"
        const val KEY_ADAPTIVE = "adaptive_learning"
        const val KEY_NIGHT_LIGHT = "night_fill_light"
        const val KEY_UNLOCK_CHIME = "unlock_chime_sound"
        const val KEY_CAPSULE_ANIM = "dynamic_capsule_anim"

        // معايرة دقيقة لكاميرا التابلت (سرعة فائقة + حماية 4 طبقات)
        private const val MATCH_COMBINED_THRESHOLD = 0.45f
        private const val MATCH_SINGLE_MAX_THRESHOLD = 0.49f
        private const val INSTANT_UNLOCK_THRESHOLD = 0.56f
        private const val OPTIMAL_SPOOF_THRESHOLD = 0.40f
        private const val MIN_NOSE_PROTRUSION_3D = 0.028f
        private const val MIN_SKIN_SHARPNESS = 1.8f
        private const val OPTIMAL_MAX_HEAD_ANGLE = 36f

        @JvmField var currentEnrollHint: String = "ضع وجهك داخل الإطار وانظر للأمام"
        @JvmField var currentEnrollPercent: Float = 0f
        @JvmField var currentEnrollStage: Int = 0

        const val CODE_SUCCESS = 0
        const val CODE_NO_FACE = 5
        const val CODE_TOO_SMALL = 6
        const val CODE_TOO_LARGE = 7
        const val CODE_MISMATCH = 12
        const val CODE_SPOOF_WARNING = 13
        const val CODE_ROTATED_LEFT = 15
        const val CODE_ROTATED_RIGHT = 17
        const val CODE_KEEP_STILL = 19
        const val CODE_EYES_CLOSED = 22
        const val CODE_MULTI_FACE = 27
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val aiExecutor = Executors.newFixedThreadPool(3)

    private val faceDetector: FaceDetector
    private val meshDetector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    private var recognizerInterpreter: Interpreter? = null
    private var antiSpoofInterpreter: Interpreter? = null
    private var recInputSize = 112
    private var recOutputSize = 192
    private var spoofInputSize = 256
    private var spoofOutputSize = 1

    private val enrolledTemplates = mutableListOf<FloatArray>()
    private var storageDir: File? = null
    private var encryptor: UnlockEncryptor? = null
    private var lastWorkingRotation: Int? = null

    private var enrollStage = 0
    private var stageSamples = 0
    private var stage1YawSign = 1f

    private var lastVerifiedEmbedding: FloatArray? = null
    private var lastVerifiedTimeMs: Long = 0L

    init {
        val savedRot = prefs.getInt(KEY_CACHED_ROTATION, -1)
        if (savedRot != -1) {
            lastWorkingRotation = savedRot
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        faceDetector = FaceDetection.getClient(options)

        val tfliteOpts = Interpreter.Options().apply { setNumThreads(4) }

        try {
            val recModel = FileUtil.loadMappedFile(appContext, "face_recognition.tflite")
            recognizerInterpreter = Interpreter(recModel, tfliteOpts).also {
                recInputSize = it.getInputTensor(0).shape()[1]
                recOutputSize = it.getOutputTensor(0).shape().last()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face_recognition.tflite", e)
        }

        try {
            val spoofModel = FileUtil.loadMappedFile(appContext, "minifasnet.tflite")
            antiSpoofInterpreter = Interpreter(spoofModel, tfliteOpts).also {
                spoofInputSize = it.getInputTensor(0).shape()[1]
                spoofOutputSize = it.getOutputTensor(0).shape().last()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load minifasnet.tflite", e)
        }
    }

    fun initStorage(path: String?, customEncryptor: UnlockEncryptor?) {
        if (path != null) {
            storageDir = File(path).apply { if (!exists()) mkdirs() }
        }
        encryptor = customEncryptor ?: CustomUnlockEncryptor()
        loadTemplates()
    }

    fun hasEnrolledFace(): Boolean = enrolledTemplates.isNotEmpty()

    fun resetEnrollProgress() {
        enrollStage = 0
        stageSamples = 0
        currentEnrollStage = 0
        currentEnrollPercent = 0f
        currentEnrollHint = "ضع وجهك داخل الإطار وانظر للأمام"
    }

    fun deleteAllTemplates() {
        enrolledTemplates.clear()
        lastVerifiedEmbedding = null
        resetEnrollProgress()
        storageDir?.let { File(it, DB_FILE_NAME).delete() }
    }

    fun enrollFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        if (enrollStage == 0 && stageSamples == 0 && enrolledTemplates.isNotEmpty()) {
            enrolledTemplates.clear()
        }

        val detected = detectFastNV21(nv21, width, height, rotation) ?: run {
            currentEnrollHint = "ضع وجهك داخل الإطار"
            return CODE_NO_FACE
        }

        if (detected.allFacesCount > 1) {
            currentEnrollHint = "يوجد أكثر من وجه في الكادر"
            return CODE_MULTI_FACE
        }

        val faceRatio = detected.scaledBox.width().toFloat() / detected.bitmap.width.toFloat()
        if (faceRatio < 0.15f) {
            currentEnrollHint = "اقترب قليلاً من الشاشة"
            return CODE_TOO_SMALL
        }
        if (faceRatio > 0.92f) {
            currentEnrollHint = "ابتعد قليلاً عن الشاشة"
            return CODE_TOO_LARGE
        }

        val yaw = detected.yaw
        val pitch = detected.pitch
        currentEnrollStage = enrollStage

        val angleMatched = when (enrollStage) {
            0 -> {
                currentEnrollHint = "انظر للأمام مباشرة بثبات"
                abs(yaw) <= 15f && abs(pitch) <= 16f
            }
            1 -> {
                currentEnrollHint = "أدر رأسك ببطء نحو اليمين"
                if (abs(yaw) in 10f..42f) {
                    if (stageSamples == 0) stage1YawSign = if (yaw >= 0) 1f else -1f
                    true
                } else false
            }
            2 -> {
                currentEnrollHint = "أدر رأسك ببطء نحو اليسار"
                (yaw * -stage1YawSign) in 10f..42f
            }
            3 -> {
                currentEnrollHint = "ارفع ذقنك قليلاً للأعلى"
                pitch in 6f..35f
            }
            4 -> {
                currentEnrollHint = "اخفض ذقنك قليلاً للأسفل"
                pitch in -35f..-6f
            }
            else -> true
        }

        if (!angleMatched) {
            return CODE_KEEP_STILL
        }

        val aligned = alignAndCrop(detected.bitmap, detected.scaledBox, detected.leftEye, detected.rightEye, recInputSize)
        val embedding = extractEmbedding(aligned) ?: return CODE_KEEP_STILL

        enrolledTemplates.add(embedding)
        stageSamples++

        val completedSteps = (enrollStage * 3 + stageSamples).toFloat()
        currentEnrollPercent = ((completedSteps / 15f) * 100f).coerceIn(4f, 100f)

        if (stageSamples >= 3) {
            enrollStage++
            stageSamples = 0
            currentEnrollStage = enrollStage
        }

        return if (enrollStage >= 5) {
            computeCentroidEmbedding()?.let { enrolledTemplates.add(0, it) }
            currentEnrollPercent = 100f
            currentEnrollHint = "اكتمل إعداد التعرّف على الوجه"
            saveTemplates()
            enrollStage = 0
            stageSamples = 0
            CODE_SUCCESS
        } else {
            currentEnrollHint = when (enrollStage) {
                1 -> "أدر رأسك ببطء نحو اليمين"
                2 -> "أدر رأسك ببطء نحو اليسار"
                3 -> "ارفع ذقنك قليلاً للأعلى"
                4 -> "اخفض ذقنك قليلاً للأسفل"
                else -> currentEnrollHint
            }
            CODE_KEEP_STILL
        }
    }

    fun verifyFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        if (enrolledTemplates.isEmpty()) return CODE_MISMATCH

        val requireEyesOpen = prefs.getBoolean(KEY_REQUIRE_EYES, true)
        val adaptiveLearning = prefs.getBoolean(KEY_ADAPTIVE, true)

        val detected = detectFastNV21(nv21, width, height, rotation) ?: return CODE_NO_FACE

        if (abs(detected.yaw) > OPTIMAL_MAX_HEAD_ANGLE || abs(detected.roll) > OPTIMAL_MAX_HEAD_ANGLE) {
            return if (detected.yaw > 0) CODE_ROTATED_LEFT else CODE_ROTATED_RIGHT
        }

        if (requireEyesOpen) {
            if (detected.leftEyeOpen < 0.45f || detected.rightEyeOpen < 0.45f) {
                return CODE_EYES_CLOSED
            }
        }

        val alignedFace = alignAndCrop(detected.bitmap, detected.scaledBox, detected.leftEye, detected.rightEye, recInputSize)

        if (computeTextureSharpness(alignedFace) < MIN_SKIN_SHARPNESS) {
            return CODE_SPOOF_WARNING
        }

        val meshFuture = aiExecutor.submit(Callable {
            runCatching {
                Tasks.await(meshDetector.process(InputImage.fromBitmap(detected.bitmap, 0)), 260, TimeUnit.MILLISECONDS)
            }.getOrNull()
        })

        val spoofFuture = aiExecutor.submit(Callable {
            if (antiSpoofInterpreter == null) return@Callable 1.0f
            val wideContext = cropExpanded(detected.bitmap, detected.scaledBox, scale = 2.1f, targetSize = spoofInputSize)
            runAntiSpoofing(wideContext)
        })

        val embedFuture = aiExecutor.submit(Callable {
            extractEmbedding(alignedFace)
        })

        val meshes = runCatching { meshFuture.get(280, TimeUnit.MILLISECONDS) }.getOrNull()
        val realScore = runCatching { spoofFuture.get(280, TimeUnit.MILLISECONDS) }.getOrDefault(1.0f)
        val currentEmbedding = runCatching { embedFuture.get(280, TimeUnit.MILLISECONDS) }.getOrNull()
            ?: return CODE_MISMATCH

        if (realScore < OPTIMAL_SPOOF_THRESHOLD) {
            lastVerifiedEmbedding = null
            return CODE_SPOOF_WARNING
        }

        if (!meshes.isNullOrEmpty()) {
            val pts = meshes[0].allPoints
            if (pts.size >= 468) {
                val noseZ = pts[1].position.z
                val leftCheekZ = pts[234].position.z
                val rightCheekZ = pts[454].position.z
                val cheekAvgZ = (leftCheekZ + rightCheekZ) * 0.5f
                val noseProtrusion = (cheekAvgZ - noseZ) / max(1f, detected.scaledBox.width().toFloat())
                if (noseProtrusion < MIN_NOSE_PROTRUSION_3D) {
                    lastVerifiedEmbedding = null
                    return CODE_SPOOF_WARNING
                }
            }
        }

        val scores = enrolledTemplates.map { cosineSimilarity(currentEmbedding, it) }
        val maxScore = scores.maxOrNull() ?: 0f
        val top3Avg = scores.sortedDescending().take(3).average().toFloat()
        val combinedScore = maxScore * 0.65f + top3Avg * 0.35f

        if (combinedScore < MATCH_COMBINED_THRESHOLD && maxScore < MATCH_SINGLE_MAX_THRESHOLD) {
            return CODE_MISMATCH
        }

        val now = System.currentTimeMillis()
        if (maxScore < INSTANT_UNLOCK_THRESHOLD) {
            val prevEmb = lastVerifiedEmbedding
            if (prevEmb == null || (now - lastVerifiedTimeMs) > 1500L) {
                lastVerifiedEmbedding = currentEmbedding
                lastVerifiedTimeMs = now
                return CODE_KEEP_STILL
            }
            val frameToFrameSim = cosineSimilarity(currentEmbedding, prevEmb)
            if (frameToFrameSim > 0.9998f) {
                lastVerifiedEmbedding = null
                return CODE_SPOOF_WARNING
            }
        }

        lastVerifiedEmbedding = null

        if (adaptiveLearning && maxScore in 0.58f..0.86f) {
            if (enrolledTemplates.size >= MAX_TEMPLATES) {
                enrolledTemplates.removeAt(5)
            }
            enrolledTemplates.add(currentEmbedding)
            saveTemplates()
        }

        return CODE_SUCCESS
    }

    private data class DetectedFrame(
        val bitmap: Bitmap,
        val scaledBox: Rect,
        val leftEye: PointF?,
        val rightEye: PointF?,
        val yaw: Float,
        val pitch: Float,
        val roll: Float,
        val leftEyeOpen: Float,
        val rightEyeOpen: Float,
        val allFacesCount: Int
    )

    private fun detectFastNV21(nv21: ByteArray, width: Int, height: Int, requestedRotation: Int): DetectedFrame? {
        val candidates = listOfNotNull(lastWorkingRotation, requestedRotation, 270, 90, 0, 180).distinct()

        for (rot in candidates) {
            val inputImage = InputImage.fromByteArray(nv21, width, height, rot, InputImage.IMAGE_FORMAT_NV21)
            val faces = runCatching {
                Tasks.await(faceDetector.process(inputImage), 250, TimeUnit.MILLISECONDS)
            }.getOrNull()

            if (!faces.isNullOrEmpty()) {
                if (lastWorkingRotation != rot) {
                    lastWorkingRotation = rot
                    prefs.edit().putInt(KEY_CACHED_ROTATION, rot).apply()
                }
                val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: continue
                val step = if (width >= 480) 2 else 1
                val raw = fastNv21ToDownsampledBitmap(nv21, width, height, step) ?: return null
                val rotated = rotateBitmap(raw, rot)
                val invScale = 1f / step.toFloat()

                val b = primaryFace.boundingBox
                val scaledBox = Rect(
                    (b.left * invScale).toInt(),
                    (b.top * invScale).toInt(),
                    (b.right * invScale).toInt(),
                    (b.bottom * invScale).toInt()
                )
                val lEye = primaryFace.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let {
                    PointF(it.x * invScale, it.y * invScale)
                }
                val rEye = primaryFace.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let {
                    PointF(it.x * invScale, it.y * invScale)
                }

                return DetectedFrame(
                    bitmap = rotated,
                    scaledBox = scaledBox,
                    leftEye = lEye,
                    rightEye = rEye,
                    yaw = primaryFace.headEulerAngleY,
                    pitch = primaryFace.headEulerAngleX,
                    roll = primaryFace.headEulerAngleZ,
                    leftEyeOpen = primaryFace.leftEyeOpenProbability ?: 1f,
                    rightEyeOpen = primaryFace.rightEyeOpenProbability ?: 1f,
                    allFacesCount = faces.size
                )
            }
        }
        return null
    }

    private fun computeCentroidEmbedding(): FloatArray? {
        if (enrolledTemplates.isEmpty()) return null
        val dim = enrolledTemplates[0].size
        val sum = FloatArray(dim)
        for (vec in enrolledTemplates) {
            if (vec.size == dim) {
                for (i in 0 until dim) sum[i] += vec[i]
            }
        }
        return l2Normalize(sum)
    }

    private fun computeTextureSharpness(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var laplacianSum = 0.0
        var count = 0
        for (y in 2 until h - 2 step 2) {
            for (x in 2 until w - 2 step 2) {
                val c = (pixels[y * w + x] shr 8) and 0xFF
                val l = (pixels[y * w + (x - 1)] shr 8) and 0xFF
                val r = (pixels[y * w + (x + 1)] shr 8) and 0xFF
                val t = (pixels[(y - 1) * w + x] shr 8) and 0xFF
                val b = (pixels[(y + 1) * w + x] shr 8) and 0xFF
                val lap = abs(4 * c - l - r - t - b)
                laplacianSum += lap
                count++
            }
        }
        return if (count > 0) (laplacianSum / count).toFloat() else 100f
    }

    private fun runAntiSpoofing(bitmap: Bitmap): Float {
        val interpreter = antiSpoofInterpreter ?: return 1.0f
        val normalize01 = (spoofOutputSize == 1 || spoofInputSize >= 224)
        val inputBuffer = bitmapToFloatBuffer(bitmap, mode = if (normalize01) 1 else 0)
        val output = Array(1) { FloatArray(spoofOutputSize) }
        interpreter.run(inputBuffer, output)

        val logits = output[0]
        return when {
            spoofOutputSize == 1 -> 1.0f - logits[0].coerceIn(0f, 1f)
            spoofOutputSize >= 3 -> {
                val expReal = exp(logits[1].toDouble())
                val sumExp = logits.sumOf { exp(it.toDouble()) }
                (expReal / sumExp).toFloat()
            }
            else -> {
                val expReal = exp(logits[0].toDouble())
                val sumExp = exp(logits[0].toDouble()) + exp(logits[1].toDouble())
                (expReal / sumExp).toFloat()
            }
        }
    }

    private fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        val interpreter = recognizerInterpreter ?: return null
        val inputBuffer = bitmapToFloatBuffer(bitmap, mode = 2)
        val output = Array(1) { FloatArray(recOutputSize) }
        interpreter.run(inputBuffer, output)
        return l2Normalize(output[0])
    }

    private fun alignAndCrop(source: Bitmap, box: Rect, leftEye: PointF?, rightEye: PointF?, targetSize: Int): Bitmap {
        var rotated = source
        if (leftEye != null && rightEye != null) {
            val angle = Math.toDegrees(
                atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble())
            ).toFloat()
            val matrix = Matrix().apply {
                postRotate(-angle, box.exactCenterX(), box.exactCenterY())
            }
            rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
        return cropExpanded(rotated, box, scale = 1.15f, targetSize = targetSize)
    }

    private fun cropExpanded(bitmap: Bitmap, box: Rect, scale: Float, targetSize: Int): Bitmap {
        val cx = box.centerX()
        val cy = box.centerY()
        val size = (max(box.width(), box.height()) * scale).toInt()
        val left = max(0, cx - size / 2)
        val top = max(0, cy - size / 2)
        val w = min(bitmap.width - left, size).coerceAtLeast(1)
        val h = min(bitmap.height - top, size).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, left, top, w, h)
        return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
    }

    private fun fastNv21ToDownsampledBitmap(nv21: ByteArray, width: Int, height: Int, step: Int): Bitmap? {
        return try {
            val frameSize = width * height
            if (nv21.size < frameSize) return null
            val outW = width / step
            val outH = height / step
            val argb = IntArray(outW * outH)
            var outIdx = 0

            for (j in 0 until height step step) {
                if (outIdx >= argb.size) break
                val uvRow = frameSize + (j shr 1) * width
                for (i in 0 until width step step) {
                    if (outIdx >= argb.size) break
                    val yIdx = j * width + i
                    val uvIdx = uvRow + (i and 1.inv())
                    var y = (0xff and nv21[yIdx].toInt()) - 16
                    if (y < 0) y = 0
                    val v = if (uvIdx < nv21.size) (0xff and nv21[uvIdx].toInt()) - 128 else 0
                    val u = if (uvIdx + 1 < nv21.size) (0xff and nv21[uvIdx + 1].toInt()) - 128 else 0

                    val y1192 = 1192 * y
                    val r = (y1192 + 1634 * v).coerceIn(0, 262143)
                    val g = (y1192 - 833 * v - 400 * u).coerceIn(0, 262143)
                    val b = (y1192 + 2066 * u).coerceIn(0, 262143)
                    argb[outIdx++] = -0x1000000 or ((r shl 6) and 0xff0000) or ((g shr 2) and 0xff00) or ((b shr 10) and 0xff)
                }
            }
            Bitmap.createBitmap(argb, outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }

    private fun rotateBitmap(raw: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, mode: Int): ByteBuffer {
        val size = bitmap.width
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            when (mode) {
                1 -> {
                    buffer.putFloat(r / 255.0f)
                    buffer.putFloat(g / 255.0f)
                    buffer.putFloat(b / 255.0f)
                }
                2 -> {
                    buffer.putFloat((r - 127.5f) / 128.0f)
                    buffer.putFloat((g - 127.5f) / 128.0f)
                    buffer.putFloat((b - 127.5f) / 128.0f)
                }
                else -> {
                    buffer.putFloat(r)
                    buffer.putFloat(g)
                    buffer.putFloat(b)
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0f) FloatArray(vec.size) { vec[it] / norm } else vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun saveTemplates() {
        val dir = storageDir ?: return
        try {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeInt(enrolledTemplates.size)
            for (vec in enrolledTemplates) {
                dos.writeInt(vec.size)
                for (f in vec) dos.writeFloat(f)
            }
            dos.flush()
            val rawBytes = baos.toByteArray()
            val finalBytes = encryptor?.encrypt(rawBytes)?.takeIf { it.isNotEmpty() } ?: rawBytes
            File(dir, DB_FILE_NAME).writeBytes(finalBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save templates", e)
        }
    }

    private fun loadTemplates() {
        val dir = storageDir ?: return
        val file = File(dir, DB_FILE_NAME)
        if (!file.exists()) return
        try {
            val fileBytes = file.readBytes()
            val rawBytes = encryptor?.decrypt(fileBytes)?.takeIf { it.isNotEmpty() } ?: fileBytes
            val dis = DataInputStream(rawBytes.inputStream())
            val count = dis.readInt()
            enrolledTemplates.clear()
            repeat(count) {
                val len = dis.readInt()
                val vec = FloatArray(len) { dis.readFloat() }
                enrolledTemplates.add(vec)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }
}

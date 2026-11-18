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
import java.util.concurrent.TimeUnit
import kotlin.math.*

class SmartFaceEngine(context: Context) {

    companion object {
        private const val TAG = "SmartFaceEngine"
        private const val DB_FILE_NAME = "smart_face_templates_v2.enc"
        const val PREFS_NAME = "smart_face_prefs"

        // مفاتيح تجربة الاستخدام القابلة للتحكم من التطبيق
        const val KEY_REQUIRE_EYES = "req_eyes_open"
        const val KEY_ADAPTIVE = "adaptive_learning"
        const val KEY_NIGHT_LIGHT = "night_fill_light"
        const val KEY_VIBRATE = "vibrate_on_unlock"

        // القيم المثالية المعايرة تلقائياً لأعلى سرعة وأمان على التابلت
        private const val OPTIMAL_MATCH_THRESHOLD = 0.54f
        private const val OPTIMAL_SPOOF_THRESHOLD = 0.42f
        private const val OPTIMAL_3D_DEPTH_MIN = 0.042f
        private const val OPTIMAL_MAX_HEAD_ANGLE = 32f

        @JvmField var currentEnrollHint: String = "الخطوة 1/5: انظر للأمام مباشرة"
        @JvmField var currentEnrollPercent: Float = 0f

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
    private var workingRotationOverride: Int? = null

    private var enrollStage = 0
    private var stageSamples = 0
    private var stage1YawSign = 1f

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.18f)
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
        currentEnrollPercent = 0f
        currentEnrollHint = "الخطوة 1/5: انظر للأمام مباشرة"
    }

    fun deleteAllTemplates() {
        enrolledTemplates.clear()
        resetEnrollProgress()
        storageDir?.let { File(it, DB_FILE_NAME).delete() }
    }

    fun enrollFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        if (enrollStage == 0 && stageSamples == 0 && enrolledTemplates.isNotEmpty()) {
            enrolledTemplates.clear()
        }

        val (bitmap, faces) = detectFastNV21(nv21, width, height, rotation)
        if (bitmap == null || faces.isEmpty()) {
            currentEnrollHint = "ضع وجهك بوضوح أمام الكاميرا"
            return CODE_NO_FACE
        }
        if (faces.size > 1) {
            currentEnrollHint = "يوجد أكثر من وجه! تأكد أنك وحدك"
            return CODE_MULTI_FACE
        }

        val face = faces[0]
        val faceRatio = face.boundingBox.width().toFloat() / bitmap.width.toFloat()
        if (faceRatio < 0.18f) {
            currentEnrollHint = "اقترب قليلاً من الكاميرا"
            return CODE_TOO_SMALL
        }
        if (faceRatio > 0.90f) {
            currentEnrollHint = "ابتعد قليلاً عن الكاميرا"
            return CODE_TOO_LARGE
        }

        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX

        val angleMatched = when (enrollStage) {
            0 -> {
                currentEnrollHint = "الخطوة 1/5: انظر للأمام مباشرة"
                abs(yaw) <= 13f && abs(pitch) <= 14f
            }
            1 -> {
                currentEnrollHint = "الخطوة 2/5: أدر وجهك قليلاً لليمين ➔"
                if (abs(yaw) in 12f..38f) {
                    if (stageSamples == 0) stage1YawSign = if (yaw >= 0) 1f else -1f
                    true
                } else false
            }
            2 -> {
                currentEnrollHint = "الخطوة 3/5: أدر وجهك قليلاً لليسار ⬅"
                (yaw * -stage1YawSign) in 12f..38f
            }
            3 -> {
                currentEnrollHint = "الخطوة 4/5: ارفع رأسك قليلاً للأعلى ⬆"
                pitch in 8f..32f
            }
            4 -> {
                currentEnrollHint = "الخطوة 5/5: اخفض رأسك قليلاً للأسفل ⬇"
                pitch in -32f..-8f
            }
            else -> true
        }

        if (!angleMatched) {
            return CODE_KEEP_STILL
        }

        val aligned = alignAndCrop(bitmap, face, recInputSize)
        val embedding = extractEmbedding(aligned) ?: return CODE_KEEP_STILL

        enrolledTemplates.add(embedding)
        stageSamples++

        val completedSteps = (enrollStage * 2 + stageSamples).toFloat()
        currentEnrollPercent = ((completedSteps / 10f) * 100f).coerceIn(5f, 100f)

        if (stageSamples >= 2) {
            enrollStage++
            stageSamples = 0
        }

        return if (enrollStage >= 5) {
            currentEnrollPercent = 100f
            currentEnrollHint = "اكتمل المسح ثلاثي الأبعاد بنجاح! ✓"
            saveTemplates()
            enrollStage = 0
            stageSamples = 0
            CODE_SUCCESS
        } else {
            currentEnrollHint = when (enrollStage) {
                1 -> "ممتاز! الآن أدر وجهك قليلاً لليمين ➔"
                2 -> "رائع! الآن أدر وجهك قليلاً لليسار ⬅"
                3 -> "جميل! الآن ارفع رأسك قليلاً للأعلى ⬆"
                4 -> "الخطوة الأخيرة: اخفض رأسك قليلاً للأسفل ⬇"
                else -> currentEnrollHint
            }
            CODE_KEEP_STILL
        }
    }

    fun verifyFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        if (enrolledTemplates.isEmpty()) return CODE_MISMATCH

        val requireEyesOpen = prefs.getBoolean(KEY_REQUIRE_EYES, true)
        val adaptiveLearning = prefs.getBoolean(KEY_ADAPTIVE, true)

        val (bitmap, faces) = detectFastNV21(nv21, width, height, rotation)
        if (bitmap == null || faces.isEmpty()) return CODE_NO_FACE

        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return CODE_NO_FACE

        if (abs(face.headEulerAngleY) > OPTIMAL_MAX_HEAD_ANGLE || abs(face.headEulerAngleZ) > OPTIMAL_MAX_HEAD_ANGLE) {
            return if (face.headEulerAngleY > 0) CODE_ROTATED_LEFT else CODE_ROTATED_RIGHT
        }

        if (requireEyesOpen) {
            val left = face.leftEyeOpenProbability ?: 1f
            val right = face.rightEyeOpenProbability ?: 1f
            if (left < 0.50f || right < 0.50f) {
                return CODE_EYES_CLOSED
            }
        }

        // فحص العمق ثلاثي الأبعاد (مفعّل دائماً ومعاير لمسافة التابلت)
        val meshes = runCatching {
            Tasks.await(meshDetector.process(InputImage.fromBitmap(bitmap, 0)), 400, TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (!meshes.isNullOrEmpty()) {
            val points = meshes[0].allPoints
            if (points.size >= 400) {
                val minZ = points.minOf { it.position.z }
                val maxZ = points.maxOf { it.position.z }
                val depthSpan = (maxZ - minZ) / max(1, face.boundingBox.width())
                if (depthSpan < OPTIMAL_3D_DEPTH_MIN) {
                    return CODE_SPOOF_WARNING
                }
            }
        }

        // كشف التزييف بالذكاء الاصطناعي (مفعّل دائماً)
        if (antiSpoofInterpreter != null) {
            val spoofCrop = cropExpanded(bitmap, face.boundingBox, scale = 2.0f, targetSize = spoofInputSize)
            val realScore = runAntiSpoofing(spoofCrop)
            if (realScore < OPTIMAL_SPOOF_THRESHOLD) {
                return CODE_SPOOF_WARNING
            }
        }

        // مطابقة البصمة
        val aligned = alignAndCrop(bitmap, face, recInputSize)
        val currentEmbedding = extractEmbedding(aligned) ?: return CODE_MISMATCH

        val bestScore = enrolledTemplates.maxOfOrNull { cosineSimilarity(currentEmbedding, it) } ?: 0f

        return if (bestScore >= OPTIMAL_MATCH_THRESHOLD) {
            if (adaptiveLearning && bestScore in 0.68f..0.95f && enrolledTemplates.size >= 5) {
                enrolledTemplates.removeAt(2)
                enrolledTemplates.add(currentEmbedding)
                saveTemplates()
            }
            CODE_SUCCESS
        } else {
            CODE_MISMATCH
        }
    }

    /**
     * اكتشاف فائق السرعة مباشرة على بيانات NV21 الخام دون ضغط صور متكرر
     */
    private fun detectFastNV21(nv21: ByteArray, width: Int, height: Int, requestedRotation: Int): Pair<Bitmap?, List<Face>> {
        val candidates = listOfNotNull(workingRotationOverride, requestedRotation, 270, 90, 0, 180).distinct()

        for (rot in candidates) {
            val inputImage = InputImage.fromByteArray(nv21, width, height, rot, InputImage.IMAGE_FORMAT_NV21)
            val faces = runCatching {
                Tasks.await(faceDetector.process(inputImage), 400, TimeUnit.MILLISECONDS)
            }.getOrNull()

            if (!faces.isNullOrEmpty()) {
                workingRotationOverride = rot
                val raw = nv21ToBitmap(nv21, width, height) ?: return Pair(null, emptyList())
                val rotated = rotateBitmap(raw, rot)
                return Pair(rotated, faces)
            }
        }
        return Pair(null, emptyList())
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

    private fun alignAndCrop(source: Bitmap, face: Face, targetSize: Int): Bitmap {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        var rotated = source

        if (leftEye != null && rightEye != null) {
            val angle = Math.toDegrees(
                atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble())
            ).toFloat()
            val matrix = Matrix().apply {
                postRotate(-angle, face.boundingBox.exactCenterX(), face.boundingBox.exactCenterY())
            }
            rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
        return cropExpanded(rotated, face.boundingBox, scale = 1.15f, targetSize = targetSize)
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

    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

package ax.nd.faceunlock.backend

import android.content.Context
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
        private const val MAX_TEMPLATES = 8
        private const val DB_FILE_NAME = "smart_face_templates.enc"

        // أكواد الرسائل المتوافقة مع شاشة القفل وشاشة التسجيل في UniversalAuth
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

    // إعدادات قابلة للتحكم من واجهة التطبيق لاحقاً
    var requireEyesOpen: Boolean = true
    var enableAntiSpoof: Boolean = true
    var enable3DMeshCheck: Boolean = true
    var adaptiveLearning: Boolean = true
    var matchThreshold: Float = 0.72f
    var antiSpoofThreshold: Float = 0.75f

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
    private var spoofInputSize = 80
    private var spoofOutputSize = 3

    private val enrolledTemplates = mutableListOf<FloatArray>()
    private var storageDir: File? = null
    private var encryptor: UnlockEncryptor? = null

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.22f)
            .build()
        faceDetector = FaceDetection.getClient(options)

        val tfliteOpts = Interpreter.Options().apply { setNumThreads(4) }

        try {
            val recModel = FileUtil.loadMappedFile(context, "face_recognition.tflite")
            recognizerInterpreter = Interpreter(recModel, tfliteOpts).also {
                recInputSize = it.getInputTensor(0).shape()[1]
                recOutputSize = it.getOutputTensor(0).shape().last()
                Log.i(TAG, "Loaded face_recognition.tflite: input=$recInputSize, output=$recOutputSize")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face_recognition.tflite", e)
        }

        try {
            val spoofModel = FileUtil.loadMappedFile(context, "minifasnet.tflite")
            antiSpoofInterpreter = Interpreter(spoofModel, tfliteOpts).also {
                spoofInputSize = it.getInputTensor(0).shape()[1]
                spoofOutputSize = it.getOutputTensor(0).shape().last()
                Log.i(TAG, "Loaded minifasnet.tflite: input=$spoofInputSize, output=$spoofOutputSize")
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

    fun deleteAllTemplates() {
        enrolledTemplates.clear()
        storageDir?.let { File(it, DB_FILE_NAME).delete() }
    }

    /**
     * تسجيل بصمة وجه جديدة (يُستدعى أثناء إعداد الوجه لأول مرة)
     */
    fun enrollFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        val bitmap = nv21ToRotatedBitmap(nv21, width, height, rotation) ?: return CODE_NO_FACE
        val faces = runCatching {
            Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)), 1500, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return CODE_NO_FACE

        if (faces.isEmpty()) return CODE_NO_FACE
        if (faces.size > 1) return CODE_MULTI_FACE

        val face = faces[0]
        val faceRatio = face.boundingBox.width().toFloat() / bitmap.width.toFloat()
        if (faceRatio < 0.22f) return CODE_TOO_SMALL
        if (faceRatio > 0.85f) return CODE_TOO_LARGE

        if (face.headEulerAngleY > 22f) return CODE_ROTATED_LEFT
        if (face.headEulerAngleY < -22f) return CODE_ROTATED_RIGHT

        if (requireEyesOpen) {
            val left = face.leftEyeOpenProbability ?: 1f
            val right = face.rightEyeOpenProbability ?: 1f
            if (left < 0.55f || right < 0.55f) return CODE_EYES_CLOSED
        }

        val aligned = alignAndCrop(bitmap, face, recInputSize)
        val embedding = extractEmbedding(aligned) ?: return CODE_KEEP_STILL

        // تجنب حفظ زوايا متطابقة تماماً لتنويع البصمات المحفوظة
        if (enrolledTemplates.isNotEmpty()) {
            val maxSim = enrolledTemplates.maxOf { cosineSimilarity(embedding, it) }
            if (maxSim > 0.96f && enrolledTemplates.size >= 3) {
                return CODE_KEEP_STILL
            }
        }

        if (enrolledTemplates.size >= MAX_TEMPLATES) {
            enrolledTemplates.removeAt(0)
        }
        enrolledTemplates.add(embedding)
        saveTemplates()
        return CODE_SUCCESS
    }

    /**
     * التحقق من الوجه لفتح القفل (سلسلة ذكاء اصطناعي متدرجة للسرعة والأمان)
     */
    fun verifyFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        if (enrolledTemplates.isEmpty()) return CODE_MISMATCH

        val bitmap = nv21ToRotatedBitmap(nv21, width, height, rotation) ?: return CODE_NO_FACE
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // 1. ML Kit Face Detection: سرعة فائقة + فحص الزاوية وفتح العينين
        val faces = runCatching {
            Tasks.await(faceDetector.process(inputImage), 1000, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return CODE_NO_FACE

        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return CODE_NO_FACE

        if (abs(face.headEulerAngleY) > 25f || abs(face.headEulerAngleZ) > 25f) {
            return if (face.headEulerAngleY > 0) CODE_ROTATED_LEFT else CODE_ROTATED_RIGHT
        }

        if (requireEyesOpen) {
            val left = face.leftEyeOpenProbability ?: 1f
            val right = face.rightEyeOpenProbability ?: 1f
            if (left < 0.60f || right < 0.60f) {
                return CODE_EYES_CLOSED
            }
        }

        // 2. ML Kit 3D Face Mesh: التحقق من العمق ثلاثي الأبعاد للوجه (468 نقطة)
        if (enable3DMeshCheck) {
            val meshes = runCatching {
                Tasks.await(meshDetector.process(inputImage), 800, TimeUnit.MILLISECONDS)
            }.getOrNull()
            if (!meshes.isNullOrEmpty()) {
                val points = meshes[0].allPoints
                if (points.size >= 400) {
                    val minZ = points.minOf { it.position.z }
                    val maxZ = points.maxOf { it.position.z }
                    val depthSpan = (maxZ - minZ) / max(1, face.boundingBox.width())
                    if (depthSpan < 0.12f) {
                        return CODE_SPOOF_WARNING
                    }
                }
            }
        }

        // 3. MiniFASNet Anti-Spoofing: كشف الصور المطبوعة وشاشات الهواتف
        if (enableAntiSpoof && antiSpoofInterpreter != null) {
            val spoofCrop = cropExpanded(bitmap, face.boundingBox, scale = 2.7f, targetSize = spoofInputSize)
            val realScore = runAntiSpoofing(spoofCrop)
            if (realScore < antiSpoofThreshold) {
                Log.w(TAG, "Spoof blocked! Score: $realScore")
                return CODE_SPOOF_WARNING
            }
        }

        // 4. InsightFace (MobileFaceNet / GhostFaceNet): استخراج البصمة والمطابقة
        val aligned = alignAndCrop(bitmap, face, recInputSize)
        val currentEmbedding = extractEmbedding(aligned) ?: return CODE_MISMATCH

        val bestScore = enrolledTemplates.maxOfOrNull { cosineSimilarity(currentEmbedding, it) } ?: 0f
        Log.d(TAG, "Face match score: $bestScore (threshold: $matchThreshold)")

        return if (bestScore >= matchThreshold) {
            // التعلم التكيفي: تحديث البصمات تلقائياً عند التطابق القوي جداً في إضاءة/زاوية جديدة
            if (adaptiveLearning && bestScore in 0.85f..0.95f && enrolledTemplates.size >= 4) {
                enrolledTemplates.removeAt(1) // الحفاظ على البصمة الأصلية الأولى وتحديث الثانوية
                enrolledTemplates.add(currentEmbedding)
                saveTemplates()
            }
            CODE_SUCCESS
        } else {
            CODE_MISMATCH
        }
    }

    private fun runAntiSpoofing(bitmap: Bitmap): Float {
        val interpreter = antiSpoofInterpreter ?: return 1.0f
        val inputBuffer = bitmapToFloatBuffer(bitmap, normalize = false)
        val output = Array(1) { FloatArray(spoofOutputSize) }
        interpreter.run(inputBuffer, output)

        val logits = output[0]
        return if (spoofOutputSize >= 3) {
            val expReal = exp(logits[1].toDouble())
            val sumExp = logits.sumOf { exp(it.toDouble()) }
            (expReal / sumExp).toFloat()
        } else if (spoofOutputSize == 2) {
            val expReal = exp(logits[0].toDouble())
            val sumExp = exp(logits[0].toDouble()) + exp(logits[1].toDouble())
            (expReal / sumExp).toFloat()
        } else {
            1f / (1f + exp(-logits[0]))
        }
    }

    private fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        val interpreter = recognizerInterpreter ?: return null
        val inputBuffer = bitmapToFloatBuffer(bitmap, normalize = true)
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

    private fun nv21ToRotatedBitmap(nv21: ByteArray, width: Int, height: Int, rotation: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val bytes = out.toByteArray()
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (rotation == 0) return raw
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "NV21 conversion failed", e)
            null
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, normalize: Boolean): ByteBuffer {
        val size = bitmap.width
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            if (normalize) {
                buffer.putFloat((r - 127.5f) / 128.0f)
                buffer.putFloat((g - 127.5f) / 128.0f)
                buffer.putFloat((b - 127.5f) / 128.0f)
            } else {
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
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
            Log.e(TAG, "Failed to save encrypted face templates", e)
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
            Log.i(TAG, "Loaded ${enrolledTemplates.size} encrypted face templates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load encrypted face templates", e)
        }
    }
}

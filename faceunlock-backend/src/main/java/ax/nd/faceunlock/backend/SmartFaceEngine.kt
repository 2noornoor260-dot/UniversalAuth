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

    interface VerificationFeedbackListener {
        fun onScanningState(faceDetected: Boolean, confidence: Float)
        fun onVerifySuccess()
        fun onVerifyRejected(reasonCode: Int)
    }

    companion object {
        private const val TAG = "SmartFaceEngine"
        private const val MAX_TEMPLATES = 18
        private const val DB_FILE_NAME = "smart_face_templates_v6.enc"
        const val PREFS_NAME = "smart_face_prefs"
        private const val KEY_CACHED_ROTATION = "cached_camera_rotation"

        const val KEY_REQUIRE_EYES = "req_eyes_open"
        const val KEY_ADAPTIVE = "adaptive_learning"
        const val KEY_NIGHT_LIGHT = "night_fill_light"
        const val KEY_UNLOCK_CHIME_TYPE = "unlock_chime_type"
        const val KEY_CAPSULE_ANIM = "dynamic_capsule_anim"

        // عتبات متوازنة بدقة: تضمن فتح القفل من أول محاولة للمستخدم الحقيقي وتمنع الصور الورقية والشاشات
        private const val MATCH_COMBINED_THRESHOLD = 0.56f
        private const val MATCH_SINGLE_MAX_THRESHOLD = 0.59f
        private const val SPOOF_REAL_THRESHOLD = 0.52f
        private const val MIN_3D_NOSE_PLANE_RATIO = 0.026f
        private const val MIN_3D_VERTICAL_CURVE_RATIO = 0.014f
        private const val MIN_3D_EYE_SOCKET_RATIO = 0.010f
        private const val MIN_SKIN_SHARPNESS = 2.6f
        private const val MIN_CHROMA_STD = 2.2f
        private const val OPTIMAL_MAX_HEAD_ANGLE = 36f

        @JvmField var currentEnrollHint: String = "حرّك رأسك ببطء نحو اليمين"
        @JvmField var currentEnrollPercent: Float = 0f
        @JvmField var currentEnrollStage: Int = 0
        @JvmField var lastFrameLuma: Float = -1f

        @Volatile
        private var feedbackListener: VerificationFeedbackListener? = null

        @JvmStatic
        fun setFeedbackListener(listener: VerificationFeedbackListener?) {
            feedbackListener = listener
        }

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

    private data class EnrolledPoseTemplate(
        val poseId: Int, // 0=Right, 1=Left, 2=Up, 3=Down, 4=Frontal/Centroid/Adaptive
        val embedding: FloatArray,
        val noseProtrusion3D: Float
    )

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
    private var spoofInputSize = 80
    private var spoofOutputSize = 3

    private val enrolledTemplates = mutableListOf<EnrolledPoseTemplate>()
    private var storageDir: File? = null
    private var encryptor: UnlockEncryptor? = null
    private var lastWorkingRotation: Int? = null

    // مراحل التسجيل الـ 4: 0=يمين، 1=يسار، 2=أعلى، 3=أسفل (+ التقاط بصمة أمامية تلقائية لضمان الفتح الفوري)
    private var enrollStage = 0
    private var frontalCapturedInSession = false
    private var stage0YawSign = 1f
    private var lastEnrollCaptureTimeMs = 0L

    private var prevFrameEmbedding: FloatArray? = null
    private var prevFrameNoseZ: Float = 0f
    private var prevFrameTimeMs: Long = 0L

    private var verifySessionStartMs: Long = 0L
    private var continuousFailStartMs: Long = 0L
    private var rejectionCycleCount: Int = 0

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
        frontalCapturedInSession = false
        currentEnrollStage = 0
        currentEnrollPercent = 0f
        currentEnrollHint = "حرّك رأسك ببطء نحو اليمين"
    }

    fun deleteAllTemplates() {
        enrolledTemplates.clear()
        prevFrameEmbedding = null
        resetEnrollProgress()
        storageDir?.let { File(it, DB_FILE_NAME).delete() }
    }

    /**
     * تسجيل من 4 اتجاهات واضحة (يمين، يسار، فوق، تحت) مع التقاط مرجع أمامي تلقائي
     * لضمان سرعة ودقة التعرّف من المواجهة المباشرة ومن جميع الزوايا
     */
    fun enrollFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        updateSceneLuma(nv21, width, height)

        if (enrollStage == 0 && !frontalCapturedInSession && enrolledTemplates.isNotEmpty()) {
            enrolledTemplates.clear()
        }

        val detected = detectFastNV21(nv21, width, height, rotation) ?: run {
            currentEnrollHint = "ضع وجهك داخل الدائرة"
            return CODE_NO_FACE
        }

        if (detected.allFacesCount > 1) {
            currentEnrollHint = "يوجد أكثر من وجه في الكادر"
            return CODE_MULTI_FACE
        }

        val faceRatio = detected.scaledBox.width().toFloat() / detected.bitmap.width.toFloat()
        if (faceRatio < 0.15f) {
            currentEnrollHint = "اقترب قليلاً من الكاميرا"
            return CODE_TOO_SMALL
        }
        if (faceRatio > 0.92f) {
            currentEnrollHint = "ابتعد قليلاً عن الكاميرا"
            return CODE_TOO_LARGE
        }

        val yaw = detected.yaw
        val pitch = detected.pitch
        currentEnrollStage = enrollStage

        // التقاط بصمة أمامية مرجعية تلقائياً أثناء النظر للكاميرا قبل أو أثناء التحريك
        if (!frontalCapturedInSession && abs(yaw) <= 13f && abs(pitch) <= 13f) {
            val depth3D = measureTrue3DFacialDepth(detected.bitmap, detected.scaledBox)
            if (depth3D != null && depth3D.isTrue3DFace) {
                val aligned = alignAndCrop(detected.bitmap, detected.scaledBox, detected.leftEye, detected.rightEye, recInputSize)
                extractEmbedding(aligned)?.let { frontEmb ->
                    enrolledTemplates.add(EnrolledPoseTemplate(4, frontEmb, depth3D.nosePlaneRatio))
                    frontalCapturedInSession = true
                }
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastEnrollCaptureTimeMs < 380L) {
            return CODE_KEEP_STILL
        }

        val angleMatched = when (enrollStage) {
            0 -> {
                currentEnrollHint = "حرّك رأسك ببطء نحو اليمين"
                if (abs(yaw) in 12f..44f && abs(pitch) <= 22f) {
                    stage0YawSign = if (yaw >= 0) 1f else -1f
                    true
                } else false
            }
            1 -> {
                currentEnrollHint = "حرّك رأسك ببطء نحو اليسار"
                (yaw * -stage0YawSign) in 12f..44f && abs(pitch) <= 22f
            }
            2 -> {
                currentEnrollHint = "ارفع رأسك ببطء للأعلى"
                pitch in 9f..38f && abs(yaw) <= 22f
            }
            3 -> {
                currentEnrollHint = "اخفض رأسك ببطء للأسفل"
                pitch in -38f..-9f && abs(yaw) <= 22f
            }
            else -> true
        }

        if (!angleMatched) {
            return CODE_KEEP_STILL
        }

        val depth3D = measureTrue3DFacialDepth(detected.bitmap, detected.scaledBox)
        if (depth3D == null || !depth3D.isTrue3DFace) {
            currentEnrollHint = "تأكد من وضوح الإضاءة وتوجّه وجهك للكاميرا"
            return CODE_KEEP_STILL
        }

        val aligned = alignAndCrop(detected.bitmap, detected.scaledBox, detected.leftEye, detected.rightEye, recInputSize)
        val embedding = extractEmbedding(aligned) ?: return CODE_KEEP_STILL

        enrolledTemplates.add(
            EnrolledPoseTemplate(
                poseId = enrollStage,
                embedding = embedding,
                noseProtrusion3D = depth3D.nosePlaneRatio
            )
        )

        lastEnrollCaptureTimeMs = now
        enrollStage++
        currentEnrollStage = enrollStage
        currentEnrollPercent = ((enrollStage / 4f) * 100f).coerceIn(0f, 100f)

        return if (enrollStage >= 4) {
            computeCentroidEmbedding()?.let { centroid ->
                val avgNose3D = enrolledTemplates.map { it.noseProtrusion3D }.average().toFloat()
                enrolledTemplates.add(0, EnrolledPoseTemplate(4, centroid, avgNose3D))
            }
            currentEnrollPercent = 100f
            currentEnrollHint = "اكتمل إعداد التعرّف ثلاثي الأبعاد"
            saveTemplates()
            enrollStage = 0
            frontalCapturedInSession = false
            CODE_SUCCESS
        } else {
            currentEnrollHint = when (enrollStage) {
                1 -> "حرّك رأسك ببطء نحو اليسار"
                2 -> "ارفع رأسك ببطء للأعلى"
                3 -> "اخفض رأسك ببطء للأسفل"
                else -> currentEnrollHint
            }
            CODE_KEEP_STILL
        }
    }

    /**
     * محرك التحقق الذكي عالي السرعة والأمان:
     * - يفتح من أول محاولة للمستخدم الحقيقي
     * - يرفض الصور الورقية والشاشات عبر العمق ثلاثي الأبعاد + MiniFASNet + تحليل نسيج الجلد + التحقق الزمني
     * - يرسل إشارة الرفض البصري للكبسولة مرة واحدة فقط كل محاولتي رفض
     */
    fun verifyFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        updateSceneLuma(nv21, width, height)

        val now = System.currentTimeMillis()
        if (verifySessionStartMs == 0L || (now - prevFrameTimeMs) > 2500L) {
            verifySessionStartMs = now
            continuousFailStartMs = 0L
        }

        if (enrolledTemplates.isEmpty()) return CODE_MISMATCH

        val requireEyesOpen = prefs.getBoolean(KEY_REQUIRE_EYES, true)
        val adaptiveLearning = prefs.getBoolean(KEY_ADAPTIVE, true)

        val detected = detectFastNV21(nv21, width, height, rotation) ?: run {
            feedbackListener?.onScanningState(faceDetected = false, confidence = 0f)
            return CODE_NO_FACE
        }

        feedbackListener?.onScanningState(faceDetected = true, confidence = 0.40f)

        // فترة استقرار تعريض الكاميرا في أول 240 مللي ثانية
        if (now - verifySessionStartMs < 240L) {
            return CODE_KEEP_STILL
        }

        if (abs(detected.yaw) > OPTIMAL_MAX_HEAD_ANGLE || abs(detected.roll) > OPTIMAL_MAX_HEAD_ANGLE) {
            return if (detected.yaw > 0) CODE_ROTATED_LEFT else CODE_ROTATED_RIGHT
        }

        if (requireEyesOpen) {
            if (detected.leftEyeOpen < 0.45f || detected.rightEyeOpen < 0.45f) {
                return CODE_EYES_CLOSED
            }
        }

        val alignedFace = alignAndCrop(detected.bitmap, detected.scaledBox, detected.leftEye, detected.rightEye, recInputSize)

        // الطبقة 1: فحص نسيج البشرة والتوزيع اللوني لكشف الطباعة الورقية
        if (!passesSkinForensics(alignedFace)) {
            prevFrameEmbedding = null
            return handleDebouncedRejection(now, CODE_SPOOF_WARNING)
        }

        // تشغيل نماذج العمق ومكافحة التزييف والتعرّف بالتوازي
        val meshFuture = aiExecutor.submit(Callable {
            measureTrue3DFacialDepth(detected.bitmap, detected.scaledBox)
        })

        val spoofFuture = aiExecutor.submit(Callable {
            if (antiSpoofInterpreter == null) return@Callable -1.0f
            val wideContext = cropExpanded(detected.bitmap, detected.scaledBox, scale = 2.2f, targetSize = spoofInputSize)
            runAntiSpoofing(wideContext)
        })

        val embedFuture = aiExecutor.submit(Callable {
            extractEmbedding(alignedFace)
        })

        val depth3D = runCatching { meshFuture.get(240, TimeUnit.MILLISECONDS) }.getOrNull()
        val realScore = runCatching { spoofFuture.get(240, TimeUnit.MILLISECONDS) }.getOrDefault(-1.0f)
        val currentEmbedding = runCatching { embedFuture.get(240, TimeUnit.MILLISECONDS) }.getOrNull()
            ?: return CODE_KEEP_STILL

        // الطبقة 2: Fail-Closed للعمق ثلاثي الأبعاد الحقيقي (رفض الصور المسطحة والمنحنية)
        if (depth3D == null) {
            return CODE_KEEP_STILL
        }
        if (!depth3D.isTrue3DFace) {
            prevFrameEmbedding = null
            return handleDebouncedRejection(now, CODE_SPOOF_WARNING)
        }

        // الطبقة 3: Fail-Closed لنموذج مكافحة التزييف MiniFASNet
        if (antiSpoofInterpreter != null) {
            if (realScore < 0f) {
                return CODE_KEEP_STILL
            }
            if (realScore < SPOOF_REAL_THRESHOLD) {
                prevFrameEmbedding = null
                return handleDebouncedRejection(now, CODE_SPOOF_WARNING)
            }
        }

        // الطبقة 4: مطابقة البصمة العصبية مع المرجع الأمامي والزوايا الأربع
        val targetPose = inferPoseBucket(detected.yaw, detected.pitch)
        val poseMatchedScore = enrolledTemplates
            .filter { it.poseId == targetPose || it.poseId == 4 }
            .maxOfOrNull { cosineSimilarity(currentEmbedding, it.embedding) } ?: 0f

        val allScores = enrolledTemplates.map { cosineSimilarity(currentEmbedding, it.embedding) }
        val maxScore = allScores.maxOrNull() ?: 0f
        val top2Avg = allScores.sortedDescending().take(2).average().toFloat()
        val combinedScore = maxScore * 0.50f + poseMatchedScore * 0.30f + top2Avg * 0.20f

        if (combinedScore < MATCH_COMBINED_THRESHOLD || maxScore < MATCH_SINGLE_MAX_THRESHOLD) {
            prevFrameEmbedding = null
            return handleDebouncedRejection(now, CODE_MISMATCH)
        }

        feedbackListener?.onScanningState(faceDetected = true, confidence = 0.85f)

        // الطبقة 5: التحقق الزمني بين إطارين حيين متتاليين (يرفض الصور الثابتة المعلقة)
        val prevEmb = prevFrameEmbedding
        if (prevEmb == null || (now - prevFrameTimeMs) > 1400L) {
            prevFrameEmbedding = currentEmbedding
            prevFrameNoseZ = depth3D.nosePlaneRatio
            prevFrameTimeMs = now
            return CODE_KEEP_STILL
        }

        val temporalSim = cosineSimilarity(currentEmbedding, prevEmb)
        if (temporalSim < 0.78f) {
            prevFrameEmbedding = currentEmbedding
            prevFrameNoseZ = depth3D.nosePlaneRatio
            prevFrameTimeMs = now
            return CODE_KEEP_STILL
        }
        if (temporalSim > 0.9998f && abs(depth3D.nosePlaneRatio - prevFrameNoseZ) < 0.00005f) {
            prevFrameEmbedding = null
            return handleDebouncedRejection(now, CODE_SPOOF_WARNING)
        }

        // نجاح مؤكد من المحاولة الأولى!
        prevFrameEmbedding = null
        continuousFailStartMs = 0L
        rejectionCycleCount = 0
        verifySessionStartMs = 0L

        if (adaptiveLearning && maxScore in 0.65f..0.88f) {
            if (enrolledTemplates.size >= MAX_TEMPLATES) {
                enrolledTemplates.removeAt(6)
            }
            enrolledTemplates.add(EnrolledPoseTemplate(targetPose, currentEmbedding, depth3D.nosePlaneRatio))
            saveTemplates()
        }

        feedbackListener?.onVerifySuccess()
        return CODE_SUCCESS
    }

    /**
     * تجميع محاولات الفشل المتواصلة وإطلاق إشارة رفض واحدة فقط كل محاولتي رفض
     */
    private fun handleDebouncedRejection(now: Long, reasonCode: Int): Int {
        if (continuousFailStartMs == 0L) {
            continuousFailStartMs = now
            return reasonCode
        }
        if (now - continuousFailStartMs >= 1650L) {
            continuousFailStartMs = now
            rejectionCycleCount++
            // تهتز الكبسولة مرة واحدة فقط كل محاولتي رفض
            if (rejectionCycleCount % 2 == 0) {
                feedbackListener?.onVerifyRejected(reasonCode)
            }
        }
        return reasonCode
    }

    private fun inferPoseBucket(yaw: Float, pitch: Float): Int {
        return when {
            abs(yaw) >= abs(pitch) && abs(yaw) > 11f -> if (yaw * stage0YawSign >= 0) 0 else 1
            abs(pitch) > 9f -> if (pitch > 0) 2 else 3
            else -> 4
        }
    }

    private data class Depth3DMetrics(
        val nosePlaneRatio: Float,
        val verticalCurveRatio: Float,
        val isTrue3DFace: Boolean
    )

    private fun measureTrue3DFacialDepth(bitmap: Bitmap, box: Rect): Depth3DMetrics? {
        val meshes = runCatching {
            Tasks.await(meshDetector.process(InputImage.fromBitmap(bitmap, 0)), 220, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return null

        if (meshes.isEmpty()) return null
        val pts = meshes[0].allPoints
        if (pts.size < 468) return null

        val faceWidth = max(1f, box.width().toFloat())
        val noseTipZ = pts[1].position.z
        val leftCheekZ = pts[234].position.z
        val rightCheekZ = pts[454].position.z
        val foreheadZ = pts[10].position.z
        val chinZ = pts[152].position.z
        val leftEyeZ = pts[33].position.z
        val rightEyeZ = pts[263].position.z

        val horizontalPlaneZ = (leftCheekZ + rightCheekZ) * 0.5f
        val horizontalRatio = (horizontalPlaneZ - noseTipZ) / faceWidth

        val verticalPlaneZ = (foreheadZ + chinZ) * 0.5f
        val verticalRatio = (verticalPlaneZ - noseTipZ) / faceWidth

        val eyeSocketRatio = (((leftEyeZ + rightEyeZ) * 0.5f) - noseTipZ) / faceWidth

        val isTrue3D = horizontalRatio >= MIN_3D_NOSE_PLANE_RATIO
                && verticalRatio >= MIN_3D_VERTICAL_CURVE_RATIO
                && eyeSocketRatio >= MIN_3D_EYE_SOCKET_RATIO

        return Depth3DMetrics(horizontalRatio, verticalRatio, isTrue3D)
    }

    private fun passesSkinForensics(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var laplacianSum = 0.0
        var count = 0
        var cbSum = 0.0
        var cbSqSum = 0.0
        var crSum = 0.0
        var crSqSum = 0.0

        val startX = (w * 0.18f).toInt()
        val endX = (w * 0.82f).toInt()
        val startY = (h * 0.18f).toInt()
        val endY = (h * 0.82f).toInt()

        for (y in startY until endY step 2) {
            for (x in startX until endX step 2) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val l = (pixels[y * w + (x - 1)] shr 8) and 0xFF
                val ri = (pixels[y * w + (x + 1)] shr 8) and 0xFF
                val t = (pixels[(y - 1) * w + x] shr 8) and 0xFF
                val bo = (pixels[(y + 1) * w + x] shr 8) and 0xFF
                laplacianSum += abs(4 * g - l - ri - t - bo)

                val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
                val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
                cbSum += cb
                cbSqSum += cb * cb
                crSum += cr
                crSqSum += cr * cr
                count++
            }
        }

        if (count == 0) return false
        val sharpness = (laplacianSum / count).toFloat()
        val cbVar = max(0.0, (cbSqSum / count) - (cbSum / count).pow(2))
        val crVar = max(0.0, (crSqSum / count) - (crSum / count).pow(2))
        val chromaStd = sqrt((cbVar + crVar) * 0.5).toFloat()

        return sharpness >= MIN_SKIN_SHARPNESS && chromaStd >= MIN_CHROMA_STD
    }

    private fun updateSceneLuma(nv21: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        if (nv21.size < frameSize || frameSize <= 0) return
        var sum = 0L
        var samples = 0
        val step = 16
        var i = 0
        while (i < frameSize) {
            sum += (nv21[i].toInt() and 0xFF)
            samples++
            i += step
        }
        if (samples > 0) {
            lastFrameLuma = sum.toFloat() / samples.toFloat()
        }
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
                Tasks.await(faceDetector.process(inputImage), 220, TimeUnit.MILLISECONDS)
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
        val dim = enrolledTemplates[0].embedding.size
        val sum = FloatArray(dim)
        for (tpl in enrolledTemplates) {
            if (tpl.embedding.size == dim) {
                for (i in 0 until dim) sum[i] += tpl.embedding[i]
            }
        }
        return l2Normalize(sum)
    }

    private fun runAntiSpoofing(bitmap: Bitmap): Float {
        val interpreter = antiSpoofInterpreter ?: return -1.0f
        val inputBuffer = bitmapToFloatBuffer(bitmap, mode = 0)
        val output = Array(1) { FloatArray(spoofOutputSize) }
        interpreter.run(inputBuffer, output)

        val logits = output[0]
        return when {
            spoofOutputSize == 1 -> 1.0f - logits[0].coerceIn(0f, 1f)
            spoofOutputSize >= 3 -> {
                val maxLogit = logits.maxOrNull() ?: 0f
                val expReal = exp((logits[1] - maxLogit).toDouble())
                val sumExp = logits.sumOf { exp((it - maxLogit).toDouble()) }
                (expReal / sumExp).toFloat()
            }
            else -> {
                val maxLogit = max(logits[0], logits[1])
                val expReal = exp((logits[0] - maxLogit).toDouble())
                val sumExp = exp((logits[0] - maxLogit).toDouble()) + exp((logits[1] - maxLogit).toDouble())
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
                2 -> {
                    buffer.putFloat((r - 127.5f) / 128.0f)
                    buffer.putFloat((g - 127.5f) / 128.0f)
                    buffer.putFloat((b - 127.5f) / 128.0f)
                }
                else -> {
                    buffer.putFloat(b)
                    buffer.putFloat(g)
                    buffer.putFloat(r)
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
            for (tpl in enrolledTemplates) {
                dos.writeInt(tpl.poseId)
                dos.writeFloat(tpl.noseProtrusion3D)
                dos.writeInt(tpl.embedding.size)
                for (f in tpl.embedding) dos.writeFloat(f)
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
                val poseId = dis.readInt()
                val nose3D = dis.readFloat()
                val len = dis.readInt()
                val vec = FloatArray(len) { dis.readFloat() }
                enrolledTemplates.add(EnrolledPoseTemplate(poseId, vec, nose3D))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }
}

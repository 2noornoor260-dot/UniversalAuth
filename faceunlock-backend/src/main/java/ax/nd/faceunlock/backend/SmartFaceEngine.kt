package ax.nd.faceunlock.backend

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ax.nd.faceunlock.backend.core.ModelContract
import ax.nd.faceunlock.backend.core.SecurityProfile
import ax.nd.faceunlock.backend.core.SimilarityTransform
import ax.nd.faceunlock.backend.core.TemplateCodec
import ax.nd.faceunlock.backend.core.VerifySession
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.megvii.facepp.sdk.UnlockEncryptor
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * محرّك التعرّف على الوجه (كاميرا أمامية RGB فقط).
 *
 * أهم ما تغيّر عن الإصدار السابق:
 *  - جلسة تحقق صريحة (VerifySession): أُزيل التجمّد الذي كان يمنع أي نجاح.
 *  - محاذاة قياسية (ArcFace 112×112) بتحويل تشابه من العينين والفم، بدل دوران كامل الصورة بإحداثيات منزاحة.
 *  - التحقق من عقد النماذج (الأشكال) بدل استخدام نموذج غير متوافق بصمت.
 *  - ملف قوالب v7: ترويسة + CRC + تشفير إلزامي (لا حفظ نصّي أبداً) + إعدادات معايرة من التسجيل.
 *  - تدرّج الطبقات: الجودة → الجلد → التطابق → (عمق + مكافحة تزييف بالتوازي) → سلسلة إطارات حيّة.
 *  - عند أي مهلة/خطأ: فشل مغلق (لا فتح أبداً)، لكن بلا احتسابه رفضاً مرئياً.
 */
class SmartFaceEngine(context: Context) {

    interface VerificationFeedbackListener {
        fun onScanningState(faceDetected: Boolean, confidence: Float)
        fun onVerifySuccess()
        fun onVerifyRejected(reasonCode: Int)

        /** متوسط سطوع الإطار وسطوع الوجه (سالب إن لا وجه) لكل إطار: تستخدمه الواجهة لقرار الإضاءة الليلية. */
        fun onFrameStats(meanLuma: Float, faceLuma: Float) {}
    }

    companion object {
        private const val TAG = "SmartFaceEngine"
        private const val MAX_TEMPLATES = 18
        private const val DB_FILE_NAME = "smart_face_templates_v7.enc"
        private const val DB_FILE_PREFIX = "smart_face_templates_v"
        const val PREFS_NAME = "smart_face_prefs"
        private const val KEY_CACHED_ROTATION = "cached_camera_rotation"

        const val KEY_REQUIRE_EYES = "req_eyes_open"
        const val KEY_ADAPTIVE = "adaptive_learning"
        const val KEY_NIGHT_LIGHT = "night_fill_light"
        const val KEY_UNLOCK_CHIME_TYPE = "unlock_chime_type"
        const val KEY_CAPSULE_ANIM = "dynamic_capsule_anim"

        // إعدادات جديدة
        const val KEY_SECURITY_LEVEL = "security_level"
        const val KEY_REQUIRE_NEURAL_PAD = "require_neural_pad"
        const val KEY_FILL_STRENGTH = "fill_light_strength"
        const val KEY_CAMERA_EDGE = "camera_edge_mode"
        const val KEY_HAPTICS = "unlock_haptics"
        const val KEY_STAT_LAST_MS = "stat_last_unlock_ms"
        const val KEY_STAT_AVG_MS = "stat_avg_unlock_ms"
        const val KEY_STAT_COUNT = "stat_unlock_count"

        // العمق ثلاثي الأبعاد (لم تُخفَّف: نفس العتبات المعايَرة سابقاً)
        private const val MIN_3D_NOSE_PLANE_RATIO = 0.026f
        private const val MIN_3D_VERTICAL_CURVE_RATIO = 0.014f
        private const val MIN_3D_EYE_SOCKET_RATIO = 0.010f

        // نسيج الجلد: الحدّ المطلق 2.6/2.2؛ ويُسمح بالنزول حتى 2.0/1.6 فقط إن كانت كاميرا هذا الجهاز نفسها
        // تُنتج قيماً أقل لوجه حقيقي أثناء التسجيل (معايرة نسبية 62% من مرجع المستخدم، لا تتجاوز الحدّ المطلق أبداً)
        private const val ABS_MIN_SKIN_SHARPNESS = 2.6f
        private const val ABS_MIN_CHROMA_STD = 2.2f
        private const val FLOOR_SKIN_SHARPNESS = 2.0f
        private const val FLOOR_CHROMA_STD = 1.6f
        private const val CALIBRATION_FRACTION = 0.62f

        private const val OPTIMAL_MAX_HEAD_ANGLE = 36f
        /** معامل قصّ سياق الوجه لنموذج MiniFASNet 80×80 (2.7 للنسخة V2؛ 4.0 للنسخة V1SE). */
        private const val SPOOF_CROP_SCALE = 2.7f

        private const val SESSION_GAP_MS = 2500L
        private const val NO_FACE_PROBE_EVERY = 3
        private const val AI_THREAD_PRIORITY = 2

        const val STATE_UNKNOWN = 2
        const val STATE_OK = 1
        const val STATE_MISSING = 0
        const val STATE_INVALID = -1

        @JvmField var currentEnrollHint: String = "حرّك رأسك ببطء نحو اليمين"
        @JvmField var currentEnrollPercent: Float = 0f
        @JvmField var currentEnrollStage: Int = 0
        @JvmField var currentEnrollFaceFound: Boolean = false
        @JvmField var currentEnrollYaw: Float = 0f
        @JvmField var currentEnrollPitch: Float = 0f
        @JvmField var currentEnrollError: Boolean = false
        /** إشارة اتجاه الرأس التي سُجّلت في المرحلة الأولى (+1 أو -1)؛ تحتاجها الواجهة لحساب تقدّم المرحلة الثانية. */
        @JvmField var currentEnrollYawSign: Float = 1f

        @Volatile var lastFrameLuma: Float = -1f
        @Volatile var lastFaceLuma: Float = -1f
        @Volatile var frameSeq: Long = 0L

        /** حالة النماذج (للواجهة): نموذج التعرّف ومكافحة التزييف وسبب أي خلل. */
        @Volatile var recognizerState: Int = STATE_UNKNOWN
        @Volatile var spoofState: Int = STATE_UNKNOWN
        @Volatile var modelReason: String = ""

        /** تشخيص آخر إطار (للشاشة الاختبارية): قيم القياس مقابل الحدود. */
        @Volatile var diagSharp: Float = -1f
        @Volatile var diagSharpMin: Float = -1f
        @Volatile var diagChroma: Float = -1f
        @Volatile var diagChromaMin: Float = -1f
        @Volatile var diagMatch: Float = -1f
        @Volatile var diagMatchMin: Float = -1f
        @Volatile var diagDepth: Float = -1f
        @Volatile var diagSpoof: Float = -1f
        @Volatile var diagSpoofMin: Float = -1f
        @Volatile var diagStreak: Int = 0
        @Volatile var diagStage: String = ""

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

        /**
         * يحذف ملفات القوالب القديمة (v6 وما قبلها): بصماتها محسوبة بمحاذاة مختلفة فلا تُطابَق بالنظام الجديد.
         *
         * @return true إذا حُذف قديم ولا توجد قوالب v7، أي يلزم إعادة التسجيل (يمسح التطبيق علامات "مسجَّل").
         */
        @JvmStatic
        fun migrateLegacyFiles(dir: File?): Boolean {
            if (dir == null || !dir.isDirectory) return false
            val current = File(dir, DB_FILE_NAME)
            var removed = false
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    if (f.name.startsWith(DB_FILE_PREFIX) && f.name.endsWith(".enc") && f.name != DB_FILE_NAME) {
                        if (f.delete()) removed = true
                    }
                }
            }
            return removed && !current.exists()
        }

        /** هل توجد قوالب v7 على القرص؟ (لا تحتاج إنشاء المحرّك). */
        @JvmStatic
        fun hasTemplatesOnDisk(dir: File?): Boolean {
            return dir != null && File(dir, DB_FILE_NAME).isFile
        }

        /** يفحص ملفات النماذج (دون إنشاء المحرّك الكامل) ويحدّث حالتها الساكنة للواجهة. */
        @JvmStatic
        fun probeModels(context: Context) {
            val app = context.applicationContext
            val rec = loadModel(app, "face_recognition.tflite", true, 1)
            val spf = loadModel(app, "minifasnet.tflite", false, 1)
            applyModelStates(rec, spf)
            rec.interpreter?.close()
            spf.interpreter?.close()
        }

        class LoadedModel(
            val interpreter: Interpreter?,
            val state: Int,
            val reason: String,
            val size: Int,
            val outDim: Int,
            val nchw: Boolean
        )

        private fun loadModel(context: Context, asset: String, recognizer: Boolean, threads: Int): LoadedModel {
            val interp: Interpreter = try {
                val buffer = FileUtil.loadMappedFile(context, asset)
                Interpreter(buffer, Interpreter.Options().apply { setNumThreads(threads) })
            } catch (e: Exception) {
                return LoadedModel(null, STATE_MISSING, "$asset: غير موجود أو لا يُحمَّل (${e.javaClass.simpleName})", 0, 0, false)
            }
            return try {
                val inShape = interp.getInputTensor(0).shape()
                val outShape = interp.getOutputTensor(0).shape()
                val outCount = interp.outputTensorCount
                val r = if (recognizer) ModelContract.checkRecognizer(inShape, outShape, outCount)
                else ModelContract.checkSpoof(inShape, outShape, outCount)
                Log.i(TAG, "model $asset in=${inShape.contentToString()} out=${outShape.contentToString()} outputs=$outCount -> ${r.reason}")
                if (r.ok) {
                    LoadedModel(interp, STATE_OK, "ok", r.size, r.outputDim, r.nchw)
                } else {
                    interp.close()
                    LoadedModel(null, STATE_INVALID, "$asset: ${r.reason}", 0, 0, false)
                }
            } catch (e: Exception) {
                interp.close()
                LoadedModel(null, STATE_INVALID, "$asset: ${e.javaClass.simpleName}", 0, 0, false)
            }
        }

        private fun applyModelStates(rec: LoadedModel, spf: LoadedModel) {
            recognizerState = rec.state
            spoofState = spf.state
            val parts = ArrayList<String>()
            if (rec.state != STATE_OK) parts.add(rec.reason)
            if (spf.state != STATE_OK) parts.add(spf.reason)
            modelReason = parts.joinToString(" | ")
        }

        private fun calibratedMin(reference: Float, absolute: Float, floor: Float): Float {
            if (reference <= 0f) return absolute
            return (CALIBRATION_FRACTION * reference).coerceIn(floor, absolute)
        }

        private fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) * 0.5f
        }
    }

    /** poseId: 0=يمين 1=يسار 2=أعلى 3=أسفل 4=أمامي/مركزي؛ +10 = قالب تكيّفي مُتعلَّم أثناء الاستخدام. */
    class FaceTemplate(val poseId: Int, val embedding: FloatArray, val noseRatio: Float)

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val aiExecutor = Executors.newFixedThreadPool(3, ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(AI_THREAD_PRIORITY)
            runnable.run()
        }, "SmartFace-AI")
    })

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
    private var spoofNchw = false
    private val recLock = Any()
    private val spoofLock = Any()

    @Volatile private var templates: List<FaceTemplate> = emptyList()
    /** [0]=مرجع حدّة الجلد، [1]=مرجع تباين اللون، [2]=إشارة اتجاه اليمين في التسجيل، [3]=محجوز. */
    @Volatile private var calibration = floatArrayOf(0f, 0f, 1f, 0f)
    private val storeLock = Any()
    private var storageDir: File? = null
    private var encryptor: UnlockEncryptor? = null
    @Volatile private var warmedUp = false

    private var lastWorkingRotation: Int? = null
    private var noFaceStreak = 0
    private val argbCache = arrayOfNulls<IntArray>(1)
    private val alignPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val session = VerifySession()
    private var lastVerifyFrameMs = 0L
    @Volatile private var scanBeginMs = 0L

    // تسجيل: قائمة مؤقتة تُستبدل بها القوالب القديمة ذرّياً عند الاكتمال فقط
    private val enrollBuffer = ArrayList<FaceTemplate>()
    private val enrollSharp = ArrayList<Float>()
    private val enrollChroma = ArrayList<Float>()
    private var enrollStage = 0
    private var frontalCapturedInSession = false
    private var stage0YawSign = 1f
    private var lastEnrollCaptureTimeMs = 0L

    init {
        val savedRot = prefs.getInt(KEY_CACHED_ROTATION, -1)
        if (savedRot != -1) lastWorkingRotation = savedRot

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        faceDetector = FaceDetection.getClient(options)

        val rec = loadModel(appContext, "face_recognition.tflite", true, 3)
        val spf = loadModel(appContext, "minifasnet.tflite", false, 2)
        recognizerInterpreter = rec.interpreter
        if (rec.state == STATE_OK) {
            recInputSize = rec.size
            recOutputSize = rec.outDim
        }
        antiSpoofInterpreter = spf.interpreter
        if (spf.state == STATE_OK) {
            spoofInputSize = spf.size
            spoofNchw = spf.nchw
        }
        applyModelStates(rec, spf)
        if (rec.state != STATE_OK || spf.state != STATE_OK) {
            Log.e(TAG, "MODEL PROBLEM: $modelReason")
        }
    }

    // ───────────────────────── التخزين والحالة ─────────────────────────

    fun initStorage(path: String?, customEncryptor: UnlockEncryptor?) {
        if (path != null) {
            storageDir = File(path).apply { if (!exists()) mkdirs() }
        }
        encryptor = customEncryptor ?: CustomUnlockEncryptor()
        loadTemplates()
    }

    fun hasEnrolledFace(): Boolean = templates.isNotEmpty()

    /** هل المحرّك قادر على التحقق؟ (نموذج التعرّف صالح + نموذج مكافحة التزييف صالح أو مسموح بدونه). */
    fun isOperational(): Boolean {
        if (recognizerInterpreter == null) return false
        return antiSpoofInterpreter != null || !requireNeuralPad()
    }

    private fun requireNeuralPad(): Boolean = prefs.getBoolean(KEY_REQUIRE_NEURAL_PAD, true)

    /** يُستدعى عند بدء كل مسح (تحقق أو تسجيل): جلسة نظيفة تماماً. */
    fun onScanStart() {
        val now = SystemClock.elapsedRealtime()
        session.begin(now)
        scanBeginMs = now
        lastVerifyFrameMs = 0L
        noFaceStreak = 0
        resetEnrollProgress()
    }

    fun resetEnrollProgress() {
        enrollBuffer.clear()
        enrollSharp.clear()
        enrollChroma.clear()
        enrollStage = 0
        frontalCapturedInSession = false
        lastEnrollCaptureTimeMs = 0L
        currentEnrollStage = 0
        currentEnrollPercent = 0f
        currentEnrollFaceFound = false
        currentEnrollError = false
        currentEnrollYawSign = 1f
        currentEnrollHint = "حرّك رأسك ببطء نحو اليمين"
    }

    fun deleteAllTemplates() {
        synchronized(storeLock) {
            templates = emptyList()
            calibration = floatArrayOf(0f, 0f, 1f, 0f)
            storageDir?.let { File(it, DB_FILE_NAME).delete() }
        }
        resetEnrollProgress()
        session.begin(SystemClock.elapsedRealtime())
    }

    /** تسخين النماذج في خيط خلفي كي لا يدفع أول فتح تكلفة التهيئة الباردة. */
    fun warmUpAsync() {
        if (warmedUp) return
        aiExecutor.execute { warmUp() }
    }

    private fun warmUp() {
        if (warmedUp) return
        try {
            val t0 = SystemClock.elapsedRealtime()
            val w = 640
            val h = 480
            val gray = ByteArray(w * h * 3 / 2) { 128.toByte() }
            runCatching {
                Tasks.await(faceDetector.process(InputImage.fromByteArray(gray, w, h, 0, InputImage.IMAGE_FORMAT_NV21)), 6000, TimeUnit.MILLISECONDS)
            }
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF808080.toInt())
            runCatching {
                Tasks.await(meshDetector.process(InputImage.fromBitmap(bmp, 0)), 6000, TimeUnit.MILLISECONDS)
            }
            if (recognizerInterpreter != null) {
                runCatching { extractEmbedding(Bitmap.createBitmap(recInputSize, recInputSize, Bitmap.Config.ARGB_8888)) }
            }
            if (antiSpoofInterpreter != null) {
                runCatching { runAntiSpoofing(Bitmap.createBitmap(spoofInputSize, spoofInputSize, Bitmap.Config.ARGB_8888)) }
            }
            Log.i(TAG, "warm-up done in ${SystemClock.elapsedRealtime() - t0} ms")
        } catch (e: Exception) {
            Log.w(TAG, "warm-up failed", e)
        } finally {
            warmedUp = true
        }
    }

    // ───────────────────────── التسجيل ─────────────────────────

    /**
     * تسجيل من 4 اتجاهات (يمين، يسار، فوق، تحت) + التقاط مرجع أمامي تلقائي.
     * القوالب القديمة تبقى صالحة حتى يكتمل التسجيل الجديد وتُحفظ بأمان، ثم تُستبدل دفعة واحدة.
     */
    fun enrollFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        try {
            return enrollFrameInternal(nv21, width, height, rotation)
        } catch (e: Exception) {
            Log.e(TAG, "enrollFrame failed", e)
            return CODE_KEEP_STILL
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "enrollFrame OOM", e)
            return CODE_KEEP_STILL
        }
    }

    private fun enrollFrameInternal(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        updateSceneLuma(nv21, width, height)

        if (recognizerInterpreter == null) {
            currentEnrollHint = "نموذج التعرّف غير صالح على هذا الإصدار"
            currentEnrollError = true
            return CODE_KEEP_STILL
        }

        val detected = detectFastNV21(nv21, width, height, rotation) ?: run {
            currentEnrollFaceFound = false
            currentEnrollHint = "ضع وجهك داخل الدائرة"
            return CODE_NO_FACE
        }
        currentEnrollFaceFound = true

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
        currentEnrollYaw = yaw
        currentEnrollPitch = pitch
        currentEnrollStage = enrollStage

        if (abs(detected.roll) > 40f) {
            currentEnrollHint = "اعدِل رأسك قليلاً دون إمالته"
            return CODE_KEEP_STILL
        }

        // التقاط بصمة أمامية مرجعية تلقائياً أثناء النظر للكاميرا
        if (!frontalCapturedInSession && abs(yaw) <= 13f && abs(pitch) <= 13f) {
            val depth3D = measureTrue3DFacialDepth(detected.bitmap, detected.scaledBox)
            if (depth3D != null && depth3D.isTrue3DFace) {
                val aligned = alignFace(detected, recInputSize)
                val frontEmb = if (aligned != null) extractEmbedding(aligned) else null
                if (aligned != null && frontEmb != null) {
                    enrollBuffer.add(FaceTemplate(4, frontEmb, depth3D.nosePlaneRatio))
                    recordSkinSample(aligned)
                    frontalCapturedInSession = true
                }
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastEnrollCaptureTimeMs < 380L) {
            return CODE_KEEP_STILL
        }

        val angleMatched = when (enrollStage) {
            0 -> {
                currentEnrollHint = "حرّك رأسك ببطء نحو اليمين"
                if (abs(yaw) in 12f..44f && abs(pitch) <= 22f) {
                    stage0YawSign = if (yaw >= 0) 1f else -1f
                    currentEnrollYawSign = stage0YawSign
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
        if (!angleMatched) return CODE_KEEP_STILL

        val depth3D = measureTrue3DFacialDepth(detected.bitmap, detected.scaledBox)
        if (depth3D == null || !depth3D.isTrue3DFace) {
            currentEnrollHint = "تأكد من وضوح الإضاءة وتوجّه وجهك للكاميرا"
            return CODE_KEEP_STILL
        }

        val aligned = alignFace(detected, recInputSize) ?: return CODE_KEEP_STILL
        val embedding = extractEmbedding(aligned) ?: return CODE_KEEP_STILL

        enrollBuffer.add(FaceTemplate(enrollStage, embedding, depth3D.nosePlaneRatio))
        recordSkinSample(aligned)

        lastEnrollCaptureTimeMs = now
        enrollStage++
        currentEnrollStage = enrollStage
        currentEnrollPercent = ((enrollStage / 4f) * 100f).coerceIn(0f, 100f)

        if (enrollStage < 4) {
            currentEnrollHint = when (enrollStage) {
                1 -> "حرّك رأسك ببطء نحو اليسار"
                2 -> "ارفع رأسك ببطء للأعلى"
                3 -> "اخفض رأسك ببطء للأسفل"
                else -> currentEnrollHint
            }
            return CODE_KEEP_STILL
        }

        // اكتمل: مركز البصمات + حفظ آمن + استبدال ذرّي للقوالب القديمة
        val finalList = ArrayList<FaceTemplate>(enrollBuffer.size + 1)
        val centroid = centroidOf(enrollBuffer)
        if (centroid != null) {
            val avgNose = enrollBuffer.map { it.noseRatio }.average().toFloat()
            finalList.add(FaceTemplate(4, centroid, avgNose))
        }
        finalList.addAll(enrollBuffer)

        val cal = floatArrayOf(
            if (enrollSharp.size >= 3) median(enrollSharp) else 0f,
            if (enrollChroma.size >= 3) median(enrollChroma) else 0f,
            stage0YawSign,
            0f
        )

        if (saveTemplates(finalList, cal)) {
            templates = finalList
            calibration = cal
            currentEnrollPercent = 100f
            currentEnrollHint = "اكتمل إعداد التعرّف ثلاثي الأبعاد"
            enrollBuffer.clear()
            enrollSharp.clear()
            enrollChroma.clear()
            enrollStage = 0
            frontalCapturedInSession = false
            return CODE_SUCCESS
        }

        // تعذّر الحفظ الآمن (مثلاً فشل مخزن المفاتيح): لا نحتفظ بشيء نصّي، ونبلغ الواجهة
        Log.e(TAG, "template save failed -> enrollment aborted")
        enrollBuffer.clear()
        enrollSharp.clear()
        enrollChroma.clear()
        enrollStage = 0
        currentEnrollStage = 0
        currentEnrollPercent = 0f
        frontalCapturedInSession = false
        currentEnrollError = true
        currentEnrollHint = "تعذّر حفظ البصمة بأمان على هذا الجهاز"
        return CODE_KEEP_STILL
    }

    private fun recordSkinSample(aligned: Bitmap) {
        val s = measureSkin(aligned) ?: return
        enrollSharp.add(s.sharpness)
        enrollChroma.add(s.chroma)
    }

    // ───────────────────────── التحقق ─────────────────────────

    /**
     * التحقق من إطار كاميرا. يُرجع CODE_SUCCESS فقط عند اكتمال سلسلة الإطارات الحيّة المتسقة
     * (عدد الإطارات حسب مستوى الأمان) بعد اجتياز كل الطبقات. أي مهلة أو خطأ => لا فتح (فشل مغلق).
     */
    fun verifyFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        try {
            return verifyFrameInternal(nv21, width, height, rotation)
        } catch (e: Exception) {
            Log.e(TAG, "verifyFrame failed (fail-closed)", e)
            return CODE_KEEP_STILL
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "verifyFrame OOM (fail-closed)", e)
            return CODE_KEEP_STILL
        }
    }

    private fun currentProfile(spoofReady: Boolean): SecurityProfile {
        var level = prefs.getInt(KEY_SECURITY_LEVEL, SecurityProfile.LEVEL_BALANCED)
        // بلا نموذج عصبي صالح (وبقرار المستخدم الصريح) نرفع الصرامة تعويضاً: مستوى "صارم" كحدّ أدنى
        if (!spoofReady && level < SecurityProfile.LEVEL_STRICT) level = SecurityProfile.LEVEL_STRICT
        return SecurityProfile.forLevel(level)
    }

    private fun notifyStats(meanLuma: Float, faceLuma: Float) {
        lastFaceLuma = faceLuma
        feedbackListener?.onFrameStats(meanLuma, faceLuma)
    }

    private fun reject(now: Long, code: Int, stage: String): Int {
        diagStage = "رفض: $stage"
        if (session.onHardReject(now)) {
            feedbackListener?.onVerifyRejected(code)
        }
        return code
    }

    private fun verifyFrameInternal(nv21: ByteArray, width: Int, height: Int, rotation: Int): Int {
        val now = SystemClock.elapsedRealtime()
        if (lastVerifyFrameMs != 0L && now - lastVerifyFrameMs > SESSION_GAP_MS) {
            // احتياط: لو لم يصل onScanStart (تغيّرت الحالة)، فجوة طويلة بلا إطارات تعني مسحاً جديداً
            session.begin(now)
            scanBeginMs = now
        }
        lastVerifyFrameMs = now
        session.onFrame(now)
        val meanLuma = updateSceneLuma(nv21, width, height)

        val snapshot = templates
        if (snapshot.isEmpty()) {
            notifyStats(meanLuma, -1f)
            diagStage = "لا توجد قوالب"
            return CODE_MISMATCH
        }
        if (recognizerInterpreter == null) {
            notifyStats(meanLuma, -1f)
            diagStage = "نموذج التعرّف غير صالح"
            return CODE_KEEP_STILL
        }
        val spoofReady = antiSpoofInterpreter != null
        if (!spoofReady && requireNeuralPad()) {
            // فشل مغلق: لا فتح بلا نموذج مكافحة تزييف صالح ما لم يسمح المستخدم صراحةً بغير ذلك
            notifyStats(meanLuma, -1f)
            diagStage = "نموذج مكافحة التزييف غير صالح"
            return reject(now, CODE_SPOOF_WARNING, "نموذج مكافحة التزييف")
        }

        val profile = currentProfile(spoofReady)
        val requireEyesOpen = profile.forceEyesOpen || prefs.getBoolean(KEY_REQUIRE_EYES, true)
        val adaptiveLearning = prefs.getBoolean(KEY_ADAPTIVE, true)

        val detected = detectFastNV21(nv21, width, height, rotation)
        if (detected == null) {
            session.onNoFace(now)
            notifyStats(meanLuma, -1f)
            feedbackListener?.onScanningState(faceDetected = false, confidence = 0f)
            diagStage = "لا وجه"
            return CODE_NO_FACE
        }
        session.onFaceSeen(now)
        feedbackListener?.onScanningState(faceDetected = true, confidence = 0.40f)

        if (profile.level >= SecurityProfile.LEVEL_STRICT && detected.allFacesCount > 1) {
            notifyStats(meanLuma, -1f)
            diagStage = "أكثر من وجه"
            return CODE_MULTI_FACE
        }

        if (abs(detected.yaw) > OPTIMAL_MAX_HEAD_ANGLE || abs(detected.roll) > OPTIMAL_MAX_HEAD_ANGLE) {
            notifyStats(meanLuma, -1f)
            diagStage = "الرأس مائل"
            return if (detected.yaw > 0) CODE_ROTATED_LEFT else CODE_ROTATED_RIGHT
        }
        if (requireEyesOpen && (detected.leftEyeOpen < 0.45f || detected.rightEyeOpen < 0.45f)) {
            notifyStats(meanLuma, -1f)
            diagStage = "العينان مغمضتان"
            return CODE_EYES_CLOSED
        }

        val aligned = alignFace(detected, recInputSize)
        if (aligned == null) {
            notifyStats(meanLuma, -1f)
            diagStage = "معالم الوجه ناقصة"
            return CODE_KEEP_STILL
        }
        val skin = measureSkin(aligned)
        if (skin == null) {
            notifyStats(meanLuma, -1f)
            return CODE_KEEP_STILL
        }
        notifyStats(meanLuma, skin.luma)

        // إحماء التعريض: إطارات الجلسة الأولى لا يُحكم عليها ولا يمكن قبولها (لكنها لا تُجمَّد أبداً)
        if (!session.isExposureSettled(now)) {
            diagStage = "إحماء الكاميرا"
            return CODE_KEEP_STILL
        }
        // إطار معتم/محترق جداً: غير حاسم (تتكفل الإضاءة الليلية بالمعتم)، ولا يُعدّ رفضاً
        if (skin.luma < 20f || skin.luma > 245f) {
            diagStage = "إضاءة غير كافية"
            return CODE_KEEP_STILL
        }

        // الطبقة 1: نسيج الجلد (معايَر نسبياً بكاميرا هذا الجهاز)
        val cal = calibration
        val sharpMin = calibratedMin(cal[0], ABS_MIN_SKIN_SHARPNESS, FLOOR_SKIN_SHARPNESS)
        val chromaMin = calibratedMin(cal[1], ABS_MIN_CHROMA_STD, FLOOR_CHROMA_STD)
        diagSharp = skin.sharpness
        diagSharpMin = sharpMin
        diagChroma = skin.chroma
        diagChromaMin = chromaMin
        if (skin.sharpness < sharpMin || skin.chroma < chromaMin) {
            return reject(now, CODE_SPOOF_WARNING, "نسيج الجلد")
        }

        // الطبقة 2: مطابقة الهوية (سريعة، قبل الطبقات الثقيلة)
        val embedding = extractEmbedding(aligned)
        if (embedding == null) {
            diagStage = "تعذّر استخراج البصمة"
            return CODE_KEEP_STILL
        }
        val targetPose = inferPoseBucket(detected.yaw, detected.pitch)
        val match = matchScore(embedding, targetPose, snapshot)
        diagMatch = match.combined
        diagMatchMin = profile.matchCombined
        if (match.combined < profile.matchCombined || match.max < profile.matchSingleMax) {
            return reject(now, CODE_MISMATCH, "المطابقة")
        }
        feedbackListener?.onScanningState(faceDetected = true, confidence = 0.85f)

        // الطبقتان 3 و4: العمق ثلاثي الأبعاد ومكافحة التزييف العصبي بالتوازي، بمهلة إجمالية
        val deadline = SystemClock.elapsedRealtime() + (if (warmedUp) 450L else 1800L)
        val meshFuture: Future<Depth3DMetrics?> = aiExecutor.submit(Callable<Depth3DMetrics?> { measureTrue3DFacialDepth(detected.bitmap, detected.scaledBox) })
        val spoofFuture: Future<Float>? = if (spoofReady) {
            aiExecutor.submit(Callable<Float> {
                val crop = cropSquare(detected.bitmap, detected.scaledBox, SPOOF_CROP_SCALE, spoofInputSize)
                runAntiSpoofing(crop)
            })
        } else null

        val depth3D = awaitResult(meshFuture, deadline)
        val realScore: Float = if (spoofFuture != null) (awaitResult(spoofFuture, deadline) ?: -1f) else -1f

        // العمق: فشل مغلق (مهلة/فشل => لا فتح، وليس رفضاً مرئياً)
        if (depth3D == null) {
            session.onInconclusive()
            diagStage = "العمق: لا نتيجة"
            return CODE_KEEP_STILL
        }
        diagDepth = depth3D.nosePlaneRatio
        if (!depth3D.isTrue3DFace) {
            return reject(now, CODE_SPOOF_WARNING, "العمق ثلاثي الأبعاد")
        }

        if (spoofReady) {
            diagSpoof = realScore
            diagSpoofMin = profile.spoofReal
            if (realScore < 0f) {
                session.onInconclusive()
                diagStage = "مكافحة التزييف: لا نتيجة"
                return CODE_KEEP_STILL
            }
            if (realScore < profile.spoofReal) {
                return reject(now, CODE_SPOOF_WARNING, "مكافحة التزييف")
            }
        }

        // الطبقة 5: سلسلة إطارات حيّة متسقة زمنياً
        val outcome = session.onGoodFrame(now, embedding, depth3D.nosePlaneRatio, profile)
        if (outcome == VerifySession.Outcome.NEED_MORE) {
            diagStreak = session.goodStreak()
            diagStage = "إطار ${session.goodStreak()}/${profile.requiredGoodFrames}"
            return CODE_KEEP_STILL
        }
        if (outcome == VerifySession.Outcome.REJECT_STATIC) {
            return reject(now, CODE_SPOOF_WARNING, "صورة ثابتة")
        }

        // نجاح مؤكد
        diagStage = "نجاح"
        session.onAccepted()
        recordUnlockTime(now)

        if (adaptiveLearning && profile.level < SecurityProfile.LEVEL_MAXIMUM &&
            match.max >= profile.matchSingleMax + 0.06f && match.max <= 0.88f
        ) {
            learnAdaptive(snapshot, targetPose, embedding, depth3D.nosePlaneRatio)
        }

        feedbackListener?.onVerifySuccess()
        return CODE_SUCCESS
    }

    private fun recordUnlockTime(now: Long) {
        val begin = scanBeginMs
        if (begin <= 0L) return
        val elapsed = (now - begin).coerceIn(0L, 60_000L)
        val count = prefs.getInt(KEY_STAT_COUNT, 0)
        val avg = prefs.getLong(KEY_STAT_AVG_MS, 0L)
        val newAvg = if (count == 0) elapsed else ((avg * 0.8) + (elapsed * 0.2)).toLong()
        prefs.edit()
            .putLong(KEY_STAT_LAST_MS, elapsed)
            .putLong(KEY_STAT_AVG_MS, newAvg)
            .putInt(KEY_STAT_COUNT, min(count + 1, 1_000_000))
            .apply()
    }

    private fun learnAdaptive(snapshot: List<FaceTemplate>, targetPose: Int, emb: FloatArray, nose: Float) {
        val list = ArrayList<FaceTemplate>(snapshot)
        if (list.size >= MAX_TEMPLATES) {
            val oldest = list.indexOfFirst { it.poseId >= 10 }
            if (oldest < 0) return
            list.removeAt(oldest)
        }
        list.add(FaceTemplate(10 + targetPose, emb, nose))
        templates = list
        val cal = calibration
        // حفظ غير متزامن كي لا يتأخر الفتح
        aiExecutor.execute { saveTemplates(list, cal) }
    }

    private fun <T> awaitResult(future: Future<T>, deadlineMs: Long): T? {
        val remaining = max(1L, deadlineMs - SystemClock.elapsedRealtime())
        return try {
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(false)
            null
        }
    }

    private class MatchResult(val combined: Float, val max: Float)

    private fun matchScore(emb: FloatArray, targetPose: Int, list: List<FaceTemplate>): MatchResult {
        var maxScore = -1f
        var poseBest = 0f
        var first = -1f
        var second = -1f
        for (t in list) {
            val s = dot(emb, t.embedding)
            if (s > maxScore) maxScore = s
            val base = t.poseId % 10
            if ((base == targetPose || base == 4) && s > poseBest) poseBest = s
            if (s > first) {
                second = first
                first = s
            } else if (s > second) {
                second = s
            }
        }
        val top2 = if (second < 0f) first else (first + second) * 0.5f
        val combined = maxScore * 0.50f + poseBest * 0.30f + top2 * 0.20f
        return MatchResult(combined, maxScore)
    }

    private fun inferPoseBucket(yaw: Float, pitch: Float): Int {
        val sign = if (calibration[2] == 0f) 1f else calibration[2]
        return when {
            abs(yaw) >= abs(pitch) && abs(yaw) > 11f -> if (yaw * sign >= 0) 0 else 1
            abs(pitch) > 9f -> if (pitch > 0) 2 else 3
            else -> 4
        }
    }

    // ───────────────────────── العمق ثلاثي الأبعاد ─────────────────────────

    class Depth3DMetrics(
        val nosePlaneRatio: Float,
        val verticalCurveRatio: Float,
        val isTrue3DFace: Boolean
    )

    private fun measureTrue3DFacialDepth(bitmap: Bitmap, box: Rect): Depth3DMetrics? {
        val timeout = if (warmedUp) 380L else 1500L
        val meshes = runCatching {
            Tasks.await(meshDetector.process(InputImage.fromBitmap(bitmap, 0)), timeout, TimeUnit.MILLISECONDS)
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

        val isTrue3D = horizontalRatio >= MIN_3D_NOSE_PLANE_RATIO &&
                verticalRatio >= MIN_3D_VERTICAL_CURVE_RATIO &&
                eyeSocketRatio >= MIN_3D_EYE_SOCKET_RATIO

        return Depth3DMetrics(horizontalRatio, verticalRatio, isTrue3D)
    }

    // ───────────────────────── نسيج الجلد والإضاءة ─────────────────────────

    private class SkinStats(val sharpness: Float, val chroma: Float, val luma: Float)

    private fun measureSkin(bitmap: Bitmap): SkinStats? {
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
        var lumaSum = 0.0

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
                lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                count++
            }
        }

        if (count == 0) return null
        val sharpness = (laplacianSum / count).toFloat()
        val cbVar = max(0.0, (cbSqSum / count) - (cbSum / count).pow(2))
        val crVar = max(0.0, (crSqSum / count) - (crSum / count).pow(2))
        val chromaStd = sqrt((cbVar + crVar) * 0.5).toFloat()
        return SkinStats(sharpness, chromaStd, (lumaSum / count).toFloat())
    }

    private fun updateSceneLuma(nv21: ByteArray, width: Int, height: Int): Float {
        val frameSize = width * height
        if (nv21.size < frameSize || frameSize <= 0) return lastFrameLuma
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
        frameSeq++
        return lastFrameLuma
    }

    // ───────────────────────── الكشف والمحاذاة ─────────────────────────

    private class DetectedFrame(
        val bitmap: Bitmap,
        val scaledBox: Rect,
        val face: Face,
        val invScale: Float,
        val yaw: Float,
        val pitch: Float,
        val roll: Float,
        val leftEyeOpen: Float,
        val rightEyeOpen: Float,
        val allFacesCount: Int
    )

    private fun detectFastNV21(nv21: ByteArray, width: Int, height: Int, requestedRotation: Int): DetectedFrame? {
        val known = lastWorkingRotation
        val probeAll = known == null || (noFaceStreak % NO_FACE_PROBE_EVERY == NO_FACE_PROBE_EVERY - 1)
        val candidates: List<Int> = if (probeAll || known == null) {
            listOfNotNull(known, requestedRotation, 270, 90, 0, 180).distinct()
        } else {
            listOf(known)
        }
        val timeout = if (warmedUp) 300L else 1500L

        for (rot in candidates) {
            val inputImage = InputImage.fromByteArray(nv21, width, height, rot, InputImage.IMAGE_FORMAT_NV21)
            val faces = runCatching {
                Tasks.await(faceDetector.process(inputImage), timeout, TimeUnit.MILLISECONDS)
            }.getOrNull()

            if (!faces.isNullOrEmpty()) {
                noFaceStreak = 0
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
                return DetectedFrame(
                    bitmap = rotated,
                    scaledBox = scaledBox,
                    face = primaryFace,
                    invScale = invScale,
                    yaw = primaryFace.headEulerAngleY,
                    pitch = primaryFace.headEulerAngleX,
                    roll = primaryFace.headEulerAngleZ,
                    leftEyeOpen = primaryFace.leftEyeOpenProbability ?: 1f,
                    rightEyeOpen = primaryFace.rightEyeOpenProbability ?: 1f,
                    allFacesCount = faces.size
                )
            }
        }
        noFaceStreak++
        return null
    }

    /**
     * محاذاة قياسية: تحويل تشابه يُرسَم مباشرة في صورة size×size (لا دوران للصورة كاملة ولا انزياح).
     * العينان تُرتَّبان بحسب الإحداثي x لا بحسب اسم المعلم، فلا ينقلب الوجه أبداً.
     */
    private fun alignFace(d: DetectedFrame, size: Int): Bitmap? {
        val f = d.face
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val ml = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mr = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val inv = d.invScale

        val mouth: PointF? = if (ml != null && mr != null) {
            PointF((ml.x + mr.x) * 0.5f * inv, (ml.y + mr.y) * 0.5f * inv)
        } else null

        val t = SimilarityTransform.alignToArcFace(
            le.x * inv, le.y * inv, re.x * inv, re.y * inv,
            mouth != null, mouth?.x ?: 0f, mouth?.y ?: 0f
        ) ?: return null

        val m = Matrix()
        m.setValues(t.toMatrixValues())
        if (size != 112) m.postScale(size / 112f, size / 112f)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(d.bitmap, m, alignPaint)
        return out
    }

    /** قصّ مربع حول مركز الصندوق بمعامل scale، مرسوماً مباشرة في target×target. */
    private fun cropSquare(source: Bitmap, box: Rect, scale: Float, target: Int): Bitmap {
        val side = max(box.width(), box.height()) * scale
        val left = box.exactCenterX() - side * 0.5f
        val top = box.exactCenterY() - side * 0.5f
        val m = Matrix()
        m.postTranslate(-left, -top)
        val s = target / max(1f, side)
        m.postScale(s, s)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, m, cropPaint)
        return out
    }

    private fun centroidOf(list: List<FaceTemplate>): FloatArray? {
        if (list.isEmpty()) return null
        val dim = list[0].embedding.size
        val sum = FloatArray(dim)
        for (tpl in list) {
            if (tpl.embedding.size == dim) {
                for (i in 0 until dim) sum[i] += tpl.embedding[i]
            }
        }
        return l2Normalize(sum)
    }

    // ───────────────────────── النماذج ─────────────────────────

    private fun runAntiSpoofing(bitmap: Bitmap): Float {
        val interpreter = antiSpoofInterpreter ?: return -1.0f
        val input = bitmapToFloatBuffer(bitmap, mode = 0, nchw = spoofNchw)
        val output = Array(1) { FloatArray(3) }
        synchronized(spoofLock) { interpreter.run(input, output) }

        // MiniFASNet ثلاثي الفئات: 0 = تزييف، 1 = حقيقي، 2 = تزييف
        val logits = output[0]
        val maxLogit = max(logits[0], max(logits[1], logits[2]))
        val expReal = exp((logits[1] - maxLogit).toDouble())
        val sumExp = exp((logits[0] - maxLogit).toDouble()) + expReal + exp((logits[2] - maxLogit).toDouble())
        return (expReal / sumExp).toFloat()
    }

    private fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        val interpreter = recognizerInterpreter ?: return null
        val input = bitmapToFloatBuffer(bitmap, mode = 2, nchw = false)
        val output = Array(1) { FloatArray(recOutputSize) }
        synchronized(recLock) { interpreter.run(input, output) }
        return l2Normalize(output[0])
    }

    private fun fastNv21ToDownsampledBitmap(nv21: ByteArray, width: Int, height: Int, step: Int): Bitmap? {
        return try {
            val frameSize = width * height
            if (nv21.size < frameSize) return null
            val outW = width / step
            val outH = height / step
            val argb = argbCache[0]?.takeIf { it.size == outW * outH }
                ?: IntArray(outW * outH).also { argbCache[0] = it }
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

    /**
     * mode 2: RGB مطبَّع (x-127.5)/128 (نماذج MobileFaceNet). mode 0: BGR خام 0..255 (MiniFASNet).
     * nchw=true: مخطط قنوات متتالية [1,3,S,S] بدل [1,S,S,3].
     */
    private fun bitmapToFloatBuffer(bitmap: Bitmap, mode: Int, nchw: Boolean): ByteBuffer {
        val size = bitmap.width
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        if (nchw) {
            for (c in 0 until 3) {
                for (p in pixels) buffer.putFloat(channelValue(p, c, mode))
            }
        } else {
            for (p in pixels) {
                buffer.putFloat(channelValue(p, 0, mode))
                buffer.putFloat(channelValue(p, 1, mode))
                buffer.putFloat(channelValue(p, 2, mode))
            }
        }
        buffer.rewind()
        return buffer
    }

    /** c: ترتيب القناة في الإخراج (0..2). mode 2 => RGB مطبَّع، غير ذلك => BGR خام. */
    private fun channelValue(p: Int, c: Int, mode: Int): Float {
        val r = ((p shr 16) and 0xFF).toFloat()
        val g = ((p shr 8) and 0xFF).toFloat()
        val b = (p and 0xFF).toFloat()
        return if (mode == 2) {
            val v = when (c) {
                0 -> r
                1 -> g
                else -> b
            }
            (v - 127.5f) / 128.0f
        } else {
            when (c) {
                0 -> b
                1 -> g
                else -> r
            }
        }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var s = 0.0
        for (v in vec) s += v.toDouble() * v
        val norm = sqrt(s).toFloat()
        return if (norm > 0f) FloatArray(vec.size) { vec[it] / norm } else vec
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = VerifySession.dot(a, b)

    // ───────────────────────── تخزين القوالب (مشفّر إلزامياً) ─────────────────────────

    /** @return true فقط إذا كُتب الملف مشفّراً بنجاح. لا حفظ نصّي أبداً. */
    private fun saveTemplates(list: List<FaceTemplate>, stats: FloatArray): Boolean {
        val dir = storageDir ?: return false
        val enc = encryptor ?: return false
        val saved: Boolean = synchronized(storeLock) {
            try {
                val raw = TemplateCodec.encode(
                    list.map { TemplateCodec.Entry(it.poseId, it.noseRatio, it.embedding) },
                    stats
                )
                var encrypted: ByteArray? = enc.encrypt(raw)
                if (encrypted == null || encrypted.isEmpty()) {
                    // محاولة ثانية بمُشفِّر جديد (يُنشئ مفتاح Keystore إن لم يكن موجوداً)
                    encrypted = CustomUnlockEncryptor().encrypt(raw)
                }
                if (encrypted == null || encrypted.isEmpty()) {
                    Log.e(TAG, "encryption unavailable: refusing to store templates in plaintext")
                    false
                } else {
                    writeAtomically(File(dir, DB_FILE_NAME), encrypted)
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save templates", e)
                false
            }
        }
        return saved
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("cannot replace template file")
            }
        }
    }

    private fun loadTemplates() {
        val dir = storageDir ?: return
        migrateLegacyFiles(dir)
        val file = File(dir, DB_FILE_NAME)
        synchronized(storeLock) {
            if (!file.exists()) {
                templates = emptyList()
                return
            }
            try {
                val raw = encryptor?.decrypt(file.readBytes())
                if (raw == null || raw.isEmpty()) {
                    Log.e(TAG, "templates unreadable (decrypt failed): re-enrollment required")
                    templates = emptyList()
                    return
                }
                val decoded = TemplateCodec.decode(raw)
                if (recognizerInterpreter != null && decoded.entries[0].embedding.size != recOutputSize) {
                    Log.e(TAG, "templates dim != model dim: re-enrollment required")
                    templates = emptyList()
                    return
                }
                templates = decoded.entries.map { FaceTemplate(it.poseId, it.embedding, it.noseRatio) }
                calibration = decoded.stats
            } catch (e: Exception) {
                // فشل مغلق: ملف تالف/معبوث به => لا قوالب (لا قبول جزئي أبداً)
                Log.e(TAG, "Failed to load templates (fail-closed)", e)
                templates = emptyList()
            }
        }
    }
}

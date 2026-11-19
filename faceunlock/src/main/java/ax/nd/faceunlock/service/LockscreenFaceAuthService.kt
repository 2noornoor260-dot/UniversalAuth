package ax.nd.faceunlock.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.getSystemService
import ax.nd.faceunlock.FaceApplication
import ax.nd.faceunlock.LibManager
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.pref.Prefs
import ax.nd.faceunlock.util.Util
import ax.nd.universalauth.xposed.common.XposedConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

class LockscreenFaceAuthService :
    AccessibilityService(),
    FaceAuthServiceCallbacks,
    SensorEventListener,
    SmartFaceEngine.VerificationFeedbackListener {

    private var windowManager: WindowManager? = null

    // 1. لوحة الإضاءة البيضاء الليلية (تُقفل عند التشغيل لمنع الوميض المتكرر، وتعمل في الظلام الحقيقي فقط)
    private var fillLightView: NightFillLightPanelView? = null
    private var fillLightAdded: Boolean = false
    private var fillLightLatchedOn: Boolean = false
    private var fillLightAnimator: ValueAnimator? = null
    private val fillLightParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
        screenBrightness = -1.0f
    }

    // 2. كبسولة آبل الفيزيائية أحادية اللون (أبيض ناصع فقط على خلفية AMOLED سوداء منغمسة في الحافة)
    private var notchView: AppleWhiteBezelCapsuleView? = null
    private var notchAdded: Boolean = false
    private val notchParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        x = 0
        y = 0
        screenBrightness = -1.0f
    }

    private var active: Boolean = false
    private var lockStateReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var controller: FaceAuthServiceController? = null
    private var keyguardManager: KeyguardManager? = null
    private var displayManager: DisplayManager? = null
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var handler: Handler? = null

    private var showStartTimeMs: Long = 0L
    private var latestLuxReading: Float = -1f
    private var darkConfirmTicks: Int = 0

    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope
    private lateinit var prefs: Prefs
    private var booted = false

    private val autoTimeoutRunnable = Runnable {
        if (active) {
            hide(animated = true)
        }
    }

    // فاحص دوري ذكي للإضاءة المحيطة يعمل سواء كان الجهاز يملك مستشعر ضوء مادي أم يعتمد على سطوع الكاميرا
    private val ambientDarknessMonitor = object : Runnable {
        override fun run() {
            if (!active || fillLightLatchedOn) return
            evaluateNightFillLightNeed()
            if (active && !fillLightLatchedOn) {
                handler?.postDelayed(this, 160L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        serviceJob = Job()
        serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

        prefs = FaceApplication.getApp().prefs
        controller = FaceAuthServiceController(this, prefs, this)
        SmartFaceEngine.setFeedbackListener(this)

        windowManager = getSystemService()
        keyguardManager = getSystemService()
        displayManager = getSystemService()
        sensorManager = getSystemService()
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        handler = Handler(Looper.getMainLooper())

        booted = !prefs.requirePinOnBoot.get()
        if (booted) {
            reconfigureUnlockHook()
        }
        setupUnlockReceiver()
    }

    private fun setupUnlockReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        if (!booted) {
                            booted = true
                            reconfigureUnlockHook()
                        }
                        prefs.failedUnlockAttempts.set(0)
                        forceImmediateCleanup()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        forceImmediateCleanup()
                    }
                }
            }
        }
        registerReceiver(unlockReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let {
            unregisterReceiver(it)
            unlockReceiver = null
        }
    }

    private fun reconfigureUnlockHook() {
        serviceScope.launch {
            prefs.earlyUnlockHook.asFlow().collect { earlyUnlock ->
                unregisterLockStateReceiver()
                if (earlyUnlock) {
                    registerEarlyUnlockReceiver()
                } else {
                    registerNormalReceiver()
                }
            }
        }
    }

    private fun registerNormalReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        lockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                doubleCheckLockscreenState()
            }
        }
        registerReceiver(lockStateReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    private fun registerEarlyUnlockReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(XposedConstants.ACTION_EARLY_UNLOCK)
        }
        lockStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent) {
                val mode = p1.getBooleanExtra(XposedConstants.EXTRA_EARLY_UNLOCK_MODE, false)
                if (mode) {
                    show()
                } else {
                    hide(animated = true)
                }
            }
        }
        registerReceiver(lockStateReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    private fun unregisterLockStateReceiver() {
        lockStateReceiver?.let {
            unregisterReceiver(it)
            lockStateReceiver = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!LibManager.libsLoaded.get()) {
            Log.w(TAG, "Libs not loaded, disabling self...")
            disableSelf()
            return
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SmartFaceEngine.setFeedbackListener(null)
        unregisterLockStateReceiver()
        unregisterUnlockReceiver()
        forceImmediateCleanup()
        serviceJob.cancel()
    }

    private fun doubleCheckLockscreenState() {
        val mainDisplayOn = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        if (mainDisplayOn && keyguardManager?.isKeyguardLocked == true) {
            show()
        } else {
            forceImmediateCleanup()
        }
    }

    private fun getCurrentDisplayRotation(): Int {
        return try {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    private fun getCameraEdgeForRotation(rotation: Int): CameraEdge {
        return when (rotation) {
            Surface.ROTATION_90 -> CameraEdge.TOP
            Surface.ROTATION_180 -> CameraEdge.LEFT
            Surface.ROTATION_270 -> CameraEdge.BOTTOM
            else -> CameraEdge.RIGHT
        }
    }

    private fun show() {
        if (active) return
        if (!Util.isFaceUnlockEnrolled(this)) return

        active = true
        showStartTimeMs = System.currentTimeMillis()
        latestLuxReading = -1f
        darkConfirmTicks = 0
        fillLightLatchedOn = false
        SmartFaceEngine.lastFrameLuma = -1f

        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (smartPrefs.getBoolean(SmartFaceEngine.KEY_CAPSULE_ANIM, true)) {
            showWaterdropNotch()
        }

        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.postDelayed(autoTimeoutRunnable, 5500L)

        handler?.removeCallbacks(ambientDarknessMonitor)
        handler?.postDelayed(ambientDarknessMonitor, 280L)

        controller?.start()
    }

    private fun hide(animated: Boolean) {
        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.removeCallbacks(ambientDarknessMonitor)
        sensorManager?.unregisterListener(this)
        fillLightLatchedOn = false
        hideFillLightPanel(animated)

        if (active) {
            active = false
            controller?.stop()
        }

        if (animated && notchAdded && notchView != null) {
            notchView?.retractIntoBezel {
                removeNotchOverlay()
            }
        } else {
            removeNotchOverlay()
        }
    }

    private fun forceImmediateCleanup() {
        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.removeCallbacks(ambientDarknessMonitor)
        sensorManager?.unregisterListener(this)
        fillLightLatchedOn = false
        if (active) {
            active = false
            controller?.stop()
        }
        hideFillLightPanel(animated = false)
        removeNotchOverlay()
    }

    private fun showWaterdropNotch() {
        removeNotchOverlay()
        try {
            val edge = getCameraEdgeForRotation(getCurrentDisplayRotation())
            notchParams.gravity = when (edge) {
                CameraEdge.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                CameraEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                CameraEdge.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                CameraEdge.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            notchParams.x = 0
            notchParams.y = 0

            val view = AppleWhiteBezelCapsuleView(this, edge)
            notchView = view
            windowManager?.addView(view, notchParams)
            notchAdded = true
            view.pullFromBezelSpring()
        } catch (_: Exception) {
        }
    }

    private fun removeNotchOverlay() {
        if (notchAdded) {
            try {
                notchView?.stopAllPhysics()
                windowManager?.removeView(notchView)
            } catch (_: Exception) {
            }
            notchView = null
            notchAdded = false
        }
    }

    /**
     * تقييم الظلام المحيط بدون أي وميض:
     * 1. ينتظر استقرار تعريض الكاميرا الأولي (280ms)
     * 2. يعتمد على سطوع الكاميرا الفعلي (lastFrameLuma) مع مستشعر الضوء إن وُجد
     * 3. بمجرد تفعيل الإضاءة الليلية تُقفل (Latch) طوال الجلسة حتى لا تنطفئ وتومض عند إضاءة الوجه!
     */
    private fun evaluateNightFillLightNeed() {
        if (!active || fillLightLatchedOn) return
        if (System.currentTimeMillis() - showStartTimeMs < 280L) return

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (!smartPrefs.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, true)) return

        val camLuma = SmartFaceEngine.lastFrameLuma
        if (camLuma < 0f) return // بانتظار وصول أول إطار فعلي من الكاميرا

        // إذا كانت الغرفة مضاءة بوضوح، لا تشغّل اللوحة أبداً
        if (camLuma > 60f) {
            darkConfirmTicks = 0
            return
        }

        // إذا كان مستشعر الضوء المادي متاحاً ويُبلغ عن نور ساطع (> 8 lux)
        if (lightSensor != null && latestLuxReading > 8.0f) {
            darkConfirmTicks = 0
            return
        }

        // الكاميرا تؤكد أن المشهد مظلم فعلياً (< 46) لمدة قراءتين متتاليتين
        if (camLuma < 46f) {
            darkConfirmTicks++
            if (darkConfirmTicks >= 2) {
                fillLightLatchedOn = true
                showSmoothNightFillLight()
            }
        } else {
            darkConfirmTicks = 0
        }
    }

    private fun showSmoothNightFillLight() {
        if (fillLightAdded) return
        try {
            val view = NightFillLightPanelView(this)
            fillLightView = view
            fillLightParams.screenBrightness = 0.08f
            view.alpha = 0f
            windowManager?.addView(view, fillLightParams)
            fillLightAdded = true

            fillLightAnimator?.cancel()
            fillLightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    if (fillLightAdded && fillLightView != null) {
                        fillLightView?.alpha = t * 0.85f
                        fillLightParams.screenBrightness = 0.08f + (0.84f * t)
                        try {
                            windowManager?.updateViewLayout(fillLightView, fillLightParams)
                        } catch (_: Exception) {
                        }
                    }
                }
                start()
            }
        } catch (_: Exception) {
        }
    }

    private fun hideFillLightPanel(animated: Boolean) {
        fillLightAnimator?.cancel()
        if (!fillLightAdded) return

        if (animated && fillLightView != null) {
            val startAlpha = fillLightView?.alpha ?: 0.85f
            val startBright = fillLightParams.screenBrightness.coerceAtLeast(0.08f)
            fillLightAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 240L
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    if (fillLightAdded && fillLightView != null) {
                        fillLightView?.alpha = startAlpha * t
                        fillLightParams.screenBrightness = if (t <= 0.05f) -1.0f else (startBright * t)
                        try {
                            windowManager?.updateViewLayout(fillLightView, fillLightParams)
                        } catch (_: Exception) {
                        }
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeFillLightImmediately()
                    }
                })
                start()
            }
        } else {
            removeFillLightImmediately()
        }
    }

    private fun removeFillLightImmediately() {
        if (fillLightAdded) {
            try {
                fillLightParams.screenBrightness = -1.0f
                windowManager?.removeView(fillLightView)
            } catch (_: Exception) {
            }
            fillLightView = null
            fillLightAdded = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event?.sensor?.type != Sensor.TYPE_LIGHT) return
        latestLuxReading = event.values.firstOrNull() ?: -1f
        evaluateNightFillLightNeed()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onScanningState(faceDetected: Boolean, confidence: Float) {
        handler?.post {
            notchView?.setScanningEngagement(faceDetected)
        }
    }

    override fun onVerifySuccess() {
    }

    override fun onVerifyRejected(reasonCode: Int) {
        handler?.post {
            notchView?.triggerRefinedWhiteLockRejectOnce()
        }
    }

    override fun onAuthed() {
        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val chimeType = smartPrefs.getString(SmartFaceEngine.KEY_UNLOCK_CHIME_TYPE, "apple_glass") ?: "apple_glass"
        if (chimeType != "none") {
            AppleAcousticChimePlayer.play(chimeType)
        }

        val unlockAnimation = when (prefs.unlockAnimation.get()) {
            "mode_wake_and_unlock" -> XposedConstants.MODE_WAKE_AND_UNLOCK
            "mode_wake_and_unlock_pulsing" -> XposedConstants.MODE_WAKE_AND_UNLOCK_PULSING
            else -> XposedConstants.MODE_UNLOCK_FADING
        }
        sendBroadcast(Intent(XposedConstants.ACTION_UNLOCK_DEVICE).apply {
            putExtra(XposedConstants.EXTRA_UNLOCK_MODE, unlockAnimation)
            putExtra(XposedConstants.EXTRA_BYPASS_KEYGUARD, prefs.bypassKeyguard.get())
        })

        handler?.post {
            handler?.removeCallbacks(autoTimeoutRunnable)
            handler?.removeCallbacks(ambientDarknessMonitor)
            sensorManager?.unregisterListener(this)
            fillLightLatchedOn = false
            hideFillLightPanel(animated = true)
            active = false

            if (notchAdded && notchView != null) {
                notchView?.morphSphereToUnlockedWhitePadlockAndRetract {
                    removeNotchOverlay()
                }
            } else {
                removeNotchOverlay()
            }
        }
    }

    override fun onError(errId: Int, message: String) {
        handler?.post {
            hide(animated = true)
        }
    }

    private enum class CameraEdge {
        TOP, BOTTOM, LEFT, RIGHT
    }

    private class NightFillLightPanelView(ctx: Context) : View(ctx) {
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w * 0.5f
            val cy = h * 0.5f
            val radius = maxOf(w, h) * 0.80f

            panelPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb(145, 252, 253, 255),
                    Color.argb(200, 255, 255, 255),
                    Color.argb(232, 255, 255, 255)
                ),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, panelPaint)
        }
    }

    /**
     * كبسولة آبل الفيزيائية أحادية اللون (أبيض ناصع #FFFFFF فقط على خلفية #000000 منغمسة في الحافة):
     * - أثناء المسح: كرة ثلاثية الأبعاد بيضاء حية تدور خطوطها العرضية والطولية مع نبض مسح ضوئي (مثل تحديث Face ID الحديث).
     * - عند النجاح: تتحول الكرة فيزيائياً بمرونة زنبركية إلى قفل أبيض يرتفع ذراعه ويتأرجح مفتوحاً بسلاسة مذهلة.
     * - عند الفشل (مرة واحدة فقط كل محاولتي رفض): تتحول إلى قفل أبيض يحاول الفتح قليلاً ثم يرتد مغلقاً مع إزاحة هادئة لا تكسر انغماس الكبسولة في الحافة.
     */
    private class AppleWhiteBezelCapsuleView(ctx: Context, private val edge: CameraEdge) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val baseSpanPx = (168f * density).toInt()
        private val baseDepthPx = (46f * density).toInt()

        private val amoledBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val whiteRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(42, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
        }
        private val whiteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.75f * density
        }
        private val whiteScanBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2.2f * density
        }
        private val whiteFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val notchPath = Path()

        // متغيرات الفيزياء والتحول
        private var springPull = 0f            // خروج وانكماش الكبسولة من الحافة
        private var continuousPhase = 0f       // دوران الكرة ثلاثية الأبعاد ونبض المسح
        private var morphToPadlock = 0f        // 0 = كرة المسح 3D، 1 = القفل الأبيض
        private var shackleLiftProgress = 0f   // 0 = مغلق، 1 = مفتوح بالكامل، 0.28 = محاولة فتح فاشلة
        private var bezelGlideOffset = 0f      // انزلاق طفيف جداً بمحاذاة الحافة دون فصل الكبسولة عنها أبداً
        private var faceEngaged = false
        private var isUnlockedState = false
        private var isRejectAnimRunning = false

        private var loopAnimator: ValueAnimator? = null
        private var springAnimator: ValueAnimator? = null
        private var morphAnimator: ValueAnimator? = null
        private var rejectAnimator: ValueAnimator? = null

        init {
            startLive3DScanLoop()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (edge == CameraEdge.TOP || edge == CameraEdge.BOTTOM) {
                setMeasuredDimension(baseSpanPx, baseDepthPx)
            } else {
                setMeasuredDimension(baseDepthPx, baseSpanPx)
            }
        }

        private fun startLive3DScanLoop() {
            loopAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    continuousPhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun setScanningEngagement(engaged: Boolean) {
            if (isUnlockedState || isRejectAnimRunning) return
            if (this.faceEngaged != engaged) {
                this.faceEngaged = engaged
                invalidate()
            }
        }

        fun pullFromBezelSpring() {
            springAnimator?.cancel()
            springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 540L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    springPull = (1.0 - exp(-6.8 * t) * cos(10.5 * t)).toFloat().coerceAtLeast(0f)
                    invalidate()
                }
                start()
            }
        }

        /**
         * نجاح الفتح: تتحول الكرة ثلاثية الأبعاد البيضاء فيزيائياً إلى قفل أبيض يرتفع ذراعه ويتأرجح مفتوحاً
         */
        fun morphSphereToUnlockedWhitePadlockAndRetract(onDone: () -> Unit) {
            if (isUnlockedState) return
            isUnlockedState = true
            isRejectAnimRunning = false
            rejectAnimator?.cancel()
            bezelGlideOffset = 0f

            morphAnimator?.cancel()
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 620L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    // تحول فيزيائي مرن من الكرة 3D إلى القفل (0 -> 0.48)
                    val mt = (t / 0.48f).coerceIn(0f, 1f)
                    morphToPadlock = (1.0 - exp(-6.0 * mt) * cos(8.0 * mt)).toFloat().coerceIn(0f, 1.05f)
                    // ارتفاع وتأرجح ذراع القفل بفيزياء زنبركية راقية (0.28 -> 1.0)
                    val st = ((t - 0.28f) / 0.72f).coerceIn(0f, 1f)
                    shackleLiftProgress = if (st <= 0f) 0f else (1.0 - exp(-6.2 * st) * cos(9.5 * st)).toFloat()
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        postDelayed({ retractIntoBezel(onDone) }, 240L)
                    }
                })
                start()
            }
        }

        /**
         * فشل الفتح (مرة واحدة فقط): تتحول الكرة إلى قفل أبيض يحاول الفتح قليلاً ثم يرتد مغلقاً
         * مع انزلاق هادئ جداً يحافظ على انغماس الكبسولة الكامل في الحافة
         */
        fun triggerRefinedWhiteLockRejectOnce() {
            if (isUnlockedState || isRejectAnimRunning) return
            isRejectAnimRunning = true

            rejectAnimator?.cancel()
            rejectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 920L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    when {
                        // 1. تحول سلس من الكرة 3D إلى القفل الأبيض (0 -> 0.24)
                        t < 0.24f -> {
                            val p = t / 0.24f
                            morphToPadlock = p * p * (3f - 2f * p)
                            shackleLiftProgress = 0f
                            bezelGlideOffset = 0f
                        }
                        // 2. القفل يحاول الفتح (يرتفع ذراعه 26%) ثم يعلق ويرتد مغلقاً مع نبضة حافة راقية (0.24 -> 0.72)
                        t < 0.72f -> {
                            morphToPadlock = 1f
                            val p = (t - 0.24f) / 0.48f
                            // موجة محاولة فتح ثم ارتداد للإغلاق
                            val attemptWave = sin(p * PI).toFloat().pow(1.6f)
                            shackleLiftProgress = attemptWave * 0.28f
                            // انزلاق هادئ جداً بمحاذاة الحافة (موجة واحدة ناعمة لا تكسر الحواف)
                            bezelGlideOffset = sin(p * 2.0 * PI).toFloat() * exp(-2.2 * p).toFloat() * (3.2f * density)
                        }
                        // 3. العودة السلسة لكرة المسح ثلاثية الأبعاد (0.72 -> 1.0)
                        else -> {
                            val p = 1f - ((t - 0.72f) / 0.28f)
                            morphToPadlock = p * p * (3f - 2f * p)
                            shackleLiftProgress = 0f
                            bezelGlideOffset = 0f
                        }
                    }
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        morphToPadlock = 0f
                        shackleLiftProgress = 0f
                        bezelGlideOffset = 0f
                        isRejectAnimRunning = false
                        invalidate()
                    }
                })
                start()
            }
        }

        fun retractIntoBezel(onDone: () -> Unit) {
            loopAnimator?.cancel()
            springAnimator?.cancel()
            morphAnimator?.cancel()
            rejectAnimator?.cancel()
            val startPull = springPull
            springAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 260L
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    springPull = startPull * (it.animatedValue as Float)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onDone()
                    }
                })
                start()
            }
        }

        fun stopAllPhysics() {
            loopAnimator?.cancel()
            springAnimator?.cancel()
            morphAnimator?.cancel()
            rejectAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (springPull <= 0.01f) return

            val w = width.toFloat()
            val h = height.toFloat()

            val breathWave = sin(continuousPhase).toFloat()
            val engageScale = if (faceEngaged) 1.035f else 1.0f
            val liveScale = (1.0f + 0.016f * breathWave) * engageScale

            val maxAllowedDepth = (if (edge == CameraEdge.TOP || edge == CameraEdge.BOTTOM) h else w) * 0.90f
            val depth = (maxAllowedDepth * 0.88f * springPull * liveScale).coerceAtMost(maxAllowedDepth)
            val spanScale = (0.64f + 0.34f * springPull) * liveScale

            notchPath.reset()
            var iconCx = w * 0.5f
            var iconCy = h * 0.5f

            // لاحظ: قاعدة المنحنى منغمسة بمقدار -2px خلف حافة الشاشة حتى لا ينكسر الاتصال بالحواف أبداً
            when (edge) {
                CameraEdge.TOP -> {
                    val centerX = w * 0.5f + bezelGlideOffset
                    val halfSpan = (w * spanScale) * 0.46f
                    val startX = centerX - halfSpan
                    val endX = centerX + halfSpan
                    val spanW = endX - startX
                    notchPath.moveTo(startX, -2f)
                    notchPath.cubicTo(startX + spanW * 0.22f, -2f, startX + spanW * 0.26f, depth, centerX, depth)
                    notchPath.cubicTo(endX - spanW * 0.26f, depth, endX - spanW * 0.22f, -2f, endX, -2f)
                    notchPath.close()
                    iconCx = centerX
                    iconCy = depth * 0.47f
                }
                CameraEdge.BOTTOM -> {
                    val centerX = w * 0.5f + bezelGlideOffset
                    val halfSpan = (w * spanScale) * 0.46f
                    val startX = centerX - halfSpan
                    val endX = centerX + halfSpan
                    val spanW = endX - startX
                    val apexY = h - depth
                    notchPath.moveTo(startX, h + 2f)
                    notchPath.cubicTo(startX + spanW * 0.22f, h + 2f, startX + spanW * 0.26f, apexY, centerX, apexY)
                    notchPath.cubicTo(endX - spanW * 0.26f, apexY, endX - spanW * 0.22f, h + 2f, endX, h + 2f)
                    notchPath.close()
                    iconCx = centerX
                    iconCy = h - depth * 0.47f
                }
                CameraEdge.RIGHT -> {
                    val centerY = h * 0.5f + bezelGlideOffset
                    val halfSpan = (h * spanScale) * 0.46f
                    val startY = centerY - halfSpan
                    val endY = centerY + halfSpan
                    val spanH = endY - startY
                    val apexX = w - depth
                    notchPath.moveTo(w + 2f, startY)
                    notchPath.cubicTo(w + 2f, startY + spanH * 0.22f, apexX, startY + spanH * 0.26f, apexX, centerY)
                    notchPath.cubicTo(apexX, endY - spanH * 0.26f, w + 2f, endY - spanH * 0.22f, w + 2f, endY)
                    notchPath.close()
                    iconCx = w - depth * 0.47f
                    iconCy = centerY
                }
                CameraEdge.LEFT -> {
                    val centerY = h * 0.5f + bezelGlideOffset
                    val halfSpan = (h * spanScale) * 0.46f
                    val startY = centerY - halfSpan
                    val endY = centerY + halfSpan
                    val spanH = endY - startY
                    notchPath.moveTo(-2f, startY)
                    notchPath.cubicTo(-2f, startY + spanH * 0.22f, depth, startY + spanH * 0.26f, depth, centerY)
                    notchPath.cubicTo(depth, endY - spanH * 0.26f, -2f, endY - spanH * 0.22f, -2f, endY)
                    notchPath.close()
                    iconCx = depth * 0.47f
                    iconCy = centerY
                }
            }

            // جسم الكبسولة أسود AMOLED خالص (#000000)
            canvas.drawPath(notchPath, amoledBlackPaint)

            // حافة بيضاء دقيقة جداً تعطي إحساس الزجاج المصقول دون أي ألوان مشتتة
            whiteRimPaint.alpha = ((32 + 16 * breathWave) * springPull.coerceIn(0f, 1f)).toInt().coerceIn(0, 64)
            canvas.drawPath(notchPath, whiteRimPaint)

            if (springPull > 0.35f) {
                val contentAlpha = (((springPull - 0.35f) / 0.65f) * 255f).toInt().coerceIn(0, 255)
                drawWhite3DSphereToPadlockMorph(canvas, iconCx, iconCy, 21f * density, contentAlpha)
            }
        }

        /**
         * رسم كرة المسح ثلاثية الأبعاد البيضاء الحية وتحويلها فيزيائياً إلى قفل أبيض راقٍ
         */
        private fun drawWhite3DSphereToPadlockMorph(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            size: Float,
            alpha: Int
        ) {
            val m = morphToPadlock.coerceIn(0f, 1f)

            // 1. الكرة ثلاثية الأبعاد البيضاء الحية مع خطوط المسح النابضة (Apple Face ID 3D Mesh Sphere)
            if (m < 0.98f) {
                val sphereAlpha = ((1f - m) * alpha).toInt().coerceIn(0, 255)
                val radius = (size * 0.50f) * (1f - 0.28f * m)

                // الإطار الخارجي للكرة
                whiteStrokePaint.color = Color.WHITE
                whiteStrokePaint.alpha = sphereAlpha
                whiteStrokePaint.strokeWidth = 1.65f * density
                canvas.drawCircle(cx, cy, radius, whiteStrokePaint)

                // خطوط الطول ثلاثية الأبعاد الدوارة (3D Meridians)
                whiteStrokePaint.strokeWidth = 1.15f * density
                for (k in 0 until 3) {
                    val angle = continuousPhase * 0.85f + k * (PI.toFloat() / 3f)
                    val rx = abs(cos(angle)) * radius
                    whiteStrokePaint.alpha = (sphereAlpha * (0.45f + 0.45f * abs(sin(angle)))).toInt().coerceIn(0, 255)
                    canvas.drawOval(RectF(cx - rx, cy - radius, cx + rx, cy + radius), whiteStrokePaint)
                }

                // خطوط العرض ثلاثية الأبعاد (3D Parallels)
                val lat1Y = cy - radius * 0.44f
                val lat1Rx = sqrt(max(0f, radius * radius - (radius * 0.44f).pow(2)))
                val lat1Ry = lat1Rx * 0.26f
                whiteStrokePaint.alpha = (sphereAlpha * 0.65f).toInt().coerceIn(0, 255)
                canvas.drawOval(RectF(cx - lat1Rx, lat1Y - lat1Ry, cx + lat1Rx, lat1Y + lat1Ry), whiteStrokePaint)

                val lat2Y = cy + radius * 0.44f
                canvas.drawOval(RectF(cx - lat1Rx, lat2Y - lat1Ry, cx + lat1Rx, lat2Y + lat1Ry), whiteStrokePaint)

                // خط المسح البيومتري النابض الذي يمسح الكرة صعوداً وهبوطاً (Biometric Pulse Scan Line)
                val scanNorm = sin(continuousPhase * 1.6f).toFloat() // -1 .. +1
                val scanY = cy + scanNorm * (radius * 0.84f)
                val chordHalfW = sqrt(max(0f, radius * radius - (scanY - cy).pow(2)))
                val pulseIntensity = 0.65f + 0.35f * cos(continuousPhase * 1.6f).toFloat()

                whiteScanBeamPaint.color = Color.WHITE
                whiteScanBeamPaint.alpha = (sphereAlpha * pulseIntensity).toInt().coerceIn(0, 255)
                whiteScanBeamPaint.strokeWidth = (if (faceEngaged) 2.4f else 1.9f) * density
                canvas.drawLine(cx - chordHalfW, scanY, cx + chordHalfW, scanY, whiteScanBeamPaint)
            }

            // 2. القفل الأبيض الفيزيائي الراقي (يظهر بانبثاق مرن ويفتح بسلاسة أو يحاول الفتح ويفشل)
            if (m > 0.02f) {
                val lockAlpha = (m * alpha).toInt().coerceIn(0, 255)
                val lockScale = 0.68f + 0.32f * morphToPadlock
                val bw = size * 0.66f * lockScale
                val bh = size * 0.50f * lockScale
                val bodyTop = cy - bh * 0.12f
                val bodyRect = RectF(cx - bw * 0.5f, bodyTop, cx + bw * 0.5f, bodyTop + bh)

                // جسم القفل الأبيض الناصع
                whiteFillPaint.color = Color.WHITE
                whiteFillPaint.alpha = lockAlpha
                canvas.drawRoundRect(bodyRect, 3.2f * density, 3.2f * density, whiteFillPaint)

                // ثقب المفتاح بلون أسود AMOLED
                whiteFillPaint.color = Color.BLACK
                whiteFillPaint.alpha = lockAlpha
                canvas.drawCircle(cx, bodyTop + bh * 0.46f, 1.45f * density * lockScale, whiteFillPaint)

                // ذراع القفل الأبيض (Shackle):
                // - عند النجاح: يرتفع ويتأرجح مفتوحاً بالكامل
                // - عند الفشل: يرتفع قليلاً كأنه يحاول الفتح ثم يرتد مغلقاً
                whiteStrokePaint.color = Color.WHITE
                whiteStrokePaint.alpha = lockAlpha
                whiteStrokePaint.strokeWidth = 2.15f * density

                val shackleR = bw * 0.31f
                val liftY = shackleLiftProgress * (bh * 0.48f)
                val swingDeg = if (isUnlockedState) (-shackleLiftProgress * 28f) else (-shackleLiftProgress * 6f)

                canvas.save()
                val pivotX = cx + shackleR
                val pivotY = bodyTop - liftY
                canvas.rotate(swingDeg, pivotX, pivotY)

                val shacklePath = Path().apply {
                    moveTo(cx - shackleR, bodyTop + 1f * density - liftY * 0.30f)
                    lineTo(cx - shackleR, bodyTop - shackleR * 0.85f - liftY)
                    arcTo(
                        RectF(
                            cx - shackleR,
                            bodyTop - shackleR * 1.85f - liftY,
                            cx + shackleR,
                            bodyTop + shackleR * 0.15f - liftY
                        ),
                        180f, 180f, false
                    )
                    lineTo(cx + shackleR, bodyTop + 1f * density - liftY)
                }
                canvas.drawPath(shacklePath, whiteStrokePaint)
                canvas.restore()
            }
        }
    }

    object AppleAcousticChimePlayer {
        fun play(type: String) {
            Thread {
                try {
                    val sampleRate = 44100
                    val durationSec = when (type) {
                        "crystal" -> 0.22
                        "titanium" -> 0.26
                        "silk" -> 0.42
                        else -> 0.36
                    }
                    val numSamples = (sampleRate * durationSec).toInt()
                    val pcm = ShortArray(numSamples)

                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate.toDouble()
                        val sample = when (type) {
                            "crystal" -> {
                                val env = exp(-t * 22.0) * min(1.0, t / 0.004)
                                (0.45 * sin(2.0 * PI * 523.25 * t)
                                        + 0.30 * sin(2.0 * PI * 783.99 * t)
                                        + 0.25 * sin(2.0 * PI * 261.63 * t)) * env
                            }
                            "titanium" -> {
                                val env1 = exp(-t * 26.0) * min(1.0, t / 0.003)
                                val t2 = (t - 0.055).coerceAtLeast(0.0)
                                val env2 = if (t >= 0.055) exp(-t2 * 18.0) * min(1.0, t2 / 0.004) else 0.0
                                (0.40 * sin(2.0 * PI * 440.0 * t) * env1
                                        + 0.45 * sin(2.0 * PI * 659.25 * t2) * env2
                                        + 0.20 * sin(2.0 * PI * 220.0 * t) * env1)
                            }
                            "silk" -> {
                                val env = exp(-t * 9.5) * min(1.0, t / 0.015)
                                (0.38 * sin(2.0 * PI * 587.33 * t)
                                        + 0.32 * sin(2.0 * PI * 739.99 * t)
                                        + 0.30 * sin(2.0 * PI * 880.00 * t)) * env
                            }
                            else -> {
                                val env1 = exp(-t * 14.0) * min(1.0, t / 0.006)
                                val t2 = (t - 0.085).coerceAtLeast(0.0)
                                val env2 = if (t >= 0.085) exp(-t2 * 11.0) * min(1.0, t2 / 0.006) else 0.0
                                (0.42 * sin(2.0 * PI * 698.46 * t) * env1
                                        + 0.16 * sin(2.0 * PI * 1396.91 * t) * env1
                                        + 0.48 * sin(2.0 * PI * 1046.50 * t2) * env2
                                        + 0.14 * sin(2.0 * PI * 523.25 * t2) * env2)
                            }
                        }
                        pcm[i] = (sample.coerceIn(-1.0, 1.0) * 18000.0).toInt().toShort()
                    }

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep((durationSec * 1000).toLong() + 80L)
                    track.release()
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    companion object {
        private val TAG = LockscreenFaceAuthService::class.simpleName
    }
}

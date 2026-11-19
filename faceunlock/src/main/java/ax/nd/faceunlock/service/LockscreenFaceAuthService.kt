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

    // 1. لوحة الإضاءة البيضاء الناعمة (تتحقق من مستشعر الضوء + سطوع الكاميرا الفعلي معاً لمنع تشغيلها في النور نهائياً)
    private var fillLightView: NightFillLightPanelView? = null
    private var fillLightAdded: Boolean = false
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

    // 2. كبسولة قطرة الماء الفيزيائية النابضة بالحياة
    private var notchView: LivingAppleNotchView? = null
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
    private var lastLuxValue: Float = -1f
    private var genuineDarkTicks: Int = 0

    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope
    private lateinit var prefs: Prefs
    private var booted = false

    private val autoTimeoutRunnable = Runnable {
        if (active) {
            hide(animated = true)
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
                        SmartFaceEngine.resetFailedAttempts(this@LockscreenFaceAuthService)
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

        // إذا تم تجاوز الحد الأقصى للمحاولات الفاشلة، لا تفتح الكاميرا حتى إدخال رمز الدخول
        if (SmartFaceEngine.isLockedOut(this)) {
            showLockedOutNotchBriefly()
            return
        }

        active = true
        showStartTimeMs = System.currentTimeMillis()
        lastLuxValue = -1f
        genuineDarkTicks = 0
        SmartFaceEngine.lastFrameLuma = 128f

        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (smartPrefs.getBoolean(SmartFaceEngine.KEY_CAPSULE_ANIM, true)) {
            showWaterdropNotch()
        }

        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.postDelayed(autoTimeoutRunnable, 5200L)

        controller?.start()
    }

    private fun showLockedOutNotchBriefly() {
        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (!smartPrefs.getBoolean(SmartFaceEngine.KEY_CAPSULE_ANIM, true)) return
        showWaterdropNotch()
        notchView?.triggerLockedRejectShake(finalLockout = true) {
            removeNotchOverlay()
        }
    }

    private fun hide(animated: Boolean) {
        handler?.removeCallbacks(autoTimeoutRunnable)
        sensorManager?.unregisterListener(this)
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
        sensorManager?.unregisterListener(this)
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

            val view = LivingAppleNotchView(this, edge)
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

    private fun showSmoothNightFillLight() {
        if (fillLightAdded) return
        try {
            val view = NightFillLightPanelView(this)
            fillLightView = view
            fillLightParams.screenBrightness = 0.06f
            view.alpha = 0f
            windowManager?.addView(view, fillLightParams)
            fillLightAdded = true

            fillLightAnimator?.cancel()
            fillLightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    if (fillLightAdded && fillLightView != null) {
                        fillLightView?.alpha = t * 0.84f
                        fillLightParams.screenBrightness = 0.06f + (0.86f * t)
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
            val startAlpha = fillLightView?.alpha ?: 0.84f
            val startBright = fillLightParams.screenBrightness.coerceAtLeast(0.06f)
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

    /**
     * التحقق المزدوج من الظلام الفعلي:
     * 1. مستشعر الضوء المحيط < 3.0 lux بعد استقراره (260ms)
     * 2. سطوع إطارات الكاميرا الفعلي (lastFrameLuma < 52f)
     * إذا كانت الكاميرا ترى إضاءة طبيعية (> 65f) فلن تعمل اللوحة البيضاء أبداً!
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values.firstOrNull() ?: 100f
        lastLuxValue = lux

        if (System.currentTimeMillis() - showStartTimeMs < 260L) return

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val nightLightEnabled = smartPrefs.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, true)
        val cameraSeesDarkness = SmartFaceEngine.lastFrameLuma < 52f
        val cameraSeesBrightRoom = SmartFaceEngine.lastFrameLuma > 68f

        if (cameraSeesBrightRoom || !nightLightEnabled || lux >= 3.2f) {
            genuineDarkTicks = 0
            hideFillLightPanel(animated = true)
            return
        }

        if (lux < 3.2f && cameraSeesDarkness) {
            genuineDarkTicks++
            if (genuineDarkTicks >= 2) {
                showSmoothNightFillLight()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onScanningState(faceDetected: Boolean, confidence: Float) {
        handler?.post {
            notchView?.setScanningEngagement(faceDetected, confidence)
        }
    }

    override fun onVerifySuccess() {
        // يتم التعامل معه في onAuthed()
    }

    override fun onVerifyRejected(reasonCode: Int, attemptsUsed: Int, maxAttempts: Int, lockedOut: Boolean) {
        handler?.post {
            if (lockedOut) {
                handler?.removeCallbacks(autoTimeoutRunnable)
                sensorManager?.unregisterListener(this)
                hideFillLightPanel(animated = true)
                if (active) {
                    active = false
                    controller?.stop()
                }
                if (notchAdded && notchView != null) {
                    notchView?.triggerLockedRejectShake(finalLockout = true) {
                        removeNotchOverlay()
                    }
                }
            } else {
                notchView?.triggerLockedRejectShake(finalLockout = false, onDone = null)
            }
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
            sensorManager?.unregisterListener(this)
            hideFillLightPanel(animated = true)
            active = false

            if (notchAdded && notchView != null) {
                notchView?.morphToUnlockedPadlockAndRetract {
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

    /**
     * لوحة إضاءة بيضاء ناعمة متدرجة تضيء الوجه بنعومة في الظلام الدامس فقط
     */
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
     * كبسولة آبل الفيزيائية النابضة بالحياة (LivingAppleNotchView):
     * - حركة خروج وانكماش بزنبرك فيزيائي مرن (Under-damped Spring Physics)
     * - نبض حي وتنفس طبيعي للوجه والعيون داخل الكبسولة أثناء البحث
     * - تحوّل فيزيائي سلس من رمز Face ID إلى قفل مفتوح يتأرجح ذراعه للأعلى عند النجاح
     * - تحوّل فيزيائي إلى قفل أحمر مغلق مع اهتزاز زنبركي أفقي عند الرفض
     */
    private class LivingAppleNotchView(ctx: Context, private val edge: CameraEdge) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val baseSpanPx = (164f * density).toInt()
        private val baseDepthPx = (46f * density).toInt()

        private val amoledBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val rimGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A84FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.15f * density
        }
        private val lockBodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val notchPath = Path()

        // متغيرات الفيزياء الزنبركية
        private var springPull = 0f          // 0 -> 1.08 -> 1.0
        private var pulseBreath = 0f         // نبض حي مستمر
        private var morphToLock = 0f         // 0 = Face ID, 1 = Padlock
        private var shackleOpenProgress = 0f // 0 = مغلق, 1 = مفتوح ومرتفع
        private var rejectShakeOffset = 0f   // اهتزاز الرفض الفيزيائي
        private var faceEngaged = false
        private var isUnlockedState = false
        private var isRejectedState = false

        private var breathAnimator: ValueAnimator? = null
        private var springAnimator: ValueAnimator? = null
        private var morphAnimator: ValueAnimator? = null
        private var shakeAnimator: ValueAnimator? = null

        init {
            startOrganicBreathing()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (edge == CameraEdge.TOP || edge == CameraEdge.BOTTOM) {
                setMeasuredDimension(baseSpanPx, baseDepthPx)
            } else {
                setMeasuredDimension(baseDepthPx, baseSpanPx)
            }
        }

        private fun startOrganicBreathing() {
            breathAnimator = ValueAnimator.ofFloat(0f, (2.0 * PI).toFloat()).apply {
                duration = 2100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulseBreath = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun setScanningEngagement(engaged: Boolean, confidence: Float) {
            if (isUnlockedState || isRejectedState) return
            this.faceEngaged = engaged
            invalidate()
        }

        /**
         * زنبرك فيزيائي مرن لسحب الكبسولة من حافة الكاميرا مع ارتداد طبيعي ناعم
         */
        fun pullFromBezelSpring() {
            springAnimator?.cancel()
            springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    // معادلة زنبرك متخامد: 1 - e^(-6.5t) * cos(11t)
                    springPull = (1.0 - exp(-6.5 * t) * cos(11.0 * t)).toFloat().coerceAtLeast(0f)
                    invalidate()
                }
                start()
            }
        }

        /**
         * التحول الفيزيائي الحي عند نجاح التحقق: Face ID -> قفل أخضر يُفتح ذراعه بمرونة
         */
        fun morphToUnlockedPadlockAndRetract(onDone: () -> Unit) {
            if (isUnlockedState) return
            isUnlockedState = true
            isRejectedState = false
            shakeAnimator?.cancel()
            rejectShakeOffset = 0f

            morphAnimator?.cancel()
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 560L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    // المرحلة الأولى (0 -> 0.45): تحول زوايا الوجه إلى جسم القفل
                    morphToLock = (t / 0.45f).coerceIn(0f, 1f)
                    // المرحلة الثانية (0.30 -> 1.0): ارتفاع ذراع القفل مع ارتداد زنبركي حي
                    val st = ((t - 0.30f) / 0.70f).coerceIn(0f, 1f)
                    shackleOpenProgress = if (st <= 0f) 0f else (1.0 - exp(-6.0 * st) * cos(10.0 * st)).toFloat()
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        postDelayed({ retractIntoBezel(onDone) }, 220L)
                    }
                })
                start()
            }
        }

        /**
         * التحول الفيزيائي الحي عند الرفض: Face ID -> قفل أحمر مغلق يهتز بفيزياء الزنبرك
         */
        fun triggerLockedRejectShake(finalLockout: Boolean, onDone: (() -> Unit)?) {
            if (isUnlockedState) return
            isRejectedState = true
            shackleOpenProgress = 0f

            shakeAnimator?.cancel()
            shakeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 620L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    morphToLock = (t / 0.25f).coerceIn(0f, 1f)
                    // اهتزاز أفقي متخامد يحاكي اهتزاز قفل iOS
                    val decay = exp(-4.8 * t).toFloat()
                    rejectShakeOffset = sin(t * 28.0).toFloat() * decay * (7.5f * density)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        rejectShakeOffset = 0f
                        if (finalLockout) {
                            postDelayed({ retractIntoBezel { onDone?.invoke() } }, 280L)
                        } else {
                            // العودة السلسة لحالة البحث
                            ValueAnimator.ofFloat(1f, 0f).apply {
                                duration = 260L
                                addUpdateListener {
                                    morphToLock = it.animatedValue as Float
                                    invalidate()
                                }
                                addListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) {
                                        isRejectedState = false
                                    }
                                })
                                start()
                            }
                        }
                    }
                })
                start()
            }
        }

        fun retractIntoBezel(onDone: () -> Unit) {
            breathAnimator?.cancel()
            springAnimator?.cancel()
            morphAnimator?.cancel()
            shakeAnimator?.cancel()
            val startPull = springPull
            springAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 250L
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
            breathAnimator?.cancel()
            springAnimator?.cancel()
            morphAnimator?.cancel()
            shakeAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (springPull <= 0.01f) return

            val w = width.toFloat()
            val h = height.toFloat()

            // نبضة تنفس حية تجعل الكبسولة تتفاعل بمرونة مع حالة المسح والفتح والرفض
            val breathWave = sin(pulseBreath).toFloat()
            val engageBoost = if (faceEngaged) 1.04f else 1.0f
            val unlockBoost = if (isUnlockedState) (1.0f + 0.06f * sin(morphToLock * PI).toFloat()) else 1.0f
            val liveScale = (1.0f + 0.022f * breathWave) * engageBoost * unlockBoost

            val maxAllowedDepth = (if (edge == CameraEdge.TOP || edge == CameraEdge.BOTTOM) h else w) * 0.90f
            val depth = (maxAllowedDepth * 0.88f * springPull * liveScale).coerceAtMost(maxAllowedDepth)
            val spanScale = (0.60f + 0.36f * springPull) * liveScale

            // تحديد اللون التفاعلي الحي
            val accentColor = when {
                isUnlockedState -> Color.parseColor("#30D158")
                isRejectedState -> Color.parseColor("#FF453A")
                faceEngaged -> Color.parseColor("#3898FF")
                else -> Color.parseColor("#0A84FF")
            }

            notchPath.reset()
            var iconCx = w * 0.5f
            var iconCy = h * 0.5f

            when (edge) {
                CameraEdge.TOP -> {
                    val centerX = w * 0.5f + rejectShakeOffset
                    val halfSpan = (w * spanScale) * 0.46f
                    val startX = centerX - halfSpan
                    val endX = centerX + halfSpan
                    val spanW = endX - startX
                    notchPath.moveTo(startX, 0f)
                    notchPath.cubicTo(startX + spanW * 0.22f, 0f, startX + spanW * 0.26f, depth, centerX, depth)
                    notchPath.cubicTo(endX - spanW * 0.26f, depth, endX - spanW * 0.22f, 0f, endX, 0f)
                    notchPath.close()
                    iconCx = centerX
                    iconCy = depth * 0.48f
                }
                CameraEdge.BOTTOM -> {
                    val centerX = w * 0.5f + rejectShakeOffset
                    val halfSpan = (w * spanScale) * 0.46f
                    val startX = centerX - halfSpan
                    val endX = centerX + halfSpan
                    val spanW = endX - startX
                    val apexY = h - depth
                    notchPath.moveTo(startX, h)
                    notchPath.cubicTo(startX + spanW * 0.22f, h, startX + spanW * 0.26f, apexY, centerX, apexY)
                    notchPath.cubicTo(endX - spanW * 0.26f, apexY, endX - spanW * 0.22f, h, endX, h)
                    notchPath.close()
                    iconCx = centerX
                    iconCy = h - depth * 0.48f
                }
                CameraEdge.RIGHT -> {
                    val centerY = h * 0.5f + rejectShakeOffset
                    val halfSpan = (h * spanScale) * 0.46f
                    val startY = centerY - halfSpan
                    val endY = centerY + halfSpan
                    val spanH = endY - startY
                    val apexX = w - depth
                    notchPath.moveTo(w, startY)
                    notchPath.cubicTo(w, startY + spanH * 0.22f, apexX, startY + spanH * 0.26f, apexX, centerY)
                    notchPath.cubicTo(apexX, endY - spanH * 0.26f, w, endY - spanH * 0.22f, w, endY)
                    notchPath.close()
                    iconCx = w - depth * 0.48f
                    iconCy = centerY
                }
                CameraEdge.LEFT -> {
                    val centerY = h * 0.5f + rejectShakeOffset
                    val halfSpan = (h * spanScale) * 0.46f
                    val startY = centerY - halfSpan
                    val endY = centerY + halfSpan
                    val spanH = endY - startY
                    notchPath.moveTo(0f, startY)
                    notchPath.cubicTo(0f, startY + spanH * 0.22f, depth, startY + spanH * 0.26f, depth, centerY)
                    notchPath.cubicTo(depth, endY - spanH * 0.26f, 0f, endY - spanH * 0.22f, 0f, endY)
                    notchPath.close()
                    iconCx = depth * 0.48f
                    iconCy = centerY
                }
            }

            // جسم النتوء بلون أسود AMOLED خالص (#000000)
            canvas.drawPath(notchPath, amoledBlackPaint)

            // توهج حافة زجاجي خفيف جداً يتنفس مع نبض الكبسولة
            val rimAlpha = ((38 + 24 * breathWave) * springPull.coerceIn(0f, 1f)).toInt().coerceIn(0, 90)
            rimGlowPaint.color = Color.argb(rimAlpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            canvas.drawPath(notchPath, rimGlowPaint)

            if (springPull > 0.35f) {
                val contentAlpha = (((springPull - 0.35f) / 0.65f) * 255f).toInt().coerceIn(0, 255)
                drawLivingMorphingGlyph(canvas, iconCx, iconCy, 20f * density, accentColor, contentAlpha, breathWave)
            }
        }

        /**
         * رسم وتحويل رمز Face ID فيزيائياً إلى قفل حي (مفتوح أو مغلق)
         */
        private fun drawLivingMorphingGlyph(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            size: Float,
            color: Int,
            alpha: Int,
            breathWave: Float
        ) {
            glyphPaint.color = color
            glyphPaint.alpha = alpha

            val m = morphToLock.coerceIn(0f, 1f)

            // 1. رسم رمز Face ID الحي (يتلاشى وينكمش بسلاسة عند التحول للقفل)
            if (m < 0.98f) {
                val faceAlpha = ((1f - m) * alpha).toInt().coerceIn(0, 255)
                glyphPaint.alpha = faceAlpha
                val s = size * (1f - 0.18f * m + 0.03f * breathWave)
                val l = cx - s * 0.5f
                val t = cy - s * 0.5f
                val r = l + s
                val b = t + s
                val arm = s * 0.26f
                val cr = s * 0.17f

                // الزوايا الأربع المرنة
                canvas.drawArc(RectF(l, t, l + 2 * cr, t + 2 * cr), 180f, 90f, false, glyphPaint)
                canvas.drawLine(l + cr, t, l + arm, t, glyphPaint)
                canvas.drawLine(l, t + cr, l, t + arm, glyphPaint)

                canvas.drawArc(RectF(r - 2 * cr, t, r, t + 2 * cr), 270f, 90f, false, glyphPaint)
                canvas.drawLine(r - arm, t, r - cr, t, glyphPaint)
                canvas.drawLine(r, t + cr, r, t + arm, glyphPaint)

                canvas.drawArc(RectF(l, b - 2 * cr, l + 2 * cr, b), 90f, 90f, false, glyphPaint)
                canvas.drawLine(l + cr, b, l + arm, b, glyphPaint)
                canvas.drawLine(l, b - arm, l, b - cr, glyphPaint)

                canvas.drawArc(RectF(r - 2 * cr, b - 2 * cr, r, b), 0f, 90f, false, glyphPaint)
                canvas.drawLine(r - arm, b, r - cr, b, glyphPaint)
                canvas.drawLine(r, b - arm, r, b - cr, glyphPaint)

                // تعبير الوجه الحي (حركة نظر خفيفة ورمشة عين طبيعية)
                val lookShiftX = sin(pulseBreath * 0.7f).toFloat() * (s * 0.045f)
                val lookShiftY = cos(pulseBreath * 0.5f).toFloat() * (s * 0.03f)
                val blinkTrigger = sin(pulseBreath * 1.5f).toFloat()
                val eyeHalfH = if (blinkTrigger > 0.94f) s * 0.01f else s * 0.055f

                val eyeY = t + s * 0.40f + lookShiftY
                canvas.drawLine(l + s * 0.35f + lookShiftX, eyeY - eyeHalfH, l + s * 0.35f + lookShiftX, eyeY + eyeHalfH, glyphPaint)
                canvas.drawLine(l + s * 0.65f + lookShiftX, eyeY - eyeHalfH, l + s * 0.65f + lookShiftX, eyeY + eyeHalfH, glyphPaint)

                // ابتسامة حية تتفاعل عند اقتراب التعرف
                val smileSweep = if (faceEngaged) 145f else 120f
                val smileStart = 90f - smileSweep * 0.5f
                canvas.drawArc(
                    RectF(l + s * 0.35f + lookShiftX, t + s * 0.47f + lookShiftY, l + s * 0.65f + lookShiftX, t + s * 0.68f + lookShiftY),
                    smileStart, smileSweep, false, glyphPaint
                )
            }

            // 2. رسم القفل الفيزيائي الحي (يظهر بسلاسة ويفتح ذراعه بمرونة زنبركية أو يهتز مغلقاً)
            if (m > 0.02f) {
                val lockAlpha = (m * alpha).toInt().coerceIn(0, 255)
                val lockScale = 0.72f + 0.28f * m
                val bw = size * 0.66f * lockScale
                val bh = size * 0.50f * lockScale
                val bodyTop = cy - bh * 0.15f
                val bodyRect = RectF(cx - bw * 0.5f, bodyTop, cx + bw * 0.5f, bodyTop + bh)

                // جسم القفل الممتلئ
                lockBodyFillPaint.color = color
                lockBodyFillPaint.alpha = lockAlpha
                canvas.drawRoundRect(bodyRect, 3.2f * density, 3.2f * density, lockBodyFillPaint)

                // ثقب المفتاح بلون أسود AMOLED
                lockBodyFillPaint.color = Color.BLACK
                lockBodyFillPaint.alpha = lockAlpha
                canvas.drawCircle(cx, bodyTop + bh * 0.46f, 1.45f * density * lockScale, lockBodyFillPaint)

                // ذراع القفل العلوي (Shackle) يتحرك ويرتفع ويتأرجح عند الفتح!
                glyphPaint.color = color
                glyphPaint.alpha = lockAlpha
                val shackleR = bw * 0.31f
                val liftY = shackleOpenProgress * (bh * 0.46f)
                val swingDeg = -shackleOpenProgress * 26f

                canvas.save()
                // محور الدوران عند الطرف الأيمن لذراع القفل
                val pivotX = cx + shackleR
                val pivotY = bodyTop - liftY
                canvas.rotate(swingDeg, pivotX, pivotY)

                val shacklePath = Path().apply {
                    moveTo(cx - shackleR, bodyTop + 1f * density - liftY * 0.35f)
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
                canvas.drawPath(shacklePath, glyphPaint)
                canvas.restore()
            }
        }
    }

    /**
     * مولّد نغمات آبل الصوتية المثقولة (Synthesized Polyphonic Harmonic Chimes)
     */
    object AppleAcousticChimePlayer {
        fun play(type: String) {
            Thread {
                try {
                    val sampleRate = 44100
                    val durationSec = when (type) {
                        "crystal" -> 0.22
                        "titanium" -> 0.26
                        "silk" -> 0.42
                        else -> 0.36 // apple_glass
                    }
                    val numSamples = (sampleRate * durationSec).toInt()
                    val pcm = ShortArray(numSamples)

                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate.toDouble()
                        val sample = when (type) {
                            "crystal" -> {
                                // نقرة كريستال عميقة ودافئة (C5 + G5 + Sub-octave)
                                val env = exp(-t * 22.0) * min(1.0, t / 0.004)
                                (0.45 * sin(2.0 * PI * 523.25 * t)
                                        + 0.30 * sin(2.0 * PI * 783.99 * t)
                                        + 0.25 * sin(2.0 * PI * 261.63 * t)) * env
                            }
                            "titanium" -> {
                                // تأكيد معدني مثقول (A4 -> E5 سريع مع أساس عميق)
                                val env1 = exp(-t * 26.0) * min(1.0, t / 0.003)
                                val t2 = (t - 0.055).coerceAtLeast(0.0)
                                val env2 = if (t >= 0.055) exp(-t2 * 18.0) * min(1.0, t2 / 0.004) else 0.0
                                (0.40 * sin(2.0 * PI * 440.0 * t) * env1
                                        + 0.45 * sin(2.0 * PI * 659.25 * t2) * env2
                                        + 0.20 * sin(2.0 * PI * 220.0 * t) * env1)
                            }
                            "silk" -> {
                                // وتر مخملي ناعم (D5 + F#5 + A5)
                                val env = exp(-t * 9.5) * min(1.0, t / 0.015)
                                (0.38 * sin(2.0 * PI * 587.33 * t)
                                        + 0.32 * sin(2.0 * PI * 739.99 * t)
                                        + 0.30 * sin(2.0 * PI * 880.00 * t)) * env
                            }
                            else -> {
                                // رنين آبل الزجاجي الكلاسيكي (F5 -> C6)
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

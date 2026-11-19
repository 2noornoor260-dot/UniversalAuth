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
import android.media.AudioManager
import android.media.ToneGenerator
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
import android.view.animation.PathInterpolator
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

class LockscreenFaceAuthService : AccessibilityService(), FaceAuthServiceCallbacks, SensorEventListener {

    private var windowManager: WindowManager? = null

    // 1. لوحة الإضاءة البيضاء الناعمة الشفافة (تظهر وترتفع إضاءتها بسلاسة في الظلام الدامس فقط)
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

    // 2. نتوء قطرة الماء (Waterdrop Notch) بلون أسود AMOLED ينسحب من حافة الكاميرا في كل الاتجاهات
    private var notchView: WaterdropCameraNotchView? = null
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
    private var darkReadingsCount: Int = 0

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

    /**
     * تحديد الحافة التي تقع بها الكاميرا الأمامية في Galaxy Tab A7 (SM-T505N) حسب دوران الشاشة:
     * - ROTATION_0 (طولي): الحافة اليمنى (Gravity.RIGHT)
     * - ROTATION_90 (أفقي): الحافة العلوية (Gravity.TOP)
     * - ROTATION_180 (طولي مقلوب): الحافة اليسرى (Gravity.LEFT)
     * - ROTATION_270 (أفقي مقلوب): الحافة السفلية (Gravity.BOTTOM)
     */
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
        darkReadingsCount = 0

        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (smartPrefs.getBoolean(SmartFaceEngine.KEY_CAPSULE_ANIM, true)) {
            showWaterdropNotch()
        }

        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.postDelayed(autoTimeoutRunnable, 4500L)

        controller?.start()
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

            val view = WaterdropCameraNotchView(this, edge)
            notchView = view
            windowManager?.addView(view, notchParams)
            notchAdded = true
            view.pullFromBezel()
        } catch (_: Exception) {
        }
    }

    private fun removeNotchOverlay() {
        if (notchAdded) {
            try {
                notchView?.stopAnimators()
                windowManager?.removeView(notchView)
            } catch (_: Exception) {
            }
            notchView = null
            notchAdded = false
        }
    }

    /**
     * لوحة بيضاء ناعمة شفافة ترتفع إضاءتها وسطوع الشاشة بسلاسة في الظلام الدامس فقط
     */
    private fun showSmoothNightFillLight() {
        if (fillLightAdded) return
        try {
            val view = NightFillLightPanelView(this)
            fillLightView = view
            fillLightParams.screenBrightness = 0.05f
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
                        fillLightView?.alpha = t * 0.86f
                        fillLightParams.screenBrightness = 0.05f + (0.87f * t)
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
            val startAlpha = fillLightView?.alpha ?: 0.86f
            val startBright = fillLightParams.screenBrightness.coerceAtLeast(0.05f)
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

        // تجاهل أول 160ms من استقرار المستشعر لمنع أي قراءة صفرية كاذبة في الغرفة المضيئة
        if (System.currentTimeMillis() - showStartTimeMs < 160L) return

        val lux = event.values.firstOrNull() ?: 100f
        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val nightLightEnabled = smartPrefs.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, true)

        if (nightLightEnabled && lux < 8.0f) {
            darkReadingsCount++
            // تأكيد قراءتين متتاليتين في الظلام الفعلي قبل تشغيل اللوحة البيضاء الناعمة
            if (darkReadingsCount >= 2) {
                showSmoothNightFillLight()
            }
        } else {
            darkReadingsCount = 0
            hideFillLightPanel(animated = true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onAuthed() {
        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (smartPrefs.getBoolean(SmartFaceEngine.KEY_UNLOCK_CHIME, false)) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 40)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 45)
                handler?.postDelayed({ tone.release() }, 120)
            } catch (_: Exception) {
            }
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
                notchView?.showSuccessAndRetract {
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
     * لوحة إضاءة بيضاء ناعمة شفافة قليلاً تضيء الوجه بنعومة في الظلام الدامس فقط
     */
    private class NightFillLightPanelView(ctx: Context) : View(ctx) {
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w * 0.5f
            val cy = h * 0.5f
            val radius = maxOf(w, h) * 0.78f

            // لوحة بيضاء ناعمة متجانسة بشفافية خفيفة في المركز وسطوع أعلى نحو الأطراف لإضاءة الوجه بوضوح
            panelPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb(155, 250, 252, 255),
                    Color.argb(205, 255, 255, 255),
                    Color.argb(235, 255, 255, 255)
                ),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, panelPaint)
        }
    }

    /**
     * نتوء قطرة الماء (Waterdrop Notch) بلون أسود AMOLED (#000000) ينسحب بسلاسة من حافة الكاميرا
     * ويتكيف ما بداخله مع دوران الشاشة الأربعة حتى لا يكون معكوساً أبداً
     */
    private class WaterdropCameraNotchView(ctx: Context, private val edge: CameraEdge) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val spanPx = (146f * density).toInt()
        private val maxDepthPx = (40f * density).toInt()

        private val amoledBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A84FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.0f * density
        }
        private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#880A84FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.6f * density
        }

        private val notchPath = Path()
        private var pullProgress = 0f
        private var scanPhase = 0f
        private var isSuccess = false
        private var successProgress = 0f
        private var scanAnimator: ValueAnimator? = null
        private var pullAnimator: ValueAnimator? = null

        init {
            startScanLoop()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (edge == CameraEdge.TOP || edge == CameraEdge.BOTTOM) {
                setMeasuredDimension(spanPx, maxDepthPx)
            } else {
                setMeasuredDimension(maxDepthPx, spanPx)
            }
        }

        private fun startScanLoop() {
            scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1050L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    scanPhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun pullFromBezel() {
            pullAnimator?.cancel()
            pullAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 340L
                interpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
                addUpdateListener {
                    pullProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun showSuccessAndRetract(onDone: () -> Unit) {
            scanAnimator?.cancel()
            isSuccess = true
            iconPaint.color = Color.parseColor("#30D158")
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 170L
                addUpdateListener {
                    successProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        postDelayed({ retractIntoBezel(onDone) }, 140L)
                    }
                })
                start()
            }
        }

        fun retractIntoBezel(onDone: () -> Unit) {
            scanAnimator?.cancel()
            pullAnimator?.cancel()
            pullAnimator = ValueAnimator.ofFloat(pullProgress, 0f).apply {
                duration = 230L
                interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
                addUpdateListener {
                    pullProgress = it.animatedValue as Float
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

        fun stopAnimators() {
            scanAnimator?.cancel()
            pullAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pullProgress <= 0.01f) return

            val w = width.toFloat()
            val h = height.toFloat()
            val depth = maxDepthPx * pullProgress
            val spanScale = 0.62f + 0.38f * pullProgress

            notchPath.reset()
            var iconCx = w * 0.5f
            var iconCy = h * 0.5f

            when (edge) {
                CameraEdge.TOP -> {
                    val halfSpan = (w * spanScale) * 0.5f
                    val startX = w * 0.5f - halfSpan
                    val endX = w * 0.5f + halfSpan
                    val spanW = endX - startX
                    notchPath.moveTo(startX, 0f)
                    notchPath.cubicTo(
                        startX + spanW * 0.22f, 0f,
                        startX + spanW * 0.27f, depth,
                        w * 0.5f, depth
                    )
                    notchPath.cubicTo(
                        endX - spanW * 0.27f, depth,
                        endX - spanW * 0.22f, 0f,
                        endX, 0f
                    )
                    notchPath.close()
                    iconCx = w * 0.5f
                    iconCy = depth * 0.48f
                }

                CameraEdge.BOTTOM -> {
                    val halfSpan = (w * spanScale) * 0.5f
                    val startX = w * 0.5f - halfSpan
                    val endX = w * 0.5f + halfSpan
                    val spanW = endX - startX
                    val apexY = h - depth
                    notchPath.moveTo(startX, h)
                    notchPath.cubicTo(
                        startX + spanW * 0.22f, h,
                        startX + spanW * 0.27f, apexY,
                        w * 0.5f, apexY
                    )
                    notchPath.cubicTo(
                        endX - spanW * 0.27f, apexY,
                        endX - spanW * 0.22f, h,
                        endX, h
                    )
                    notchPath.close()
                    iconCx = w * 0.5f
                    iconCy = h - depth * 0.48f
                }

                CameraEdge.RIGHT -> {
                    val halfSpan = (h * spanScale) * 0.5f
                    val startY = h * 0.5f - halfSpan
                    val endY = h * 0.5f + halfSpan
                    val spanH = endY - startY
                    val apexX = w - depth
                    notchPath.moveTo(w, startY)
                    notchPath.cubicTo(
                        w, startY + spanH * 0.22f,
                        apexX, startY + spanH * 0.27f,
                        apexX, h * 0.5f
                    )
                    notchPath.cubicTo(
                        apexX, endY - spanH * 0.27f,
                        w, endY - spanH * 0.22f,
                        w, endY
                    )
                    notchPath.close()
                    iconCx = w - depth * 0.48f
                    iconCy = h * 0.5f
                }

                CameraEdge.LEFT -> {
                    val halfSpan = (h * spanScale) * 0.5f
                    val startY = h * 0.5f - halfSpan
                    val endY = h * 0.5f + halfSpan
                    val spanH = endY - startY
                    notchPath.moveTo(0f, startY)
                    notchPath.cubicTo(
                        0f, startY + spanH * 0.22f,
                        depth, startY + spanH * 0.27f,
                        depth, h * 0.5f
                    )
                    notchPath.cubicTo(
                        depth, endY - spanH * 0.27f,
                        0f, endY - spanH * 0.22f,
                        0f, endY
                    )
                    notchPath.close()
                    iconCx = depth * 0.48f
                    iconCy = h * 0.5f
                }
            }

            // رسم النتوء باللون الأسود AMOLED الخالص (#000000) بدون أي إطار خارجي ليلتحم بحواف الشاشة
            canvas.drawPath(notchPath, amoledBlackPaint)

            // رسم أيقونة Face ID داخل النتوء دائماً بوضع مستقيم يتكيف مع اتجاه الشاشة الحالي
            if (pullProgress > 0.45f) {
                val contentAlpha = (((pullProgress - 0.45f) / 0.55f) * 255f).toInt().coerceIn(0, 255)
                iconPaint.alpha = contentAlpha
                beamPaint.alpha = (contentAlpha * 0.65f).toInt()

                val iconSize = 18.5f * density
                val iconLeft = iconCx - iconSize * 0.5f
                val iconTop = iconCy - iconSize * 0.5f

                if (isSuccess) {
                    val p = Path().apply {
                        moveTo(iconLeft + iconSize * 0.18f, iconTop + iconSize * 0.52f)
                        lineTo(iconLeft + iconSize * 0.42f, iconTop + iconSize * 0.76f)
                        if (successProgress > 0.4f) {
                            val t = ((successProgress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                            lineTo(
                                iconLeft + iconSize * (0.42f + 0.42f * t),
                                iconTop + iconSize * (0.76f - 0.50f * t)
                            )
                        }
                    }
                    canvas.drawPath(p, iconPaint)
                } else {
                    drawMiniFaceId(canvas, iconLeft, iconTop, iconSize)
                    val beamY = iconTop + iconSize * (0.15f + 0.70f * scanPhase)
                    canvas.drawLine(iconLeft + 2f * density, beamY, iconLeft + iconSize - 2f * density, beamY, beamPaint)
                }
            }
        }

        private fun drawMiniFaceId(canvas: Canvas, l: Float, t: Float, s: Float) {
            val arm = s * 0.25f
            val r = s * 0.16f
            val right = l + s
            val bottom = t + s

            canvas.drawArc(RectF(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false, iconPaint)
            canvas.drawLine(l + r, t, l + arm, t, iconPaint)
            canvas.drawLine(l, t + r, l, t + arm, iconPaint)

            canvas.drawArc(RectF(right - 2 * r, t, right, t + 2 * r), 270f, 90f, false, iconPaint)
            canvas.drawLine(right - arm, t, right - r, t, iconPaint)
            canvas.drawLine(right, t + r, right, t + arm, iconPaint)

            canvas.drawArc(RectF(l, bottom - 2 * r, l + 2 * r, bottom), 90f, 90f, false, iconPaint)
            canvas.drawLine(l + r, bottom, l + arm, bottom, iconPaint)
            canvas.drawLine(l, bottom - arm, l, bottom - r, iconPaint)

            canvas.drawArc(RectF(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false, iconPaint)
            canvas.drawLine(right - arm, bottom, right - r, bottom, iconPaint)
            canvas.drawLine(right, bottom - arm, right, bottom - r, iconPaint)

            val eyeY = t + s * 0.40f
            canvas.drawLine(l + s * 0.35f, eyeY - s * 0.05f, l + s * 0.35f, eyeY + s * 0.05f, iconPaint)
            canvas.drawLine(l + s * 0.65f, eyeY - s * 0.05f, l + s * 0.65f, eyeY + s * 0.05f, iconPaint)
            canvas.drawArc(RectF(l + s * 0.36f, t + s * 0.48f, l + s * 0.64f, t + s * 0.68f), 25f, 130f, false, iconPaint)
        }
    }

    companion object {
        private val TAG = LockscreenFaceAuthService::class.simpleName
    }
}

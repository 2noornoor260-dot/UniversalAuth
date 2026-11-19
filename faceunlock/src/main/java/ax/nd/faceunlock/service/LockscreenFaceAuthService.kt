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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

    private var islandView: DynamicFaceIslandView? = null
    private var islandAdded: Boolean = false
    private val islandParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 0
        screenBrightness = -1.0f
    }

    private var glowView: SoftEdgeGlowView? = null
    private var glowAdded: Boolean = false
    private val glowParams = WindowManager.LayoutParams(
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
        screenBrightness = 0.78f
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

    private fun show() {
        if (active) return
        if (!Util.isFaceUnlockEnrolled(this)) return

        active = true
        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (smartPrefs.getBoolean(SmartFaceEngine.KEY_CAPSULE_ANIM, true)) {
            showDynamicIsland()
        }

        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.postDelayed(autoTimeoutRunnable, 4500L)

        controller?.start()
    }

    private fun hide(animated: Boolean) {
        handler?.removeCallbacks(autoTimeoutRunnable)
        sensorManager?.unregisterListener(this)
        removeGlowOverlay()

        if (active) {
            active = false
            controller?.stop()
        }

        if (animated && islandAdded && islandView != null) {
            islandView?.animateOut {
                removeIslandOverlay()
            }
        } else {
            removeIslandOverlay()
        }
    }

    private fun forceImmediateCleanup() {
        handler?.removeCallbacks(autoTimeoutRunnable)
        sensorManager?.unregisterListener(this)
        if (active) {
            active = false
            controller?.stop()
        }
        removeGlowOverlay()
        removeIslandOverlay()
    }

    private fun showDynamicIsland() {
        removeIslandOverlay()
        try {
            val view = DynamicFaceIslandView(this)
            islandView = view
            windowManager?.addView(view, islandParams)
            islandAdded = true
            view.animateIn()
        } catch (_: Exception) {
        }
    }

    private fun removeIslandOverlay() {
        if (islandAdded) {
            try {
                islandView?.stopAnimators()
                windowManager?.removeView(islandView)
            } catch (_: Exception) {
            }
            islandView = null
            islandAdded = false
        }
    }

    private fun showGlowOverlay() {
        if (glowAdded) return
        try {
            val view = SoftEdgeGlowView(this)
            glowView = view
            windowManager?.addView(view, glowParams)
            glowAdded = true
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
        } catch (_: Exception) {
        }
    }

    private fun removeGlowOverlay() {
        if (glowAdded) {
            try {
                windowManager?.removeView(glowView)
            } catch (_: Exception) {
            }
            glowView = null
            glowAdded = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values.firstOrNull() ?: 100f
        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val nightLightEnabled = smartPrefs.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, false)

        if (nightLightEnabled && lux < 12f) {
            showGlowOverlay()
        } else {
            removeGlowOverlay()
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
            removeGlowOverlay()
            active = false

            if (islandAdded && islandView != null) {
                islandView?.showSuccessAndRetract {
                    removeIslandOverlay()
                }
            } else {
                removeIslandOverlay()
            }
        }
    }

    override fun onError(errId: Int, message: String) {
        handler?.post {
            hide(animated = true)
        }
    }

    private class DynamicFaceIslandView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val pillW = (148f * density).toInt()
        private val pillH = (38f * density).toInt()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A0C10")
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A303E")
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * density
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0A84FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.0f * density
        }
        private val scanBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#660A84FF")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 1.6f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5F7FA")
            textSize = 12.5f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        private val clipPath = Path()
        private var scanPhase = 0f
        private var isSuccess = false
        private var successProgress = 0f
        private var scanAnimator: ValueAnimator? = null

        init {
            startScanLoop()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(pillW, pillH)
        }

        private fun startScanLoop() {
            scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100L
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

        fun animateIn() {
            pivotX = pillW * 0.5f
            pivotY = 0f
            scaleX = 0.42f
            scaleY = 0f
            alpha = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(300L)
                .setInterpolator(PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f))
                .start()
        }

        fun showSuccessAndRetract(onDone: () -> Unit) {
            scanAnimator?.cancel()
            isSuccess = true
            iconPaint.color = Color.parseColor("#30D158")
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180L
                addUpdateListener {
                    successProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        postDelayed({ animateOut(onDone) }, 140L)
                    }
                })
                start()
            }
        }

        fun animateOut(onDone: () -> Unit) {
            scanAnimator?.cancel()
            pivotX = pillW * 0.5f
            pivotY = 0f
            animate()
                .scaleX(0.42f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(210L)
                .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onDone()
                    }
                })
                .start()
        }

        fun stopAnimators() {
            scanAnimator?.cancel()
            animate().cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val bottomR = h * 0.50f

            clipPath.reset()
            clipPath.addRoundRect(
                RectF(0f, 0f, w, h),
                floatArrayOf(0f, 0f, 0f, 0f, bottomR, bottomR, bottomR, bottomR),
                Path.Direction.CW
            )
            canvas.drawPath(clipPath, bgPaint)
            canvas.drawPath(clipPath, borderPaint)

            val iconSize = 18f * density
            val iconLeft = 18f * density
            val iconTop = (h - iconSize) * 0.5f

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
                val fontMetrics = textPaint.fontMetrics
                val textY = h * 0.5f - (fontMetrics.ascent + fontMetrics.descent) * 0.5f
                canvas.drawText("تم الفتح", w * 0.58f, textY, textPaint)
            } else {
                drawMiniFaceId(canvas, iconLeft, iconTop, iconSize)
                val beamY = iconTop + iconSize * (0.15f + 0.70f * scanPhase)
                canvas.drawLine(iconLeft + 2f * density, beamY, iconLeft + iconSize - 2f * density, beamY, scanBeamPaint)

                val fontMetrics = textPaint.fontMetrics
                val textY = h * 0.5f - (fontMetrics.ascent + fontMetrics.descent) * 0.5f
                canvas.drawText("جاري التحقق", w * 0.58f, textY, textPaint)
            }
        }

        private fun drawMiniFaceId(canvas: Canvas, l: Float, t: Float, s: Float) {
            val arm = s * 0.24f
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

    private class SoftEdgeGlowView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w * 0.5f
            val cy = h * 0.5f
            val radius = maxOf(w, h) * 0.72f

            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(35, 255, 255, 255),
                    Color.argb(190, 255, 255, 255)
                ),
                floatArrayOf(0.55f, 0.82f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    companion object {
        private val TAG = LockscreenFaceAuthService::class.simpleName
    }
}

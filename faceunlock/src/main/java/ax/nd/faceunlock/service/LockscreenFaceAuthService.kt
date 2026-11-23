package ax.nd.faceunlock.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import ax.nd.faceunlock.FaceApplication
import ax.nd.faceunlock.LibManager
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.backend.core.AmbientLightDecider
import ax.nd.faceunlock.overlay.ChimePlayer
import ax.nd.faceunlock.overlay.FaceUnlockOverlayView
import ax.nd.faceunlock.pref.Prefs
import ax.nd.faceunlock.util.Util
import ax.nd.universalauth.xposed.common.XposedConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * خدمة إمكانية الوصول التي تعرض طبقة التعرّف على الوجه فوق شاشة القفل وتُشغّل المسح.
 *
 * التغييرات الجوهرية:
 *  - نافذة overlay واحدة ([FaceUnlockOverlayView]) تحوي إضاءة الظلام ثم الكبسولة بترتيب رسم ثابت،
 *    فلا تغطي الإضاءة الكبسولة أبداً، ولا يُحدَّث WindowManager أثناء الحركة.
 *  - قرار الإضاءة الليلية في [AmbientLightDecider] (يتجاهل إحماء تعريض الكاميرا، ويعتمد حساس الضوء أولاً).
 *  - اهتزاز خفيف عند النجاح (اختياري) وإعدادات الكاميرا/الإضاءة الجديدة.
 *  - مسار الفتح (بث ACTION_UNLOCK_DEVICE) واستقبال الفتح المبكر من وحدة Xposed بلا أي تغيير في البروتوكول.
 */
class LockscreenFaceAuthService :
    AccessibilityService(),
    FaceAuthServiceCallbacks,
    SensorEventListener,
    SmartFaceEngine.VerificationFeedbackListener {

    private var windowManager: WindowManager? = null

    // نافذة واحدة: [إضاءة الظلام] ثم [الكبسولة] فوقها
    private var overlay: FaceUnlockOverlayView? = null
    private var overlayAdded: Boolean = false
    private var fillStrength: Float = 0.9f
    private val overlayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        // يسمح للنافذة بالامتداد تحت فتحة الكاميرا كي تلتصق الكبسولة بالإطار فعلاً
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private var active: Boolean = false
    private var nightLightOn: Boolean = true
    private var capsuleOn: Boolean = true
    private var lockStateReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var controller: FaceAuthServiceController? = null
    private var keyguardManager: KeyguardManager? = null
    private var displayManager: DisplayManager? = null
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var handler: Handler? = null
    private lateinit var ambient: AmbientLightDecider

    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope
    private lateinit var prefs: Prefs
    private var booted = false

    private val autoTimeoutRunnable = Runnable {
        if (active) {
            hide(animated = true)
        }
    }

    // فحص دوري خفيف لقرار الإضاءة (القرار نفسه في AmbientLightDecider، والبيانات تصله من الكاميرا والحساس)
    private val ambientMonitor = object : Runnable {
        override fun run() {
            if (!active || !nightLightOn || ambient.isLatched()) return
            if (ambient.update(SystemClock.elapsedRealtime())) {
                latchFillLight()
                return
            }
            handler?.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        serviceJob = Job()
        serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

        prefs = FaceApplication.getApp().prefs
        controller = FaceAuthServiceController(this, this)

        windowManager = getSystemService()
        keyguardManager = getSystemService()
        displayManager = getSystemService()
        sensorManager = getSystemService()
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        ambient = AmbientLightDecider(lightSensor != null)
        handler = Handler(Looper.getMainLooper())

        booted = !prefs.requirePinOnBoot.get()
        if (booted) {
            reconfigureUnlockHook()
        }
        setupUnlockReceiver()

        // تحضير النغمة المختارة مسبقاً: أول صوت فتح يُسمع فوراً
        val chime = smartPrefs().getString(SmartFaceEngine.KEY_UNLOCK_CHIME_TYPE, "apple_glass") ?: "apple_glass"
        ChimePlayer.prepare(chime)

        // آخر خطوة: كل الحقول (بما فيها ambient) جاهزة قبل أن يبدأ المحرّك بإرسال الإحصاءات
        SmartFaceEngine.setFeedbackListener(this)
    }

    private fun smartPrefs() = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)

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
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
    }

    // ───────────────────────── دورة العرض ─────────────────────────

    private fun show() {
        if (active) return
        if (!Util.isFaceUnlockEnrolled(this)) return

        // إن كانت طبقة الجلسة السابقة ما زالت تنسحب، أزِلها فوراً وابدأ جلسة نظيفة
        if (overlayAdded) removeOverlay()

        active = true
        val now = SystemClock.elapsedRealtime()
        ambient.begin(now)
        SmartFaceEngine.lastFrameLuma = -1f

        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        val sp = smartPrefs()
        capsuleOn = sp.getBoolean(SmartFaceEngine.KEY_CAPSULE_ANIM, true)
        nightLightOn = sp.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, true)
        fillStrength = (sp.getInt(SmartFaceEngine.KEY_FILL_STRENGTH, 90) / 100f).coerceIn(0.5f, 1f)

        if (capsuleOn) ensureOverlay()

        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.postDelayed(autoTimeoutRunnable, 5500L)

        handler?.removeCallbacks(ambientMonitor)
        if (nightLightOn) handler?.postDelayed(ambientMonitor, 120L)

        controller?.start()
    }

    private fun hide(animated: Boolean) {
        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.removeCallbacks(ambientMonitor)
        sensorManager?.unregisterListener(this)

        if (active) {
            active = false
            controller?.stop()
        }

        val o = overlay
        if (animated && overlayAdded && o != null) {
            o.retract { removeOverlay() }
        } else {
            removeOverlay()
        }
    }

    private fun forceImmediateCleanup() {
        handler?.removeCallbacks(autoTimeoutRunnable)
        handler?.removeCallbacks(ambientMonitor)
        sensorManager?.unregisterListener(this)
        if (active) {
            active = false
            controller?.stop()
        }
        removeOverlay()
    }

    // ───────────────────────── النافذة الواحدة ─────────────────────────

    private fun ensureOverlay() {
        if (overlayAdded) return
        try {
            val sp = smartPrefs()
            val edgeMode = sp.getString(SmartFaceEngine.KEY_CAMERA_EDGE, "auto") ?: "auto"
            val view = FaceUnlockOverlayView(this)
            view.configure(edgeMode, capsuleOn, getCurrentDisplayRotation(), fillStrength)
            overlayParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            windowManager?.addView(view, overlayParams)
            overlay = view
            overlayAdded = true
            view.show()
        } catch (e: Exception) {
            Log.w(TAG, "overlay add failed", e)
            overlay = null
            overlayAdded = false
        }
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        try {
            overlay?.stopAnimations()
            windowManager?.removeView(overlay)
        } catch (e: Exception) {
        }
        overlay = null
        overlayAdded = false
    }

    /** إضاءة الظلام: تُقفل طوال الجلسة (لا إطفاء أثناء المسح) وتُرفع الإضاءة تدريجياً بخطوات قليلة. */
    private fun latchFillLight() {
        if (!active) return
        if (!overlayAdded) ensureOverlay()
        overlay?.setFillLight(true)

        // سطوع الشاشة: 4 خطوات فقط (لا تحديث لكل إطار) كي لا تتأثر سلاسة الحركة
        val target = fillStrength.coerceIn(0.5f, 1f)
        val from = 0.15f
        for (i in 1..4) {
            handler?.postDelayed(Runnable {
                if (overlayAdded) {
                    overlayParams.screenBrightness = from + (target - from) * (i / 4f)
                    try {
                        windowManager?.updateViewLayout(overlay, overlayParams)
                    } catch (e: Exception) {
                    }
                }
            }, i * 55L)
        }
    }

    // ───────────────────────── الحساس والتغذية الراجعة ─────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values.firstOrNull() ?: return
        ambient.onLux(SystemClock.elapsedRealtime(), lux)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onFrameStats(meanLuma: Float, faceLuma: Float) {
        if (active && nightLightOn) {
            ambient.onCameraFrame(SystemClock.elapsedRealtime(), meanLuma, faceLuma)
        }
    }

    override fun onScanningState(faceDetected: Boolean, confidence: Float) {
        handler?.post {
            if (overlayAdded) overlay?.setEngaged(faceDetected)
        }
    }

    override fun onVerifySuccess() {
    }

    override fun onVerifyRejected(reasonCode: Int) {
        handler?.post {
            if (overlayAdded) overlay?.playReject()
        }
    }

    // ───────────────────────── النتيجة ─────────────────────────

    override fun onAuthed() {
        val sp = smartPrefs()
        val chimeType = sp.getString(SmartFaceEngine.KEY_UNLOCK_CHIME_TYPE, "apple_glass") ?: "apple_glass"
        if (chimeType != "none") {
            ChimePlayer.play(chimeType)
        }
        if (sp.getBoolean(SmartFaceEngine.KEY_HAPTICS, true)) {
            vibrateSuccess()
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
            handler?.removeCallbacks(ambientMonitor)
            sensorManager?.unregisterListener(this)
            active = false

            val o = overlay
            if (overlayAdded && o != null) {
                o.playSuccess { removeOverlay() }
            } else {
                removeOverlay()
            }
        }
    }

    override fun onError(errId: Int, message: String) {
        handler?.post {
            hide(animated = true)
        }
    }

    private fun vibrateSuccess() {
        try {
            val vibrator = getSystemService<Vibrator>() ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (e: Exception) {
        }
    }

    companion object {
        private val TAG = LockscreenFaceAuthService::class.simpleName
    }
}

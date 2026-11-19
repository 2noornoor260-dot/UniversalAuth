package ax.nd.faceunlock.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.getSystemService
import ax.nd.faceunlock.FaceApplication
import ax.nd.faceunlock.LibManager
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.pref.Prefs
import ax.nd.faceunlock.util.Util
import ax.nd.faceunlock.util.dpToPx
import ax.nd.universalauth.xposed.common.XposedConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LockscreenFaceAuthService : AccessibilityService(), FaceAuthServiceCallbacks, SensorEventListener {
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        screenBrightness = -1.0f
    }

    private var active: Boolean = false
    private var viewAdded: Boolean = false
    private var lockStateReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var controller: FaceAuthServiceController? = null
    private var keyguardManager: KeyguardManager? = null
    private var displayManager: DisplayManager? = null
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lastAmbientLux: Float = 100f
    private var handler: Handler? = null

    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope
    private lateinit var prefs: Prefs
    private var showStatusText = false
    private var booted = false

    private val autoTimeoutRunnable = Runnable {
        if (active) {
            hide()
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

        textView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, 14.dpToPx.toInt(), 0, 0)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }

        booted = !prefs.requirePinOnBoot.get()
        if (booted) {
            reconfigureUnlockHook()
        }
        setupUnlockReceiver()

        serviceScope.launch {
            prefs.showStatusText.asFlow().collect { show ->
                showStatusText = show
            }
        }
    }

    private fun setupUnlockReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                if (!booted) {
                    booted = true
                    reconfigureUnlockHook()
                }
                prefs.failedUnlockAttempts.set(0)
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
                    hide()
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
        hide()
        serviceJob.cancel()
    }

    private fun doubleCheckLockscreenState() {
        val mainDisplayOn = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        if (mainDisplayOn && keyguardManager?.isKeyguardLocked == true) {
            show()
        } else {
            hide()
        }
    }

    private fun show() {
        if (!active) {
            if (Util.isFaceUnlockEnrolled(this)) {
                active = true
                lastAmbientLux = 100f
                lightSensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                }

                params.screenBrightness = -1.0f
                if (showStatusText) {
                    textView?.text = "جاري التحقق من الوجه..."
                    addOrUpdateOverlay()
                }

                handler?.removeCallbacks(autoTimeoutRunnable)
                handler?.postDelayed(autoTimeoutRunnable, 4500L)

                controller?.start()
            }
        }
    }

    private fun hide() {
        handler?.removeCallbacks(autoTimeoutRunnable)
        sensorManager?.unregisterListener(this)
        if (active) {
            active = false
            params.screenBrightness = -1.0f
            removeTextViewFromWindowManager()
            controller?.stop()
        }
    }

    private fun addOrUpdateOverlay() {
        try {
            if (!viewAdded) {
                windowManager?.addView(textView, params)
                viewAdded = true
            } else {
                windowManager?.updateViewLayout(textView, params)
            }
        } catch (_: Exception) {
        }
    }

    private fun removeTextViewFromWindowManager() {
        if (viewAdded) {
            try {
                windowManager?.removeView(textView)
            } catch (_: Exception) {
            }
            viewAdded = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event?.sensor?.type != Sensor.TYPE_LIGHT) return
        lastAmbientLux = event.values.firstOrNull() ?: 100f
        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val nightLightEnabled = smartPrefs.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, false)

        if (nightLightEnabled && lastAmbientLux < 10f) {
            if (params.screenBrightness != 0.72f) {
                params.screenBrightness = 0.72f
                if (!showStatusText) textView?.text = ""
                addOrUpdateOverlay()
            }
        } else if (params.screenBrightness > 0f) {
            params.screenBrightness = -1.0f
            if (!showStatusText) {
                removeTextViewFromWindowManager()
            } else {
                addOrUpdateOverlay()
            }
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
            hide()
        }
    }

    override fun onError(errId: Int, message: String) {
        handler?.post {
            if (showStatusText) {
                textView?.text = message
                handler?.postDelayed({ hide() }, 1000)
            } else {
                hide()
            }
        }
    }

    companion object {
        private val TAG = LockscreenFaceAuthService::class.simpleName
    }
}

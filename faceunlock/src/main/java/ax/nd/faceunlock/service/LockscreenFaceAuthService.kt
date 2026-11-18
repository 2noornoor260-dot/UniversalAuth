package ax.nd.faceunlock.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.ViewPropertyAnimator
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

class LockscreenFaceAuthService : AccessibilityService(), FaceAuthServiceCallbacks {
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
    }

    private var active: Boolean = false
    private var viewAdded: Boolean = false
    private var lockStateReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var controller: FaceAuthServiceController? = null
    private var keyguardManager: KeyguardManager? = null
    private var displayManager: DisplayManager? = null
    private var handler: Handler? = null
    private var startTime: Long = 0
    private var textViewAnimator: ViewPropertyAnimator? = null

    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope
    private lateinit var prefs: Prefs

    private var showStatusText = true
    private var booted = false

    override fun onCreate() {
        super.onCreate()

        serviceJob = Job()
        serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

        prefs = FaceApplication.getApp().prefs
        controller = FaceAuthServiceController(this, prefs, this)

        windowManager = getSystemService()
        keyguardManager = getSystemService()
        displayManager = getSystemService()
        handler = Handler(Looper.getMainLooper())

        textView = TextView(this).apply {
            setTextColor(getColor(android.R.color.white))
            setShadowLayer(10f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 16.dpToPx.toInt(), 0, 0)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }

        booted = !prefs.requirePinOnBoot.get()
        if (booted) {
            reconfigureUnlockHook()
        }
        setupUnlockReceiver()

        serviceScope.launch {
            prefs.showStatusText.asFlow().collect { showStatusText ->
                this@LockscreenFaceAuthService.showStatusText = showStatusText
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
                val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
                val nightLight = smartPrefs.getBoolean(SmartFaceEngine.KEY_NIGHT_LIGHT, false)

                // تفعيل إضاءة الشاشة التلقائية في الظلام إذا كان المستخدم قد فعلها
                params.screenBrightness = if (nightLight) 1.0f else -1.0f

                if (showStatusText || nightLight) {
                    textViewAnimator?.cancel()
                    textView?.text = if (showStatusText) "جاري التعرف على الوجه..." else ""
                    if (!viewAdded) {
                        try {
                            windowManager?.addView(textView, params)
                            viewAdded = true
                        } catch (_: Exception) {
                        }
                    } else {
                        try {
                            windowManager?.updateViewLayout(textView, params)
                        } catch (_: Exception) {
                        }
                    }
                }
                startTime = System.currentTimeMillis()
                controller?.start()
            }
        }
    }

    private fun hide(delay: Int = 0) {
        if (active) {
            active = false
            params.screenBrightness = -1.0f
            if (viewAdded) {
                if (delay > 0 && showStatusText) {
                    textViewAnimator = textView?.animate()
                        ?.alpha(0f)
                        ?.setListener(object : Animator.AnimatorListener {
                            override fun onAnimationStart(p0: Animator) {}
                            override fun onAnimationEnd(p0: Animator) = onTextViewAnimationEnd()
                            override fun onAnimationCancel(p0: Animator) {}
                            override fun onAnimationRepeat(p0: Animator) {}
                        })
                        ?.setStartDelay(delay.toLong())
                        ?.setDuration(250)
                    textViewAnimator?.start()
                } else {
                    removeTextViewFromWindowManager()
                }
            }
            controller?.stop()
        } else {
            if (delay == 0) {
                textViewAnimator?.cancel()
            }
        }
    }

    private fun onTextViewAnimationEnd() {
        removeTextViewFromWindowManager()
        textViewAnimator?.cancel()
        textViewAnimator = null
        textView?.alpha = 1f
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

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    @Suppress("DEPRECATION")
    override fun onAuthed() {
        Log.d("MeasureFaceUnlock", "Total time: " + (System.currentTimeMillis() - startTime))

        val smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        if (smartPrefs.getBoolean(SmartFaceEngine.KEY_VIBRATE, true)) {
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator?.vibrate(35)
                }
            } catch (_: Exception) {
            }
        }

        val unlockAnimation = when (FaceApplication.getApp().prefs.unlockAnimation.get()) {
            "mode_wake_and_unlock" -> XposedConstants.MODE_WAKE_AND_UNLOCK
            "mode_wake_and_unlock_pulsing" -> XposedConstants.MODE_WAKE_AND_UNLOCK_PULSING
            else -> XposedConstants.MODE_UNLOCK_FADING
        }
        sendBroadcast(Intent(XposedConstants.ACTION_UNLOCK_DEVICE).apply {
            putExtra(XposedConstants.EXTRA_UNLOCK_MODE, unlockAnimation)
            putExtra(XposedConstants.EXTRA_BYPASS_KEYGUARD, prefs.bypassKeyguard.get())
        })
        handler?.post {
            textView?.text = "تم الفتح ✓"
            hide(delay = 800)
        }
    }

    override fun onError(errId: Int, message: String) {
        handler?.post {
            textView?.text = message
            hide(delay = 1500)
        }
    }

    companion object {
        private val TAG = LockscreenFaceAuthService::class.simpleName
    }
}

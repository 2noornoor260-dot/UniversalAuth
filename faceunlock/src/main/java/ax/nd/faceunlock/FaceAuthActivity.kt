package ax.nd.faceunlock

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.service.FaceAuthServiceCallbacks
import ax.nd.faceunlock.service.FaceAuthServiceController
import ax.nd.faceunlock.stub.biometrics.BiometricFaceConstants
import java.util.Locale

/**
 * شاشة اختبار التعرّف: عربية بالكامل (RTL) وتعرض قياسات كل طبقة حيّة أثناء المسح مقابل حدّها،
 * فتُظهر بالضبط لماذا نجح الفتح أو فشل (جلد/مطابقة/عمق/مكافحة تزييف).
 */
class FaceAuthActivity : AppCompatActivity(), FaceAuthServiceCallbacks {

    private var controller: FaceAuthServiceController? = null
    private var startElapsed: Long = 0
    private var finished = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var metricsView: TextView
    private lateinit var retryButton: TextView

    private var dark = false
    private var okColor = 0
    private var badColor = 0
    private var primary = 0
    private var secondary = 0

    private val poller = object : Runnable {
        override fun run() {
            renderMetrics()
            uiHandler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val pageBg = if (dark) Color.parseColor("#000000") else Color.parseColor("#F2F2F7")
        val cardBg = if (dark) Color.parseColor("#1C1C1E") else Color.WHITE
        primary = if (dark) Color.WHITE else Color.BLACK
        secondary = Color.parseColor("#8E8E93")
        okColor = if (dark) Color.parseColor("#30D158") else Color.parseColor("#34C759")
        badColor = if (dark) Color.parseColor("#FF453A") else Color.parseColor("#FF3B30")
        val blue = if (dark) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF")

        window.statusBarColor = pageBg
        window.navigationBarColor = pageBg
        if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(36), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "اختبار التعرّف على الوجه"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(primary)
            gravity = Gravity.CENTER
        })

        statusView = TextView(this).apply {
            text = "انظر إلى الكاميرا الأمامية…"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primary)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(22), dp(8), dp(18))
        }
        root.addView(statusView)

        metricsView = TextView(this).apply {
            textSize = 14f
            setTextColor(primary)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(14).toFloat()
            }
        }
        root.addView(metricsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        retryButton = TextView(this).apply {
            text = "إعادة المحاولة"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                setColor(blue)
                cornerRadius = dp(26).toFloat()
            }
            setOnClickListener { restartScan() }
        }
        root.addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "إغلاق"
            textSize = 15.5f
            setTextColor(blue)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(4))
            setOnClickListener { finish() }
        })

        setContentView(root)
        controller = FaceAuthServiceController(this, this)
    }

    override fun onStart() {
        super.onStart()
        beginScan()
        uiHandler.post(poller)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(poller)
        controller?.stop()
    }

    private fun beginScan() {
        finished = false
        startElapsed = SystemClock.elapsedRealtime()
        statusView.text = "انظر إلى الكاميرا الأمامية…"
        statusView.setTextColor(primary)
        controller?.start()
    }

    private fun restartScan() {
        controller?.stop()
        beginScan()
    }

    private fun renderMetrics() {
        if (!::metricsView.isInitialized) return
        fun v(x: Float) = if (x < 0f) "—" else String.format(Locale.US, "%.2f", x)
        fun mark(value: Float, min: Float) = if (value < 0f || min < 0f) "" else if (value >= min) "  ✓" else "  ✗"

        val sb = StringBuilder()
        sb.append("المرحلة: ").append(if (SmartFaceEngine.diagStage.isEmpty()) "—" else SmartFaceEngine.diagStage).append('\n')
        sb.append("نسيج الجلد: ").append(v(SmartFaceEngine.diagSharp)).append(" / ").append(v(SmartFaceEngine.diagSharpMin)).append(mark(SmartFaceEngine.diagSharp, SmartFaceEngine.diagSharpMin)).append('\n')
        sb.append("تباين اللون: ").append(v(SmartFaceEngine.diagChroma)).append(" / ").append(v(SmartFaceEngine.diagChromaMin)).append(mark(SmartFaceEngine.diagChroma, SmartFaceEngine.diagChromaMin)).append('\n')
        sb.append("المطابقة: ").append(v(SmartFaceEngine.diagMatch)).append(" / ").append(v(SmartFaceEngine.diagMatchMin)).append(mark(SmartFaceEngine.diagMatch, SmartFaceEngine.diagMatchMin)).append('\n')
        sb.append("العمق ثلاثي الأبعاد: ").append(v(SmartFaceEngine.diagDepth)).append('\n')
        sb.append("مكافحة التزييف: ").append(v(SmartFaceEngine.diagSpoof)).append(" / ").append(v(SmartFaceEngine.diagSpoofMin)).append(mark(SmartFaceEngine.diagSpoof, SmartFaceEngine.diagSpoofMin)).append('\n')
        sb.append("الإطارات الحيّة المتتالية: ").append(SmartFaceEngine.diagStreak)
        metricsView.text = sb.toString()
    }

    // FaceAuthService قد يستدعي هذه الدوال من خيوط الكاميرا/العمل (مثل cancel() عند onStop)،
    // لذا ننتقل دائماً إلى خيط الواجهة قبل لمس العناصر.
    override fun onAuthed() {
        runOnUiThread {
            finished = true
            val seconds = (SystemClock.elapsedRealtime() - startElapsed) / 1000.0
            statusView.text = String.format(Locale.US, "تم التعرّف بنجاح خلال %.2f ثانية", seconds)
            statusView.setTextColor(okColor)
            renderMetrics()
        }
    }

    override fun onError(errId: Int, message: String) {
        runOnUiThread {
            if (finished) return@runOnUiThread
            statusView.text = if (errId == BiometricFaceConstants.FACE_ERROR_TIMEOUT) {
                "انتهت المهلة دون تعرّف. اضغط «إعادة المحاولة»"
            } else {
                "تعذّر التعرّف (رمز $errId). اضغط «إعادة المحاولة»"
            }
            statusView.setTextColor(badColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

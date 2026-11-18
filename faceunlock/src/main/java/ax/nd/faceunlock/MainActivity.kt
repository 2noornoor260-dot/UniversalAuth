package ax.nd.faceunlock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Camera
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import ax.nd.faceunlock.backend.FaceUnlockVendorImpl
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.service.LockscreenFaceAuthService
import ax.nd.faceunlock.service.RemoveFaceController
import ax.nd.faceunlock.service.RemoveFaceControllerCallbacks
import ax.nd.faceunlock.util.SharedUtil
import ax.nd.universalauth.xposed.common.XposedConstants
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), RemoveFaceControllerCallbacks {

    private var removeFaceController: RemoveFaceController? = null
    private var requestUnlockPermsLauncher: ActivityResultLauncher<String>? = null
    private lateinit var prefs: SharedPreferences

    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var accessBadge: TextView
    private lateinit var enrollBtn: MaterialButton
    private lateinit var liveTestBtn: MaterialButton
    private lateinit var systemAuthBtn: MaterialButton
    private lateinit var removeBtn: MaterialButton

    private var liveCamera: Camera? = null
    private var isTestingLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        requestUnlockPermsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        buildModernDashboard()
        checkPermissionsSilently()
    }

    private fun buildModernDashboard() {
        val bgColor = Color.parseColor("#0F1117")
        val cardColor = Color.parseColor("#1A1E29")
        val accentColor = Color.parseColor("#4F8EF7")
        val textPrimary = Color.parseColor("#F4F6FA")
        val textSecondary = Color.parseColor("#9BA3B4")

        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(36))
        }

        // 1. العنوان العلوي
        root.addView(TextView(this).apply {
            text = "SmartFace AI Unlock"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        root.addView(TextView(this).apply {
            text = "محرك التعرف ثلاثي الأبعاد • ML Kit + 3D Mesh + InsightFace"
            textSize = 13f
            setTextColor(accentColor)
            setPadding(0, dp(4), 0, dp(18))
        })

        // 2. بطاقة حالة النظام والوصول
        val statusCard = createCard(cardColor)
        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        statusTitle = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        statusSubtitle = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        }
        accessBadge = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        statusLayout.addView(statusTitle)
        statusLayout.addView(statusSubtitle)
        statusLayout.addView(accessBadge)
        statusCard.addView(statusLayout)
        root.addView(statusCard)

        // 3. أزرار الإجراءات الرئيسية
        val actionsCard = createCard(cardColor)
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        enrollBtn = MaterialButton(this).apply {
            text = "تسجيل الوجه (مسح 5 زوايا 3D)"
            setBackgroundColor(accentColor)
            setTextColor(Color.WHITE)
            cornerRadius = dp(12)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SetupFaceIntroActivity::class.java))
            }
        }

        liveTestBtn = MaterialButton(this).apply {
            text = "اختبار الذكاء الاصطناعي الحي (Live AI Test)"
            setBackgroundColor(Color.parseColor("#243B55"))
            setTextColor(Color.parseColor("#7FB3FF"))
            cornerRadius = dp(12)
            setOnClickListener { openLiveCameraTestDialog() }
        }

        systemAuthBtn = MaterialButton(this).apply {
            text = "تجربة قفل النظام"
            setBackgroundColor(Color.parseColor("#262B38"))
            setTextColor(textPrimary)
            cornerRadius = dp(12)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FaceAuthActivity::class.java))
            }
        }

        removeBtn = MaterialButton(this).apply {
            text = "حذف بيانات الوجه المسجلة"
            setBackgroundColor(Color.parseColor("#3B1E26"))
            setTextColor(Color.parseColor("#FF7A8A"))
            cornerRadius = dp(12)
        }

        actionsLayout.addView(enrollBtn, fullWidthParams(0))
        actionsLayout.addView(liveTestBtn, fullWidthParams(dp(8)))
        actionsLayout.addView(systemAuthBtn, fullWidthParams(dp(8)))
        actionsLayout.addView(removeBtn, fullWidthParams(dp(8)))
        actionsCard.addView(actionsLayout)
        root.addView(actionsCard)

        // 4. قسم مفاتيح التحكم الفورية (Switches)
        root.addView(sectionHeader("التحكم في الميزات والأمان", textPrimary))
        val togglesCard = createCard(cardColor)
        val togglesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        togglesLayout.addView(createSwitchRow(
            title = "شرط فتح العينين (Eye Open Check)",
            subtitle = "يمنع فتح الهاتف أثناء النوم أو إغماض العينين",
            key = SmartFaceEngine.KEY_REQUIRE_EYES,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "كشف التزييف (MiniFASNet Anti-Spoof)",
            subtitle = "يحظر فتح القفل بصورة مطبوعة أو شاشة هاتف آخر",
            key = SmartFaceEngine.KEY_ANTI_SPOOF,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "فحص العمق ثلاثي الأبعاد (3D Face Mesh)",
            subtitle = "تحليل 468 نقطة ثلاثية الأبعاد للتأكد من بروز ملامح الوجه",
            key = SmartFaceEngine.KEY_3D_MESH,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "التعلم التكيفي الذكي (Adaptive AI)",
            subtitle = "يحدّث بصمة وجهك تلقائياً مع تغير الإضاءة والنظارة",
            key = SmartFaceEngine.KEY_ADAPTIVE,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "إضاءة الشاشة في الظلام (Night Fill-Light)",
            subtitle = "زيادة سطوع الشاشة مؤقتاً عند محاولة الفتح في الظلام",
            key = SmartFaceEngine.KEY_NIGHT_LIGHT,
            defaultVal = false,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "نبضة اهتزاز عند الفتح (Haptic Feedback)",
            subtitle = "اهتزاز خفيف لتأكيد نجاح التعرف على الوجه",
            key = SmartFaceEngine.KEY_VIBRATE,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesCard.addView(togglesLayout)
        root.addView(togglesCard)

        // 5. قسم ضبط الحساسية والدقة (Sliders)
        root.addView(sectionHeader("ضبط الحساسية والسرعة", textPrimary))
        val slidersCard = createCard(cardColor)
        val slidersLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        slidersLayout.addView(createSliderRow(
            title = "دقة تطابق الوجه (Match Threshold)",
            subtitle = "الأقل = أسرع وأكثر مرونة | الأعلى = أكثر صرامة",
            key = SmartFaceEngine.KEY_MATCH_THRESHOLD,
            minVal = 0.50f,
            maxVal = 0.85f,
            defaultVal = 0.65f,
            unitSuffix = "%",
            scaleMultiplier = 100f,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        ))

        slidersLayout.addView(createSliderRow(
            title = "صرامة كشف التزييف (Anti-Spoof Level)",
            subtitle = "تحديد قوة حظر الصور والشاشات المزيفة",
            key = SmartFaceEngine.KEY_SPOOF_THRESHOLD,
            minVal = 0.35f,
            maxVal = 0.90f,
            defaultVal = 0.60f,
            unitSuffix = "%",
            scaleMultiplier = 100f,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        ))

        slidersLayout.addView(createSliderRow(
            title = "زاوية ميل الرأس المسموحة",
            subtitle = "أقصى زاوية دوران للوجه لقبول الفتح",
            key = SmartFaceEngine.KEY_MAX_ANGLE,
            minVal = 15f,
            maxVal = 40f,
            defaultVal = 28f,
            unitSuffix = "°",
            scaleMultiplier = 1f,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            accentColor = accentColor
        ))

        slidersCard.addView(slidersLayout)
        root.addView(slidersCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun openLiveCameraTestDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val surfaceView = SurfaceView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(300))
        }

        val infoText = TextView(this).apply {
            text = "جاري تشغيل الكاميرا والذكاء الاصطناعي..."
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }

        val metricsText = TextView(this).apply {
            text = "التطابق: --% | الحقيقية: --% | السرعة: -- ms"
            textSize = 13f
            gravity = Gravity.CENTER
        }

        container.addView(surfaceView)
        container.addView(infoText)
        container.addView(metricsText)

        isTestingLive = true
        val dialog = MaterialDialog(this).show {
            title(text = "اختبار الذكاء الاصطناعي الحي")
            customView(view = container)
            positiveButton(text = "إغلاق") {
                stopLiveCamera()
            }
            setOnDismissListener {
                stopLiveCamera()
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    val camId = findFrontCameraId()
                    liveCamera = Camera.open(camId).apply {
                        setDisplayOrientation(90)
                        val params = parameters
                        params.setPreviewSize(640, 480)
                        parameters = params
                        setPreviewDisplay(holder)
                        setPreviewCallback { data, camera ->
                            if (!isTestingLive || data == null) return@setPreviewCallback
                            val size = camera.parameters.previewSize
                            val code = FaceUnlockVendorImpl.getInstance().compare(data, size.width, size.height, 270, IntArray(20))
                            runOnUiThread {
                                if (!dialog.isShowing) return@runOnUiThread
                                infoText.text = SmartFaceEngine.lastStatusText
                                infoText.setTextColor(if (code == 0) Color.parseColor("#2ECC71") else Color.parseColor("#FF7A8A"))
                                val matchPct = (SmartFaceEngine.lastMatchScore * 100).toInt()
                                val realPct = (SmartFaceEngine.lastRealScore * 100).toInt()
                                metricsText.text = "التطابق: $matchPct%  |  الحقيقية: $realPct%  |  السرعة: ${SmartFaceEngine.lastProcessMs} ms"
                            }
                        }
                        startPreview()
                    }
                } catch (e: Exception) {
                    infoText.text = "تعذر فتح الكاميرا الأمامية: ${e.message}"
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopLiveCamera()
            }
        })
    }

    private fun findFrontCameraId(): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return 0
    }

    private fun stopLiveCamera() {
        isTestingLive = false
        try {
            liveCamera?.setPreviewCallback(null)
            liveCamera?.stopPreview()
            liveCamera?.release()
        } catch (_: Exception) {
        }
        liveCamera = null
    }

    private fun createCard(bgColor: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(bgColor)
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun sectionHeader(title: String, color: Int): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
    }

    private fun createSwitchRow(
        title: String,
        subtitle: String,
        key: String,
        defaultVal: Boolean,
        textPrimary: Int,
        textSecondary: Int
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        })

        val switchView = SwitchMaterial(this).apply {
            isChecked = prefs.getBoolean(key, defaultVal)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }

        row.addView(textCol)
        row.addView(switchView)
        return row
    }

    private fun createSliderRow(
        title: String,
        subtitle: String,
        key: String,
        minVal: Float,
        maxVal: Float,
        defaultVal: Float,
        unitSuffix: String,
        scaleMultiplier: Float,
        textPrimary: Int,
        textSecondary: Int,
        accentColor: Int
    ): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }

        val currentVal = prefs.getFloat(key, defaultVal).coerceIn(minVal, maxVal)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val valueBadge = TextView(this).apply {
            text = "${(currentVal * scaleMultiplier).toInt()}$unitSuffix"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }

        headerRow.addView(titleView)
        headerRow.addView(valueBadge)

        val subView = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, dp(4))
        }

        val slider = Slider(this).apply {
            valueFrom = minVal
            valueTo = maxVal
            value = currentVal
            addOnChangeListener { _, value, _ ->
                valueBadge.text = "${(value * scaleMultiplier).toInt()}$unitSuffix"
                prefs.edit().putFloat(key, value).apply()
            }
        }

        col.addView(headerRow)
        col.addView(subView)
        col.addView(slider)
        return col
    }

    private fun fullWidthParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply { this.topMargin = topMargin }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun checkPermissionsSilently() {
        if (ContextCompat.checkSelfPermission(this, XposedConstants.PERMISSION_UNLOCK_DEVICE) != PackageManager.PERMISSION_GRANTED) {
            requestUnlockPermsLauncher?.launch(XposedConstants.PERMISSION_UNLOCK_DEVICE)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDashboardState()
    }

    override fun onPause() {
        super.onPause()
        stopLiveCamera()
    }

    override fun onStop() {
        super.onStop()
        releaseRemoveFaceController()
    }

    private fun updateDashboardState() {
        val faceId = SharedUtil(this).getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID)
        val isEnrolled = faceId > -1

        if (isEnrolled) {
            statusTitle.text = "حالة الوجه: مسجّل ومُشفّر ✓"
            statusSubtitle.text = "محمي بتشفير AndroidKeyStore AES-GCM وجاهز لفتح القفل"
            enrollBtn.text = "إعادة تسجيل الوجه (مسح 5 زوايا 3D)"
            enrollBtn.isEnabled = true
            liveTestBtn.isEnabled = true
            systemAuthBtn.isEnabled = true
            removeBtn.isEnabled = true
            removeBtn.setOnClickListener { removeFace(faceId) }
        } else {
            statusTitle.text = "حالة الوجه: غير مسجّل"
            statusSubtitle.text = "اضغط على زر التسجيل بالأسفل لمسح وجهك من 5 زوايا"
            enrollBtn.text = "تسجيل الوجه الآن (مسح 5 زوايا 3D)"
            enrollBtn.isEnabled = true
            liveTestBtn.isEnabled = false
            systemAuthBtn.isEnabled = false
            removeBtn.isEnabled = false
        }

        val accessOk = isAccessServiceEnabled(this, LockscreenFaceAuthService::class.java)
        if (accessOk) {
            accessBadge.text = "● خدمة شاشة القفل مفعّلة وتعمل"
            accessBadge.setTextColor(Color.parseColor("#2ECC71"))
            accessBadge.setBackgroundColor(Color.parseColor("#163326"))
        } else {
            accessBadge.text = "⚠ خدمة شاشة القفل غير مفعّلة (اضغط هنا للتفعيل)"
            accessBadge.setTextColor(Color.parseColor("#FFB74D"))
            accessBadge.setBackgroundColor(Color.parseColor("#382A16"))
        }
    }

    private fun isAccessServiceEnabled(context: Context, accessibilityServiceClass: Class<*>): Boolean {
        val prefString = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val serviceName = accessibilityServiceClass.canonicalName
        return prefString?.split(":")?.filter { it.isNotBlank() }?.any {
            val slashIndex = it.indexOf('/')
            val possibleServiceName = it.substring(slashIndex + 1)
            possibleServiceName == serviceName || (it.substring(0, slashIndex) + possibleServiceName) == serviceName
        } == true
    }

    private fun removeFace(faceId: Int) {
        releaseRemoveFaceController()
        removeFaceController = RemoveFaceController(this, faceId, this).apply { start() }
    }

    private fun releaseRemoveFaceController() {
        removeFaceController?.stop()
        removeFaceController = null
    }

    override fun onRemove() {
        runOnUiThread {
            Toast.makeText(this, "تم حذف بيانات الوجه بنجاح", Toast.LENGTH_SHORT).show()
            updateDashboardState()
            releaseRemoveFaceController()
        }
    }

    override fun onError(errId: Int, message: String) {
        runOnUiThread {
            Toast.makeText(this, "فشل الحذف: $message", Toast.LENGTH_LONG).show()
            releaseRemoveFaceController()
        }
    }
}

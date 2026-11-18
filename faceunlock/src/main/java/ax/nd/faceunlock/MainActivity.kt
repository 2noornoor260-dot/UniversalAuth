package ax.nd.faceunlock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentContainerView
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.pref.SettingsFragment
import ax.nd.faceunlock.service.LockscreenFaceAuthService
import ax.nd.faceunlock.service.RemoveFaceController
import ax.nd.faceunlock.service.RemoveFaceControllerCallbacks
import ax.nd.faceunlock.util.SharedUtil
import ax.nd.universalauth.xposed.common.XposedConstants
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity(), RemoveFaceControllerCallbacks {

    private var removeFaceController: RemoveFaceController? = null
    private var requestUnlockPermsLauncher: ActivityResultLauncher<String>? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var themedCtx: Context

    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var accessBadge: TextView
    private lateinit var enrollBtn: MaterialButton
    private lateinit var systemAuthBtn: MaterialButton
    private lateinit var removeBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        // الحفاظ على ثيم الإعدادات الأصلية مع إضافة خصائص Material الحديثة
        theme.applyStyle(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar, false)
        theme.applyStyle(androidx.preference.R.style.PreferenceThemeOverlay, true)
        super.onCreate(savedInstanceState)

        themedCtx = ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar
        )
        prefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        requestUnlockPermsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        buildDashboard(savedInstanceState)
        checkAndAskForPermissions()
    }

    private fun buildDashboard(savedInstanceState: Bundle?) {
        val bgColor = Color.parseColor("#0F1117")
        val cardColor = Color.parseColor("#1A1E29")
        val accentColor = Color.parseColor("#4F8EF7")
        val textPrimary = Color.parseColor("#F4F6FA")
        val textSecondary = Color.parseColor("#9BA3B4")

        val scroll = NestedScrollView(themedCtx).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
        }

        val root = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        // 1. العنوان
        root.addView(TextView(themedCtx).apply {
            text = "SmartFace AI Unlock"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        root.addView(TextView(themedCtx).apply {
            text = "حماية 3D Face Mesh وكشف التزييف مفعّلان تلقائياً بأقصى سرعة"
            textSize = 12.5f
            setTextColor(accentColor)
            setPadding(0, dp(4), 0, dp(16))
        })

        // 2. بطاقة الحالة
        val statusCard = createCard(cardColor)
        val statusLayout = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusTitle = TextView(themedCtx).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        }
        statusSubtitle = TextView(themedCtx).apply {
            textSize = 12.5f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, dp(12))
        }
        accessBadge = TextView(themedCtx).apply {
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

        // 3. أزرار التحكم بالوجه
        val actionsCard = createCard(cardColor)
        val actionsLayout = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        enrollBtn = MaterialButton(themedCtx).apply {
            text = "تسجيل الوجه (مسح 5 زوايا 3D)"
            setBackgroundColor(accentColor)
            setTextColor(Color.WHITE)
            cornerRadius = dp(12)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SetupFaceIntroActivity::class.java))
            }
        }

        systemAuthBtn = MaterialButton(themedCtx).apply {
            text = "اختبار فتح القفل الآن"
            setBackgroundColor(Color.parseColor("#262B38"))
            setTextColor(textPrimary)
            cornerRadius = dp(12)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FaceAuthActivity::class.java))
            }
        }

        removeBtn = MaterialButton(themedCtx).apply {
            text = "حذف بيانات الوجه المسجلة"
            setBackgroundColor(Color.parseColor("#3B1E26"))
            setTextColor(Color.parseColor("#FF7A8A"))
            cornerRadius = dp(12)
        }

        actionsLayout.addView(enrollBtn, fullWidthParams(0))
        actionsLayout.addView(systemAuthBtn, fullWidthParams(dp(8)))
        actionsLayout.addView(removeBtn, fullWidthParams(dp(8)))
        actionsCard.addView(actionsLayout)
        root.addView(actionsCard)

        // 4. خيارات تجربة الاستخدام الذكية
        root.addView(sectionHeader("خيارات تجربة الاستخدام", textPrimary))
        val togglesCard = createCard(cardColor)
        val togglesLayout = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        togglesLayout.addView(createSwitchRow(
            title = "شرط فتح العينين",
            subtitle = "يمنع فتح الجهاز أثناء النوم أو إغماض العينين",
            key = SmartFaceEngine.KEY_REQUIRE_EYES,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "التعلم التكيفي الذكي",
            subtitle = "تحديث البصمة تلقائياً مع تغير الإضاءة وزوايا الاستخدام",
            key = SmartFaceEngine.KEY_ADAPTIVE,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "إضاءة الشاشة في الظلام",
            subtitle = "رفع سطوع الشاشة مؤقتاً أثناء التعرف على الوجه في الظلام",
            key = SmartFaceEngine.KEY_NIGHT_LIGHT,
            defaultVal = false,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesLayout.addView(createSwitchRow(
            title = "نبضة اهتزاز عند الفتح",
            subtitle = "اهتزاز خفيف لتأكيد نجاح التعرف على الوجه",
            key = SmartFaceEngine.KEY_VIBRATE,
            defaultVal = true,
            textPrimary = textPrimary,
            textSecondary = textSecondary
        ))

        togglesCard.addView(togglesLayout)
        root.addView(togglesCard)

        // 5. إعدادات النظام وشاشة القفل الأصلية (SettingsFragment)
        root.addView(sectionHeader("إعدادات شاشة القفل والنظام", textPrimary))
        val settingsContainer = FragmentContainerView(this).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(settingsContainer)

        scroll.addView(root)
        setContentView(scroll)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(settingsContainer.id, SettingsFragment())
                .commitNowAllowingStateLoss()
        }
    }

    private fun createCard(bgColor: Int): MaterialCardView {
        return MaterialCardView(themedCtx).apply {
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
        return TextView(themedCtx).apply {
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
        val row = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val textCol = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(themedCtx).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        textCol.addView(TextView(themedCtx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        })

        val switchView = SwitchMaterial(themedCtx).apply {
            isChecked = prefs.getBoolean(key, defaultVal)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }

        row.addView(textCol)
        row.addView(switchView)
        return row
    }

    private fun fullWidthParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply { this.topMargin = topMargin }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun checkAndAskForPermissions() {
        if (ContextCompat.checkSelfPermission(this, XposedConstants.PERMISSION_UNLOCK_DEVICE) != PackageManager.PERMISSION_GRANTED) {
            requestUnlockPermsLauncher?.launch(XposedConstants.PERMISSION_UNLOCK_DEVICE)
        }
    }

    fun browseForFiles() {
    }

    override fun onResume() {
        super.onResume()
        updateDashboardState()
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
            systemAuthBtn.isEnabled = true
            removeBtn.isEnabled = true
            removeBtn.setOnClickListener { removeFace(faceId) }
        } else {
            statusTitle.text = "حالة الوجه: غير مسجّل"
            statusSubtitle.text = "اضغط على زر التسجيل بالأسفل لمسح وجهك من 5 زوايا"
            enrollBtn.text = "تسجيل الوجه الآن (مسح 5 زوايا 3D)"
            enrollBtn.isEnabled = true
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

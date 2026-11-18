package ax.nd.faceunlock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity(), RemoveFaceControllerCallbacks {

    private var removeFaceController: RemoveFaceController? = null
    private var requestUnlockPermsLauncher: ActivityResultLauncher<String>? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var themedCtx: Context

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var accessRowText: TextView
    private lateinit var enrollRowText: TextView
    private lateinit var removeRowText: TextView

    private var isDarkTheme = false
    private var appleBlue = 0
    private var appleRed = 0
    private var appleGreen = 0
    private var textPrimary = 0
    private var textSecondary = 0
    private var dividerColor = 0
    private var cardBgColor = 0
    private var pageBgColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val baseTheme = if (isDarkTheme) {
            com.google.android.material.R.style.Theme_MaterialComponents_NoActionBar
        } else {
            com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar
        }
        theme.applyStyle(baseTheme, false)
        theme.applyStyle(androidx.preference.R.style.PreferenceThemeOverlay, true)
        super.onCreate(savedInstanceState)

        themedCtx = ContextThemeWrapper(this, baseTheme)
        prefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        requestUnlockPermsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        initApplePalette()
        window.statusBarColor = pageBgColor
        window.navigationBarColor = pageBgColor
        if (!isDarkTheme) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        buildAppleSettingsScreen(savedInstanceState)
        checkAndAskForPermissions()
    }

    private fun initApplePalette() {
        if (isDarkTheme) {
            pageBgColor = Color.parseColor("#000000")
            cardBgColor = Color.parseColor("#1C1C1E")
            dividerColor = Color.parseColor("#2C2C2E")
            textPrimary = Color.parseColor("#FFFFFF")
            textSecondary = Color.parseColor("#8E8E93")
            appleBlue = Color.parseColor("#0A84FF")
            appleRed = Color.parseColor("#FF453A")
            appleGreen = Color.parseColor("#30D158")
        } else {
            pageBgColor = Color.parseColor("#F2F2F7")
            cardBgColor = Color.parseColor("#FFFFFF")
            dividerColor = Color.parseColor("#E5E5EA")
            textPrimary = Color.parseColor("#000000")
            textSecondary = Color.parseColor("#8E8E93")
            appleBlue = Color.parseColor("#007AFF")
            appleRed = Color.parseColor("#FF3B30")
            appleGreen = Color.parseColor("#34C759")
        }
    }

    private fun buildAppleSettingsScreen(savedInstanceState: Bundle?) {
        val scroll = NestedScrollView(themedCtx).apply {
            setBackgroundColor(pageBgColor)
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val root = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(36))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // عنوان كبير بأسلوب إعدادات آبل
        root.addView(TextView(themedCtx).apply {
            text = "التعرّف على الوجه"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(dp(4), 0, dp(4), dp(18))
        })

        // المجموعة 1: حالة النظام وخدمة القفل
        val statusGroup = createInsetGroup()
        val statusRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        statusDot = View(themedCtx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
        }

        val statusTexts = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTitle = TextView(themedCtx).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
        }
        statusSubtitle = TextView(themedCtx).apply {
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        }
        statusTexts.addView(statusTitle)
        statusTexts.addView(statusSubtitle)
        statusRow.addView(statusDot)
        statusRow.addView(statusTexts)
        statusGroup.addView(statusRow)

        statusGroup.addView(createHairlineDivider())

        val accessRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        accessRowText = TextView(themedCtx).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chevron = TextView(themedCtx).apply {
            text = "‹"
            textSize = 20f
            setTextColor(textSecondary)
        }
        accessRow.addView(accessRowText)
        accessRow.addView(chevron)
        statusGroup.addView(accessRow)
        root.addView(statusGroup)

        // المجموعة 2: إدارة الوجه (صفوف نصية أنيقة مثل آبل)
        val faceActionsGroup = createInsetGroup()
        enrollRowText = TextView(themedCtx).apply {
            text = "إعداد مظهر جديد للوجه"
            textSize = 16f
            setTextColor(appleBlue)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SetupFaceIntroActivity::class.java))
            }
        }
        faceActionsGroup.addView(enrollRowText)
        faceActionsGroup.addView(createHairlineDivider())

        removeRowText = TextView(themedCtx).apply {
            text = "إعادة تعيين بيانات الوجه"
            textSize = 16f
            setTextColor(appleRed)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        faceActionsGroup.addView(removeRowText)
        root.addView(faceActionsGroup)

        // المجموعة 3: الذكاء والانتباه وتجربة الاستخدام
        root.addView(createSectionLabel("الانتباه وتجربة الاستخدام"))
        val smartGroup = createInsetGroup()

        smartGroup.addView(createAppleToggleRow(
            title = "طلب الانتباه عند الفتح",
            subtitle = "التحقق من فتح العينين والنظر إلى الشاشة",
            key = SmartFaceEngine.KEY_REQUIRE_EYES,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createAppleToggleRow(
            title = "التعلّم التكيفي المستمر",
            subtitle = "تحديث البصمة تلقائياً مع تغيّر الإضاءة والمظهر",
            key = SmartFaceEngine.KEY_ADAPTIVE,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createAppleToggleRow(
            title = "إضاءة ذكية في الظلام الدامس",
            subtitle = "رفع السطوع تلقائياً فقط عندما تكون الغرفة مظلمة",
            key = SmartFaceEngine.KEY_NIGHT_LIGHT,
            defaultVal = false
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createAppleToggleRow(
            title = "إظهار حالة المسح على شاشة القفل",
            subtitle = "عرض نص صغير أعلى الشاشة أثناء التعرّف",
            key = SmartFaceEngine.KEY_SHOW_LOCK_STATUS,
            defaultVal = false
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createAppleToggleRow(
            title = "نغمة تأكيد خفيفة عند الفتح",
            subtitle = "إصدار صوت نظام خفيف عند نجاح التعرّف",
            key = SmartFaceEngine.KEY_UNLOCK_CHIME,
            defaultVal = false
        ))
        root.addView(smartGroup)

        root.addView(createSectionFootnote(
            "الحماية ثلاثية الأبعاد وكشف التزييف العصبي مفعّلان تلقائياً في الخلفية لمنع الفتح عبر الصور أو الشاشات."
        ))

        // المجموعة 4: إعدادات شاشة القفل والنظام
        root.addView(createSectionLabel("إعدادات شاشة القفل"))
        val systemGroup = createInsetGroup()
        val settingsContainer = FragmentContainerView(this).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        systemGroup.addView(settingsContainer)
        root.addView(systemGroup)

        scroll.addView(root)
        setContentView(scroll)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(settingsContainer.id, SettingsFragment())
                .commitNowAllowingStateLoss()
        }
    }

    private fun createInsetGroup(): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(cardBgColor)
            cornerRadius = dp(13).toFloat()
            setStroke(1, dividerColor)
        }
        return LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
    }

    private fun createHairlineDivider(): View {
        return View(themedCtx).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply { marginStart = dp(16) }
        }
    }

    private fun createSectionLabel(text: String): TextView {
        return TextView(themedCtx).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textSecondary)
            setPadding(dp(14), dp(2), dp(14), dp(8))
        }
    }

    private fun createSectionFootnote(text: String): TextView {
        return TextView(themedCtx).apply {
            this.text = text
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(dp(14), 0, dp(14), dp(18))
        }
    }

    private fun createAppleToggleRow(
        title: String,
        subtitle: String,
        key: String,
        defaultVal: Boolean
    ): LinearLayout {
        val row = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val textCol = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        textCol.addView(TextView(themedCtx).apply {
            text = title
            textSize = 15.5f
            setTextColor(textPrimary)
        })
        textCol.addView(TextView(themedCtx).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        })

        val trackStates = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(appleGreen, if (isDarkTheme) Color.parseColor("#39393D") else Color.parseColor("#E9E9EA"))
        )
        val thumbStates = ColorStateList.valueOf(Color.WHITE)

        val switchView = SwitchMaterial(themedCtx).apply {
            isChecked = prefs.getBoolean(key, defaultVal)
            trackTintList = trackStates
            thumbTintList = thumbStates
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }

        row.addView(textCol)
        row.addView(switchView)
        return row
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

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isEnrolled) appleGreen else textSecondary)
        }
        statusDot.background = dotDrawable

        if (isEnrolled) {
            statusTitle.text = "التعرّف على الوجه مُفعّل"
            statusSubtitle.text = "البصمة ثلاثية الأبعاد مشفّرة ومحفوظة بأمان"
            enrollRowText.text = "إعادة إعداد مظهر الوجه"
            removeRowText.alpha = 1.0f
            removeRowText.isEnabled = true
            removeRowText.setOnClickListener { removeFace(faceId) }
        } else {
            statusTitle.text = "لم يتم إعداد الوجه بعد"
            statusSubtitle.text = "ابدأ المسح من 5 زوايا لتفعيل فتح القفل بالوجه"
            enrollRowText.text = "إعداد التعرّف على الوجه"
            removeRowText.alpha = 0.4f
            removeRowText.isEnabled = false
            removeRowText.setOnClickListener(null)
        }

        val accessOk = isAccessServiceEnabled(this, LockscreenFaceAuthService::class.java)
        if (accessOk) {
            accessRowText.text = "خدمة شاشة القفل نشطة"
            accessRowText.setTextColor(appleGreen)
        } else {
            accessRowText.text = "تفعيل خدمة شاشة القفل (مطلوب)"
            accessRowText.setTextColor(appleRed)
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
            Toast.makeText(this, "تمت إعادة تعيين بيانات الوجه", Toast.LENGTH_SHORT).show()
            updateDashboardState()
            releaseRemoveFaceController()
        }
    }

    override fun onError(errId: Int, message: String) {
        runOnUiThread {
            Toast.makeText(this, "تعذّر الحذف: $message", Toast.LENGTH_LONG).show()
            releaseRemoveFaceController()
        }
    }
}

package ax.nd.faceunlock

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import ax.nd.faceunlock.backend.SmartFaceEngine
import ax.nd.faceunlock.backend.core.SecurityProfile
import ax.nd.faceunlock.overlay.ChimePlayer
import ax.nd.faceunlock.pref.Prefs
import ax.nd.faceunlock.service.LockscreenFaceAuthService
import ax.nd.faceunlock.service.RemoveFaceController
import ax.nd.faceunlock.service.RemoveFaceControllerCallbacks
import ax.nd.faceunlock.util.SharedUtil
import ax.nd.universalauth.xposed.common.XposedConstants
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class MainActivity : AppCompatActivity(), RemoveFaceControllerCallbacks {

    private var removeFaceController: RemoveFaceController? = null
    private var requestUnlockPermsLauncher: ActivityResultLauncher<String>? = null
    private lateinit var smartPrefs: SharedPreferences
    private lateinit var sysPrefs: Prefs
    private lateinit var themedCtx: Context

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var accessRowText: TextView
    private lateinit var enrollRowText: TextView
    private lateinit var testRowText: TextView
    private lateinit var removeRowText: TextView
    private lateinit var chimeValueText: TextView
    private lateinit var animValueText: TextView
    private lateinit var modelRowText: TextView
    private lateinit var securityValueText: TextView
    private lateinit var edgeValueText: TextView
    private lateinit var fillValueText: TextView
    private lateinit var perfValueText: TextView

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
        theme.applyStyle(baseTheme, true)
        super.onCreate(savedInstanceState)

        themedCtx = ContextThemeWrapper(this, baseTheme)
        smartPrefs = getSharedPreferences(SmartFaceEngine.PREFS_NAME, Context.MODE_PRIVATE)
        sysPrefs = FaceApplication.getApp().prefs
        requestUnlockPermsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        initApplePalette()
        window.statusBarColor = pageBgColor
        window.navigationBarColor = pageBgColor
        if (!isDarkTheme) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        buildAppleSettingsScreen()
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

    private fun buildAppleSettingsScreen() {
        val scroll = NestedScrollView(themedCtx).apply {
            setBackgroundColor(pageBgColor)
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val root = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(40))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val headerBox = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(22))
        }
        val glyphView = FaceIdEmblemView(themedCtx, appleBlue).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        headerBox.addView(glyphView)
        headerBox.addView(TextView(themedCtx).apply {
            text = "التعرّف على الوجه"
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        })
        headerBox.addView(TextView(themedCtx).apply {
            text = "فتح القفل السريع والحماية ثلاثية الأبعاد"
            textSize = 14f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
        })
        root.addView(headerBox)

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
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        accessRowText = TextView(themedCtx).apply {
            textSize = 15.5f
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
        statusGroup.addView(createHairlineDivider())

        modelRowText = TextView(themedCtx).apply {
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { showModelDetails() }
        }
        statusGroup.addView(modelRowText)
        root.addView(statusGroup)

        val faceActionsGroup = createInsetGroup()

        enrollRowText = TextView(themedCtx).apply {
            text = "إعداد التعرّف على الوجه"
            textSize = 16f
            setTextColor(appleBlue)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SetupFaceIntroActivity::class.java))
            }
        }
        faceActionsGroup.addView(enrollRowText)
        faceActionsGroup.addView(createHairlineDivider())

        testRowText = TextView(themedCtx).apply {
            text = "اختبار التعرّف على الوجه"
            textSize = 16f
            setTextColor(appleBlue)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, FaceAuthActivity::class.java))
            }
        }
        faceActionsGroup.addView(testRowText)
        faceActionsGroup.addView(createHairlineDivider())

        removeRowText = TextView(themedCtx).apply {
            text = "إعادة تعيين بيانات الوجه"
            textSize = 16f
            setTextColor(appleRed)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        faceActionsGroup.addView(removeRowText)
        root.addView(faceActionsGroup)

        root.addView(createSectionLabel("الأمان والأداء"))
        val securityGroup = createInsetGroup()

        securityValueText = TextView(themedCtx)
        securityGroup.addView(createValueRow("مستوى الأمان", securityValueText) { showSecurityPicker() })
        securityGroup.addView(createHairlineDivider())

        securityGroup.addView(buildToggleRowView(
            "اشتراط نموذج الذكاء الاصطناعي لمكافحة التزييف",
            "عند الإيقاف يُسمح بالفتح بالتحقق الهندسي فقط (أقل أمانًا)",
            smartPrefs.getBoolean(SmartFaceEngine.KEY_REQUIRE_NEURAL_PAD, true)
        ) { checked ->
            smartPrefs.edit().putBoolean(SmartFaceEngine.KEY_REQUIRE_NEURAL_PAD, checked).apply()
            updateModelStatusRow()
        })
        securityGroup.addView(createHairlineDivider())

        perfValueText = TextView(themedCtx)
        securityGroup.addView(createValueRow("سرعة الفتح", perfValueText, showChevron = false) { })
        root.addView(securityGroup)

        root.addView(createSectionLabel("الانتباه والتفاعل الحي"))
        val smartGroup = createInsetGroup()

        smartGroup.addView(createSmartToggleRow(
            title = "طلب الانتباه عند الفتح",
            subtitle = "التحقق من فتح العينين والنظر إلى الشاشة",
            key = SmartFaceEngine.KEY_REQUIRE_EYES,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createSmartToggleRow(
            title = "التعلّم التكيفي المستمر",
            subtitle = "تحديث البصمة تلقائياً مع تغيّر الإضاءة والنظارات",
            key = SmartFaceEngine.KEY_ADAPTIVE,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createSmartToggleRow(
            title = "كبسولة Face ID ثلاثية الأبعاد",
            subtitle = "كبسولة ملتصقة بالكاميرا برمز وجه تتحول إلى قفل عند الفتح أو الرفض",
            key = SmartFaceEngine.KEY_CAPSULE_ANIM,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createSmartToggleRow(
            title = "لوحة الإضاءة البيضاء في الظلام",
            subtitle = "تعمل بثبات في الظلام لإضاءة الوجه تلقائياً",
            key = SmartFaceEngine.KEY_NIGHT_LIGHT,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        fillValueText = TextView(themedCtx)
        smartGroup.addView(createValueRow("شدة إضاءة الظلام", fillValueText) { showFillPicker() })
        smartGroup.addView(createHairlineDivider())

        smartGroup.addView(createSmartToggleRow(
            title = "اهتزاز خفيف عند الفتح",
            subtitle = "نقرة لمسية تؤكد نجاح التعرّف",
            key = SmartFaceEngine.KEY_HAPTICS,
            defaultVal = true
        ))
        smartGroup.addView(createHairlineDivider())

        edgeValueText = TextView(themedCtx)
        smartGroup.addView(createValueRow("موضع الكاميرا الأمامية", edgeValueText) { showEdgePicker() })
        smartGroup.addView(createHairlineDivider())

        val chimeRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { showChimePicker() }
        }
        chimeRow.addView(TextView(themedCtx).apply {
            text = "نغمة تأكيد الفتح"
            textSize = 15.5f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        chimeValueText = TextView(themedCtx).apply {
            text = getChimeLabel(smartPrefs.getString(SmartFaceEngine.KEY_UNLOCK_CHIME_TYPE, "apple_glass"))
            textSize = 14.5f
            setTextColor(textSecondary)
            setPadding(0, 0, dp(6), 0)
        }
        chimeRow.addView(chimeValueText)
        chimeRow.addView(TextView(themedCtx).apply {
            text = "‹"
            textSize = 20f
            setTextColor(textSecondary)
        })
        smartGroup.addView(chimeRow)
        root.addView(smartGroup)

        root.addView(createSectionLabel("سلوك شاشة القفل"))
        val lockBehaviorGroup = createInsetGroup()

        lockBehaviorGroup.addView(createSysToggleRow(
            title = "تخطي شاشة القفل مباشرة",
            subtitle = "الانتقال للشاشة الرئيسية فوراً أو البقاء لقراءة الإشعارات",
            initialVal = sysPrefs.bypassKeyguard.get(),
            onChanged = { sysPrefs.bypassKeyguard.set(it) }
        ))
        lockBehaviorGroup.addView(createHairlineDivider())

        lockBehaviorGroup.addView(createSysToggleRow(
            title = "التعرّف السريع عند إيقاظ الشاشة",
            subtitle = "بدء المسح فور الضغط على زر التشغيل قبل ظهور القفل",
            initialVal = sysPrefs.earlyUnlockHook.get(),
            onChanged = { sysPrefs.earlyUnlockHook.set(it) }
        ))
        lockBehaviorGroup.addView(createHairlineDivider())

        lockBehaviorGroup.addView(createSysToggleRow(
            title = "طلب رمز الدخول بعد إعادة التشغيل",
            subtitle = "تعزيز الأمان بعد إقلاع الجهاز لأول مرة",
            initialVal = sysPrefs.requirePinOnBoot.get(),
            onChanged = { sysPrefs.requirePinOnBoot.set(it) }
        ))
        lockBehaviorGroup.addView(createHairlineDivider())

        val animRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { showUnlockAnimationPicker() }
        }
        animRow.addView(TextView(themedCtx).apply {
            text = "حركة فتح القفل"
            textSize = 15.5f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        animValueText = TextView(themedCtx).apply {
            text = getAnimLabel(sysPrefs.unlockAnimation.get())
            textSize = 14.5f
            setTextColor(textSecondary)
            setPadding(0, 0, dp(6), 0)
        }
        animRow.addView(animValueText)
        animRow.addView(TextView(themedCtx).apply {
            text = "‹"
            textSize = 20f
            setTextColor(textSecondary)
        })
        lockBehaviorGroup.addView(animRow)
        root.addView(lockBehaviorGroup)

        root.addView(createSectionFootnote(
            "طبقات الحماية: تحليل نسيج الجلد، عمق الوجه ثلاثي الأبعاد، نموذج ذكاء اصطناعي لكشف الصور والشاشات، ثم تأكيد على عدة إطارات حيّة متتالية. " +
                    "لا يوجد نظام يعتمد كاميرا أمامية عادية وحدها يستحيل خداعه تماماً، ومستوى «أقصى» يرفع الصعوبة أكثر ويُبطئ الفتح قليلاً."
        ))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun getChimeLabel(key: String?): String {
        return when (key) {
            "none" -> "صامت"
            "crystal" -> "نبضة كريستال (Taptic)"
            "silk" -> "همسة الحرير"
            "titanium" -> "تأكيد معدني عميق"
            else -> "رنين آبل الزجاجي"
        }
    }

    private fun showChimePicker() {
        val labels = arrayOf(
            "رنين آبل الزجاجي (Apple Glass)",
            "نبضة كريستال (Crystal Taptic)",
            "همسة الحرير (Silk Aura)",
            "تأكيد معدني عميق (Titanium Lock)",
            "صامت (بدون صوت)"
        )
        val values = arrayOf("apple_glass", "crystal", "silk", "titanium", "none")
        val current = smartPrefs.getString(SmartFaceEngine.KEY_UNLOCK_CHIME_TYPE, "apple_glass")
        val currentIdx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(themedCtx)
            .setTitle("نغمة تأكيد الفتح")
            .setSingleChoiceItems(labels, currentIdx) { _, which ->
                val chosen = values[which]
                smartPrefs.edit().putString(SmartFaceEngine.KEY_UNLOCK_CHIME_TYPE, chosen).apply()
                chimeValueText.text = getChimeLabel(chosen)
                if (chosen != "none") {
                    ChimePlayer.play(chosen)
                }
            }
            .setPositiveButton("تم", null)
            .show()
    }

    private fun getAnimLabel(mode: String?): String {
        return when (mode) {
            "mode_wake_and_unlock" -> "إيقاظ وفتح فوري"
            "mode_wake_and_unlock_pulsing" -> "إيقاظ مع نبض"
            else -> "تلاشي ناعم"
        }
    }

    private fun showUnlockAnimationPicker() {
        val labels = arrayOf("تلاشي ناعم", "إيقاظ وفتح فوري", "إيقاظ مع نبض")
        val values = arrayOf("mode_unlock_fading", "mode_wake_and_unlock", "mode_wake_and_unlock_pulsing")
        val currentIdx = values.indexOf(sysPrefs.unlockAnimation.get()).coerceAtLeast(0)

        AlertDialog.Builder(themedCtx)
            .setTitle("حركة فتح القفل")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                sysPrefs.unlockAnimation.set(values[which])
                animValueText.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun createInsetGroup(): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(cardBgColor)
            cornerRadius = dp(14).toFloat()
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

    private fun createSmartToggleRow(
        title: String,
        subtitle: String,
        key: String,
        defaultVal: Boolean
    ): LinearLayout {
        return buildToggleRowView(title, subtitle, smartPrefs.getBoolean(key, defaultVal)) { isChecked ->
            smartPrefs.edit().putBoolean(key, isChecked).apply()
        }
    }

    private fun createSysToggleRow(
        title: String,
        subtitle: String,
        initialVal: Boolean,
        onChanged: (Boolean) -> Unit
    ): LinearLayout {
        return buildToggleRowView(title, subtitle, initialVal, onChanged)
    }

    private fun buildToggleRowView(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
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
            isChecked = checked
            trackTintList = trackStates
            thumbTintList = thumbStates
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
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
        // فحص ملفات النماذج في الخلفية (سريع) لعرض حالتها الحقيقية بدل افتراض سلامتها
        Thread {
            SmartFaceEngine.probeModels(applicationContext)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) updateModelStatusRow()
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        releaseRemoveFaceController()
    }

    private fun updateDashboardState() {
        val faceId = SharedUtil(this).getIntValueByKey(AppConstants.SHARED_KEY_FACE_ID)
        val flagged = faceId > -1
        val hasTemplates = SmartFaceEngine.hasTemplatesOnDisk(getDir("faceunlock_data", Context.MODE_PRIVATE))
        val isEnrolled = flagged && hasTemplates
        val needsReEnroll = flagged && !hasTemplates

        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isEnrolled) appleGreen else textSecondary)
        }
        statusDot.background = dotDrawable

        if (isEnrolled) {
            statusTitle.text = "التعرّف على الوجه مُفعّل"
            statusSubtitle.text = "البصمة رباعية الزوايا مشفّرة ومحفوظة بأمان"
            enrollRowText.text = "إعادة إعداد مظهر الوجه"

            testRowText.alpha = 1.0f
            testRowText.isEnabled = true
            testRowText.setOnClickListener {
                startActivity(Intent(this@MainActivity, FaceAuthActivity::class.java))
            }

            removeRowText.alpha = 1.0f
            removeRowText.isEnabled = true
            removeRowText.setOnClickListener {
                AlertDialog.Builder(themedCtx)
                    .setTitle("إعادة تعيين بيانات الوجه")
                    .setMessage("هل تريد حذف بصمة الوجه المسجلة؟")
                    .setPositiveButton("حذف") { _, _ -> removeFace(faceId) }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        } else {
            statusTitle.text = if (needsReEnroll) "يلزم إعادة إعداد الوجه" else "لم يتم إعداد الوجه بعد"
            statusSubtitle.text = if (needsReEnroll) {
                "تحسّنت صيغة البصمة في هذا الإصدار، أعد المسح مرة واحدة"
            } else {
                "ابدأ المسح من 4 زوايا لتفعيل فتح القفل بالوجه"
            }
            enrollRowText.text = "إعداد التعرّف على الوجه"

            testRowText.alpha = 0.4f
            testRowText.isEnabled = false
            testRowText.setOnClickListener(null)

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

        refreshValueTexts()
        updateModelStatusRow()
        updatePerformanceText()
    }

    // ───────────── الأمان والإعدادات الجديدة ─────────────

    private fun createValueRow(
        title: String,
        valueView: TextView,
        onClick: () -> Unit
    ): LinearLayout = createValueRow(title, valueView, true, onClick)

    private fun createValueRow(
        title: String,
        valueView: TextView,
        showChevron: Boolean = true,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            if (showChevron) setOnClickListener { onClick() }
        }
        row.addView(TextView(themedCtx).apply {
            text = title
            textSize = 15.5f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        valueView.apply {
            textSize = 14.5f
            setTextColor(textSecondary)
            setPadding(0, 0, dp(6), 0)
        }
        row.addView(valueView)
        if (showChevron) {
            row.addView(TextView(themedCtx).apply {
                text = "‹"
                textSize = 20f
                setTextColor(textSecondary)
            })
        }
        return row
    }

    private fun securityLabel(level: Int): String = when {
        level == SecurityProfile.LEVEL_STRICT -> "صارم"
        level == SecurityProfile.LEVEL_MAXIMUM -> "أقصى"
        else -> "متوازن"
    }

    private fun edgeLabel(mode: String?): String = when (mode) {
        "top" -> "أعلى الشاشة"
        "right" -> "يمين الشاشة"
        "bottom" -> "أسفل الشاشة"
        "left" -> "يسار الشاشة"
        else -> "تلقائي"
    }

    private fun refreshValueTexts() {
        securityValueText.text = securityLabel(
            smartPrefs.getInt(SmartFaceEngine.KEY_SECURITY_LEVEL, SecurityProfile.LEVEL_BALANCED)
        )
        edgeValueText.text = edgeLabel(smartPrefs.getString(SmartFaceEngine.KEY_CAMERA_EDGE, "auto"))
        fillValueText.text = "${smartPrefs.getInt(SmartFaceEngine.KEY_FILL_STRENGTH, 90)}%"
    }

    private fun showSecurityPicker() {
        val labels = arrayOf(
            "متوازن: سريع، إطاران حيّان متتاليان",
            "صارم: 3 إطارات وعتبات أعلى",
            "أقصى: 4 إطارات، عينان مفتوحتان دائماً، بلا تعلّم تكيّفي"
        )
        val current = smartPrefs.getInt(SmartFaceEngine.KEY_SECURITY_LEVEL, SecurityProfile.LEVEL_BALANCED).coerceIn(0, 2)
        AlertDialog.Builder(themedCtx)
            .setTitle("مستوى الأمان")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                smartPrefs.edit().putInt(SmartFaceEngine.KEY_SECURITY_LEVEL, which).apply()
                securityValueText.text = securityLabel(which)
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showEdgePicker() {
        val values = arrayOf("auto", "top", "right", "bottom", "left")
        val labels = Array(values.size) { i ->
            if (i == 0) "تلقائي (حسب قطع الكاميرا في الشاشة)" else edgeLabel(values[i])
        }
        val current = values.indexOf(smartPrefs.getString(SmartFaceEngine.KEY_CAMERA_EDGE, "auto")).coerceAtLeast(0)
        AlertDialog.Builder(themedCtx)
            .setTitle("موضع الكاميرا الأمامية")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                smartPrefs.edit().putString(SmartFaceEngine.KEY_CAMERA_EDGE, values[which]).apply()
                edgeValueText.text = edgeLabel(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showFillPicker() {
        val values = intArrayOf(50, 60, 70, 80, 90, 100)
        val labels = Array(values.size) { i -> "${values[i]}%" }
        val cur = values.indexOf(smartPrefs.getInt(SmartFaceEngine.KEY_FILL_STRENGTH, 90))
        AlertDialog.Builder(themedCtx)
            .setTitle("شدة إضاءة الظلام")
            .setSingleChoiceItems(labels, if (cur < 0) 4 else cur) { dialog, which ->
                smartPrefs.edit().putInt(SmartFaceEngine.KEY_FILL_STRENGTH, values[which]).apply()
                fillValueText.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun stateLabel(state: Int): String = when (state) {
        SmartFaceEngine.STATE_OK -> "سليم"
        SmartFaceEngine.STATE_MISSING -> "مفقود"
        SmartFaceEngine.STATE_INVALID -> "غير متوافق"
        else -> "جارٍ الفحص"
    }

    private fun updateModelStatusRow() {
        val rec = SmartFaceEngine.recognizerState
        val spf = SmartFaceEngine.spoofState
        val requirePad = smartPrefs.getBoolean(SmartFaceEngine.KEY_REQUIRE_NEURAL_PAD, true)
        val text: String
        val color: Int
        when {
            rec == SmartFaceEngine.STATE_UNKNOWN -> {
                text = "جارٍ فحص نماذج الذكاء الاصطناعي…"
                color = textSecondary
            }
            rec != SmartFaceEngine.STATE_OK -> {
                text = "نموذج التعرّف غير متوافق أو مفقود: التعرّف معطّل (اضغط للتفاصيل)"
                color = appleRed
            }
            spf == SmartFaceEngine.STATE_OK -> {
                text = "نماذج التعرّف ومكافحة التزييف سليمة"
                color = appleGreen
            }
            requirePad -> {
                text = "نموذج مكافحة التزييف غير متوافق: الفتح معطّل حفاظاً على الأمان (اضغط للتفاصيل)"
                color = appleRed
            }
            else -> {
                text = "يعمل بحماية هندسية فقط بلا نموذج مكافحة التزييف (أقل أمانًا)"
                color = appleRed
            }
        }
        modelRowText.text = text
        modelRowText.setTextColor(color)
    }

    private fun showModelDetails() {
        val reason = SmartFaceEngine.modelReason.ifEmpty { "لا توجد مشكلات في النماذج." }
        val recOk = SmartFaceEngine.recognizerState == SmartFaceEngine.STATE_OK
        val spoofBad = SmartFaceEngine.spoofState == SmartFaceEngine.STATE_MISSING ||
                SmartFaceEngine.spoofState == SmartFaceEngine.STATE_INVALID
        val requirePad = smartPrefs.getBoolean(SmartFaceEngine.KEY_REQUIRE_NEURAL_PAD, true)

        val builder = AlertDialog.Builder(themedCtx)
            .setTitle("حالة النماذج")
            .setMessage(
                "نموذج التعرّف: ${stateLabel(SmartFaceEngine.recognizerState)}\n" +
                        "نموذج مكافحة التزييف: ${stateLabel(SmartFaceEngine.spoofState)}\n\n" +
                        reason
            )
            .setPositiveButton("حسناً", null)

        if (recOk && spoofBad) {
            if (requirePad) {
                builder.setNeutralButton("السماح بالتحقق الهندسي فقط (أقل أمانًا)") { _, _ ->
                    smartPrefs.edit().putBoolean(SmartFaceEngine.KEY_REQUIRE_NEURAL_PAD, false).apply()
                    Toast.makeText(this, "تم السماح بالوضع الهندسي. ضع نموذجاً صالحاً لاستعادة الحماية الكاملة.", Toast.LENGTH_LONG).show()
                    recreate()
                }
            } else {
                builder.setNeutralButton("اشتراط النموذج (أكثر أماناً)") { _, _ ->
                    smartPrefs.edit().putBoolean(SmartFaceEngine.KEY_REQUIRE_NEURAL_PAD, true).apply()
                    recreate()
                }
            }
        }
        builder.show()
    }

    private fun updatePerformanceText() {
        val count = smartPrefs.getInt(SmartFaceEngine.KEY_STAT_COUNT, 0)
        if (count == 0) {
            perfValueText.text = "لا توجد بيانات بعد"
            return
        }
        val last = smartPrefs.getLong(SmartFaceEngine.KEY_STAT_LAST_MS, 0L) / 1000.0
        val avg = smartPrefs.getLong(SmartFaceEngine.KEY_STAT_AVG_MS, 0L) / 1000.0
        perfValueText.text = String.format(Locale.US, "آخر: %.2f ث · المتوسط: %.2f ث", last, avg)
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

    private class FaceIdEmblemView(ctx: Context, color: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3.2f * ctx.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = paint.strokeWidth
            val arm = w * 0.22f
            val r = w * 0.16f

            canvas.drawArc(RectF(pad, pad, pad + 2 * r, pad + 2 * r), 180f, 90f, false, paint)
            canvas.drawLine(pad + r, pad, pad + arm, pad, paint)
            canvas.drawLine(pad, pad + r, pad, pad + arm, paint)

            canvas.drawArc(RectF(w - pad - 2 * r, pad, w - pad, pad + 2 * r), 270f, 90f, false, paint)
            canvas.drawLine(w - pad - arm, pad, w - pad - r, pad, paint)
            canvas.drawLine(w - pad, pad + r, w - pad, pad + arm, paint)

            canvas.drawArc(RectF(pad, h - pad - 2 * r, pad + 2 * r, h - pad), 90f, 90f, false, paint)
            canvas.drawLine(pad + r, h - pad, pad + arm, h - pad, paint)
            canvas.drawLine(pad, h - pad - arm, pad, h - pad - r, paint)

            canvas.drawArc(RectF(w - pad - 2 * r, h - pad - 2 * r, w - pad, h - pad), 0f, 90f, false, paint)
            canvas.drawLine(w - pad - arm, h - pad, w - pad - r, h - pad, paint)
            canvas.drawLine(w - pad, h - pad - arm, w - pad, h - pad - r, paint)

            val eyeY = h * 0.40f
            canvas.drawLine(w * 0.35f, eyeY - h * 0.04f, w * 0.35f, eyeY + h * 0.04f, paint)
            canvas.drawLine(w * 0.65f, eyeY - h * 0.04f, w * 0.65f, eyeY + h * 0.04f, paint)

            canvas.drawLine(w * 0.50f, h * 0.42f, w * 0.50f, h * 0.55f, paint)
            canvas.drawLine(w * 0.46f, h * 0.55f, w * 0.50f, h * 0.55f, paint)

            canvas.drawArc(RectF(w * 0.36f, h * 0.48f, w * 0.64f, h * 0.68f), 25f, 130f, false, paint)
        }
    }
}

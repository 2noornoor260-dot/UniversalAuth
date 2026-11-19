package ax.nd.faceunlock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SetupFaceIntroActivity extends FaceBaseActivity {
    public static final String EXTRA_ENROLL_SUCCESS = "enroll_success";

    public static boolean hasAllPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bgColor = isDark ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
        int cardColor = isDark ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
        int primaryText = isDark ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
        int secondaryText = Color.parseColor("#8E8E93");
        int appleBlue = isDark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        getWindow().setStatusBarColor(bgColor);
        getWindow().setNavigationBarColor(bgColor);
        if (!isDark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        root.setPadding(dp(24), dp(20), dp(24), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // زر إلغاء علوي نظيف
        FrameLayout topBar = new FrameLayout(this);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("إلغاء");
        cancelBtn.setTextSize(17f);
        cancelBtn.setTextColor(appleBlue);
        cancelBtn.setPadding(dp(4), dp(6), dp(4), dp(6));
        cancelBtn.setOnClickListener(v -> {
            setResult(0);
            finish();
        });
        topBar.addView(cancelBtn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        root.addView(topBar);

        // المحتوى الأوسط
        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        centerCol.setLayoutParams(centerParams);

        // أيقونة Face ID داخل هالة ناعمة
        FrameLayout halo = new FrameLayout(this);
        GradientDrawable haloBg = new GradientDrawable();
        haloBg.setShape(GradientDrawable.OVAL);
        haloBg.setColor(isDark ? Color.parseColor("#14263D") : Color.parseColor("#E8F2FF"));
        halo.setBackground(haloBg);
        LinearLayout.LayoutParams haloParams = new LinearLayout.LayoutParams(dp(110), dp(110));
        haloParams.topMargin = dp(24);
        halo.setLayoutParams(haloParams);

        FaceIdVectorView glyph = new FaceIdVectorView(this, appleBlue);
        halo.addView(glyph, new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER));
        centerCol.addView(halo);

        TextView title = new TextView(this);
        title.setText("كيفية إعداد التعرّف على الوجه");
        title.setTextSize(26f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(24), 0, dp(10));
        centerCol.addView(title);

        TextView desc = new TextView(this);
        desc.setText("للحصول على أعلى سرعة وأمان، ضع وجهك أمام الكاميرا ثم حرّك رأسك برفق في 5 اتجاهات.");
        desc.setTextSize(15f);
        desc.setTextColor(secondaryText);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(dp(8), 0, dp(8), dp(26));
        centerCol.addView(desc);

        // بطاقة إرشادات مرتبة
        LinearLayout tipsCard = new LinearLayout(this);
        tipsCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(16));
        tipsCard.setBackground(cardBg);
        tipsCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        tipsCard.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tipsCard.addView(buildTipRow("1", "إضاءة متوازنة", "تأكّد من وجود إضاءة واضحة على وجهك وتجنّب الإضاءة الخلفية القوية.", primaryText, secondaryText, appleBlue));
        tipsCard.addView(buildTipRow("2", "رؤية واضحة للعينين", "أزل أي عائق يغطي الوجه، ويمكنك إبقاء النظارات الطبية المعتادة.", primaryText, secondaryText, appleBlue));
        tipsCard.addView(buildTipRow("3", "خصوصية كاملة على جهازك", "تُشفّر بيانات وجهك محلياً ولا تُغادر جهازك مطلقاً.", primaryText, secondaryText, appleBlue));

        centerCol.addView(tipsCard);
        root.addView(centerCol);

        // زر البدء السفلي بأسلوب آبل
        TextView startBtn = new TextView(this);
        startBtn.setText("البدء");
        startBtn.setTextSize(17f);
        startBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        startBtn.setTextColor(Color.WHITE);
        startBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(appleBlue);
        btnBg.setCornerRadius(dp(14));
        startBtn.setBackground(btnBg);
        startBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        startBtn.setOnClickListener(v -> {
            Intent enrollIntent = new Intent(SetupFaceIntroActivity.this, FaceEnrollActivity.class);
            parseIntent(enrollIntent);
            startActivityForResult(enrollIntent, 1);
        });
        root.addView(startBtn);

        setContentView(root);
    }

    private LinearLayout buildTipRow(String num, String title, String subtitle, int titleColor, int subColor, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView badge = new TextView(this);
        badge.setText(num);
        badge.setTextSize(13f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(accent);
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        badgeParams.setMarginEnd(dp(14));
        row.addView(badge, badgeParams);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextColor(titleColor);
        col.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(13f);
        s.setTextColor(subColor);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);

        row.addView(col);
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            setResult(resultCode, data);
            finish();
        }
    }

    private static class FaceIdVectorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FaceIdVectorView(Context ctx, int color) {
            super(ctx);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(3.2f * ctx.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float pad = paint.getStrokeWidth();
            float arm = w * 0.22f;
            float r = w * 0.16f;

            canvas.drawArc(new RectF(pad, pad, pad + 2 * r, pad + 2 * r), 180f, 90f, false, paint);
            canvas.drawLine(pad + r, pad, pad + arm, pad, paint);
            canvas.drawLine(pad, pad + r, pad, pad + arm, paint);

            canvas.drawArc(new RectF(w - pad - 2 * r, pad, w - pad, pad + 2 * r), 270f, 90f, false, paint);
            canvas.drawLine(w - pad - arm, pad, w - pad - r, pad, paint);
            canvas.drawLine(w - pad, pad + r, w - pad, pad + arm, paint);

            canvas.drawArc(new RectF(pad, h - pad - 2 * r, pad + 2 * r, h - pad), 90f, 90f, false, paint);
            canvas.drawLine(pad + r, h - pad, pad + arm, h - pad, paint);
            canvas.drawLine(pad, h - pad - arm, pad, h - pad - r, paint);

            canvas.drawArc(new RectF(w - pad - 2 * r, h - pad - 2 * r, w - pad, h - pad), 0f, 90f, false, paint);
            canvas.drawLine(w - pad - arm, h - pad, w - pad - r, h - pad, paint);
            canvas.drawLine(w - pad, h - pad - arm, w - pad, h - pad - r, paint);

            float eyeY = h * 0.40f;
            canvas.drawLine(w * 0.35f, eyeY - h * 0.04f, w * 0.35f, eyeY + h * 0.04f, paint);
            canvas.drawLine(w * 0.65f, eyeY - h * 0.04f, w * 0.65f, eyeY + h * 0.04f, paint);
            canvas.drawLine(w * 0.50f, h * 0.42f, w * 0.50f, h * 0.55f, paint);
            canvas.drawLine(w * 0.46f, h * 0.55f, w * 0.50f, h * 0.55f, paint);
            canvas.drawArc(new RectF(w * 0.36f, h * 0.48f, w * 0.64f, h * 0.68f), 25f, 130f, false, paint);
        }
    }
}

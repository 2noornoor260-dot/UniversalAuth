package ax.nd.faceunlock;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FaceFinish extends FaceBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bgColor = isDark ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
        int primaryText = isDark ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
        int secondaryText = Color.parseColor("#8E8E93");
        int appleBlue = isDark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");
        int appleGreen = isDark ? Color.parseColor("#30D158") : Color.parseColor("#34C759");

        getWindow().setStatusBarColor(bgColor);
        getWindow().setNavigationBarColor(bgColor);
        if (!isDark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        root.setPadding(dp(24), dp(24), dp(24), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER);
        centerCol.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout checkCircle = new FrameLayout(this);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(appleGreen);
        checkCircle.setBackground(circleBg);
        checkCircle.setLayoutParams(new LinearLayout.LayoutParams(dp(92), dp(92)));
        checkCircle.addView(new CheckmarkView(this), new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        centerCol.addView(checkCircle);

        TextView title = new TextView(this);
        title.setText("تم إعداد التعرّف على الوجه");
        title.setTextSize(26f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(26), 0, dp(10));
        centerCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("يمكنك الآن فتح قفل جهازك بمجرد النظر إليه بسرعة وأمان.");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(secondaryText);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(12), 0, dp(12), 0);
        centerCol.addView(subtitle);

        root.addView(centerCol);

        TextView doneBtn = new TextView(this);
        doneBtn.setText("تم");
        doneBtn.setTextSize(17f);
        doneBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        doneBtn.setTextColor(Color.WHITE);
        doneBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(appleBlue);
        btnBg.setCornerRadius(dp(14));
        doneBtn.setBackground(btnBg);
        doneBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        doneBtn.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });
        root.addView(doneBtn);

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static class CheckmarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckmarkView(Context ctx) {
            super(ctx);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(4.5f * ctx.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            Path p = new Path();
            p.moveTo(w * 0.22f, h * 0.52f);
            p.lineTo(w * 0.42f, h * 0.72f);
            p.lineTo(w * 0.78f, h * 0.30f);
            canvas.drawPath(p, paint);
        }
    }
}

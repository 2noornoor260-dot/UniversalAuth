package ax.nd.faceunlock;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FaceTryAgain extends FaceBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bgColor = isDark ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
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
        root.setPadding(dp(24), dp(24), dp(24), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER);
        centerCol.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = new TextView(this);
        title.setText("تعذّر إكمال المسح");
        title.setTextSize(26f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextColor(primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        centerCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("تأكّد من وجود إضاءة كافية وأن وجهك بالكامل داخل الإطار دون حركة سريعة.");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(secondaryText);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(12), 0, dp(12), 0);
        centerCol.addView(subtitle);

        root.addView(centerCol);

        TextView retryBtn = new TextView(this);
        retryBtn.setText("المحاولة مرة أخرى");
        retryBtn.setTextSize(17f);
        retryBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        retryBtn.setTextColor(Color.WHITE);
        retryBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(appleBlue);
        btnBg.setCornerRadius(dp(14));
        retryBtn.setBackground(btnBg);
        retryBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        retryBtn.setOnClickListener(v -> {
            Intent retryIntent = new Intent(FaceTryAgain.this, FaceEnrollActivity.class);
            parseIntent(retryIntent);
            startActivity(retryIntent);
            finish();
        });
        root.addView(retryBtn);

        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("إلغاء");
        cancelBtn.setTextSize(16f);
        cancelBtn.setTextColor(appleBlue);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(0, dp(16), 0, dp(4));
        cancelBtn.setOnClickListener(v -> {
            setResult(0);
            finish();
        });
        root.addView(cancelBtn);

        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}

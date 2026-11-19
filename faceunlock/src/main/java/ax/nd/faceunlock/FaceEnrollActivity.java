package ax.nd.faceunlock;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import ax.nd.faceunlock.backend.SmartFaceEngine;
import ax.nd.faceunlock.camera.CameraFaceEnrollController;
import ax.nd.faceunlock.service.FaceAuthService;
import ax.nd.faceunlock.stub.ContextShim;
import ax.nd.faceunlock.stub.biometrics.BiometricFaceConstants;
import ax.nd.faceunlock.stub.face.FaceManager;
import ax.nd.faceunlock.util.CircleSurfaceView;
import ax.nd.faceunlock.util.Settings;
import ax.nd.faceunlock.util.SharedUtil;
import ax.nd.faceunlock.util.Util;

import static ax.nd.faceunlock.FaceConstants.MG_UNLOCK_FAILED;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.internal.util.custom.faceunlock.IFaceServiceReceiver;

public class FaceEnrollActivity extends FaceBaseActivity {
    private static final String TAG = FaceEnrollActivity.class.getSimpleName();
    protected Handler mHandler;
    protected HandlerThread mHandlerThread;
    protected CancellationSignal mEnrollmentCancel = new CancellationSignal();
    protected SharedUtil mSharedUtil;
    private TextView mFaceEnrollMsg;
    private TextView mStepCounterText;
    private AppleFaceIdRingView mTicksView;
    private final View[] mStagePills = new View[5];
    private boolean mHasCameraPermission = false;
    private CameraFaceEnrollController mCameraEnrollService;
    private FaceManager mFaceManager;
    private boolean mIsActivityPaused = false;
    private CircleSurfaceView mSurface;
    private boolean mIsDarkTheme = false;

    private final CameraFaceEnrollController.CameraCallback mCameraCallback = new CameraFaceEnrollController.CameraCallback() {
        @Override
        public int handleSaveFeature(byte[] bArr, int i, int i2, int i3) {
            return -1;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void setDetectArea(Camera.Size size) {
        }

        @Override
        public void handleSaveFeatureResult(int error) {
            runOnUiThread(FaceEnrollActivity.this::syncProgressUi);
        }

        @Override
        public void onTimeout() {
            if (!isFinishing() && !isDestroyed()) {
                if (Settings.isFaceUnlockAvailable(FaceEnrollActivity.this)) {
                    startFinishActivity();
                    return;
                }
                if (!mIsActivityPaused) {
                    Intent intent = new Intent();
                    intent.setClass(FaceEnrollActivity.this, FaceTryAgain.class);
                    parseIntent(intent);
                    startActivity(intent);
                }
                finish();
            }
        }

        @Override
        public void onCameraError() {
            if (!isFinishing() && !isDestroyed()) {
                if (!mIsActivityPaused) {
                    Intent intent = new Intent();
                    intent.setClass(FaceEnrollActivity.this, FaceTryAgain.class);
                    parseIntent(intent);
                    startActivity(intent);
                }
                finish();
            }
        }

        @Override
        public void onFaceDetected() {
            runOnUiThread(FaceEnrollActivity.this::syncProgressUi);
        }
    };

    private final FaceManager.EnrollmentCallback mEnrollmentCallback = new FaceManager.EnrollmentCallback() {
        @Override
        public void onEnrollmentProgress(int i) {
            runOnUiThread(() -> {
                if (i == 0) {
                    SmartFaceEngine.currentEnrollPercent = 100f;
                    SmartFaceEngine.currentEnrollStage = 5;
                }
                syncProgressUi();
                if (i == 0) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isDestroyed() && !isFinishing()) {
                            startFinishActivity();
                        }
                    }, 800);
                }
            });
        }

        @Override
        public void onEnrollmentHelp(int i, final CharSequence charSequence) {
            runOnUiThread(FaceEnrollActivity.this::syncProgressUi);
        }

        @Override
        public void onEnrollmentError(int i, CharSequence charSequence) {
            if (!mIsActivityPaused) {
                Intent intent = new Intent();
                intent.setClass(FaceEnrollActivity.this, FaceTryAgain.class);
                if (i != MG_UNLOCK_FAILED) {
                    parseIntent(intent);
                } else {
                    setResult(-1);
                }
                startActivity(intent);
            }
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        mIsDarkTheme = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mSharedUtil = new SharedUtil(this);
        SmartFaceEngine.currentEnrollPercent = 0f;
        SmartFaceEngine.currentEnrollStage = 0;
        SmartFaceEngine.currentEnrollHint = "ضع وجهك داخل الإطار وانظر للأمام";

        int bg = mIsDarkTheme ? Color.BLACK : Color.WHITE;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!mIsDarkTheme) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        mHasCameraPermission = false;
        mFaceManager = new ContextShim(this).getSystemService(FaceManager.class);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != 0) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 0);
            return;
        }
        synchronized (this) {
            mHasCameraPermission = true;
        }
    }

    boolean enrollCalled = false;
    private final ServiceConnection faceAuthConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            IFaceServiceReceiver authCallback = new IFaceServiceReceiver.Stub() {
                @Override
                public void onEnrollResult(int faceId, int userId, int remaining) throws RemoteException {
                    mEnrollmentCallback.onEnrollmentProgress(remaining);
                }

                @Override
                public void onAuthenticated(int faceId, int userId, byte[] token) {
                }

                @Override
                public void onError(int error, int vendorCode) throws RemoteException {
                    int clientErrId = FaceManager.calcClientMsgId(error, vendorCode);
                    String message = FaceManager.getErrorString(FaceEnrollActivity.this, error, vendorCode);
                    mEnrollmentCallback.onEnrollmentError(clientErrId, message);
                }

                @Override
                public void onRemoved(int[] faceIds, int userId) {
                }

                @Override
                public void onEnumerate(int[] faceIds, int userId) {
                }
            };
            IFaceService authBinder = (IFaceService) iBinder;
            try {
                authBinder.setCallback(authCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback setup error!", e);
            }

            if (!enrollCalled) {
                enrollCalled = true;
                try {
                    authBinder.enroll(new byte[]{0}, 90, new int[]{1});
                } catch (RemoteException e) {
                    try {
                        authCallback.onError(BiometricFaceConstants.FACE_ERROR_UNKNOWN, 0);
                    } catch (RemoteException ignored) {
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
        }
    };

    private void init() {
        int bgColor = mIsDarkTheme ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
        int primaryText = mIsDarkTheme ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
        int appleBlue = mIsDarkTheme ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        root.setPadding(dp(24), dp(20), dp(24), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

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

        LinearLayout centerCol = new LinearLayout(this);
        centerCol.setOrientation(LinearLayout.VERTICAL);
        centerCol.setGravity(Gravity.CENTER);
        centerCol.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView titleView = new TextView(this);
        titleView.setText("مسح الوجه ثلاثي الأبعاد");
        titleView.setTextSize(26f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleView.setTextColor(primaryText);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, dp(6));
        centerCol.addView(titleView);

        mStepCounterText = new TextView(this);
        mStepCounterText.setText("الزاوية 1 من 5");
        mStepCounterText.setTextSize(14f);
        mStepCounterText.setTextColor(appleBlue);
        mStepCounterText.setGravity(Gravity.CENTER);
        mStepCounterText.setPadding(0, 0, 0, dp(26));
        centerCol.addView(mStepCounterText);

        int ringSize = dp(284);
        int camSize = dp(236);

        FrameLayout scannerContainer = new FrameLayout(this);
        scannerContainer.setLayoutParams(new LinearLayout.LayoutParams(ringSize, ringSize));

        mSurface = new CircleSurfaceView(this);
        mSurface.setPercent(0f);
        scannerContainer.addView(mSurface, new FrameLayout.LayoutParams(camSize, camSize, Gravity.CENTER));

        CameraVignetteOverlayView vignetteView = new CameraVignetteOverlayView(this, bgColor, appleBlue);
        scannerContainer.addView(vignetteView, new FrameLayout.LayoutParams(camSize, camSize, Gravity.CENTER));

        mTicksView = new AppleFaceIdRingView(this, mIsDarkTheme);
        scannerContainer.addView(mTicksView, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        centerCol.addView(scannerContainer);

        mFaceEnrollMsg = new TextView(this);
        mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        mFaceEnrollMsg.setTextSize(18f);
        mFaceEnrollMsg.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mFaceEnrollMsg.setTextColor(primaryText);
        mFaceEnrollMsg.setGravity(Gravity.CENTER);
        mFaceEnrollMsg.setPadding(dp(12), dp(28), dp(12), dp(18));
        centerCol.addView(mFaceEnrollMsg);

        LinearLayout pillsRow = new LinearLayout(this);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 5; i++) {
            View pill = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(5));
            lp.setMargins(dp(4), 0, dp(4), 0);
            pill.setLayoutParams(lp);
            mStagePills[i] = pill;
            pillsRow.addView(pill);
        }
        centerCol.addView(pillsRow);

        root.addView(centerCol);
        setContentView(root);
        syncProgressUi();

        mSurface.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    syncProgressUi();
                    mSurface.postDelayed(this, 120);
                }
            }
        }, 120);

        if (mCameraEnrollService == null) {
            mCameraEnrollService = CameraFaceEnrollController.getInstance();
            mCameraEnrollService.setSurfaceHolder(mSurface.getHolder());
        }

        enrollCalled = false;
        bindService(new Intent(this, FaceAuthService.class), faceAuthConn, Context.BIND_AUTO_CREATE);
        mCameraEnrollService.start(mCameraCallback, 45000);
    }

    private void syncProgressUi() {
        float pct = SmartFaceEngine.currentEnrollPercent;
        int stage = SmartFaceEngine.currentEnrollStage;

        if (mSurface != null) {
            mSurface.setPercent(pct);
        }
        if (mTicksView != null) {
            mTicksView.updateState(pct, stage);
        }
        if (mFaceEnrollMsg != null) {
            mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        }
        if (mStepCounterText != null) {
            int displayStep = Math.min(stage + 1, 5);
            mStepCounterText.setText(stage >= 5 ? "اكتمل المسح بنجاح" : ("الزاوية " + displayStep + " من 5"));
        }

        int activeColor = mIsDarkTheme ? Color.parseColor("#30D158") : Color.parseColor("#34C759");
        int currentColor = mIsDarkTheme ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");
        int idleColor = mIsDarkTheme ? Color.parseColor("#2C2C2E") : Color.parseColor("#D1D1D6");

        for (int i = 0; i < 5; i++) {
            View pill = mStagePills[i];
            if (pill == null) continue;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(dp(3));

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
            if (i < stage) {
                d.setColor(activeColor);
                lp.width = dp(14);
            } else if (i == stage) {
                d.setColor(currentColor);
                lp.width = dp(26);
            } else {
                d.setColor(idleColor);
                lp.width = dp(14);
            }
            pill.setLayoutParams(lp);
            pill.setBackground(d);
        }
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }

    private static class CameraVignetteOverlayView extends View {
        private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int bgColor;

        CameraVignetteOverlayView(Context ctx, int bgColor, int accentColor) {
            super(ctx);
            this.bgColor = bgColor;
            framePaint.setColor(Color.argb(70, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(1.5f * ctx.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float r = Math.min(cx, cy);

            vignettePaint.setShader(new RadialGradient(
                    cx, cy, r,
                    new int[]{Color.TRANSPARENT, Color.TRANSPARENT, bgColor},
                    new float[]{0f, 0.84f, 1.0f},
                    Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(cx, cy, r, vignettePaint);
            canvas.drawCircle(cx, cy, r * 0.90f, framePaint);
        }
    }

    private static class AppleFaceIdRingView extends View {
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guideArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int greenColor;
        private final int blueColor;
        private final int idleColor;
        private float animatedProgress = 0f;
        private int currentStage = 0;
        private ValueAnimator progressAnim;

        AppleFaceIdRingView(Context ctx, boolean dark) {
            super(ctx);
            float density = ctx.getResources().getDisplayMetrics().density;
            greenColor = dark ? Color.parseColor("#30D158") : Color.parseColor("#34C759");
            blueColor = dark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");
            idleColor = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#D1D1D6");

            tickPaint.setStrokeCap(Paint.Cap.ROUND);
            tickPaint.setStrokeWidth(3.2f * density);

            guideArcPaint.setColor(blueColor);
            guideArcPaint.setStyle(Paint.Style.STROKE);
            guideArcPaint.setStrokeCap(Paint.Cap.ROUND);
            guideArcPaint.setStrokeWidth(3.5f * density);
        }

        void updateState(float targetPct, int stage) {
            this.currentStage = stage;
            if (Math.abs(targetPct - animatedProgress) > 0.5f) {
                if (progressAnim != null) progressAnim.cancel();
                progressAnim = ValueAnimator.ofFloat(animatedProgress, targetPct);
                progressAnim.setDuration(220L);
                progressAnim.setInterpolator(new DecelerateInterpolator());
                progressAnim.addUpdateListener(a -> {
                    animatedProgress = (float) a.getAnimatedValue();
                    invalidate();
                });
                progressAnim.start();
            } else {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float outerR = Math.min(cx, cy) - (6f * density);
            float innerR = outerR - (14f * density);

            int totalTicks = 60;
            int litTicks = Math.round((animatedProgress / 100f) * totalTicks);

            for (int i = 0; i < totalTicks; i++) {
                double angleRad = Math.toRadians((i * (360.0 / totalTicks)) - 90.0);
                float cos = (float) Math.cos(angleRad);
                float sin = (float) Math.sin(angleRad);

                if (i < litTicks) {
                    tickPaint.setColor(greenColor);
                } else if (i == litTicks && litTicks > 0 && litTicks < totalTicks) {
                    tickPaint.setColor(blueColor);
                } else {
                    tickPaint.setColor(idleColor);
                }

                canvas.drawLine(
                        cx + innerR * cos,
                        cy + innerR * sin,
                        cx + outerR * cos,
                        cy + outerR * sin,
                        tickPaint
                );
            }

            if (currentStage >= 1 && currentStage <= 4) {
                float arcR = outerR + (4f * density);
                RectF oval = new RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
                float startAngle = -90f;
                switch (currentStage) {
                    case 1:
                        startAngle = -25f;
                        break;
                    case 2:
                        startAngle = 155f;
                        break;
                    case 3:
                        startAngle = -115f;
                        break;
                    case 4:
                        startAngle = 65f;
                        break;
                }
                canvas.drawArc(oval, startAngle, 50f, false, guideArcPaint);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHandler != null && mHandlerThread != null) {
            mHandler.post(() -> mHandlerThread.quit());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsActivityPaused = true;
        if (mCameraEnrollService != null) {
            mCameraEnrollService.setSurfaceHolder(null);
            mCameraEnrollService.stop(mCameraCallback);
            mCameraEnrollService = null;
        }
        mEnrollmentCancel.cancel();
        try {
            unbindService(faceAuthConn);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsActivityPaused = false;
        synchronized (this) {
            if (mHasCameraPermission) {
                init();
            }
        }
    }

    private void showPermissionRequiredDialog() {
        new AlertDialog.Builder(this, R.style.AppTheme_AlertDialog)
                .setTitle(R.string.perm_required_alert_title)
                .setMessage(R.string.perm_required_alert_msg)
                .setPositiveButton(R.string.perm_required_alert_button_app_info,
                        (dialogInterface, i) -> Util.jumpToAppInfo(this, 2)).setOnCancelListener(dialogInterface -> finish()).show();
    }

    private void startFinishActivity() {
        Intent intent = new Intent();
        intent.setClass(this, FaceFinish.class);
        intent.putExtra(SetupFaceIntroActivity.EXTRA_ENROLL_SUCCESS, true);
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i == 1) {
            setResult(i2);
            finish();
        } else if (i == 2 && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 0) {
            synchronized (this) {
                mHasCameraPermission = true;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        if (i == 0 && SetupFaceIntroActivity.hasAllPermissionsGranted(iArr)) {
            synchronized (this) {
                mHasCameraPermission = true;
            }
        } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            showPermissionRequiredDialog();
        } else {
            finish();
        }
    }
}

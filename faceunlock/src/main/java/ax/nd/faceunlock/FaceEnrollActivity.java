package ax.nd.faceunlock;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private TextView mSubHintText;
    private FaceIdTicksView mTicksView;
    private final View[] mStageDots = new View[5];
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
                    }, 900);
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
        int secondaryText = Color.parseColor("#8E8E93");
        int appleBlue = mIsDarkTheme ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(32));

        // شريط علوي بزر إلغاء نظيف مثل آبل
        FrameLayout topBar = new FrameLayout(this);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("إلغاء");
        cancelBtn.setTextSize(17f);
        cancelBtn.setTextColor(appleBlue);
        cancelBtn.setPadding(dp(4), dp(8), dp(4), dp(8));
        cancelBtn.setOnClickListener(v -> {
            setResult(0);
            finish();
        });
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END);
        topBar.addView(cancelBtn, cancelParams);
        root.addView(topBar);

        // عنوان الإعداد
        TextView titleView = new TextView(this);
        titleView.setText("إعداد التعرّف على الوجه");
        titleView.setTextSize(26f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleView.setTextColor(primaryText);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, dp(16), 0, dp(8));
        root.addView(titleView);

        mSubHintText = new TextView(this);
        mSubHintText.setText("حرّك رأسك برفق في دائرة لإتمام المسح ثلاثي الأبعاد");
        mSubHintText.setTextSize(14f);
        mSubHintText.setTextColor(secondaryText);
        mSubHintText.setGravity(Gravity.CENTER);
        mSubHintText.setPadding(dp(12), 0, dp(12), dp(28));
        root.addView(mSubHintText);

        // إطار الكاميرا الدائري + حلقة مؤشرات Apple Face ID
        int ringSize = dp(270);
        int camSize = dp(224);

        FrameLayout scannerContainer = new FrameLayout(this);
        scannerContainer.setLayoutParams(new LinearLayout.LayoutParams(ringSize, ringSize));

        mSurface = new CircleSurfaceView(this);
        mSurface.setPercent(0f);
        FrameLayout.LayoutParams camParams = new FrameLayout.LayoutParams(camSize, camSize, Gravity.CENTER);
        scannerContainer.addView(mSurface, camParams);

        mTicksView = new FaceIdTicksView(this, mIsDarkTheme);
        FrameLayout.LayoutParams ticksParams = new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER);
        scannerContainer.addView(mTicksView, ticksParams);

        root.addView(scannerContainer);

        // نص التوجيه الحي
        mFaceEnrollMsg = new TextView(this);
        mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        mFaceEnrollMsg.setTextSize(18f);
        mFaceEnrollMsg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mFaceEnrollMsg.setTextColor(primaryText);
        mFaceEnrollMsg.setGravity(Gravity.CENTER);
        mFaceEnrollMsg.setPadding(dp(12), dp(28), dp(12), dp(16));
        root.addView(mFaceEnrollMsg);

        // مؤشر المراحل الـ 5 (نقاط نظيفة)
        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 5; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams dpParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dpParams.setMargins(dp(5), 0, dp(5), 0);
            dot.setLayoutParams(dpParams);
            mStageDots[i] = dot;
            dotsRow.addView(dot);
        }
        root.addView(dotsRow);

        setContentView(root);
        syncProgressUi();

        mSurface.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    syncProgressUi();
                    mSurface.postDelayed(this, 150);
                }
            }
        }, 150);

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
            mTicksView.setProgress(pct);
        }
        if (mFaceEnrollMsg != null) {
            mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        }

        int activeColor = mIsDarkTheme ? Color.parseColor("#30D158") : Color.parseColor("#34C759");
        int currentColor = mIsDarkTheme ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");
        int idleColor = mIsDarkTheme ? Color.parseColor("#2C2C2E") : Color.parseColor("#D1D1D6");

        for (int i = 0; i < 5; i++) {
            if (mStageDots[i] == null) continue;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            if (i < stage) {
                d.setColor(activeColor);
            } else if (i == stage) {
                d.setColor(currentColor);
            } else {
                d.setColor(idleColor);
            }
            mStageDots[i].setBackground(d);
        }
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }

    /**
     * حلقة مؤشرات دائرية مستوحاة من Apple Face ID (60 مؤشراً شعاعياً)
     */
    private static class FaceIdTicksView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int activeColor;
        private final int inactiveColor;
        private float progress = 0f;

        FaceIdTicksView(Context ctx, boolean dark) {
            super(ctx);
            activeColor = dark ? Color.parseColor("#30D158") : Color.parseColor("#34C759");
            inactiveColor = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#D1D1D6");
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(3.2f * ctx.getResources().getDisplayMetrics().density);
        }

        void setProgress(float pct) {
            if (Math.abs(this.progress - pct) > 0.1f) {
                this.progress = pct;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float outerR = Math.min(cx, cy) - paint.getStrokeWidth();
            float innerR = outerR - (14f * getResources().getDisplayMetrics().density);

            int totalTicks = 60;
            int litTicks = Math.round((progress / 100f) * totalTicks);

            for (int i = 0; i < totalTicks; i++) {
                double angleRad = Math.toRadians((i * (360.0 / totalTicks)) - 90.0);
                float cos = (float) Math.cos(angleRad);
                float sin = (float) Math.sin(angleRad);

                paint.setColor(i < litTicks ? activeColor : inactiveColor);
                canvas.drawLine(
                        cx + innerR * cos,
                        cy + innerR * sin,
                        cx + outerR * cos,
                        cy + outerR * sin,
                        paint
                );
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
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

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
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
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
    private CameraEdgeArrowGuideView mCameraArrowGuide;
    private FrameLayout mRootFrame;
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
        SmartFaceEngine.currentEnrollHint = "انظر نحو الكاميرا وحرّك رأسك ببطء";

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

    private int getDisplayRotation() {
        try {
            if (getDisplay() != null) {
                return getDisplay().getRotation();
            }
        } catch (Throwable ignored) {
        }
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private void positionCameraArrowGuide() {
        if (mCameraArrowGuide == null) return;
        int rot = getDisplayRotation();
        mCameraArrowGuide.setDisplayRotation(rot);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        // في Galaxy Tab A7 (SM-T505N): الكاميرا في منتصف الحافة اليمنى في الوضع الطولي (ROTATION_0)
        switch (rot) {
            case Surface.ROTATION_90:
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(8);
                break;
            case Surface.ROTATION_180:
                lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                lp.leftMargin = dp(8);
                break;
            case Surface.ROTATION_270:
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(8);
                break;
            default:
                lp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                lp.rightMargin = dp(8);
                break;
        }
        mCameraArrowGuide.setLayoutParams(lp);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        positionCameraArrowGuide();
    }

    private void init() {
        int bgColor = mIsDarkTheme ? Color.parseColor("#000000") : Color.parseColor("#FFFFFF");
        int primaryText = mIsDarkTheme ? Color.parseColor("#FFFFFF") : Color.parseColor("#000000");
        int appleBlue = mIsDarkTheme ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        mRootFrame = new FrameLayout(this);
        mRootFrame.setBackgroundColor(bgColor);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        root.setPadding(dp(24), dp(18), dp(24), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // الشريط العلوي
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

        // المحتوى الأوسط الكبير والواضح
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
        titleView.setPadding(0, 0, 0, dp(4));
        centerCol.addView(titleView);

        mStepCounterText = new TextView(this);
        mStepCounterText.setText("الزاوية 1 من 5");
        mStepCounterText.setTextSize(14f);
        mStepCounterText.setTextColor(appleBlue);
        mStepCounterText.setGravity(Gravity.CENTER);
        mStepCounterText.setPadding(0, 0, 0, dp(22));
        centerCol.addView(mStepCounterText);

        // إطار الكاميرا الدائري الكبير (270dp) بدون أي زوايا مربعة أو إطار أسود سميك
        int ringSize = dp(316);
        int camSize = dp(270);
        int maskSize = dp(274);

        FrameLayout scannerContainer = new FrameLayout(this);
        scannerContainer.setLayoutParams(new LinearLayout.LayoutParams(ringSize, ringSize));

        mSurface = new CircleSurfaceView(this);
        mSurface.setPercent(0f);
        mSurface.setClipToOutline(true);
        mSurface.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        scannerContainer.addView(mSurface, new FrameLayout.LayoutParams(camSize, camSize, Gravity.CENTER));

        // قناع صلب يغطي أي زوايا خارج الدائرة بلون الخلفية مع حافة زجاجية رفيعة جداً
        CircularMaskOverlayView maskOverlay = new CircularMaskOverlayView(this, bgColor, appleBlue, camSize);
        scannerContainer.addView(maskOverlay, new FrameLayout.LayoutParams(maskSize, maskSize, Gravity.CENTER));

        mTicksView = new AppleFaceIdRingView(this, mIsDarkTheme);
        scannerContainer.addView(mTicksView, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));

        centerCol.addView(scannerContainer);

        // نص التوجيه الحي
        mFaceEnrollMsg = new TextView(this);
        mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        mFaceEnrollMsg.setTextSize(18f);
        mFaceEnrollMsg.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mFaceEnrollMsg.setTextColor(primaryText);
        mFaceEnrollMsg.setGravity(Gravity.CENTER);
        mFaceEnrollMsg.setPadding(dp(12), dp(24), dp(12), dp(16));
        centerCol.addView(mFaceEnrollMsg);

        // مؤشر المراحل الـ 5
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
        mRootFrame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // مؤشر الأسهم الصغيرة بجانب مكان الكاميرا الفعلي في الجهاز (يتكيف مع دوران الشاشة)
        mCameraArrowGuide = new CameraEdgeArrowGuideView(this, mIsDarkTheme);
        mRootFrame.addView(mCameraArrowGuide);
        positionCameraArrowGuide();

        setContentView(mRootFrame);
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
        if (mCameraArrowGuide != null) {
            mCameraArrowGuide.setEnrollStage(stage);
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

    /**
     * قناع دائري صلب بتقنية EVEN_ODD يخفي تماماً أي زوايا مربعة للـ SurfaceView دون تضييق الكاميرا
     */
    private static class CircularMaskOverlayView extends View {
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final float circleDiameter;

        CircularMaskOverlayView(Context ctx, int bgColor, int accentColor, float circleDiameter) {
            super(ctx);
            this.circleDiameter = circleDiameter;
            maskPaint.setColor(bgColor);
            maskPaint.setStyle(Paint.Style.FILL);
            clipPath.setFillType(Path.FillType.EVEN_ODD);

            rimPaint.setColor(Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)));
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(1.5f * ctx.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float r = circleDiameter * 0.5f;

            clipPath.reset();
            clipPath.addRect(0f, 0f, w, h, Path.Direction.CW);
            clipPath.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.drawPath(clipPath, maskPaint);

            canvas.drawCircle(cx, cy, r, rimPaint);
        }
    }

    /**
     * مؤشر أسهم صغير يظهر مباشرة بجانب الكاميرا الأمامية في التابلت
     * ليوجّه نظرك وحركة وجهك أثناء التسجيل في جميع أوضاع دوران الشاشة
     */
    private static class CameraEdgeArrowGuideView extends View {
        private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int blueColor;
        private final int greenColor;
        private int stage = 0;
        private int displayRotation = Surface.ROTATION_0;
        private float pulsePhase = 0f;
        private ValueAnimator pulseAnim;

        CameraEdgeArrowGuideView(Context ctx, boolean dark) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            blueColor = dark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");
            greenColor = dark ? Color.parseColor("#30D158") : Color.parseColor("#34C759");

            pillBgPaint.setColor(dark ? Color.parseColor("#E614161B") : Color.parseColor("#F0F2F2F7"));
            pillBgPaint.setStyle(Paint.Style.FILL);

            pillStrokePaint.setColor(dark ? Color.parseColor("#330A84FF") : Color.parseColor("#33007AFF"));
            pillStrokePaint.setStyle(Paint.Style.STROKE);
            pillStrokePaint.setStrokeWidth(1.2f * d);

            arrowPaint.setColor(blueColor);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setStrokeWidth(2.4f * d);

            dotPaint.setColor(blueColor);
            dotPaint.setStyle(Paint.Style.FILL);

            pulseAnim = ValueAnimator.ofFloat(0f, 1f);
            pulseAnim.setDuration(900L);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.setInterpolator(new LinearInterpolator());
            pulseAnim.addUpdateListener(a -> {
                pulsePhase = (float) a.getAnimatedValue();
                invalidate();
            });
            pulseAnim.start();
        }

        void setEnrollStage(int stage) {
            if (this.stage != stage) {
                this.stage = stage;
                invalidate();
            }
        }

        void setDisplayRotation(int rotation) {
            if (this.displayRotation != rotation) {
                this.displayRotation = rotation;
                requestLayout();
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float d = getResources().getDisplayMetrics().density;
            boolean horizontalPill = (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270);
            int w = (int) ((horizontalPill ? 92f : 42f) * d);
            int h = (int) ((horizontalPill ? 42f : 92f) * d);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) * 0.5f;

            RectF box = new RectF(1f * d, 1f * d, w - 1f * d, h - 1f * d);
            canvas.drawRoundRect(box, r, r, pillBgPaint);
            canvas.drawRoundRect(box, r, r, pillStrokePaint);

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float shift = (pulsePhase - 0.5f) * 5f * d;

            if (stage >= 5) {
                arrowPaint.setColor(greenColor);
                Path check = new Path();
                check.moveTo(cx - 7f * d, cy);
                check.lineTo(cx - 2f * d, cy + 5f * d);
                check.lineTo(cx + 8f * d, cy - 6f * d);
                canvas.drawPath(check, arrowPaint);
                return;
            }

            arrowPaint.setColor(blueColor);
            dotPaint.setColor(blueColor);

            switch (stage) {
                case 0:
                    // نقطة تركيز نابضة تشير للنظر مباشرة للكاميرا
                    dotPaint.setAlpha((int) (140 + 115 * pulsePhase));
                    canvas.drawCircle(cx, cy, (4.5f + 2.0f * pulsePhase) * d, dotPaint);
                    canvas.drawCircle(cx, cy, 10f * d, pillStrokePaint);
                    break;
                case 1:
                    // أسهم تشير لليمين
                    drawChevron(canvas, cx - 3f * d + shift, cy, 6f * d, 0f);
                    drawChevron(canvas, cx + 6f * d + shift, cy, 6f * d, 0f);
                    break;
                case 2:
                    // أسهم تشير لليسار
                    drawChevron(canvas, cx + 3f * d - shift, cy, 6f * d, 180f);
                    drawChevron(canvas, cx - 6f * d - shift, cy, 6f * d, 180f);
                    break;
                case 3:
                    // أسهم تشير للأعلى
                    drawChevron(canvas, cx, cy + 3f * d - shift, 6f * d, -90f);
                    drawChevron(canvas, cx, cy - 6f * d - shift, 6f * d, -90f);
                    break;
                case 4:
                    // أسهم تشير للأسفل
                    drawChevron(canvas, cx, cy - 3f * d + shift, 6f * d, 90f);
                    drawChevron(canvas, cx, cy + 6f * d + shift, 6f * d, 90f);
                    break;
            }
        }

        private void drawChevron(Canvas canvas, float cx, float cy, float size, float angleDeg) {
            canvas.save();
            canvas.rotate(angleDeg, cx, cy);
            Path p = new Path();
            p.moveTo(cx - size * 0.45f, cy - size * 0.75f);
            p.lineTo(cx + size * 0.45f, cy);
            p.lineTo(cx - size * 0.45f, cy + size * 0.75f);
            canvas.drawPath(p, arrowPaint);
            canvas.restore();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (pulseAnim != null) {
                pulseAnim.cancel();
            }
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

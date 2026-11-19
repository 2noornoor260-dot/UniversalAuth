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
    private TextView mStepBadgeText;
    private FourSegmentAppleArcView mFourArcsView;
    private CameraEdgeArrowGuideView mCameraArrowGuide;
    private FrameLayout mRootFrame;
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
                    SmartFaceEngine.currentEnrollStage = 4;
                }
                syncProgressUi();
                if (i == 0) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isDestroyed() && !isFinishing()) {
                            startFinishActivity();
                        }
                    }, 650);
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
        SmartFaceEngine.currentEnrollHint = "حرّك رأسك ببطء نحو اليمين";

        int bg = mIsDarkTheme ? Color.parseColor("#000000") : Color.parseColor("#F5F5F7");
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

    /**
     * معاينة كاميرا دائرية كبيرة وواضحة بحجم كامل محاطة بـ 4 أقواس سميكة فقط (يمين، يسار، فوق، تحت)
     */
    private void init() {
        int pageBg = mIsDarkTheme ? Color.parseColor("#000000") : Color.parseColor("#F5F5F7");
        int primaryText = mIsDarkTheme ? Color.parseColor("#FFFFFF") : Color.parseColor("#1D1D1F");
        int pillBg = mIsDarkTheme ? Color.parseColor("#1C1C1E") : Color.parseColor("#E5E5EA");
        int appleBlue = mIsDarkTheme ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");

        mRootFrame = new FrameLayout(this);
        mRootFrame.setBackgroundColor(pageBg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        View topSpacer = new View(this);
        topSpacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 0.75f));
        root.addView(topSpacer);

        // حاوية الكاميرا الدائرية الكبيرة (276dp) + الأقواس الأربعة السميكة (306dp)
        int outerRingSize = dp(306);
        int camCircleSize = dp(274);

        FrameLayout cameraRingContainer = new FrameLayout(this);
        cameraRingContainer.setLayoutParams(new LinearLayout.LayoutParams(outerRingSize, outerRingSize));

        mSurface = new CircleSurfaceView(this);
        mSurface.setPercent(0f);
        mSurface.setClipToOutline(true);
        mSurface.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        cameraRingContainer.addView(mSurface, new FrameLayout.LayoutParams(camCircleSize, camCircleSize, Gravity.CENTER));

        // قناع خارجي بلون الخلفية لضمان حواف دائرية مثالية دون تصغير الكاميرا
        CircularOuterMaskView maskView = new CircularOuterMaskView(this, pageBg, camCircleSize);
        cameraRingContainer.addView(maskView, new FrameLayout.LayoutParams(outerRingSize, outerRingSize, Gravity.CENTER));

        // الأقواس الأربعة السميكة (يمين، يسار، فوق، تحت)
        mFourArcsView = new FourSegmentAppleArcView(this, mIsDarkTheme);
        cameraRingContainer.addView(mFourArcsView, new FrameLayout.LayoutParams(outerRingSize, outerRingSize, Gravity.CENTER));

        root.addView(cameraRingContainer);

        mFaceEnrollMsg = new TextView(this);
        mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        mFaceEnrollMsg.setTextSize(18f);
        mFaceEnrollMsg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mFaceEnrollMsg.setTextColor(primaryText);
        mFaceEnrollMsg.setGravity(Gravity.CENTER);
        mFaceEnrollMsg.setPadding(dp(16), dp(26), dp(16), dp(6));
        root.addView(mFaceEnrollMsg);

        mStepBadgeText = new TextView(this);
        mStepBadgeText.setText("الزاوية 1 من 4");
        mStepBadgeText.setTextSize(13.5f);
        mStepBadgeText.setTextColor(appleBlue);
        mStepBadgeText.setGravity(Gravity.CENTER);
        root.addView(mStepBadgeText);

        View bottomSpacer = new View(this);
        bottomSpacer.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1.0f));
        root.addView(bottomSpacer);

        TextView cancelPillBtn = new TextView(this);
        cancelPillBtn.setText("إلغاء الإعداد");
        cancelPillBtn.setTextSize(15.5f);
        cancelPillBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancelPillBtn.setTextColor(primaryText);
        cancelPillBtn.setGravity(Gravity.CENTER);
        cancelPillBtn.setPadding(dp(36), dp(14), dp(36), dp(14));
        GradientDrawable pillDrawable = new GradientDrawable();
        pillDrawable.setColor(pillBg);
        pillDrawable.setCornerRadius(dp(26));
        cancelPillBtn.setBackground(pillDrawable);
        cancelPillBtn.setOnClickListener(v -> {
            setResult(0);
            finish();
        });
        root.addView(cancelPillBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRootFrame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
                    mSurface.postDelayed(this, 100);
                }
            }
        }, 100);

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
        if (mFourArcsView != null) {
            mFourArcsView.setStage(stage);
        }
        if (mCameraArrowGuide != null) {
            mCameraArrowGuide.setEnrollStage(stage);
        }
        if (mFaceEnrollMsg != null) {
            mFaceEnrollMsg.setText(SmartFaceEngine.currentEnrollHint);
        }
        if (mStepBadgeText != null) {
            int step = Math.min(stage + 1, 4);
            mStepBadgeText.setText(stage >= 4 ? "اكتملت الزوايا الأربع" : ("الزاوية " + step + " من 4"));
        }
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density);
    }

    private static class CircularOuterMaskView extends View {
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final float circleDiameter;

        CircularOuterMaskView(Context ctx, int bgColor, float circleDiameter) {
            super(ctx);
            this.circleDiameter = circleDiameter;
            maskPaint.setColor(bgColor);
            maskPaint.setStyle(Paint.Style.FILL);
            clipPath.setFillType(Path.FillType.EVEN_ODD);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            clipPath.reset();
            clipPath.addRect(0f, 0f, w, h, Path.Direction.CW);
            clipPath.addCircle(w * 0.5f, h * 0.5f, circleDiameter * 0.5f, Path.Direction.CW);
            canvas.drawPath(clipPath, maskPaint);
        }
    }

    private static class FourSegmentAppleArcView extends View {
        private final Paint inactiveArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint doneArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int currentStage = 0;
        private float pulse = 0f;
        private ValueAnimator pulseAnim;

        FourSegmentAppleArcView(Context ctx, boolean dark) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            float strokeW = 7.0f * d;

            inactiveArcPaint.setColor(dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#D1D1D6"));
            inactiveArcPaint.setStyle(Paint.Style.STROKE);
            inactiveArcPaint.setStrokeCap(Paint.Cap.ROUND);
            inactiveArcPaint.setStrokeWidth(strokeW);

            activeArcPaint.setColor(dark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF"));
            activeArcPaint.setStyle(Paint.Style.STROKE);
            activeArcPaint.setStrokeCap(Paint.Cap.ROUND);
            activeArcPaint.setStrokeWidth(strokeW + 1.0f * d);

            doneArcPaint.setColor(dark ? Color.parseColor("#30D158") : Color.parseColor("#34C759"));
            doneArcPaint.setStyle(Paint.Style.STROKE);
            doneArcPaint.setStrokeCap(Paint.Cap.ROUND);
            doneArcPaint.setStrokeWidth(strokeW);

            pulseAnim = ValueAnimator.ofFloat(0f, 1f);
            pulseAnim.setDuration(900L);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.setInterpolator(new LinearInterpolator());
            pulseAnim.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                invalidate();
            });
            pulseAnim.start();
        }

        void setStage(int stage) {
            if (this.currentStage != stage) {
                this.currentStage = stage;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float pad = 6f * d;
            RectF oval = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);

            float[] startAngles = new float[]{-36f, 144f, 234f, 54f};
            float sweep = 72f;

            for (int i = 0; i < 4; i++) {
                Paint p;
                if (i < currentStage) {
                    p = doneArcPaint;
                } else if (i == currentStage) {
                    activeArcPaint.setAlpha((int) (185 + 70 * pulse));
                    p = activeArcPaint;
                } else {
                    p = inactiveArcPaint;
                }
                canvas.drawArc(oval, startAngles[i], sweep, false, p);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (pulseAnim != null) {
                pulseAnim.cancel();
            }
        }
    }

    private static class CameraEdgeArrowGuideView extends View {
        private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

            pillBgPaint.setColor(dark ? Color.parseColor("#E614161B") : Color.parseColor("#F0E5E5EA"));
            pillBgPaint.setStyle(Paint.Style.FILL);

            pillStrokePaint.setColor(dark ? Color.parseColor("#330A84FF") : Color.parseColor("#33007AFF"));
            pillStrokePaint.setStyle(Paint.Style.STROKE);
            pillStrokePaint.setStrokeWidth(1.2f * d);

            arrowPaint.setColor(blueColor);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setStrokeWidth(2.4f * d);

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

            if (stage >= 4) {
                arrowPaint.setColor(greenColor);
                Path check = new Path();
                check.moveTo(cx - 7f * d, cy);
                check.lineTo(cx - 2f * d, cy + 5f * d);
                check.lineTo(cx + 8f * d, cy - 6f * d);
                canvas.drawPath(check, arrowPaint);
                return;
            }

            arrowPaint.setColor(blueColor);
            switch (stage) {
                case 0:
                    drawChevron(canvas, cx - 3f * d + shift, cy, 6f * d, 0f);
                    drawChevron(canvas, cx + 6f * d + shift, cy, 6f * d, 0f);
                    break;
                case 1:
                    drawChevron(canvas, cx + 3f * d - shift, cy, 6f * d, 180f);
                    drawChevron(canvas, cx - 6f * d - shift, cy, 6f * d, 180f);
                    break;
                case 2:
                    drawChevron(canvas, cx, cy + 3f * d - shift, 6f * d, -90f);
                    drawChevron(canvas, cx, cy - 6f * d - shift, 6f * d, -90f);
                    break;
                case 3:
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

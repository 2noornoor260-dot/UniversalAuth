package ax.nd.universalauth.xposed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import java.lang.reflect.Field;

public class FaceIdOverlay extends View {

    private static final int STATE_SCANNING = 0;
    private static final int STATE_SUCCESS = 1;
    private static final int STATE_FAILURE = 2;

    private static final long WAKE_APPEAR_DELAY_MS = 120L;
    private static final long SCAN_TIMEOUT_MS = 5200L;
    private static final float DARK_LUX_THRESHOLD = 8.0f; // يعمل فقط في الظلام الحقيقي

    private static FaceIdOverlay instance;
    private static View fillLightView;
    private static SensorManager lightSensorManager;
    private static SensorEventListener lightSensorListener;
    private static boolean fillLightTriggered = false;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final Runnable timeoutRunnable = () -> {
        if (instance != null && instance.state == STATE_SCANNING) {
            instance.triggerFailureAnimation();
        }
    };

    private static Runnable pendingShowRunnable;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final RectF mouthRect = new RectF();
    private final Path nosePath = new Path();
    private final Path checkPath = new Path();
    private final Path partialCheckPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();

    private WindowManager.LayoutParams layoutParams;
    private int edgeGravity = Gravity.TOP;
    private int state = STATE_SCANNING;

    private float scanAngle = 0f;
    private float morphProgress = 0f;
    private float checkProgress = 0f;

    private ValueAnimator scanAnimator;
    private ValueAnimator stateAnimator;
    private DisplayManager.DisplayListener displayListener;

    public FaceIdOverlay(Context context) {
        super(context);
        bgPaint.setColor(Color.parseColor("#F50C0C0F"));
        bgPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.parseColor("#2EFFFFFF"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(context, 1.1f));

        glyphPaint.setColor(Color.parseColor("#F2F2F7"));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public static void showScanning(Context context) {
        if (context == null) return;
        mainHandler.post(() -> {
            if (pendingShowRunnable != null) {
                mainHandler.removeCallbacks(pendingShowRunnable);
            }
            pendingShowRunnable = () -> showScanningInternal(context);
            mainHandler.postDelayed(pendingShowRunnable, WAKE_APPEAR_DELAY_MS);
        });
    }

    private static void showScanningInternal(Context context) {
        try {
            if (instance != null && instance.isAttachedToWindow()) {
                if (instance.state == STATE_SCANNING) return;
                removeInternal(true);
            }

            Context appCtx = context.getApplicationContext();
            WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            startAmbientLightCheck(appCtx);

            FaceIdOverlay view = new FaceIdOverlay(appCtx);
            int sizePx = dp(appCtx, 68);

            int[] windowTypes = new int[]{
                    2024, // TYPE_NAVIGATION_BAR_PANEL
                    2015, // TYPE_SECURE_SYSTEM_OVERLAY
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            };

            boolean added = false;
            for (int type : windowTypes) {
                try {
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            sizePx,
                            sizePx,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                            PixelFormat.TRANSLUCENT
                    );
                    applyTrustedOverlayFlag(params);

                    view.layoutParams = params;
                    view.updateCameraPosition(appCtx, false);

                    wm.addView(view, params);
                    instance = view;
                    added = true;
                    break;
                } catch (Throwable ignored) {
                }
            }

            if (!added) return;

            view.registerRotationTracker(appCtx);
            view.playEmergenceAnimation();
            view.startScanningLoop();

            mainHandler.removeCallbacks(timeoutRunnable);
            mainHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
        } catch (Throwable ignored) {
        }
    }

    public static void triggerRetryPulse() {
        mainHandler.post(() -> {
            if (instance != null && instance.state == STATE_SCANNING && instance.isAttachedToWindow()) {
                instance.animate()
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .setDuration(100)
                        .withEndAction(() -> {
                            if (instance != null && instance.isAttachedToWindow()) {
                                instance.animate()
                                        .scaleX(1.05f)
                                        .scaleY(1.05f)
                                        .setDuration(130)
                                        .withEndAction(() -> {
                                            if (instance != null && instance.isAttachedToWindow()) {
                                                instance.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                                            }
                                        })
                                        .start();
                            }
                        })
                        .start();
            }
        });
    }

    private static void startAmbientLightCheck(Context appCtx) {
        stopAmbientLightCheck();
        fillLightTriggered = false;
        try {
            lightSensorManager = (SensorManager) appCtx.getSystemService(Context.SENSOR_SERVICE);
            if (lightSensorManager == null) return;
            Sensor lightSensor = lightSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor == null) return;

            lightSensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.values != null && event.values.length > 0) {
                        float lux = event.values[0];
                        if (lux <= DARK_LUX_THRESHOLD && !fillLightTriggered && instance != null && instance.state == STATE_SCANNING) {
                            fillLightTriggered = true;
                            showSmoothFillLight(appCtx);
                        }
                        stopAmbientLightCheck();
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            lightSensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (Throwable ignored) {
        }
    }

    private static void stopAmbientLightCheck() {
        if (lightSensorManager != null && lightSensorListener != null) {
            try {
                lightSensorManager.unregisterListener(lightSensorListener);
            } catch (Throwable ignored) {
            }
        }
        lightSensorListener = null;
    }

    private static void showSmoothFillLight(Context appCtx) {
        mainHandler.post(() -> {
            try {
                if (fillLightView != null || instance == null || instance.state != STATE_SCANNING) return;
                WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                View lightPanel = new View(appCtx);
                lightPanel.setBackgroundColor(Color.parseColor("#FFF8EE"));
                lightPanel.setAlpha(0f);

                int[] windowTypes = new int[]{
                        2024,
                        2015,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                };

                for (int type : windowTypes) {
                    try {
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                type,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                                PixelFormat.TRANSLUCENT
                        );
                        applyTrustedOverlayFlag(params);

                        wm.addView(lightPanel, params);
                        fillLightView = lightPanel;

                        if (instance != null && instance.isAttachedToWindow()) {
                            wm.removeViewImmediate(instance);
                            wm.addView(instance, instance.layoutParams);
                        }

                        lightPanel.animate()
                                .alpha(0.52f)
                                .setDuration(480)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void hideSmoothFillLight(boolean immediate) {
        if (fillLightView == null) return;
        View panel = fillLightView;
        fillLightView = null;

        if (immediate) {
            try {
                WindowManager wm = (WindowManager) panel.getContext().getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && panel.isAttachedToWindow()) {
                    wm.removeView(panel);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        panel.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            WindowManager wm = (WindowManager) panel.getContext().getSystemService(Context.WINDOW_SERVICE);
                            if (wm != null && panel.isAttachedToWindow()) {
                                wm.removeView(panel);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .start();
    }

    private static void applyTrustedOverlayFlag(WindowManager.LayoutParams params) {
        try {
            Field privateFlags = WindowManager.LayoutParams.class.getField("privateFlags");
            privateFlags.setInt(params, privateFlags.getInt(params) | 0x00000010);
        } catch (Throwable ignored) {
        }
    }

    public static void showSuccess(Context context) {
        if (context == null) return;
        mainHandler.post(() -> {
            if (pendingShowRunnable != null) {
                mainHandler.removeCallbacks(pendingShowRunnable);
                pendingShowRunnable = null;
            }
            mainHandler.removeCallbacks(timeoutRunnable);
            stopAmbientLightCheck();
            hideSmoothFillLight(false);

            if (instance == null || !instance.isAttachedToWindow()) {
                showScanningInternal(context);
            }
            if (instance != null) {
                instance.triggerSuccessAnimation();
            }
        });
    }

    public static void showFailure(Context context) {
        if (context == null) return;
        mainHandler.post(() -> {
            if (pendingShowRunnable != null) {
                mainHandler.removeCallbacks(pendingShowRunnable);
                pendingShowRunnable = null;
            }
            mainHandler.removeCallbacks(timeoutRunnable);
            stopAmbientLightCheck();
            hideSmoothFillLight(false);

            if (instance != null && instance.isAttachedToWindow()) {
                instance.triggerFailureAnimation();
            }
        });
    }

    public static void hide() {
        mainHandler.post(() -> {
            if (pendingShowRunnable != null) {
                mainHandler.removeCallbacks(pendingShowRunnable);
                pendingShowRunnable = null;
            }
            mainHandler.removeCallbacks(timeoutRunnable);
            stopAmbientLightCheck();
            hideSmoothFillLight(false);

            if (instance != null && (instance.state == STATE_SUCCESS || instance.state == STATE_FAILURE)) {
                return;
            }
            removeInternal(false);
        });
    }

    private void updateCameraPosition(Context ctx, boolean applyToWm) {
        if (layoutParams == null) return;

        int rotation = Surface.ROTATION_90;
        boolean naturalPortrait = true;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display display = (dm != null) ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display != null) {
                rotation = display.getRotation();
            }
            DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
            boolean isCurrentlyPortrait = metrics.heightPixels >= metrics.widthPixels;
            if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                naturalPortrait = isCurrentlyPortrait;
            } else {
                naturalPortrait = !isCurrentlyPortrait;
            }
        } catch (Throwable ignored) {
        }

        int effectiveRotation = naturalPortrait ? rotation : ((rotation + 1) % 4);
        int marginFromBezel = dp(ctx, 18);

        switch (effectiveRotation) {
            case Surface.ROTATION_0:
                edgeGravity = Gravity.RIGHT;
                layoutParams.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
                layoutParams.x = marginFromBezel;
                layoutParams.y = 0;
                break;

            case Surface.ROTATION_180:
                edgeGravity = Gravity.LEFT;
                layoutParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                layoutParams.x = marginFromBezel;
                layoutParams.y = 0;
                break;

            case Surface.ROTATION_270:
                edgeGravity = Gravity.BOTTOM;
                layoutParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                layoutParams.x = 0;
                layoutParams.y = marginFromBezel;
                break;

            case Surface.ROTATION_90:
            default:
                edgeGravity = Gravity.TOP;
                layoutParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                layoutParams.x = 0;
                layoutParams.y = marginFromBezel;
                break;
        }

        if (applyToWm && isAttachedToWindow()) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.updateViewLayout(this, layoutParams);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void registerRotationTracker(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY && isAttachedToWindow()) {
                        updateCameraPosition(ctx, true);
                    }
                }
            };
            dm.registerDisplayListener(displayListener, mainHandler);
        } catch (Throwable ignored) {
        }
    }

    private void unregisterRotationTracker() {
        if (displayListener != null) {
            try {
                DisplayManager dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    dm.unregisterDisplayListener(displayListener);
                }
            } catch (Throwable ignored) {
            }
            displayListener = null;
        }
    }

    private void playEmergenceAnimation() {
        float offset = dp(getContext(), 28);
        float startTx = 0f;
        float startTy = 0f;

        if (edgeGravity == Gravity.TOP) startTy = -offset;
        else if (edgeGravity == Gravity.BOTTOM) startTy = offset;
        else if (edgeGravity == Gravity.LEFT) startTx = -offset;
        else if (edgeGravity == Gravity.RIGHT) startTx = offset;

        setScaleX(0.22f);
        setScaleY(0.22f);
        setAlpha(0f);
        setTranslationX(startTx);
        setTranslationY(startTy);

        animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.3f))
                .start();
    }

    private void startScanningLoop() {
        scanAnimator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        scanAnimator.setDuration(1900);
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.addUpdateListener(anim -> {
            scanAngle = (float) anim.getAnimatedValue();
            invalidate();
        });
        scanAnimator.start();
    }

    private void triggerSuccessAnimation() {
        if (state == STATE_SUCCESS) return;
        state = STATE_SUCCESS;
        stopAmbientLightCheck();
        hideSmoothFillLight(false);
        if (scanAnimator != null) scanAnimator.cancel();
        if (stateAnimator != null) stateAnimator.cancel();

        vibrateHaptic(false);

        animate()
                .scaleX(1.12f)
                .scaleY(1.12f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> animate().scaleX(1f).scaleY(1f).setDuration(170).start())
                .start();

        stateAnimator = ValueAnimator.ofFloat(0f, 1f);
        stateAnimator.setDuration(360);
        stateAnimator.setInterpolator(new DecelerateInterpolator());
        stateAnimator.addUpdateListener(anim -> {
            float v = (float) anim.getAnimatedValue();
            morphProgress = Math.min(1f, v * 1.5f);
            checkProgress = Math.max(0f, (v - 0.22f) / 0.78f);
            invalidate();
        });
        stateAnimator.start();

        mainHandler.postDelayed(() -> removeInternal(false), 1000);
    }

    private void triggerFailureAnimation() {
        if (state != STATE_SCANNING) return;
        state = STATE_FAILURE;
        stopAmbientLightCheck();
        hideSmoothFillLight(false);
        if (scanAnimator != null) scanAnimator.cancel();
        if (stateAnimator != null) stateAnimator.cancel();

        vibrateHaptic(true);
        invalidate();

        float d = dp(getContext(), 10);
        ObjectAnimator shake = ObjectAnimator.ofFloat(
                this,
                "translationX",
                0f, -d, d, -d * 0.75f, d * 0.75f, -d * 0.35f, d * 0.35f, 0f
        );
        shake.setDuration(400);
        shake.setInterpolator(new DecelerateInterpolator());
        shake.start();

        mainHandler.postDelayed(() -> removeInternal(false), 750);
    }

    private void vibrateHaptic(boolean isError) {
        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int effect = isError ? VibrationEffect.EFFECT_DOUBLE_CLICK : VibrationEffect.EFFECT_CLICK;
                vibrator.vibrate(VibrationEffect.createPredefined(effect));
            } else {
                vibrator.vibrate(isError ? 45 : 22);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void removeInternal(boolean immediate) {
        stopAmbientLightCheck();
        hideSmoothFillLight(immediate);
        if (instance == null) return;
        FaceIdOverlay view = instance;
        instance = null;
        view.unregisterRotationTracker();
        if (view.scanAnimator != null) view.scanAnimator.cancel();
        if (view.stateAnimator != null) view.stateAnimator.cancel();

        if (immediate) {
            try {
                WindowManager wm = (WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && view.isAttachedToWindow()) {
                    wm.removeView(view);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        float offset = dp(view.getContext(), 26);
        float endTx = 0f;
        float endTy = 0f;
        if (view.edgeGravity == Gravity.TOP) endTy = -offset;
        else if (view.edgeGravity == Gravity.BOTTOM) endTy = offset;
        else if (view.edgeGravity == Gravity.LEFT) endTx = -offset;
        else if (view.edgeGravity == Gravity.RIGHT) endTx = offset;

        view.animate()
                .scaleX(0.22f)
                .scaleY(0.22f)
                .alpha(0f)
                .translationX(endTx)
                .translationY(endTy)
                .setDuration(230)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            WindowManager wm = (WindowManager) view.getContext()
                                    .getSystemService(Context.WINDOW_SERVICE);
                            if (wm != null && view.isAttachedToWindow()) {
                                wm.removeView(view);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = dp(getContext(), 3.5f);
        pillRect.set(pad, pad, w - pad, h - pad);

        float cornerRadius = dp(getContext(), 20) + (morphProgress * (w / 2f));
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint);
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint);

        float cx = w / 2f;
        float cy = h / 2f;

        if (state == STATE_SUCCESS) {
            glyphPaint.setColor(Color.parseColor("#34C759"));
            glyphPaint.setAlpha(255);
            glyphPaint.setStrokeWidth(dp(getContext(), 2.8f));
            canvas.drawCircle(cx, cy, dp(getContext(), 17.5f), glyphPaint);

            checkPath.reset();
            checkPath.moveTo(cx - dp(getContext(), 7.5f), cy + dp(getContext(), 0.5f));
            checkPath.lineTo(cx - dp(getContext(), 1.8f), cy + dp(getContext(), 6.2f));
            checkPath.lineTo(cx + dp(getContext(), 8.5f), cy - dp(getContext(), 5.5f));

            pathMeasure.setPath(checkPath, false);
            partialCheckPath.reset();
            pathMeasure.getSegment(0f, pathMeasure.getLength() * checkProgress, partialCheckPath, true);
            canvas.drawPath(partialCheckPath, glyphPaint);
            return;
        }

        boolean isFail = (state == STATE_FAILURE);
        float breath = isFail ? 0f : (float) Math.sin(scanAngle);
        float lookX = isFail ? 0f : (float) Math.sin(scanAngle) * dp(getContext(), 1.6f);
        float lookY = isFail ? 0f : (float) Math.cos(scanAngle * 2.0) * dp(getContext(), 0.9f);

        float boxHalf = dp(getContext(), 14.5f) + (breath * dp(getContext(), 0.8f));
        float bracketLen = dp(getContext(), 6.2f);
        float arcR = dp(getContext(), 3.5f);

        glyphPaint.setColor(isFail ? Color.parseColor("#FF3B30") : Color.parseColor("#F2F2F7"));
        glyphPaint.setAlpha(255);
        glyphPaint.setStrokeWidth(dp(getContext(), 2.3f));

        drawCornerBrackets(canvas, cx, cy, boxHalf, bracketLen, arcR);

        float fcx = cx + lookX;
        float fcy = cy + lookY;
        float eyeOffsetX = dp(getContext(), 5.2f);
        float eyeOffsetY = dp(getContext(), 3.2f);

        float blinkFactor = (!isFail && Math.sin(scanAngle) > 0.94f) ? 0.25f : 1f;
        float eyeHalfH = dp(getContext(), 2.1f) * blinkFactor;

        glyphPaint.setStrokeWidth(dp(getContext(), 2.2f));
        canvas.drawLine(fcx - eyeOffsetX, fcy - eyeOffsetY - eyeHalfH,
                fcx - eyeOffsetX, fcy - eyeOffsetY + eyeHalfH, glyphPaint);
        canvas.drawLine(fcx + eyeOffsetX, fcy - eyeOffsetY - eyeHalfH,
                fcx + eyeOffsetX, fcy - eyeOffsetY + eyeHalfH, glyphPaint);

        nosePath.reset();
        nosePath.moveTo(fcx + dp(getContext(), 0.3f), fcy - dp(getContext(), 2.2f));
        nosePath.lineTo(fcx + dp(getContext(), 0.3f), fcy + dp(getContext(), 1.6f));
        nosePath.lineTo(fcx - dp(getContext(), 1.3f), fcy + dp(getContext(), 1.6f));
        glyphPaint.setStrokeWidth(dp(getContext(), 1.9f));
        canvas.drawPath(nosePath, glyphPaint);

        glyphPaint.setStrokeWidth(dp(getContext(), 2.1f));
        if (!isFail) {
            mouthRect.set(
                    fcx - dp(getContext(), 5.2f),
                    fcy + dp(getContext(), 1.2f),
                    fcx + dp(getContext(), 5.2f),
                    fcy + dp(getContext(), 7.2f)
            );
            canvas.drawArc(mouthRect, 25f, 130f, false, glyphPaint);
        } else {
            mouthRect.set(
                    fcx - dp(getContext(), 4.8f),
                    fcy + dp(getContext(), 4.5f),
                    fcx + dp(getContext(), 4.8f),
                    fcy + dp(getContext(), 9.5f)
            );
            canvas.drawArc(mouthRect, 205f, 130f, false, glyphPaint);
        }
    }

    private void drawCornerBrackets(Canvas canvas, float cx, float cy, float half, float len, float r) {
        float l = cx - half;
        float t = cy - half;
        float ri = cx + half;
        float b = cy + half;

        Path p = new Path();
        p.moveTo(l, t + len);
        p.lineTo(l, t + r);
        p.quadTo(l, t, l + r, t);
        p.lineTo(l + len, t);
        canvas.drawPath(p, glyphPaint);

        p.reset();
        p.moveTo(ri - len, t);
        p.lineTo(ri - r, t);
        p.quadTo(ri, t, ri, t + r);
        p.lineTo(ri, t + len);
        canvas.drawPath(p, glyphPaint);

        p.reset();
        p.moveTo(l, b - len);
        p.lineTo(l, b - r);
        p.quadTo(l, b, l + r, b);
        p.lineTo(l + len, b);
        canvas.drawPath(p, glyphPaint);

        p.reset();
        p.moveTo(ri - len, b);
        p.lineTo(ri - r, b);
        p.quadTo(ri, b, ri, b - r);
        p.lineTo(ri, b - len);
        canvas.drawPath(p, glyphPaint);
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
                    }

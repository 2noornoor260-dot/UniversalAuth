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
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class FaceIdOverlay extends View {

    private static final int STATE_SCANNING = 0;
    private static final int STATE_SUCCESS = 1;
    private static final int STATE_FAILURE = 2;

    private static final long SCAN_TIMEOUT_MS = 6200L;

    private static FaceIdOverlay instance;
    private static View fillLightView;
    private static ValueAnimator fillLightAnimator;
    private static boolean hardwareBrightnessBoosted = false;
    private static float savedBrightnessBeforeBoost = 0.4f;
    private static long lastPinToastTime = 0L;

    private static Handler mainHandler;

    private static Handler getMainHandler() {
        if (mainHandler == null) {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                mainHandler = new Handler(looper);
            }
        }
        return mainHandler;
    }

    private static final Runnable timeoutRunnable = () -> {
        if (instance != null && instance.state == STATE_SCANNING) {
            instance.triggerFailureAnimation();
        }
    };

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
    private boolean isRetrying = false;

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

        borderPaint.setColor(Color.parseColor("#33FFFFFF"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(context, 1.2f));

        glyphPaint.setColor(Color.parseColor("#F2F2F7"));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public static void showScanning(Context context) {
        if (context == null) return;
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> showScanningInternal(context));
    }

    public static void enableFillLight(Context context) {
        if (context == null) return;
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> {
            if (instance != null && instance.state == STATE_SCANNING && instance.isAttachedToWindow()) {
                showSmoothFillLight(context.getApplicationContext());
            }
        });
    }

    public static void showPinRequired(Context context) {
        if (context == null) return;
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> {
            long now = System.currentTimeMillis();
            if (now - lastPinToastTime > 4000L) {
                lastPinToastTime = now;
                try {
                    Toast.makeText(context.getApplicationContext(),
                            "مطلوب رمز PIN بعد 45 دقيقة من عدم الاستخدام",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
            if (instance == null || !instance.isAttachedToWindow()) {
                showScanningInternal(context);
            }
            if (instance != null) {
                h.removeCallbacks(timeoutRunnable);
                instance.triggerFailureAnimation();
            }
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

            FaceIdOverlay view = new FaceIdOverlay(appCtx);
            int sizePx = dp(appCtx, 68);

            // استخدام أعلى طبقة للكبسولة (2015 TYPE_SECURE_SYSTEM_OVERLAY) لتبقى دائماً فوق طبقة الإضاءة البيضاء
            int[] windowTypes = new int[]{
                    2015, // TYPE_SECURE_SYSTEM_OVERLAY (Layer 29)
                    2024, // TYPE_NAVIGATION_BAR_PANEL (Layer 24)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY // (Layer 21)
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

            Handler h = getMainHandler();
            if (h != null) {
                h.removeCallbacks(timeoutRunnable);
                h.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void triggerRetryTransition() {
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> {
            if (instance == null || instance.state != STATE_SCANNING || !instance.isAttachedToWindow()) return;
            FaceIdOverlay view = instance;
            view.isRetrying = true;
            view.invalidate();
            view.vibrateRetryTick();

            float d = dp(view.getContext(), 7);
            ObjectAnimator shake = ObjectAnimator.ofFloat(
                    view,
                    "translationX",
                    0f, -d, d, -d * 0.7f, d * 0.7f, 0f
            );
            shake.setDuration(300);
            shake.setInterpolator(new DecelerateInterpolator());
            shake.start();

            view.animate()
                    .scaleX(0.82f)
                    .scaleY(0.82f)
                    .setDuration(220)
                    .withEndAction(() -> {
                        if (instance != null && instance.isAttachedToWindow()) {
                            instance.isRetrying = false;
                            instance.invalidate();
                            instance.animate()
                                    .scaleX(1.10f)
                                    .scaleY(1.10f)
                                    .setDuration(150)
                                    .withEndAction(() -> {
                                        if (instance != null && instance.isAttachedToWindow()) {
                                            instance.animate()
                                                    .scaleX(1f)
                                                    .scaleY(1f)
                                                    .setDuration(130)
                                                    .start();
                                        }
                                    })
                                    .start();
                        }
                    })
                    .start();
        });
    }

    private static void setHardwareDisplayBrightness(Context ctx, float brightness) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Method setTemp = DisplayManager.class.getMethod("setTemporaryBrightness", int.class, float.class);
                setTemp.invoke(dm, Display.DEFAULT_DISPLAY, brightness);
                hardwareBrightnessBoosted = !Float.isNaN(brightness);
            }
        } catch (Throwable ignored) {
        }
    }

    private static float readCurrentDisplayBrightness(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Method getBright = DisplayManager.class.getMethod("getBrightness", int.class);
                Object val = getBright.invoke(dm, Display.DEFAULT_DISPLAY);
                if (val instanceof Float && !Float.isNaN((Float) val) && (Float) val > 0f) {
                    return (Float) val;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int sysVal = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 100);
            return Math.max(0.05f, Math.min(1.0f, sysVal / 255f));
        } catch (Throwable ignored) {
        }
        return 0.35f;
    }

    private static void showSmoothFillLight(Context appCtx) {
        try {
            if (fillLightView != null) return;
            WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            savedBrightnessBeforeBoost = readCurrentDisplayBrightness(appCtx);

            View lightPanel = new View(appCtx);
            lightPanel.setBackgroundColor(Color.WHITE);
            lightPanel.setAlpha(0f);

            // استخدام طبقات أدنى من كبسولة Face ID (2015) حتى تظل الكبسولة ظاهرة فوق الإضاءة البيضاء دائماً
            int[] windowTypes = new int[]{
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // Layer 21 (أدنى من 2015 و 2024)
                    2024, // TYPE_NAVIGATION_BAR_PANEL (Layer 24 - أدنى من 2015)
                    2006  // TYPE_SYSTEM_OVERLAY (Layer 17)
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
                    params.screenBrightness = 1.0f;
                    applyTrustedOverlayFlag(params);

                    wm.addView(lightPanel, params);
                    fillLightView = lightPanel;

                    // تدرج سريع وسلس خلال 400ms من السطوع الحالي حتى 100% سطوع كامل
                    if (fillLightAnimator != null) fillLightAnimator.cancel();
                    final float startB = savedBrightnessBeforeBoost;
                    fillLightAnimator = ValueAnimator.ofFloat(0f, 1f);
                    fillLightAnimator.setDuration(400L);
                    fillLightAnimator.setInterpolator(new DecelerateInterpolator());
                    fillLightAnimator.addUpdateListener(anim -> {
                        float p = (float) anim.getAnimatedValue();
                        float targetBrightness = startB + (p * (1.0f - startB));
                        setHardwareDisplayBrightness(appCtx, targetBrightness);

                        if (fillLightView != null && fillLightView.isAttachedToWindow()) {
                            fillLightView.setAlpha(p * 0.94f);
                        }
                    });
                    fillLightAnimator.start();
                    break;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreDisplayBrightness(Context ctx) {
        if (!hardwareBrightnessBoosted || ctx == null) return;
        try {
            setHardwareDisplayBrightness(ctx, Float.NaN);
        } catch (Throwable ignored) {
        }
        if (hardwareBrightnessBoosted) {
            setHardwareDisplayBrightness(ctx, savedBrightnessBeforeBoost);
            hardwareBrightnessBoosted = false;
        }
    }

    private static void hideSmoothFillLight(boolean immediate) {
        if (fillLightAnimator != null) {
            fillLightAnimator.cancel();
            fillLightAnimator = null;
        }
        if (fillLightView == null) return;
        View panel = fillLightView;
        Context ctx = panel.getContext();
        fillLightView = null;

        restoreDisplayBrightness(ctx);

        if (immediate) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && panel.isAttachedToWindow()) {
                    wm.removeView(panel);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        panel.animate()
                .alpha(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
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
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(timeoutRunnable);
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
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(timeoutRunnable);
            hideSmoothFillLight(false);

            if (instance != null && instance.isAttachedToWindow()) {
                instance.triggerFailureAnimation();
            }
        });
    }

    public static void hide() {
        Handler h = getMainHandler();
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(timeoutRunnable);
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
            dm.registerDisplayListener(displayListener, getMainHandler());
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
                .setDuration(300)
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
        isRetrying = false;
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

        Handler h = getMainHandler();
        if (h != null) {
            h.postDelayed(() -> removeInternal(false), 1000);
        }
    }

    private void triggerFailureAnimation() {
        if (state != STATE_SCANNING) return;
        state = STATE_FAILURE;
        isRetrying = false;
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

        Handler h = getMainHandler();
        if (h != null) {
            h.postDelayed(() -> removeInternal(false), 750);
        }
    }

    private void vibrateRetryTick() {
        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else {
                vibrator.vibrate(15);
            }
        } catch (Throwable ignored) {
        }
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

        if (isRetrying) {
            borderPaint.setColor(Color.parseColor("#CCFF6B35"));
            borderPaint.setStrokeWidth(dp(getContext(), 1.8f));
        } else {
            borderPaint.setColor(Color.parseColor("#4DFFFFFF"));
            borderPaint.setStrokeWidth(dp(getContext(), 1.3f));
        }

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
        float breath = (isFail || isRetrying) ? 0f : (float) Math.sin(scanAngle);
        float lookX = (isFail || isRetrying) ? 0f : (float) Math.sin(scanAngle) * dp(getContext(), 1.6f);
        float lookY = (isFail || isRetrying) ? 0f : (float) Math.cos(scanAngle * 2.0) * dp(getContext(), 0.9f);

        float boxHalf = dp(getContext(), 14.5f) + (breath * dp(getContext(), 0.8f));
        float bracketLen = dp(getContext(), 6.2f);
        float arcR = dp(getContext(), 3.5f);

        if (isFail) {
            glyphPaint.setColor(Color.parseColor("#FF3B30"));
        } else if (isRetrying) {
            glyphPaint.setColor(Color.parseColor("#FF6B35"));
        } else {
            glyphPaint.setColor(Color.parseColor("#F2F2F7"));
        }
        glyphPaint.setAlpha(255);
        glyphPaint.setStrokeWidth(dp(getContext(), 2.3f));

        drawCornerBrackets(canvas, cx, cy, boxHalf, bracketLen, arcR);

        float fcx = cx + lookX;
        float fcy = cy + lookY;
        float eyeOffsetX = dp(getContext(), 5.2f);
        float eyeOffsetY = dp(getContext(), 3.2f);

        float blinkFactor = (!isFail && !isRetrying && Math.sin(scanAngle) > 0.94f) ? 0.25f : 1f;
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
        if (!isFail && !isRetrying) {
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

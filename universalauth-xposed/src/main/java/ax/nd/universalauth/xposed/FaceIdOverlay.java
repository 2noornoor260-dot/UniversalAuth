package ax.nd.universalauth.xposed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

public class FaceIdOverlay extends View {

    private static FaceIdOverlay instance;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final Path checkPath = new Path();
    private final Path partialCheckPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();

    private boolean isSuccess = false;
    private float pulsePhase = 0f;
    private float checkProgress = 0f;
    private float morphProgress = 0f;

    private ValueAnimator pulseAnimator;
    private ValueAnimator successAnimator;

    public FaceIdOverlay(Context context) {
        super(context);
        bgPaint.setColor(Color.parseColor("#EB121215"));
        bgPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        scanLinePaint.setColor(Color.parseColor("#6638BDF8"));
        scanLinePaint.setStyle(Paint.Style.STROKE);
        scanLinePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public static void showScanning(Context context) {
        if (context == null) return;
        mainHandler.post(() -> {
            try {
                if (instance != null && instance.isAttachedToWindow()) {
                    if (!instance.isSuccess) return;
                    removeInternal();
                }
                Context appCtx = context.getApplicationContext();
                WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                FaceIdOverlay view = new FaceIdOverlay(appCtx);
                int sizePx = dp(appCtx, 68);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        sizePx,
                        sizePx,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.y = dp(appCtx, 38);

                view.setScaleX(0.5f);
                view.setScaleY(0.5f);
                view.setAlpha(0f);

                wm.addView(view, params);
                instance = view;

                view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(280)
                        .setInterpolator(new OvershootInterpolator(1.25f))
                        .start();

                view.startPulse();
            } catch (Throwable ignored) {
            }
        });
    }

    public static void showSuccess(Context context) {
        if (context == null) return;
        mainHandler.post(() -> {
            try {
                if (instance == null || !instance.isAttachedToWindow()) {
                    showScanning(context);
                }
                if (instance != null) {
                    instance.triggerSuccessAnimation();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public static void hide() {
        mainHandler.post(FaceIdOverlay::removeInternal);
    }

    private static void removeInternal() {
        if (instance != null) {
            FaceIdOverlay view = instance;
            instance = null;
            if (view.pulseAnimator != null) view.pulseAnimator.cancel();
            if (view.successAnimator != null) view.successAnimator.cancel();
            view.animate()
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .alpha(0f)
                    .setDuration(220)
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
    }

    private void startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1100);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new DecelerateInterpolator());
        pulseAnimator.addUpdateListener(anim -> {
            pulsePhase = (float) anim.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    private void triggerSuccessAnimation() {
        if (isSuccess) return;
        isSuccess = true;
        if (pulseAnimator != null) pulseAnimator.cancel();

        try {
            Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                } else {
                    vibrator.vibrate(25);
                }
            }
        } catch (Throwable ignored) {
        }

        animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(160)
                .withEndAction(() -> animate().scaleX(1f).scaleY(1f).setDuration(160).start())
                .start();

        successAnimator = ValueAnimator.ofFloat(0f, 1f);
        successAnimator.setDuration(360);
        successAnimator.setInterpolator(new DecelerateInterpolator());
        successAnimator.addUpdateListener(anim -> {
            float v = (float) anim.getAnimatedValue();
            morphProgress = Math.min(1f, v * 1.4f);
            checkProgress = Math.max(0f, (v - 0.25f) / 0.75f);
            invalidate();
        });
        successAnimator.start();

        mainHandler.postDelayed(FaceIdOverlay::removeInternal, 850);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = dp(getContext(), 4);
        pillRect.set(pad, pad, w - pad, h - pad);

        float radius = dp(getContext(), 20) + (morphProgress * (w / 2f));
        canvas.drawRoundRect(pillRect, radius, radius, bgPaint);

        float cx = w / 2f;
        float cy = h / 2f;

        if (!isSuccess) {
            float boxSize = dp(getContext(), 26) + (pulsePhase * dp(getContext(), 2));
            float half = boxSize / 2f;
            float corner = dp(getContext(), 6);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setAlpha((int) (190 + (65 * pulsePhase)));
            strokePaint.setStrokeWidth(dp(getContext(), 2.4f));

            // زوايا إطار Face ID الأربعة
            canvas.drawLine(cx - half, cy - half + corner, cx - half, cy - half, strokePaint);
            canvas.drawLine(cx - half, cy - half, cx - half + corner, cy - half, strokePaint);

            canvas.drawLine(cx + half - corner, cy - half, cx + half, cy - half, strokePaint);
            canvas.drawLine(cx + half, cy - half, cx + half, cy - half + corner, strokePaint);

            canvas.drawLine(cx - half, cy + half - corner, cx - half, cy + half, strokePaint);
            canvas.drawLine(cx - half, cy + half, cx - half + corner, cy + half, strokePaint);

            canvas.drawLine(cx + half - corner, cy + half, cx + half, cy + half, strokePaint);
            canvas.drawLine(cx + half, cy + half, cx + half, cy + half - corner, strokePaint);

            // خط المسح الضوئي المتحرك
            float scanY = (cy - half + dp(getContext(), 3)) + (pulsePhase * (boxSize - dp(getContext(), 6)));
            scanLinePaint.setStrokeWidth(dp(getContext(), 2f));
            canvas.drawLine(cx - half + dp(getContext(), 3), scanY, cx + half - dp(getContext(), 3), scanY, scanLinePaint);
        } else {
            // حلقة النجاح الخضراء وعلامة الصح المتحركة
            strokePaint.setColor(Color.parseColor("#34C759"));
            strokePaint.setAlpha(255);
            strokePaint.setStrokeWidth(dp(getContext(), 2.8f));
            canvas.drawCircle(cx, cy, dp(getContext(), 17), strokePaint);

            checkPath.reset();
            checkPath.moveTo(cx - dp(getContext(), 7.5f), cy + dp(getContext(), 0.5f));
            checkPath.lineTo(cx - dp(getContext(), 2f), cy + dp(getContext(), 6f));
            checkPath.lineTo(cx + dp(getContext(), 8.5f), cy - dp(getContext(), 5.5f));

            pathMeasure.setPath(checkPath, false);
            partialCheckPath.reset();
            pathMeasure.getSegment(0f, pathMeasure.getLength() * checkProgress, partialCheckPath, true);
            canvas.drawPath(partialCheckPath, strokePaint);
        }
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
  }

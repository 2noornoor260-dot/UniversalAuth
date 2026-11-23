package ax.nd.faceunlock.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * حلقة التسجيل: 4 قطاعات (يمين، يسار، أعلى، أسفل) من علامات شعاعية على طراز Face ID.
 *
 * - القطاع الحالي يضيء من منتصفه نحو الطرفين بحسب تقدّم حركة الرأس الفعلية (لحظياً، بنابض).
 * - عند اكتمال مرحلة يتحول قطاعها للأخضر موجةً من المنتصف، مع اهتزاز خفيف ونبضة للحلقة كلها.
 * - عند غياب الوجه تخفت الحلقة وتبقى في انتظار.
 * - عند خطأ حفظ تتحول العلامات النشطة إلى الأحمر.
 *
 * الحركة بنوابض مستقلة عن معدّل الإطارات وتُدار بـ Choreographer (بلا ValueAnimator).
 * تُستدعى {@link #update} من خيط الواجهة (النشاط يستدعيها كل 100ms).
 */
public class EnrollRingView extends View {

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int inactiveColor;
    private final int activeColor;
    private final int doneColor;
    private final int errorColor;

    private int stage = 0;
    private int lastStage = 0;
    private boolean faceFound = false;
    private boolean error = false;

    private final Spring progress = new Spring(0.20f, 0.9f, 0f);
    private final Spring faceVisible = new Spring(0.30f, 1.0f, 0f);
    private final Spring pop = new Spring(0.26f, 0.5f, 0f);
    private final Spring[] done = new Spring[EnrollRingGeometry.STAGES];
    private float wave = 0f;

    private final float[] ends = new float[4];
    private boolean running = false;
    private long lastFrameNanos = 0L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float dt = lastFrameNanos == 0L ? (1f / 60f)
                    : Math.min(0.05f, (frameTimeNanos - lastFrameNanos) / 1_000_000_000f);
            lastFrameNanos = frameTimeNanos;
            stepAll(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public EnrollRingView(Context ctx, boolean dark) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        inactiveColor = dark ? Color.parseColor("#3A3A3C") : Color.parseColor("#C7C7CC");
        activeColor = dark ? Color.parseColor("#0A84FF") : Color.parseColor("#007AFF");
        doneColor = dark ? Color.parseColor("#30D158") : Color.parseColor("#34C759");
        errorColor = dark ? Color.parseColor("#FF453A") : Color.parseColor("#FF3B30");
        for (int i = 0; i < done.length; i++) {
            done[i] = new Spring(0.34f, 0.9f, 0f);
        }
        faceVisible.snapTo(0f);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStrokeWidth(3.2f * density);
    }

    /**
     * @param stage     المرحلة الحالية 0..3 (4 = اكتمل التسجيل)
     * @param yawSign   إشارة اتجاه الرأس المسجّلة في المرحلة الأولى
     */
    public void update(int stage, boolean faceFound, float yaw, float pitch, float yawSign, boolean error) {
        this.faceFound = faceFound;
        this.error = error;
        faceVisible.setTarget(faceFound ? 1f : 0f);

        if (stage < lastStage) {
            // بدأ تسجيل جديد: أعد كل شيء
            for (Spring s : done) {
                s.snapTo(0f);
            }
            progress.snapTo(0f);
        }
        if (stage > lastStage) {
            for (int s = lastStage; s < Math.min(stage, EnrollRingGeometry.STAGES); s++) {
                done[s].setTarget(1f);
            }
            pop.kick(stage >= EnrollRingGeometry.STAGES ? 5f : 3.2f);
            haptic(stage >= EnrollRingGeometry.STAGES);
            progress.snapTo(0f);
        }
        this.stage = stage;
        this.lastStage = stage;

        float target = (stage >= EnrollRingGeometry.STAGES || !faceFound) ? 0f
                : EnrollRingGeometry.headProgress(stage, yaw, pitch, yawSign);
        progress.setTarget(target);
    }

    private void haptic(boolean strong) {
        try {
            int c;
            if (Build.VERSION.SDK_INT >= 30) {
                c = strong ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK;
            } else {
                c = HapticFeedbackConstants.VIRTUAL_KEY;
            }
            performHapticFeedback(c);
        } catch (Exception ignored) {
        }
    }

    private void stepAll(float dt) {
        progress.step(dt);
        faceVisible.step(dt);
        pop.step(dt);
        for (Spring s : done) {
            s.step(dt);
        }
        wave = (wave + dt / 1.6f) % 1f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        final float rOuter = Math.min(w, h) * 0.5f - 5f * density;
        final float baseLen = 9f * density;
        final float visible = faceVisible.get();
        final float popV = pop.get();

        canvas.save();
        float sc = 1f + 0.025f * popV;
        canvas.scale(sc, sc, w * 0.5f, h * 0.5f);
        canvas.translate(w * 0.5f, h * 0.5f);

        final boolean allDone = stage >= EnrollRingGeometry.STAGES;

        for (int s = 0; s < EnrollRingGeometry.STAGES; s++) {
            final boolean isCurrent = (s == stage) && !allDone;
            final float d = done[s].get();
            for (int t = 0; t < EnrollRingGeometry.TICKS_PER_STAGE; t++) {
                final float dist = EnrollRingGeometry.distanceFromCenter(t);

                // اكتمال القطاع: موجة خضراء من المنتصف نحو الأطراف
                float tickDone = clamp01(d * 1.6f - dist * 0.6f);

                // تقدّم الرأس: يضيء القطاع من المنتصف نحو الطرفين
                float filled = 0f;
                if (isCurrent) {
                    filled = clamp01((progress.get() - dist) * 6f + 0.5f);
                    if (progress.get() < 0.02f) {
                        // قبل أي حركة: نبضة هادئة تسير على علامات القطاع الحالي تدعو للحركة
                        float ph = 0.5f + 0.5f * (float) Math.sin((wave - dist * 0.5f) * 2f * Math.PI);
                        filled = 0.28f * ph;
                    }
                }

                int base = mix(inactiveColor, error && isCurrent ? errorColor : activeColor, filled);
                int color = mix(base, doneColor, tickDone);

                float alphaDim = 0.45f + 0.55f * visible;
                float alpha = (isCurrent || tickDone > 0f) ? 1f : alphaDim;
                tickPaint.setColor(color);
                tickPaint.setAlpha((int) (255f * alpha));

                float len = baseLen * (1f + 0.40f * filled + 0.55f * tickDone);
                EnrollRingGeometry.tickEnds(s, t, rOuter, len, ends);
                canvas.drawLine(ends[0], ends[1], ends[2], ends[3], tickPaint);
            }
        }
        canvas.restore();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int mix(int a, int b, float t) {
        float k = clamp01(t);
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * k);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * k);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k);
        return Color.rgb(r, g, bl);
    }
}

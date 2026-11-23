package ax.nd.faceunlock.overlay;

/**
 * هندسة حلقة التسجيل على طراز Face ID: أربع مراحل (يمين، يسار، أعلى، أسفل)، لكل مرحلة قطاع بزاوية 72°
 * مقسَّم إلى علامات شعاعية قصيرة تضيء تدريجياً بحسب تقدّم حركة الرأس، ثم تتحول للأخضر عند اكتمال المرحلة.
 * الزوايا بالدرجات باتجاه عقارب الساعة من موضع الساعة 3 (نفس اصطلاح Canvas.drawArc).
 */
public final class EnrollRingGeometry {

    public static final int STAGES = 4;
    public static final int TICKS_PER_STAGE = 13;
    public static final float SWEEP_DEG = 72f;

    /** مراكز القطاعات: يمين 0°، يسار 180°، أعلى 270°، أسفل 90°. */
    private static final float[] CENTER_DEG = {0f, 180f, 270f, 90f};

    private EnrollRingGeometry() {
    }

    public static float tickAngleDeg(int stage, int tick) {
        float start = CENTER_DEG[stage] - SWEEP_DEG * 0.5f;
        return start + SWEEP_DEG * (tick + 0.5f) / TICKS_PER_STAGE;
    }

    /** بُعد العلامة عن مركز القطاع (0 = المركز، 1 = الطرف): يُستخدم ليتسع الإضاءة من الوسط للأطراف. */
    public static float distanceFromCenter(int tick) {
        float t = (tick + 0.5f) / TICKS_PER_STAGE;
        return Math.abs(t - 0.5f) * 2f;
    }

    /**
     * تقدّم حركة الرأس نحو هدف المرحلة (0..1). العتبات تطابق عتبات المحرّك (12° أفقياً، 9° عمودياً)،
     * فيبلغ التقدّم 1 تماماً عند لحظة الالتقاط.
     *
     * @param yawSign إشارة اتجاه الرأس المسجّلة في المرحلة الأولى (لتحديد "الاتجاه المعاكس" في الثانية)
     */
    public static float headProgress(int stage, float yaw, float pitch, float yawSign) {
        float v;
        switch (stage) {
            case 0:
                v = Math.abs(yaw) / 12f;
                break;
            case 1:
                v = (yaw * -yawSign) / 12f;
                break;
            case 2:
                v = pitch / 9f;
                break;
            case 3:
                v = -pitch / 9f;
                break;
            default:
                v = 1f;
        }
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** نهايتا العلامة بإحداثيات مركزها الأصل: out = {x1,y1 (الخارجية), x2,y2 (الداخلية)}. */
    public static void tickEnds(int stage, int tick, float rOuter, float length, float[] out) {
        double a = Math.toRadians(tickAngleDeg(stage, tick));
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        out[0] = c * rOuter;
        out[1] = s * rOuter;
        out[2] = c * (rOuter - length);
        out[3] = s * (rOuter - length);
    }
}

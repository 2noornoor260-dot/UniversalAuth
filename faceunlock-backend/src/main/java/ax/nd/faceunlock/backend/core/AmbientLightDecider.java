package ax.nd.faceunlock.backend.core;

/**
 * يقرّر متى تُقفل (Latch) لوحة الإضاءة الليلية في جلسة المسح.
 *
 * أسباب الخلل في المنطق القديم (كانت اللوحة تُضاء حتى في النور):
 *  1) أول إطارات الكاميرا الأمامية تكون معتمة لأن التعريض التلقائي لم يستقر بعد، فيُقرأ "ظلام" وهمي.
 *  2) مؤقّت الفحص كان يُعدّ "تأكيدين" على نفس قيمة الإضاءة القديمة (لا إطار جديد بينهما) فيُفعَّل القفل مبكراً.
 *  3) غياب قراءة حساس الضوء كان يُعامَل كأنه "لا اعتراض" بدل الانتظار.
 *
 * القواعد هنا:
 *  - تُتجاهل أول 4 إطارات وأول 450ms من أول إطار (إحماء التعريض).
 *  - القرار بوسيط (median) آخر 3 عينات مستقرة مختلفة، لا بعينة واحدة.
 *  - إن وُجد حساس ضوء: القراءة >= 30 lux تمنع الإضاءة نهائياً، وبين 12 و30 تتطلب عتمة كاميرا أشدّ،
 *    وإن لم يُرسل الحساس قراءة خلال 700ms يُعامَل كغير متوفر (كاميرا فقط بعتبة أشدّ).
 *  - يجب أن تستمر العتمة 350ms متواصلة.
 *  - كاميرا مغطّاة تماماً (جيب/وجه للأسفل) لا تُشغّل الإضاءة.
 *  - وجه ساطع الإضاءة (>= 80) يمنع الإضاءة.
 *  - بعد القفل تبقى اللوحة ثابتة حتى نهاية الجلسة (قاعدة "لا إطفاء أثناء المسح").
 *
 * الصنف نقيّ وقابل للاختبار (الزمن يُمرَّر كمعامل).
 */
public final class AmbientLightDecider {

    public static final int SETTLE_FRAMES = 4;
    public static final long SETTLE_MS = 450L;
    public static final int SAMPLES_REQUIRED = 3;

    public static final float LUX_DARK_MAX = 12f;
    public static final float LUX_BRIGHT_MIN = 30f;

    public static final float DARK_LUMA_SENSOR_OK = 38f;
    public static final float DARK_LUMA_AMBIGUOUS = 26f;
    public static final float DARK_LUMA_CAMERA_ONLY = 30f;
    public static final float COVERED_LUMA = 3f;
    public static final float FACE_LUMA_BRIGHT = 80f;

    public static final long SENSOR_GRACE_MS = 700L;
    public static final long DARK_HOLD_MS = 350L;
    private static final long STALE_MS = 1500L;

    private final boolean hasSensor;

    private long beginMs = -1L;
    private long firstFrameMs = -1L;
    private int frameIndex;
    private final float[] ring = new float[SAMPLES_REQUIRED];
    private int ringCount;
    private int ringPos;
    private long lastSettledMs = -1L;
    private float lastFaceLuma = -1f;
    private float lux = -1f;
    private boolean luxSeen;
    private long darkSinceMs = -1L;
    private boolean latched;
    private String reason = "idle";

    public AmbientLightDecider(boolean hasSensor) {
        this.hasSensor = hasSensor;
    }

    /** جلسة مسح جديدة: يُمسح كل شيء بما فيه القفل. */
    public synchronized void begin(long nowMs) {
        beginMs = nowMs;
        firstFrameMs = -1L;
        frameIndex = 0;
        ringCount = 0;
        ringPos = 0;
        lastSettledMs = -1L;
        lastFaceLuma = -1f;
        lux = -1f;
        luxSeen = false;
        darkSinceMs = -1L;
        latched = false;
        reason = "begin";
    }

    public synchronized void onLux(long nowMs, float luxValue) {
        if (luxValue < 0f || Float.isNaN(luxValue)) return;
        lux = luxValue;
        luxSeen = true;
    }

    /**
     * @param meanLuma متوسط سطوع الإطار (0..255)
     * @param faceLuma سطوع منطقة الوجه (0..255) أو سالب إن لم يوجد وجه
     */
    public synchronized void onCameraFrame(long nowMs, float meanLuma, float faceLuma) {
        if (firstFrameMs < 0L) firstFrameMs = nowMs;
        frameIndex++;
        if (faceLuma >= 0f) lastFaceLuma = faceLuma;
        boolean settled = frameIndex > SETTLE_FRAMES && (nowMs - firstFrameMs) >= SETTLE_MS;
        if (settled) {
            ring[ringPos] = meanLuma;
            ringPos = (ringPos + 1) % SAMPLES_REQUIRED;
            if (ringCount < SAMPLES_REQUIRED) ringCount++;
            lastSettledMs = nowMs;
        }
    }

    /** @return true إذا وجب (أو سبق) قفل الإضاءة الليلية في هذه الجلسة. */
    public synchronized boolean update(long nowMs) {
        if (latched) return true;

        if (ringCount < SAMPLES_REQUIRED) {
            return hold("warming-up");
        }
        if (lastSettledMs >= 0L && (nowMs - lastSettledMs) > STALE_MS) {
            return hold("stale-frames");
        }

        float m = median();
        if (m < COVERED_LUMA) {
            return hold("camera-covered");
        }
        if (lastFaceLuma >= FACE_LUMA_BRIGHT) {
            return hold("face-bright");
        }

        float threshold;
        if (hasSensor) {
            if (luxSeen) {
                if (lux >= LUX_BRIGHT_MIN) {
                    return hold("sensor-bright");
                }
                threshold = lux < LUX_DARK_MAX ? DARK_LUMA_SENSOR_OK : DARK_LUMA_AMBIGUOUS;
            } else {
                if (beginMs >= 0L && (nowMs - beginMs) < SENSOR_GRACE_MS) {
                    return hold("awaiting-sensor");
                }
                threshold = DARK_LUMA_CAMERA_ONLY;
            }
        } else {
            threshold = DARK_LUMA_CAMERA_ONLY;
        }

        if (m >= threshold) {
            return hold("camera-bright");
        }

        if (darkSinceMs < 0L) darkSinceMs = nowMs;
        if ((nowMs - darkSinceMs) >= DARK_HOLD_MS) {
            latched = true;
            reason = "dark-latched";
            return true;
        }
        reason = "dark-pending";
        return false;
    }

    public synchronized boolean isLatched() {
        return latched;
    }

    public synchronized String reason() {
        return reason;
    }

    private boolean hold(String why) {
        darkSinceMs = -1L;
        reason = why;
        return false;
    }

    private float median() {
        float a = ring[0];
        float b = ring[1];
        float c = ring[2];
        float lo = Math.min(a, Math.min(b, c));
        float hi = Math.max(a, Math.max(b, c));
        return a + b + c - lo - hi;
    }
}

package ax.nd.faceunlock.backend.core;

/**
 * جلسة تحقق واحدة (من لحظة بدء مسح الشاشة حتى النجاح أو انتهاء المهلة).
 *
 * سبب إنشائها: في النسخة السابقة كانت بوابة "استقرار التعريض 240ms" تعتمد على
 * زمن آخر إطار ناجح (prevFrameTimeMs) الذي قيمته الابتدائية 0 ولا يتحدّث إلا بعد
 * اجتياز البوابة نفسها، فكانت تُعاد كل إطار ولا يمرّ أي إطار أبداً (تجمّد دائم).
 *
 * هنا: كل شيء مرتبط بجلسة صريحة يبدأها {@link #begin(long)} عند بدء كل مسح،
 * وزمن الاستقرار يُقاس من أول إطار وصل فعلاً في هذه الجلسة، ولا توجد أي حالة تتسرّب بين الجلسات.
 *
 * الصنف نقيّ (بلا Android) ويستقبل الزمن كمعامل ليكون قابلاً للاختبار الحتمي.
 * الخيوط: الدوال متزامنة لأن begin() قد تُستدعى من خيط الخدمة بينما التحقق يعمل على خيط الكاميرا.
 */
public final class VerifySession {

    public enum Outcome {
        /** الإطار جيد لكن لم يكتمل عدد الإطارات المطلوب بعد. */
        NEED_MORE,
        /** اكتملت سلسلة الإطارات الحيّة المتسقة: يُسمح بالفتح. */
        ACCEPT,
        /** إطاران متطابقان تماماً (صورة ثابتة/إعادة عرض إطار): رفض. */
        REJECT_STATIC
    }

    /** إطارات/زمن الاستقرار قبل أن يُحتسب أي إطار كإطار "جيد". */
    public static final int SETTLE_FRAMES = 3;
    public static final long SETTLE_MS = 220L;

    private static final long NO_FACE_RESET_MS = 700L;
    private static final long REJECT_CYCLE_MS = 1650L;
    private static final float STATIC_SIM = 0.9998f;
    private static final float STATIC_NOSE_DELTA = 0.00005f;

    private long startMs = -1L;
    private int frameCount;
    private int goodStreak;
    private long lastGoodMs = -1L;
    private float[] prevEmbedding;
    private float prevNoseRatio;
    private long lastFaceMs = -1L;
    private long continuousFailStartMs = -1L;
    private int rejectionCycleCount;

    /** يبدأ جلسة جديدة نظيفة تماماً. تُستدعى عند بدء كل مسح. */
    public synchronized void begin(long nowMs) {
        startMs = -1L; // يُضبط عند وصول أول إطار فعلي (فتح الكاميرا قد يتأخر)
        frameCount = 0;
        goodStreak = 0;
        lastGoodMs = -1L;
        prevEmbedding = null;
        prevNoseRatio = 0f;
        lastFaceMs = -1L;
        continuousFailStartMs = -1L;
        rejectionCycleCount = 0;
    }

    /** يُستدعى مع كل إطار كاميرا يصل للمحرّك (قبل أي معالجة). */
    public synchronized void onFrame(long nowMs) {
        if (startMs < 0L) startMs = nowMs;
        frameCount++;
    }

    public synchronized boolean isExposureSettled(long nowMs) {
        if (startMs < 0L) return false;
        return frameCount >= SETTLE_FRAMES || (nowMs - startMs) >= SETTLE_MS;
    }

    public synchronized void onFaceSeen(long nowMs) {
        lastFaceMs = nowMs;
    }

    /** لا وجه في هذا الإطار: بعد غياب طويل تُلغى السلسلة، ولا يُعدّ ذلك "فشلاً". */
    public synchronized void onNoFace(long nowMs) {
        continuousFailStartMs = -1L;
        if (lastFaceMs >= 0L && (nowMs - lastFaceMs) > NO_FACE_RESET_MS) {
            restartStreak();
        }
    }

    /**
     * إطار اجتاز كل الطبقات (جودة + عمق + مكافحة تزييف + مطابقة).
     *
     * @param embedding بصمة الإطار (مطبَّعة L2)
     * @param noseRatio نسبة بروز الأنف الثلاثية الأبعاد في هذا الإطار
     */
    public synchronized Outcome onGoodFrame(long nowMs, float[] embedding, float noseRatio, SecurityProfile p) {
        if (!isExposureSettled(nowMs)) {
            return Outcome.NEED_MORE; // إطارات الإحماء لا تُحتسب أبداً (ولا يمكن أن تُقبل)
        }
        continuousFailStartMs = -1L; // إطار جيد = لسنا في حالة فشل متواصل

        if (prevEmbedding != null && (nowMs - lastGoodMs) > p.maxFrameGapMs) {
            restartStreak(); // فجوة طويلة: السلسلة السابقة لم تعد موثوقة
        }

        if (prevEmbedding == null) {
            beginStreak(nowMs, embedding, noseRatio);
            return p.requiredGoodFrames <= 1 ? Outcome.ACCEPT : Outcome.NEED_MORE;
        }

        float sim = dot(embedding, prevEmbedding);
        if (sim < p.temporalMinSim) {
            beginStreak(nowMs, embedding, noseRatio); // تغيّر كبير بين الإطارين: ابدأ سلسلة جديدة
            return Outcome.NEED_MORE;
        }
        if (sim > STATIC_SIM && Math.abs(noseRatio - prevNoseRatio) < STATIC_NOSE_DELTA) {
            restartStreak();
            return Outcome.REJECT_STATIC;
        }

        goodStreak++;
        prevEmbedding = embedding;
        prevNoseRatio = noseRatio;
        lastGoodMs = nowMs;
        return goodStreak >= p.requiredGoodFrames ? Outcome.ACCEPT : Outcome.NEED_MORE;
    }

    /**
     * رفض قاطع (انتحال/عدم تطابق). يُلغي السلسلة، ويُرجع true فقط عندما يجب إطلاق
     * إشارة الرفض المرئية: مرة واحدة كل دورتي رفض (نافذة 1650ms) حسب متطلبات التصميم.
     */
    public synchronized boolean onHardReject(long nowMs) {
        restartStreak();
        if (continuousFailStartMs < 0L) {
            continuousFailStartMs = nowMs;
            return false;
        }
        if (nowMs - continuousFailStartMs >= REJECT_CYCLE_MS) {
            continuousFailStartMs = nowMs;
            rejectionCycleCount++;
            return (rejectionCycleCount % 2) == 0;
        }
        return false;
    }

    /** حالة غير حاسمة (انتهت مهلة طبقة، رمشة...): لا تُلغي السلسلة ولا تُحتسب. */
    public synchronized void onInconclusive() {
        // لا شيء عمداً: فجوة الزمن في onGoodFrame هي من تُنهي السلسلة القديمة.
    }

    public synchronized void onAccepted() {
        restartStreak();
        continuousFailStartMs = -1L;
        rejectionCycleCount = 0;
    }

    public synchronized int frameCount() {
        return frameCount;
    }

    public synchronized int goodStreak() {
        return goodStreak;
    }

    private void beginStreak(long nowMs, float[] embedding, float noseRatio) {
        goodStreak = 1;
        prevEmbedding = embedding;
        prevNoseRatio = noseRatio;
        lastGoodMs = nowMs;
    }

    private void restartStreak() {
        goodStreak = 0;
        prevEmbedding = null;
        prevNoseRatio = 0f;
        lastGoodMs = -1L;
    }

    public static float dot(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }
}

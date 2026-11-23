package ax.nd.faceunlock.backend.core;

/**
 * مستوى الأمان: مجموعة عتبات ثابتة (immutable) تتحكم في صرامة التحقق.
 *
 * المستوى "متوازن" يطابق العتبات المعايَرة سابقاً (0.56 / 0.59 / 0.52) كي لا يتغيّر سلوك المستخدم الحالي،
 * والمستويان الأعلى يرفعان العتبات ويطلبان إطارات حيّة متتالية أكثر.
 * لا يوجد مستوى "أضعف" من المتوازن: الحد الأدنى للأمان ثابت.
 */
public final class SecurityProfile {

    public static final int LEVEL_BALANCED = 0;
    public static final int LEVEL_STRICT = 1;
    public static final int LEVEL_MAXIMUM = 2;

    public final int level;
    /** الحد الأدنى للنتيجة المركّبة للمطابقة. */
    public final float matchCombined;
    /** الحد الأدنى لأفضل قالب مفرد. */
    public final float matchSingleMax;
    /** الحد الأدنى لدرجة "حقيقي" من نموذج كشف التزييف العصبي. */
    public final float spoofReal;
    /** عدد الإطارات الجيدة المتتالية اللازمة قبل قبول الفتح. */
    public final int requiredGoodFrames;
    /** أقل تشابه (cosine) مسموح بين إطارين جيدين متتاليين. */
    public final float temporalMinSim;
    /** أقصى فجوة زمنية (مللي ثانية) بين إطارين جيدين ضمن نفس السلسلة. */
    public final long maxFrameGapMs;
    /** اشتراط فتح العينين مهما كان إعداد المستخدم. */
    public final boolean forceEyesOpen;

    private SecurityProfile(int level, float matchCombined, float matchSingleMax, float spoofReal,
                            int requiredGoodFrames, float temporalMinSim, long maxFrameGapMs,
                            boolean forceEyesOpen) {
        this.level = level;
        this.matchCombined = matchCombined;
        this.matchSingleMax = matchSingleMax;
        this.spoofReal = spoofReal;
        this.requiredGoodFrames = requiredGoodFrames;
        this.temporalMinSim = temporalMinSim;
        this.maxFrameGapMs = maxFrameGapMs;
        this.forceEyesOpen = forceEyesOpen;
    }

    private static final SecurityProfile BALANCED =
            new SecurityProfile(LEVEL_BALANCED, 0.56f, 0.59f, 0.52f, 2, 0.78f, 1400L, false);
    private static final SecurityProfile STRICT =
            new SecurityProfile(LEVEL_STRICT, 0.60f, 0.63f, 0.62f, 3, 0.80f, 1500L, false);
    private static final SecurityProfile MAXIMUM =
            new SecurityProfile(LEVEL_MAXIMUM, 0.64f, 0.67f, 0.72f, 4, 0.82f, 1600L, true);

    /** أي قيمة غير معروفة تُعامَل كـ"متوازن" (لا تنزل أبداً تحته). */
    public static SecurityProfile forLevel(int level) {
        switch (level) {
            case LEVEL_STRICT:
                return STRICT;
            case LEVEL_MAXIMUM:
                return MAXIMUM;
            default:
                return BALANCED;
        }
    }
}

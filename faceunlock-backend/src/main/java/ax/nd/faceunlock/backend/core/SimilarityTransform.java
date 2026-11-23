package ax.nd.faceunlock.backend.core;

/**
 * تحويل تشابه ثنائي الأبعاد (دوران + تحجيم منتظم + إزاحة، بلا انعكاس):
 *   x' = a*x - b*y + tx
 *   y' = b*x + a*y + ty
 *
 * يُستخدم لمحاذاة الوجه إلى القالب القياسي 112×112 (نفس القالب الذي تُدرَّب عليه نماذج
 * MobileFaceNet/ArcFace). المحاذاة السابقة كانت تدوّر الصورة كلها ثم تقصّ بإحداثيات الصندوق الأصلي،
 * فكان القصّ ينزاح عن الوجه (دوران الصورة يغيّر حدودها) وقد يقلب الوجه رأساً على عقب إذا فُسّرت
 * تسمية العين اليسرى/اليمنى بعكس المتوقع. هنا نرتّب العينين بحسب الإحداثي x لا بحسب الاسم.
 */
public final class SimilarityTransform {

    /** عين الصورة اليسرى في قالب ArcFace 112×112. */
    public static final float EYE_L_X = 38.2946f;
    public static final float EYE_L_Y = 51.6963f;
    /** عين الصورة اليمنى. */
    public static final float EYE_R_X = 73.5318f;
    public static final float EYE_R_Y = 51.5014f;
    /** منتصف الفم (متوسط زاويتَي الفم 41.5493,92.3655 و70.7299,92.2041). */
    public static final float MOUTH_X = 56.1396f;
    public static final float MOUTH_Y = 92.2848f;

    public final float a;
    public final float b;
    public final float tx;
    public final float ty;

    public SimilarityTransform(float a, float b, float tx, float ty) {
        this.a = a;
        this.b = b;
        this.tx = tx;
        this.ty = ty;
    }

    public float scale() {
        return (float) Math.sqrt(a * a + b * b);
    }

    public float rotationDegrees() {
        return (float) Math.toDegrees(Math.atan2(b, a));
    }

    public void apply(float x, float y, float[] out) {
        out[0] = a * x - b * y + tx;
        out[1] = b * x + a * y + ty;
    }

    /** قيم مصفوفة 3×3 بترتيب android.graphics.Matrix.setValues. */
    public float[] toMatrixValues() {
        return new float[]{a, -b, tx, b, a, ty, 0f, 0f, 1f};
    }

    /**
     * أفضل تحويل تشابه بالمربعات الصغرى يحوّل src إلى dst (n >= 2 نقطة، مصفوفات x,y متتالية).
     *
     * @return null إذا كانت النقاط متطابقة/منحلّة
     */
    public static SimilarityTransform estimate(float[] src, float[] dst, int n) {
        if (src == null || dst == null || n < 2 || src.length < 2 * n || dst.length < 2 * n) return null;

        double msx = 0, msy = 0, mdx = 0, mdy = 0;
        for (int i = 0; i < n; i++) {
            msx += src[2 * i];
            msy += src[2 * i + 1];
            mdx += dst[2 * i];
            mdy += dst[2 * i + 1];
        }
        msx /= n;
        msy /= n;
        mdx /= n;
        mdy /= n;

        double numA = 0, numB = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double sx = src[2 * i] - msx;
            double sy = src[2 * i + 1] - msy;
            double dx = dst[2 * i] - mdx;
            double dy = dst[2 * i + 1] - mdy;
            numA += sx * dx + sy * dy;
            numB += sx * dy - sy * dx;
            den += sx * sx + sy * sy;
        }
        if (den < 1e-6) return null;

        double a = numA / den;
        double b = numB / den;
        double tx = mdx - (a * msx - b * msy);
        double ty = mdy - (b * msx + a * msy);
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(tx) || Double.isNaN(ty)) return null;
        return new SimilarityTransform((float) a, (float) b, (float) tx, (float) ty);
    }

    /**
     * محاذاة إلى قالب ArcFace 112×112 باستخدام العينين (+ منتصف الفم إن توفّر).
     * ترتيب العينين المُمرَّرتين غير مهم: تُرتَّبان داخلياً بحسب x.
     *
     * @return null إذا كانت العينان متطابقتين أو غير صالحتين
     */
    public static SimilarityTransform alignToArcFace(float eye1X, float eye1Y, float eye2X, float eye2Y,
                                                     boolean hasMouth, float mouthX, float mouthY) {
        float lx, ly, rx, ry;
        if (eye1X <= eye2X) {
            lx = eye1X;
            ly = eye1Y;
            rx = eye2X;
            ry = eye2Y;
        } else {
            lx = eye2X;
            ly = eye2Y;
            rx = eye1X;
            ry = eye1Y;
        }
        if (Math.abs(rx - lx) < 1e-3f && Math.abs(ry - ly) < 1e-3f) return null;

        if (hasMouth) {
            float[] src = {lx, ly, rx, ry, mouthX, mouthY};
            float[] dst = {EYE_L_X, EYE_L_Y, EYE_R_X, EYE_R_Y, MOUTH_X, MOUTH_Y};
            SimilarityTransform t = estimate(src, dst, 3);
            if (t != null && isPlausible(t)) return t;
            // سقوط آمن إلى العينين فقط إن كان الفم شاذاً
        }
        float[] src2 = {lx, ly, rx, ry};
        float[] dst2 = {EYE_L_X, EYE_L_Y, EYE_R_X, EYE_R_Y};
        SimilarityTransform t2 = estimate(src2, dst2, 2);
        return (t2 != null && isPlausible(t2)) ? t2 : null;
    }

    /** يرفض تحويلات غير منطقية (تحجيم شاذ) الناتجة عن معالم خاطئة. */
    private static boolean isPlausible(SimilarityTransform t) {
        float s = t.scale();
        return s > 0.05f && s < 5f;
    }
}

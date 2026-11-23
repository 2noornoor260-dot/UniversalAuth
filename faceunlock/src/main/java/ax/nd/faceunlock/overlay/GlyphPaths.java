package ax.nd.faceunlock.overlay;

/**
 * هندسة الرموز داخل الكبسولة بوحدات "رمز" ضمن المربع [-1,1]×[-1,1] (المحور y للأسفل).
 *
 * التصميم أحادي اللون بالكامل (أبيض على أسود): أقواس زوايا مستديرة على طراز Face ID + ملامح وجه
 * (عينان تُرمشان، أنف بخطّاف، فم مبتسم)، ثم قفل: جسم + ذراع تُرفع وتتأرجح حول الساق اليسرى عند الفتح.
 * الدوال ترسم مسارات خطّية (stroke) تُرسم بسماكة {@link #STROKE} وأطراف/وصلات مستديرة،
 * عدا {@link #lockBody} و{@link #keyholeDot} فهي أشكال ممتلئة.
 */
public final class GlyphPaths {

    /** السماكة المقترحة للخطوط بوحدات الرمز. */
    public static final float STROKE = 0.15f;
    /** ارتفاع ذراع القفل؛ "الرفع 28%" = 0.28 × هذه القيمة. */
    public static final float SHACKLE_HEIGHT = 0.58f;
    /** إزاحة رأسية تُوسِّط القفل بصرياً في المربع. */
    public static final float LOCK_DY = -0.12f;

    private static final float KAPPA = 0.5522847498f;

    private GlyphPaths() {
    }

    /** أقواس الزوايا الأربعة. inset=0 واسعة، وكلما زاد ضاقت (حالة "تثبيت الوجه"). */
    public static void brackets(PathSink s, float inset) {
        float h = 1f - clamp(inset, 0f, 0.35f);
        float arm = 0.44f;
        float r = 0.27f;
        corner(s, -h, -h, 1f, 1f, arm, r);
        corner(s, h, -h, -1f, 1f, arm, r);
        corner(s, h, h, -1f, -1f, arm, r);
        corner(s, -h, h, 1f, -1f, arm, r);
    }

    private static void corner(PathSink s, float cx, float cy, float dx, float dy, float arm, float r) {
        s.moveTo(cx, cy + dy * arm);
        s.lineTo(cx, cy + dy * r);
        s.quadTo(cx, cy, cx + dx * r, cy);
        s.lineTo(cx + dx * arm, cy);
    }

    /** العينان: blink=0 مفتوحتان، 1 مغمضتان (تتحوّلان إلى نقطتين). */
    public static void eyes(PathSink s, float blink) {
        float b = clamp(blink, 0f, 1f);
        float len = 0.30f * (1f - 0.88f * b);
        float y0 = -0.34f + (0.30f - len) * 0.5f;
        for (int side = -1; side <= 1; side += 2) {
            float x = 0.37f * side;
            s.moveTo(x, y0);
            s.lineTo(x, y0 + len);
        }
    }

    /** الأنف: خط رأسي بخطّاف مستدير للأسفل. */
    public static void nose(PathSink s) {
        s.moveTo(0.06f, -0.14f);
        s.lineTo(0.06f, 0.18f);
        s.quadTo(0.06f, 0.29f, -0.07f, 0.29f);
    }

    /** الفم: ابتسامة هادئة. */
    public static void mouth(PathSink s) {
        s.moveTo(-0.34f, 0.50f);
        s.cubicTo(-0.18f, 0.71f, 0.18f, 0.71f, 0.34f, 0.50f);
    }

    /** جسم القفل (ممتلئ). */
    public static void lockBody(PathSink s) {
        roundRect(s, -0.50f, 0.04f + LOCK_DY, 0.50f, 0.80f + LOCK_DY, 0.17f);
    }

    /** ثقب المفتاح: دائرة صغيرة (ممتلئة بلون الخلفية فوق جسم القفل). */
    public static void keyholeDot(PathSink s) {
        circle(s, 0f, 0.35f + LOCK_DY, 0.085f);
    }

    /** ساق ثقب المفتاح (خط بطرف مستدير). */
    public static void keyholeStem(PathSink s) {
        s.moveTo(0f, 0.37f + LOCK_DY);
        s.lineTo(0f, 0.55f + LOCK_DY);
    }

    /**
     * ذراع القفل.
     *
     * @param lift   رفع رأسي كنسبة من {@link #SHACKLE_HEIGHT} (0 مغلق، 0.28 = رفعة الرفض، 1 = أقصى)
     * @param swingDeg تأرجح بالدرجات حول قاعدة الساق اليسرى (0 مغلق، ~28 = مفتوح)
     */
    public static void lockShackle(PathSink s, float lift, float swingDeg) {
        final float px = -0.30f;
        final float py = 0.08f + LOCK_DY;
        final float dy = -lift * SHACKLE_HEIGHT;
        final double a = -Math.toRadians(swingDeg);
        final float cs = (float) Math.cos(a);
        final float sn = (float) Math.sin(a);

        float[] p0 = xf(-0.30f, 0.08f + LOCK_DY, px, py, cs, sn, dy);
        float[] p1 = xf(-0.30f, -0.24f + LOCK_DY, px, py, cs, sn, dy);
        float[] c1 = xf(-0.30f, -0.64f + LOCK_DY, px, py, cs, sn, dy);
        float[] c2 = xf(0.30f, -0.64f + LOCK_DY, px, py, cs, sn, dy);
        float[] p2 = xf(0.30f, -0.24f + LOCK_DY, px, py, cs, sn, dy);
        float[] p3 = xf(0.30f, 0.08f + LOCK_DY, px, py, cs, sn, dy);

        s.moveTo(p0[0], p0[1]);
        s.lineTo(p1[0], p1[1]);
        s.cubicTo(c1[0], c1[1], c2[0], c2[1], p2[0], p2[1]);
        s.lineTo(p3[0], p3[1]);
    }

    /** علامة صح بقص تدريجي progress ∈ [0,1] (لواجهة التسجيل). */
    public static void check(PathSink s, float progress) {
        float p = clamp(progress, 0f, 1f);
        final float ax = -0.46f, ay = 0.04f;
        final float bx = -0.12f, by = 0.38f;
        final float cx = 0.52f, cy = -0.30f;
        float l1 = (float) Math.hypot(bx - ax, by - ay);
        float l2 = (float) Math.hypot(cx - bx, cy - by);
        float total = l1 + l2;
        float d = p * total;
        if (d <= 0f) return;
        s.moveTo(ax, ay);
        if (d <= l1) {
            float t = d / l1;
            s.lineTo(ax + (bx - ax) * t, ay + (by - ay) * t);
        } else {
            s.lineTo(bx, by);
            float t = (d - l1) / l2;
            s.lineTo(bx + (cx - bx) * t, by + (cy - by) * t);
        }
    }

    public static void circle(PathSink s, float cx, float cy, float r) {
        float k = KAPPA * r;
        s.moveTo(cx + r, cy);
        s.cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        s.cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        s.cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        s.cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        s.close();
    }

    public static void roundRect(PathSink s, float l, float t, float r, float b, float rad) {
        float k = KAPPA * rad;
        s.moveTo(l + rad, t);
        s.lineTo(r - rad, t);
        s.cubicTo(r - rad + k, t, r, t + rad - k, r, t + rad);
        s.lineTo(r, b - rad);
        s.cubicTo(r, b - rad + k, r - rad + k, b, r - rad, b);
        s.lineTo(l + rad, b);
        s.cubicTo(l + rad - k, b, l, b - rad + k, l, b - rad);
        s.lineTo(l, t + rad);
        s.cubicTo(l, t + rad - k, l + rad - k, t, l + rad, t);
        s.close();
    }

    private static float[] xf(float x, float y, float px, float py, float cs, float sn, float dy) {
        float rx = x - px;
        float ry = y - py;
        return new float[]{px + rx * cs - ry * sn, py + rx * sn + ry * cs + dy};
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

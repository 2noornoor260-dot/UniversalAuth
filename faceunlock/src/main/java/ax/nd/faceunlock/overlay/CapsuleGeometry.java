package ax.nd.faceunlock.overlay;

/**
 * هندسة الكبسولة في "الإحداثيات المحلية":
 *   u = على امتداد حافة الشاشة (0 عند مركز فتحة الكاميرا)،
 *   v = للداخل من الحافة (0 عند الحافة، موجب نحو الشاشة).
 * يحوّل المُصيِّر هذه الإحداثيات للشاشة بتدوير اللوحة (canvas) حسب الحافة، فيعمل التصميم نفسه
 * على الحواف الأربع بلا تكرار.
 *
 * الحبّة ملتصقة بالحافة وتبدأ {@link #BEZEL_OVERLAP_PX} خلف الإطار (قاعدة التصميم)،
 * وتنمو من حجم فتحة الكاميرا نفسها إلى الحجم الكامل بنابض (p من 0 إلى ~1، وقد يتجاوز 1 قليلاً بالارتداد).
 */
public final class CapsuleGeometry {

    public static final int EDGE_TOP = 0;
    public static final int EDGE_RIGHT = 1;
    public static final int EDGE_BOTTOM = 2;
    public static final int EDGE_LEFT = 3;

    /** تداخل الحبّة خلف الإطار (بكسل) كي لا يظهر خط فاصل مع الحافة. */
    public static final float BEZEL_OVERLAP_PX = 2f;

    public static final float PILL_MIN_DEPTH_DP = 34f;
    public static final float GLYPH_R_DP = 10.5f;
    public static final float SIDE_GAP_DP = 8f;
    public static final float PAD_DP = 11f;
    public static final float MAX_HOLE_LEN_DP = 140f;

    private static final float KAPPA = 0.5522847498f;

    public static final class Layout {
        public float halfW;
        public float depth;
        public float radius;
        public float glyphU;
        public float glyphV;
        public float glyphR;
        public float dotsU;
        public float contentAlpha;
        /** شفافية الحبّة نفسها: تظهر تدريجياً من فتحة الكاميرا كي لا "تقفز" عند البداية. */
        public float pillAlpha;
    }

    public static final class Anchor {
        public final int edge;
        /** مركز الفتحة على امتداد الحافة (x للأعلى/الأسفل، y لليمين/اليسار) بالبكسل. */
        public final float along;
        public final float holeLen;
        public final float holeDepth;
        public final boolean fromCutout;

        public Anchor(int edge, float along, float holeLen, float holeDepth, boolean fromCutout) {
            this.edge = edge;
            this.along = along;
            this.holeLen = holeLen;
            this.holeDepth = holeDepth;
            this.fromCutout = fromCutout;
        }
    }

    private CapsuleGeometry() {
    }

    public static void layout(Layout o, float p, float holeLenPx, float holeDepthPx, float density) {
        float dp = density;
        float holeHalf = Math.max(Math.min(holeLenPx, MAX_HOLE_LEN_DP * dp), 10f * dp) * 0.5f;
        float gR = GLYPH_R_DP * dp;
        float fullHalfW = holeHalf + (SIDE_GAP_DP + PAD_DP) * dp + 2f * gR;
        float fullDepth = Math.max(PILL_MIN_DEPTH_DP * dp, holeDepthPx + 6f * dp);
        float startHalfW = holeHalf + 3f * dp;
        // فتحة القطع (Cutout) تشمل هامشاً أكبر من الثقب الفعلي، فنبدأ بنحو 60% منها
        float startDepth = Math.max(holeDepthPx * 0.6f, 10f * dp);

        float e = p < 0f ? 0f : p;
        o.halfW = lerp(startHalfW, fullHalfW, e);
        o.depth = lerp(startDepth, fullDepth, e);
        // زوايا سفلية مستديرة بنصف قطر ~62% من العمق: "لسان" أنيق ملتصق بالحافة لا وعاء مفرط الاستدارة
        o.radius = Math.min(o.depth * 0.62f, o.halfW * 0.98f);

        float side = holeHalf + SIDE_GAP_DP * dp + gR;
        o.glyphU = -side;
        o.glyphV = o.depth * 0.5f;
        o.glyphR = gR * (0.55f + 0.45f * clamp01(p));
        o.dotsU = side;
        o.contentAlpha = smoothstep(0.45f, 0.95f, p);
        o.pillAlpha = smoothstep(0.0f, 0.22f, p);
    }

    /** مسار الحبّة المرئية: مسطّحة عند الحافة، وزواياها السفلى مستديرة. */
    public static void pill(PathSink s, Layout o, float bezelPx) {
        float top = -bezelPx;
        float r = o.radius;
        float k = KAPPA * r;
        s.moveTo(-o.halfW, top);
        s.lineTo(-o.halfW, o.depth - r);
        s.cubicTo(-o.halfW, o.depth - r + k, -o.halfW + r - k, o.depth, -o.halfW + r, o.depth);
        s.lineTo(o.halfW - r, o.depth);
        s.cubicTo(o.halfW - r + k, o.depth, o.halfW, o.depth - r + k, o.halfW, o.depth - r);
        s.lineTo(o.halfW, top);
        s.close();
    }

    /** يطابق التعيين القديم للتوافق عند عدم وجود قطع: 0→يمين، 90→أعلى، 180→يسار، 270→أسفل. */
    public static int legacyEdgeForRotation(int surfaceRotation) {
        switch (surfaceRotation) {
            case 1:
                return EDGE_TOP;
            case 2:
                return EDGE_LEFT;
            case 3:
                return EDGE_BOTTOM;
            default:
                return EDGE_RIGHT;
        }
    }

    /** "top"/"right"/"bottom"/"left" => الحافة، وغير ذلك (auto) => autoEdge. */
    public static int parseEdge(String mode, int autoEdge) {
        if (mode == null) return autoEdge;
        switch (mode) {
            case "top":
                return EDGE_TOP;
            case "right":
                return EDGE_RIGHT;
            case "bottom":
                return EDGE_BOTTOM;
            case "left":
                return EDGE_LEFT;
            default:
                return autoEdge;
        }
    }

    /**
     * يختار فتحة الكاميرا من قائمة مستطيلات القطع [left,top,right,bottom]: أصغر مستطيل ملاصق لحافة.
     *
     * @return null إذا لم يوجد قطع ملاصق لحافة
     */
    public static Anchor fromCutouts(int[][] rects, int w, int h) {
        if (rects == null || w <= 0 || h <= 0) return null;
        float tol = Math.max(3f, 0.015f * Math.min(w, h));
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            int[] r = rects[i];
            if (r == null || r.length < 4) continue;
            long rw = r[2] - r[0];
            long rh = r[3] - r[1];
            if (rw <= 0 || rh <= 0) continue;
            float dTop = r[1];
            float dBottom = h - r[3];
            float dLeft = r[0];
            float dRight = w - r[2];
            float m = Math.min(Math.min(dTop, dBottom), Math.min(dLeft, dRight));
            if (m > tol) continue;
            long area = rw * rh;
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        if (best < 0) return null;

        int[] r = rects[best];
        float dTop = r[1];
        float dBottom = h - r[3];
        float dLeft = r[0];
        float dRight = w - r[2];
        float m = Math.min(Math.min(dTop, dBottom), Math.min(dLeft, dRight));
        if (m == dTop) {
            return new Anchor(EDGE_TOP, (r[0] + r[2]) * 0.5f, r[2] - r[0], r[3], true);
        }
        if (m == dBottom) {
            return new Anchor(EDGE_BOTTOM, (r[0] + r[2]) * 0.5f, r[2] - r[0], h - r[1], true);
        }
        if (m == dLeft) {
            return new Anchor(EDGE_LEFT, (r[1] + r[3]) * 0.5f, r[3] - r[1], r[2], true);
        }
        return new Anchor(EDGE_RIGHT, (r[1] + r[3]) * 0.5f, r[3] - r[1], w - r[0], true);
    }

    /** بلا قطع: الحبّة معلّقة من منتصف الحافة المختارة. */
    public static Anchor centered(int edge, int w, int h) {
        boolean horizontal = (edge == EDGE_TOP || edge == EDGE_BOTTOM);
        return new Anchor(edge, horizontal ? w * 0.5f : h * 0.5f, 0f, 0f, false);
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }
}

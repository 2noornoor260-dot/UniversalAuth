package ax.nd.faceunlock.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class GlyphPathsTest {

    /** يسجّل كل النقاط (بما فيها نقاط التحكم) لفحص الحدود. */
    static final class Rec implements PathSink {
        final List<float[]> pts = new ArrayList<float[]>();

        public void moveTo(float x, float y) { pts.add(new float[]{x, y}); }
        public void lineTo(float x, float y) { pts.add(new float[]{x, y}); }
        public void quadTo(float cx, float cy, float x, float y) { pts.add(new float[]{cx, cy}); pts.add(new float[]{x, y}); }
        public void cubicTo(float a, float b, float c, float d, float x, float y) { pts.add(new float[]{a, b}); pts.add(new float[]{c, d}); pts.add(new float[]{x, y}); }
        public void close() { }
    }

    /** يأخذ عيّنات من المنحنى المرسوم فعلاً (مكعّب/تربيعي) بدل نقاط التحكم التي تقع خارجه. */
    static final class Sampler implements PathSink {
        final List<float[]> pts = new ArrayList<float[]>();
        private float cx, cy;

        public void moveTo(float x, float y) { cx = x; cy = y; pts.add(new float[]{x, y}); }
        public void lineTo(float x, float y) { cx = x; cy = y; pts.add(new float[]{x, y}); }
        public void quadTo(float qx, float qy, float x, float y) {
            for (int i = 1; i <= 12; i++) {
                float t = i / 12f, u = 1 - t;
                pts.add(new float[]{u * u * cx + 2 * u * t * qx + t * t * x, u * u * cy + 2 * u * t * qy + t * t * y});
            }
            cx = x; cy = y;
        }
        public void cubicTo(float a, float b, float c, float d, float x, float y) {
            for (int i = 1; i <= 12; i++) {
                float t = i / 12f, u = 1 - t;
                float px = u * u * u * cx + 3 * u * u * t * a + 3 * u * t * t * c + t * t * t * x;
                float py = u * u * u * cy + 3 * u * u * t * b + 3 * u * t * t * d + t * t * t * y;
                pts.add(new float[]{px, py});
            }
            cx = x; cy = y;
        }
        public void close() { }
    }

    private static void assertInside(Rec r, float lim, String what) {
        assertInside(r.pts, lim, what);
    }

    private static void assertInside(Sampler r, float lim, String what) {
        assertInside(r.pts, lim, what);
    }

    private static void assertInside(List<float[]> pts, float lim, String what) {
        for (float[] p : pts) {
            assertTrue(what + " x out of range: " + p[0], Math.abs(p[0]) <= lim);
            assertTrue(what + " y out of range: " + p[1], Math.abs(p[1]) <= lim);
        }
    }

    @Test
    public void everyGlyphStaysInsideTheUnitBox() {
        for (float inset = 0f; inset <= 0.4f; inset += 0.05f) {
            Rec r = new Rec();
            GlyphPaths.brackets(r, inset);
            assertInside(r, 1.0f, "brackets");
        }
        for (float b = 0f; b <= 1f; b += 0.25f) {
            Rec r = new Rec();
            GlyphPaths.eyes(r, b);
            assertInside(r, 1.0f, "eyes");
        }
        Rec n = new Rec(); GlyphPaths.nose(n); assertInside(n, 1.0f, "nose");
        Rec m = new Rec(); GlyphPaths.mouth(m); assertInside(m, 1.0f, "mouth");
        Rec b = new Rec(); GlyphPaths.lockBody(b); assertInside(b, 1.0f, "body");
        // المدى العملي لحركات التطبيق: رفع حتى 0.5 وتأرجح حتى 30° (الرفض 0.28، الفتح ≈0.35 و28°)
        for (float lift = 0f; lift <= 0.5001f; lift += 0.1f) {
            for (float sw = 0f; sw <= 30f; sw += 6f) {
                Sampler s = new Sampler();
                GlyphPaths.lockShackle(s, lift, sw);
                assertInside(s, 1.2f, "shackle lift=" + lift + " swing=" + sw);
            }
        }
    }

    @Test
    public void closedShackleStartsAtItsPivot() {
        Rec s = new Rec();
        GlyphPaths.lockShackle(s, 0f, 0f);
        assertEquals(-0.30, s.pts.get(0)[0], 1e-6);
        assertEquals(0.08 + GlyphPaths.LOCK_DY, s.pts.get(0)[1], 1e-6);
    }

    @Test
    public void swingKeepsLeftLegAnchored() {
        Rec closed = new Rec();
        Rec open = new Rec();
        GlyphPaths.lockShackle(closed, 0f, 0f);
        GlyphPaths.lockShackle(open, 0f, 28f);
        // نقطة البداية (قاعدة الساق اليسرى) هي المحور فلا تتحرك
        assertEquals(closed.pts.get(0)[0], open.pts.get(0)[0], 1e-6);
        assertEquals(closed.pts.get(0)[1], open.pts.get(0)[1], 1e-6);
        // أما الساق اليمنى (آخر نقطة) فترتفع عند الفتح
        float[] cEnd = closed.pts.get(closed.pts.size() - 1);
        float[] oEnd = open.pts.get(open.pts.size() - 1);
        assertTrue("right leg must rise when open", oEnd[1] < cEnd[1] - 0.1f);
    }

    @Test
    public void rejectLiftIs28PercentOfShackleHeight() {
        Rec closed = new Rec();
        Rec lifted = new Rec();
        GlyphPaths.lockShackle(closed, 0f, 0f);
        GlyphPaths.lockShackle(lifted, 0.28f, 0f);
        float dy = closed.pts.get(0)[1] - lifted.pts.get(0)[1];
        assertEquals(0.28 * GlyphPaths.SHACKLE_HEIGHT, dy, 1e-5);
    }

    @Test
    public void checkMarkTrimsProgressively() {
        Rec none = new Rec();
        GlyphPaths.check(none, 0f);
        assertEquals(0, none.pts.size());
        float prevLen = 0f;
        for (float p = 0.1f; p <= 1.0001f; p += 0.1f) {
            Rec r = new Rec();
            GlyphPaths.check(r, p);
            float len = 0f;
            for (int i = 1; i < r.pts.size(); i++) {
                len += (float) Math.hypot(r.pts.get(i)[0] - r.pts.get(i - 1)[0], r.pts.get(i)[1] - r.pts.get(i - 1)[1]);
            }
            assertTrue("length must grow with progress", len > prevLen);
            prevLen = len;
        }
        Rec full = new Rec();
        GlyphPaths.check(full, 1f);
        float[] last = full.pts.get(full.pts.size() - 1);
        assertEquals(0.52, last[0], 1e-5);
        assertEquals(-0.30, last[1], 1e-5);
    }
}

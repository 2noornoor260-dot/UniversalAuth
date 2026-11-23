package ax.nd.faceunlock.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CapsuleGeometryTest {

    private static final float D = 2.75f; // كثافة شاشة نموذجية

    @Test
    public void fullLayoutContainsGlyphAndIndicatorInsidePill() {
        CapsuleGeometry.Layout l = new CapsuleGeometry.Layout();
        CapsuleGeometry.layout(l, 1f, 14f * D, 36f * D, D);
        float margin = 3f * D;
        assertTrue("glyph inside horizontally", Math.abs(l.glyphU) + l.glyphR <= l.halfW - margin);
        assertTrue("indicator inside horizontally", Math.abs(l.dotsU) + l.glyphR <= l.halfW - margin);
        assertTrue("glyph inside vertically (bottom)", l.glyphV + l.glyphR <= l.depth - margin);
        assertTrue("glyph inside vertically (top)", l.glyphV - l.glyphR >= margin);
        assertTrue("compact: total width under 130dp", l.halfW * 2f <= 130f * D);
        assertEquals(1.0, l.contentAlpha, 1e-6);
    }

    @Test
    public void growsFromTheCameraHole() {
        CapsuleGeometry.Layout l0 = new CapsuleGeometry.Layout();
        CapsuleGeometry.Layout l1 = new CapsuleGeometry.Layout();
        CapsuleGeometry.layout(l0, 0f, 14f * D, 36f * D, D);
        CapsuleGeometry.layout(l1, 1f, 14f * D, 36f * D, D);
        assertTrue("starts about as wide as the hole", l0.halfW <= 14f * D);
        assertTrue(l1.halfW > l0.halfW * 3f);
        assertEquals(0.0, l0.contentAlpha, 1e-6);
    }

    @Test
    public void overshootAndNegativeProgressAreSafe() {
        CapsuleGeometry.Layout l = new CapsuleGeometry.Layout();
        for (float p = -0.2f; p <= 1.3f; p += 0.1f) {
            CapsuleGeometry.layout(l, p, 0f, 0f, D); // بلا قطع
            assertTrue(l.halfW > 0 && l.depth > 0 && l.radius > 0);
            assertTrue(!Float.isNaN(l.glyphU) && !Float.isNaN(l.contentAlpha));
            assertTrue(l.contentAlpha >= 0f && l.contentAlpha <= 1f);
        }
    }

    @Test
    public void giantNotchIsClampedSoPillStaysCompact() {
        CapsuleGeometry.Layout l = new CapsuleGeometry.Layout();
        CapsuleGeometry.layout(l, 1f, 900f * D, 30f * D, D);
        // عرض الحبّة = فتحة مقيّدة (140dp) + 2×(فجوة 8 + أيقونة 21 + حشوة 11) = 220dp كحدّ أقصى، لا 900dp
        assertTrue(l.halfW * 2f <= (CapsuleGeometry.MAX_HOLE_LEN_DP + 81f) * D);
    }

    @Test
    public void cutoutOnEachEdgeIsRecognized() {
        // ثقب في الأعلى (بكسل-بيكسل 6)
        CapsuleGeometry.Anchor top = CapsuleGeometry.fromCutouts(new int[][]{{472, 0, 608, 136}}, 1080, 2400);
        assertNotNull(top);
        assertEquals(CapsuleGeometry.EDGE_TOP, top.edge);
        assertEquals(540.0, top.along, 1e-3);
        assertEquals(136.0, top.holeLen, 1e-3);
        assertEquals(136.0, top.holeDepth, 1e-3);
        // أفقي: الكاميرا على اليسار
        CapsuleGeometry.Anchor left = CapsuleGeometry.fromCutouts(new int[][]{{0, 500, 136, 636}}, 2400, 1080);
        assertEquals(CapsuleGeometry.EDGE_LEFT, left.edge);
        assertEquals(568.0, left.along, 1e-3);
        // على اليمين
        CapsuleGeometry.Anchor right = CapsuleGeometry.fromCutouts(new int[][]{{2264, 440, 2400, 576}}, 2400, 1080);
        assertEquals(CapsuleGeometry.EDGE_RIGHT, right.edge);
        assertEquals(136.0, right.holeDepth, 1e-3);
        // في الأسفل (دوران 180)
        CapsuleGeometry.Anchor bottom = CapsuleGeometry.fromCutouts(new int[][]{{472, 2264, 608, 2400}}, 1080, 2400);
        assertEquals(CapsuleGeometry.EDGE_BOTTOM, bottom.edge);
        assertEquals(136.0, bottom.holeDepth, 1e-3);
    }

    @Test
    public void picksSmallestAttachedCutoutAndIgnoresFloatingOnes() {
        int[][] rects = {{0, 0, 1080, 90}, {472, 0, 608, 136}, {300, 1000, 400, 1100}};
        CapsuleGeometry.Anchor a = CapsuleGeometry.fromCutouts(rects, 1080, 2400);
        assertNotNull(a);
        assertEquals(136.0, a.holeLen, 1e-3); // الأصغر مساحةً (136×136) بدل شريط 1080×90
        assertNull(CapsuleGeometry.fromCutouts(new int[][]{{300, 1000, 400, 1100}}, 1080, 2400));
        assertNull(CapsuleGeometry.fromCutouts(null, 1080, 2400));
        assertNull(CapsuleGeometry.fromCutouts(new int[][]{}, 1080, 2400));
    }

    @Test
    public void legacyMappingAndParsing() {
        assertEquals(CapsuleGeometry.EDGE_RIGHT, CapsuleGeometry.legacyEdgeForRotation(0));
        assertEquals(CapsuleGeometry.EDGE_TOP, CapsuleGeometry.legacyEdgeForRotation(1));
        assertEquals(CapsuleGeometry.EDGE_LEFT, CapsuleGeometry.legacyEdgeForRotation(2));
        assertEquals(CapsuleGeometry.EDGE_BOTTOM, CapsuleGeometry.legacyEdgeForRotation(3));
        assertEquals(CapsuleGeometry.EDGE_TOP, CapsuleGeometry.parseEdge("top", 9));
        assertEquals(CapsuleGeometry.EDGE_LEFT, CapsuleGeometry.parseEdge("left", 9));
        assertEquals(9, CapsuleGeometry.parseEdge("auto", 9));
        assertEquals(9, CapsuleGeometry.parseEdge(null, 9));
    }

    @Test
    public void pillIsFlushWithBezelAndRoundedAtTheBottom() {
        CapsuleGeometry.Layout l = new CapsuleGeometry.Layout();
        CapsuleGeometry.layout(l, 1f, 14f * D, 36f * D, D);
        final List<float[]> pts = new ArrayList<float[]>();
        CapsuleGeometry.pill(new PathSink() {
            public void moveTo(float x, float y) { pts.add(new float[]{x, y}); }
            public void lineTo(float x, float y) { pts.add(new float[]{x, y}); }
            public void quadTo(float a, float b, float x, float y) { pts.add(new float[]{x, y}); }
            public void cubicTo(float a, float b, float c, float d, float x, float y) { pts.add(new float[]{x, y}); }
            public void close() { }
        }, l, CapsuleGeometry.BEZEL_OVERLAP_PX);
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : pts) { minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
        assertEquals(-CapsuleGeometry.BEZEL_OVERLAP_PX, minY, 1e-4);
        assertEquals(l.depth, maxY, 1e-3);
    }
}

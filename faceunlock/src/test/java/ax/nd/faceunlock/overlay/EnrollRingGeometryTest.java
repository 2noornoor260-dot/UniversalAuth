package ax.nd.faceunlock.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EnrollRingGeometryTest {

    @Test
    public void segmentsCoverTheirSides() {
        // يمين: حول 0°، يسار: حول 180°، أعلى: حول 270°، أسفل: حول 90°
        float[] centers = {0f, 180f, 270f, 90f};
        for (int s = 0; s < 4; s++) {
            float first = EnrollRingGeometry.tickAngleDeg(s, 0);
            float last = EnrollRingGeometry.tickAngleDeg(s, EnrollRingGeometry.TICKS_PER_STAGE - 1);
            assertEquals(centers[s], (first + last) * 0.5, 1e-3);
            assertTrue("sweep stays within 72°", last - first < EnrollRingGeometry.SWEEP_DEG);
            float prev = -1e9f;
            for (int t = 0; t < EnrollRingGeometry.TICKS_PER_STAGE; t++) {
                float a = EnrollRingGeometry.tickAngleDeg(s, t);
                assertTrue("angles increase clockwise", a > prev);
                prev = a;
            }
        }
    }

    @Test
    public void segmentsDoNotOverlap() {
        // أقرب علامتين من قطاعين متجاورين تفصلهما فجوة موجبة (القطاعات 72° والمراكز متباعدة 90°)
        float rightEnd = EnrollRingGeometry.tickAngleDeg(0, EnrollRingGeometry.TICKS_PER_STAGE - 1);
        float bottomStart = EnrollRingGeometry.tickAngleDeg(3, 0);
        assertTrue(bottomStart - rightEnd > 10f);
    }

    @Test
    public void distanceFromCenterIsSymmetric() {
        int n = EnrollRingGeometry.TICKS_PER_STAGE;
        for (int t = 0; t < n; t++) {
            assertEquals(EnrollRingGeometry.distanceFromCenter(t), EnrollRingGeometry.distanceFromCenter(n - 1 - t), 1e-6);
        }
        assertEquals(0.0, EnrollRingGeometry.distanceFromCenter(n / 2), 1e-6);
        assertTrue(EnrollRingGeometry.distanceFromCenter(0) < 1.0f);
    }

    @Test
    public void headProgressMatchesEngineThresholds() {
        // المرحلة 0: أي اتجاه أفقي حتى 12°
        assertEquals(0.5, EnrollRingGeometry.headProgress(0, 6f, 0f, 1f), 1e-6);
        assertEquals(0.5, EnrollRingGeometry.headProgress(0, -6f, 0f, 1f), 1e-6);
        assertEquals(1.0, EnrollRingGeometry.headProgress(0, 30f, 0f, 1f), 1e-6);
        // المرحلة 1: الاتجاه المعاكس لما سُجّل في الأولى
        assertEquals(1.0, EnrollRingGeometry.headProgress(1, -15f, 0f, 1f), 1e-6);
        assertEquals(0.0, EnrollRingGeometry.headProgress(1, 15f, 0f, 1f), 1e-6);
        assertEquals(1.0, EnrollRingGeometry.headProgress(1, 15f, 0f, -1f), 1e-6);
        // أعلى/أسفل
        assertEquals(1.0, EnrollRingGeometry.headProgress(2, 0f, 20f, 1f), 1e-6);
        assertEquals(0.0, EnrollRingGeometry.headProgress(2, 0f, -20f, 1f), 1e-6);
        assertEquals(1.0, EnrollRingGeometry.headProgress(3, 0f, -20f, 1f), 1e-6);
        assertEquals(1.0, EnrollRingGeometry.headProgress(4, 0f, 0f, 1f), 1e-6);
    }

    @Test
    public void tickEndsAreRadial() {
        float[] e = new float[4];
        for (int s = 0; s < 4; s++) {
            for (int t = 0; t < EnrollRingGeometry.TICKS_PER_STAGE; t++) {
                EnrollRingGeometry.tickEnds(s, t, 100f, 12f, e);
                assertEquals(100.0, Math.hypot(e[0], e[1]), 1e-3);
                assertEquals(88.0, Math.hypot(e[2], e[3]), 1e-3);
            }
        }
    }
}

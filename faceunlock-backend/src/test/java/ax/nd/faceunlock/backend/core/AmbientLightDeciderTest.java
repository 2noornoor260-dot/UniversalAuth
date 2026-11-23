package ax.nd.faceunlock.backend.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmbientLightDeciderTest {

    /** يحاكي جلسة: إطار كاميرا كل frameMs، وفحص دوري كل tickMs. يرجع زمن القفل أو -1. */
    private static long run(AmbientLightDecider d, float[] lumaSeq, float steadyLuma, float faceLuma,
                            long frameMs, long tickMs, long totalMs, float lux, long luxAtMs) {
        d.begin(0);
        long latchedAt = -1;
        long nextFrame = 0;
        int frame = 0;
        boolean luxSent = false;
        for (long t = 0; t <= totalMs; t += tickMs) {
            if (!luxSent && lux >= 0 && t >= luxAtMs) {
                d.onLux(t, lux);
                luxSent = true;
            }
            if (t >= nextFrame) {
                float l = frame < lumaSeq.length ? lumaSeq[frame] : steadyLuma;
                d.onCameraFrame(t, l, faceLuma);
                frame++;
                nextFrame += frameMs;
            }
            if (d.update(t) && latchedAt < 0) latchedAt = t;
        }
        return latchedAt;
    }

    private static final float[] WARMUP_BRIGHT = {6, 14, 30, 55, 85, 110, 118};
    private static final float[] WARMUP_DARK = {3, 5, 9, 14, 17};

    @Test
    public void brightRoomWithExposureWarmupNeverLatches_withSensor() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        assertEquals(-1, run(d, WARMUP_BRIGHT, 120, -1, 100, 40, 4000, 300, 60));
    }

    @Test
    public void brightRoomWithExposureWarmupNeverLatches_noSensor() {
        AmbientLightDecider d = new AmbientLightDecider(false);
        assertEquals(-1, run(d, WARMUP_BRIGHT, 120, -1, 100, 40, 4000, -1, 0));
    }

    @Test
    public void brightRoomWhenSensorIsSilentNeverLatches() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        assertEquals(-1, run(d, WARMUP_BRIGHT, 120, -1, 100, 40, 4000, -1, 0));
    }

    @Test
    public void darkRoomLatchesAfterSettleAndHold() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        long t = run(d, WARMUP_DARK, 18, -1, 100, 40, 4000, 1.2f, 80);
        assertTrue("must latch, got " + t, t > 0);
        assertTrue("not before settle+hold (>=800), got " + t, t >= 800);
        assertTrue("but promptly (<=1500), got " + t, t <= 1500);
    }

    @Test
    public void dimRoomOver30LuxNeverLatches() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        assertEquals(-1, run(d, WARMUP_DARK, 28, -1, 100, 40, 4000, 45, 80));
    }

    @Test
    public void ambiguousLuxNeedsDarkerCamera() {
        AmbientLightDecider a = new AmbientLightDecider(true);
        assertEquals(-1, run(a, WARMUP_DARK, 33, -1, 100, 40, 4000, 20, 80));
        AmbientLightDecider b = new AmbientLightDecider(true);
        assertTrue(run(b, WARMUP_DARK, 20, -1, 100, 40, 4000, 20, 80) > 0);
    }

    @Test
    public void silentSensorFallsBackToCameraAfterGrace() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        long t = run(d, WARMUP_DARK, 18, -1, 100, 40, 4000, -1, 0);
        assertTrue("must latch via camera-only, got " + t, t > 0);
        assertTrue("not before sensor grace, got " + t, t >= AmbientLightDecider.SENSOR_GRACE_MS);
    }

    @Test
    public void coveredCameraNeverLatches() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        assertEquals(-1, run(d, new float[]{1, 1, 1}, 1.5f, -1, 100, 40, 4000, 0f, 80));
    }

    @Test
    public void brightFaceVetoesEvenIfFrameIsDark() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        assertEquals(-1, run(d, WARMUP_DARK, 25, 95, 100, 40, 4000, 5f, 80));
    }

    @Test
    public void latchIsPermanentUntilNextSession() {
        AmbientLightDecider d = new AmbientLightDecider(true);
        assertTrue(run(d, WARMUP_DARK, 18, -1, 100, 40, 3000, 1f, 80) > 0);
        // النور يعود لكن القفل يبقى (قاعدة: لا إطفاء أثناء المسح)
        d.onLux(5000, 500f);
        d.onCameraFrame(5000, 200f, 200f);
        assertTrue(d.update(5100));
        d.begin(9000);
        assertFalse(d.isLatched());
    }

    @Test
    public void regression_twoStaleTicksOnOneDarkFrameDoNotLatch() {
        // الخلل القديم: إطار أول معتم + تيكان متتاليان على نفس القيمة => قفل مبكر
        AmbientLightDecider d = new AmbientLightDecider(false);
        d.begin(0);
        d.onCameraFrame(0, 10f, -1f);
        for (long t = 0; t <= 1000; t += 160) assertFalse(d.update(t));
    }

    @Test
    public void staleFramesBlockDecision() {
        AmbientLightDecider d = new AmbientLightDecider(false);
        long t = run(d, WARMUP_DARK, 100, -1, 100, 40, 1200, -1, 0); // كاميرا مضيئة
        assertEquals(-1, t);
        assertFalse(d.update(1200 + 2500));
        assertEquals("stale-frames", d.reason());
    }
}

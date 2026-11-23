package ax.nd.faceunlock.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpringTest {

    private static float simulate(Spring s, float hz, float seconds) {
        int n = Math.round(hz * seconds);
        for (int i = 0; i < n; i++) s.step(1f / hz);
        return s.get();
    }

    @Test
    public void criticalSpringSettlesWithoutOvershoot() {
        Spring s = new Spring(0.35f, 1.0f, 0f);
        s.setTarget(1f);
        float max = 0f;
        for (int i = 0; i < 120; i++) {
            s.step(1f / 60f);
            max = Math.max(max, s.get());
        }
        assertTrue("no overshoot, max=" + max, max <= 1.001f);
        assertTrue("settled, v=" + s.get(), Math.abs(s.get() - 1f) < 0.01f);
        assertTrue(s.isAtRest());
    }

    @Test
    public void underdampedSpringOvershootsABit() {
        Spring s = new Spring(0.35f, 0.6f, 0f);
        s.setTarget(1f);
        float max = 0f;
        for (int i = 0; i < 180; i++) {
            s.step(1f / 60f);
            max = Math.max(max, s.get());
        }
        assertTrue("overshoot between 3% and 15%, max=" + max, max > 1.03f && max < 1.15f);
        assertTrue(s.isAtRest());
    }

    @Test
    public void frameRateIndependent() {
        float[] v = new float[4];
        float[] hz = {30f, 60f, 120f, 240f};
        for (int i = 0; i < 4; i++) {
            Spring s = new Spring(0.4f, 0.8f, 0f);
            s.setTarget(1f);
            v[i] = simulate(s, hz[i], 0.25f);
        }
        for (int i = 1; i < 4; i++) assertEquals(v[0], v[i], 0.02);
    }

    @Test
    public void stableWithHugeOrOddTimeSteps() {
        Spring s = new Spring(0.2f, 0.7f, 0f);
        s.setTarget(1f);
        s.step(5f);          // يُقصّ داخلياً
        s.step(-1f);         // يُتجاهل
        s.step(Float.NaN);   // يُتجاهل
        assertFalse(Float.isNaN(s.get()) || Float.isInfinite(s.get()));
        for (int i = 0; i < 100; i++) s.step(0.05f);
        assertEquals(1.0, s.get(), 0.01);
    }

    @Test
    public void kickAndSnap() {
        Spring s = new Spring(0.3f, 0.8f, 0f);
        s.kick(3f);
        s.step(1f / 60f);
        assertTrue(s.get() > 0f);
        s.snapTo(0.5f);
        assertEquals(0.5, s.get(), 0);
        assertEquals(0.0, s.velocity(), 0);
        assertTrue(s.isAtRest());
    }
}

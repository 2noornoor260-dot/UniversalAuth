package ax.nd.faceunlock.backend.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class VerifySessionTest {

    private static final int DIM = 192;

    /** بصمة قريبة من الأساس (تشابه ~0.95-0.99) ومطبَّعة. */
    private static float[] near(float[] base, long seed, float noise) {
        Random r = new Random(seed);
        float[] v = new float[base.length];
        double n = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] = base[i] + noise * (float) r.nextGaussian();
            n += (double) v[i] * v[i];
        }
        float inv = (float) (1.0 / Math.sqrt(n));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }

    private static float[] base() {
        return unit(new Random(42), DIM);
    }

    private static float[] unit(Random r, int dim) {
        float[] v = new float[dim];
        double n = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) r.nextGaussian();
            n += (double) v[i] * v[i];
        }
        float inv = (float) (1.0 / Math.sqrt(n));
        for (int i = 0; i < dim; i++) v[i] *= inv;
        return v;
    }

    /** يحاكي مسحاً: كل إطار يصل ويكون "جيداً". يرجع عدد الإطارات حتى القبول أو -1. */
    private static int framesToAccept(VerifySession s, SecurityProfile p, long t0, long stepMs, int maxFrames) {
        float[] b = base();
        s.begin(t0);
        for (int f = 0; f < maxFrames; f++) {
            long t = t0 + f * stepMs;
            s.onFrame(t);
            s.onFaceSeen(t);
            float nose = 0.040f + 0.0007f * f;
            VerifySession.Outcome o = s.onGoodFrame(t, near(b, 100 + f, 0.01f), nose, p);
            if (o == VerifySession.Outcome.ACCEPT) return f + 1;
        }
        return -1;
    }

    @Test
    public void freshSessionAcceptsLiveUser_noDeadlock() {
        VerifySession s = new VerifySession();
        // 120ms بين الإطارات كما في الجهاز الفعلي تقريباً. النسخة القديمة كانت تعطي -1 دائماً.
        int n = framesToAccept(s, SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED), 1_760_000_000_000L, 120, 40);
        assertTrue("must accept, got " + n, n > 0);
        assertTrue("should accept within 6 frames, got " + n, n <= 6);
    }

    @Test
    public void staleStateFromPreviousScanDoesNotLeak() {
        VerifySession s = new VerifySession();
        SecurityProfile p = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        long t = 1_760_000_000_000L;
        int first = framesToAccept(s, p, t, 120, 40);
        s.onAccepted();
        // بعد ساعة كاملة: جلسة جديدة تتصرف بالتطابق نفسه (هذا بالضبط السيناريو الذي كان ينهار)
        int second = framesToAccept(s, p, t + 3_600_000L, 120, 40);
        assertEquals(first, second);
        // وجلسة أخرى بفاصل قصير جداً
        int third = framesToAccept(s, p, t + 3_600_500L, 120, 40);
        assertEquals(first, third);
    }

    @Test
    public void warmupFramesAreNeverCounted() {
        VerifySession s = new VerifySession();
        SecurityProfile p = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        float[] b = base();
        s.begin(0);
        s.onFrame(0);
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(0, near(b, 1, 0.01f), 0.04f, p));
        assertEquals(0, s.goodStreak());
        s.onFrame(100);
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(100, near(b, 2, 0.01f), 0.041f, p));
        assertEquals(0, s.goodStreak());
        s.onFrame(200); // الإطار الثالث => مستقر
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(200, near(b, 3, 0.01f), 0.042f, p));
        assertEquals(1, s.goodStreak());
        s.onFrame(300);
        assertEquals(VerifySession.Outcome.ACCEPT, s.onGoodFrame(300, near(b, 4, 0.01f), 0.043f, p));
    }

    @Test
    public void longGapRestartsStreak() {
        VerifySession s = new VerifySession();
        SecurityProfile p = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        float[] b = base();
        s.begin(0);
        for (int i = 0; i < 4; i++) s.onFrame(i * 100L);
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(300, near(b, 1, 0.01f), 0.04f, p));
        assertEquals(1, s.goodStreak());
        s.onFrame(300 + p.maxFrameGapMs + 100);
        // بعد فجوة أطول من المسموح: لا قبول، تبدأ سلسلة جديدة
        assertEquals(VerifySession.Outcome.NEED_MORE,
                s.onGoodFrame(300 + p.maxFrameGapMs + 100, near(b, 2, 0.01f), 0.041f, p));
        assertEquals(1, s.goodStreak());
    }

    @Test
    public void identicalFramesAreRejectedAsStatic() {
        VerifySession s = new VerifySession();
        SecurityProfile p = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        float[] b = base();
        s.begin(0);
        for (int i = 0; i < 4; i++) s.onFrame(i * 100L);
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(300, b, 0.04f, p));
        s.onFrame(400);
        assertEquals(VerifySession.Outcome.REJECT_STATIC, s.onGoodFrame(400, b, 0.04f, p));
        assertEquals(0, s.goodStreak());
    }

    @Test
    public void lowTemporalSimilarityRestartsNotAccepts() {
        VerifySession s = new VerifySession();
        SecurityProfile p = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        s.begin(0);
        for (int i = 0; i < 4; i++) s.onFrame(i * 100L);
        float[] a = unit(new Random(1), DIM);
        float[] c = unit(new Random(2), DIM); // وجه مختلف تماماً (تشابه ~0)
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(300, a, 0.04f, p));
        s.onFrame(400);
        assertEquals(VerifySession.Outcome.NEED_MORE, s.onGoodFrame(400, c, 0.05f, p));
        assertEquals(1, s.goodStreak());
    }

    @Test
    public void rejectionFeedbackFiresOncePerTwoCycles() {
        VerifySession s = new VerifySession();
        s.begin(0);
        int fired = 0;
        long firedAt = -1;
        for (long t = 0; t <= 3500; t += 100) {
            if (s.onHardReject(t)) {
                fired++;
                firedAt = t;
            }
        }
        assertEquals(1, fired);
        assertTrue("fires at second cycle, got " + firedAt, firedAt >= 3300);
    }

    @Test
    public void noFaceAndGoodFrameResetFailureWindow() {
        VerifySession s = new VerifySession();
        SecurityProfile p = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        s.begin(0);
        for (int i = 0; i < 4; i++) s.onFrame(i * 100L);
        assertFalse(s.onHardReject(0));      // يبدأ نافذة الفشل
        s.onNoFace(500);                     // غياب الوجه ليس فشلاً: تُصفَّر النافذة
        assertFalse(s.onHardReject(1700));   // نافذة جديدة تبدأ الآن
        assertFalse(s.onHardReject(3400));   // دورة 1 (فرديّة) => لا إشارة
        assertTrue(s.onHardReject(5100));    // دورة 2 => إشارة
    }

    @Test
    public void maximumLevelNeedsFourGoodFrames() {
        VerifySession s = new VerifySession();
        int n = framesToAccept(s, SecurityProfile.forLevel(SecurityProfile.LEVEL_MAXIMUM), 5_000L, 120, 40);
        int bal = framesToAccept(s, SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED), 5_000L, 120, 40);
        assertTrue("maximum (" + n + ") must need more frames than balanced (" + bal + ")", n > bal);
    }

    @Test
    public void profilesNeverDropBelowBalanced() {
        SecurityProfile bal = SecurityProfile.forLevel(SecurityProfile.LEVEL_BALANCED);
        for (int lvl = -3; lvl < 6; lvl++) {
            SecurityProfile p = SecurityProfile.forLevel(lvl);
            assertTrue(p.matchCombined >= bal.matchCombined);
            assertTrue(p.matchSingleMax >= bal.matchSingleMax);
            assertTrue(p.spoofReal >= bal.spoofReal);
            assertTrue(p.requiredGoodFrames >= bal.requiredGoodFrames);
        }
    }
}

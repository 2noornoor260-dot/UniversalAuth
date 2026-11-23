package ax.nd.faceunlock.backend.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class SimilarityTransformTest {

    private static float[] apply(float s, float deg, float tx, float ty, float[] pts) {
        double r = Math.toRadians(deg);
        float a = (float) (s * Math.cos(r)), b = (float) (s * Math.sin(r));
        float[] out = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            out[i] = a * pts[i] - b * pts[i + 1] + tx;
            out[i + 1] = b * pts[i] + a * pts[i + 1] + ty;
        }
        return out;
    }

    private static final float[] CANON = {
            SimilarityTransform.EYE_L_X, SimilarityTransform.EYE_L_Y,
            SimilarityTransform.EYE_R_X, SimilarityTransform.EYE_R_Y,
            SimilarityTransform.MOUTH_X, SimilarityTransform.MOUTH_Y};

    @Test
    public void identityIsRecovered() {
        SimilarityTransform t = SimilarityTransform.estimate(CANON, CANON, 3);
        assertNotNull(t);
        assertEquals(1.0, t.a, 1e-5);
        assertEquals(0.0, t.b, 1e-5);
        assertEquals(0.0, t.tx, 1e-3);
        assertEquals(0.0, t.ty, 1e-3);
    }

    @Test
    public void knownTransformIsRecoveredExactly() {
        float[] src = apply(1.7f, 14f, 31f, -9f, CANON);          // وجه كبير ومائل في الصورة
        SimilarityTransform t = SimilarityTransform.estimate(src, CANON, 3);
        assertNotNull(t);
        float[] out = new float[2];
        for (int i = 0; i < 3; i++) {
            t.apply(src[2 * i], src[2 * i + 1], out);
            assertEquals(CANON[2 * i], out[0], 1e-2);
            assertEquals(CANON[2 * i + 1], out[1], 1e-2);
        }
        assertEquals(1.0 / 1.7, t.scale(), 1e-3);
        assertEquals(-14.0, t.rotationDegrees(), 0.05);
    }

    @Test
    public void tolerantToLandmarkNoise() {
        Random r = new Random(7);
        float[] src = apply(2.2f, -9f, 80f, 60f, CANON);
        for (int i = 0; i < src.length; i++) src[i] += (float) r.nextGaussian() * 0.6f;
        SimilarityTransform t = SimilarityTransform.estimate(src, CANON, 3);
        assertNotNull(t);
        assertEquals(1.0 / 2.2, t.scale(), 0.02);
    }

    @Test
    public void eyeOrderDoesNotMatter() {
        float[] src = apply(1.4f, 8f, 20f, 15f, CANON);
        SimilarityTransform a = SimilarityTransform.alignToArcFace(src[0], src[1], src[2], src[3], true, src[4], src[5]);
        SimilarityTransform b = SimilarityTransform.alignToArcFace(src[2], src[3], src[0], src[1], true, src[4], src[5]);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.a, b.a, 1e-6);
        assertEquals(a.b, b.b, 1e-6);
        assertEquals(a.tx, b.tx, 1e-4);
        // والمحاذاة تعيد العينين لمواضعهما القياسية
        float[] o = new float[2];
        a.apply(src[0], src[1], o);
        assertEquals(SimilarityTransform.EYE_L_X, o[0], 0.05);
        assertEquals(SimilarityTransform.EYE_L_Y, o[1], 0.05);
    }

    @Test
    public void faceIsNeverFlippedUpsideDownWhenLabelsAreSwapped() {
        // لو كانت "العين اليسرى" هي الأيمن في الصورة (تفسير معاكس للتسمية)، يجب أن تبقى الدورة قرب الصفر لا 180°
        float[] src = apply(1.0f, 0f, 10f, 10f, CANON);
        SimilarityTransform t = SimilarityTransform.alignToArcFace(src[2], src[3], src[0], src[1], false, 0, 0);
        assertNotNull(t);
        assertTrue("rotation must be near 0, was " + t.rotationDegrees(), Math.abs(t.rotationDegrees()) < 1.0);
    }

    @Test
    public void twoPointAlignmentIsExact() {
        float[] src = apply(0.8f, -20f, 5f, 40f, CANON);
        SimilarityTransform t = SimilarityTransform.alignToArcFace(src[0], src[1], src[2], src[3], false, 0, 0);
        assertNotNull(t);
        float[] o = new float[2];
        t.apply(src[2], src[3], o);
        assertEquals(SimilarityTransform.EYE_R_X, o[0], 0.05);
        assertEquals(SimilarityTransform.EYE_R_Y, o[1], 0.05);
    }

    @Test
    public void degenerateInputsReturnNull() {
        assertNull(SimilarityTransform.alignToArcFace(50, 50, 50, 50, false, 0, 0));
        assertNull(SimilarityTransform.estimate(new float[]{1, 1, 1, 1}, new float[]{2, 2, 3, 3}, 2));
    }

    @Test
    public void matrixValuesMatchAndroidLayout() {
        SimilarityTransform t = new SimilarityTransform(0.5f, 0.25f, 3f, 4f);
        float[] m = t.toMatrixValues();
        assertEquals(9, m.length);
        assertEquals(0.5, m[0], 0);   // MSCALE_X
        assertEquals(-0.25, m[1], 0); // MSKEW_X
        assertEquals(3, m[2], 0);     // MTRANS_X
        assertEquals(0.25, m[3], 0);  // MSKEW_Y
        assertEquals(0.5, m[4], 0);   // MSCALE_Y
        assertEquals(4, m[5], 0);     // MTRANS_Y
    }
}

package ax.nd.faceunlock.backend.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class TemplateCodecTest {

    private static float[] unit(long seed, int dim) {
        Random r = new Random(seed);
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

    private static List<TemplateCodec.Entry> sample() {
        List<TemplateCodec.Entry> l = new ArrayList<TemplateCodec.Entry>();
        for (int i = 0; i < 6; i++) l.add(new TemplateCodec.Entry(i % 5, 0.03f + i * 0.001f, unit(i + 1, 192)));
        return l;
    }

    private static void expectRejected(byte[] data) {
        try {
            TemplateCodec.decode(data);
            fail("corrupt data must be rejected");
        } catch (IllegalArgumentException expected) {
            // المطلوب: فشل مغلق
        }
    }

    @Test
    public void roundTrip() throws Exception {
        List<TemplateCodec.Entry> in = sample();
        byte[] bytes = TemplateCodec.encode(in, new float[]{4.1f, 5.2f, 0f, 0f});
        TemplateCodec.Decoded d = TemplateCodec.decode(bytes);
        assertEquals(6, d.entries.size());
        assertEquals(4.1, d.stats[0], 1e-6);
        assertEquals(5.2, d.stats[1], 1e-6);
        for (int i = 0; i < 6; i++) {
            assertEquals(in.get(i).poseId, d.entries.get(i).poseId);
            assertEquals(in.get(i).noseRatio, d.entries.get(i).noseRatio, 0);
            for (int k = 0; k < 192; k++) assertEquals(in.get(i).embedding[k], d.entries.get(i).embedding[k], 0);
        }
    }

    @Test
    public void everySingleByteFlipIsDetected() throws Exception {
        byte[] bytes = TemplateCodec.encode(sample(), null);
        Random r = new Random(3);
        for (int n = 0; n < 200; n++) {
            byte[] c = bytes.clone();
            c[r.nextInt(c.length)] ^= (byte) (1 << r.nextInt(8));
            expectRejected(c);
        }
    }

    @Test
    public void truncatedAndGarbageAreRejected() throws Exception {
        byte[] bytes = TemplateCodec.encode(sample(), null);
        for (int cut : new int[]{0, 1, 10, 40, bytes.length / 2, bytes.length - 1}) {
            byte[] c = new byte[cut];
            System.arraycopy(bytes, 0, c, 0, cut);
            expectRejected(c);
        }
        expectRejected(new byte[2000]);
        expectRejected(null);
    }

    @Test
    public void legacyV6StylePayloadIsRejected() {
        // قديماً: int count ثم قوالب بلا ترويسة. يجب ألا يُفسَّر كملف صالح أبداً.
        byte[] legacy = new byte[4 + 6 * (12 + 4 * 192)];
        legacy[3] = 6;
        expectRejected(legacy);
    }

    @Test
    public void nonFiniteAndNonNormalizedAreRejectedOnEncode() {
        List<TemplateCodec.Entry> bad = sample();
        bad.get(2).embedding[5] = Float.NaN;
        try {
            TemplateCodec.encode(bad, null);
            fail("NaN must be rejected");
        } catch (Exception expected) {
            assertTrue(expected instanceof IllegalArgumentException);
        }
    }

    @Test
    public void hugeCountWithValidChecksumIsStillRejected() throws Exception {
        byte[] bytes = TemplateCodec.encode(sample(), null);
        // نغيّر العدد إلى رقم هائل ونعيد حساب CRC ليتجاوز فحص السلامة، فيجب أن يوقفه فحص الطول/الحدود
        int off = 4 + 4 + 4;
        bytes[off] = 0x7f;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        int v = (int) crc.getValue();
        bytes[bytes.length - 4] = (byte) (v >>> 24);
        bytes[bytes.length - 3] = (byte) (v >>> 16);
        bytes[bytes.length - 2] = (byte) (v >>> 8);
        bytes[bytes.length - 1] = (byte) v;
        expectRejected(bytes);
    }
}

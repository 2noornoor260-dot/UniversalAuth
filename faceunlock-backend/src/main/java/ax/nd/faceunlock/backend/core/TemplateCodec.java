package ax.nd.faceunlock.backend.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * صيغة ملف قوالب الوجه (الإصدار 7) قبل التشفير:
 *
 *   "SFT7" | int version | int dim | int count | float stats[STATS_LEN] | count × (int pose, float nose, float emb[dim]) | int crc32
 *
 * - الصيغة القديمة (v6) لم تكن تحتوي أي تحقق: ملف تالف قد يُنتج عدداً هائلاً فيستنفد الذاكرة أو يُقبل نصفه.
 * - هنا: حدود صارمة (عدد القوالب والبُعد)، تحقق من الطول الدقيق، وCRC32، ورفض أي قيمة غير منتهية أو بصمة غير مطبَّعة.
 * - عند أي خلل يُرفَض الملف كله (فشل مغلق): لا تُقبل قوالب جزئية أبداً.
 */
public final class TemplateCodec {

    public static final int VERSION = 7;
    public static final int MAX_TEMPLATES = 64;
    public static final int MIN_DIM = 64;
    public static final int MAX_DIM = 1024;
    /** [0]=sharpness مرجعية، [1]=chroma std مرجعي، [2..3] محجوزة. */
    public static final int STATS_LEN = 4;

    private static final byte[] MAGIC = {'S', 'F', 'T', '7'};

    public static final class Entry {
        public final int poseId;
        public final float noseRatio;
        public final float[] embedding;

        public Entry(int poseId, float noseRatio, float[] embedding) {
            this.poseId = poseId;
            this.noseRatio = noseRatio;
            this.embedding = embedding;
        }
    }

    public static final class Decoded {
        public final List<Entry> entries;
        public final float[] stats;

        Decoded(List<Entry> entries, float[] stats) {
            this.entries = entries;
            this.stats = stats;
        }
    }

    private TemplateCodec() {
    }

    public static byte[] encode(List<Entry> entries, float[] stats) throws IOException {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_TEMPLATES) {
            throw new IllegalArgumentException("bad template count");
        }
        int dim = entries.get(0).embedding.length;
        if (dim < MIN_DIM || dim > MAX_DIM) throw new IllegalArgumentException("bad embedding dim " + dim);
        float[] st = new float[STATS_LEN];
        if (stats != null) {
            for (int i = 0; i < Math.min(STATS_LEN, stats.length); i++) st[i] = stats[i];
        }
        for (float f : st) requireFinite(f);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + entries.size() * (8 + 4 * dim));
        DataOutputStream out = new DataOutputStream(bos);
        out.write(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(dim);
        out.writeInt(entries.size());
        for (float f : st) out.writeFloat(f);
        for (Entry e : entries) {
            if (e.embedding.length != dim) throw new IllegalArgumentException("mixed embedding dims");
            requireFinite(e.noseRatio);
            out.writeInt(e.poseId);
            out.writeFloat(e.noseRatio);
            for (float f : e.embedding) {
                requireFinite(f);
                out.writeFloat(f);
            }
        }
        out.flush();
        byte[] body = bos.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);

        ByteArrayOutputStream full = new ByteArrayOutputStream(body.length + 4);
        DataOutputStream fo = new DataOutputStream(full);
        fo.write(body);
        fo.writeInt((int) crc.getValue());
        fo.flush();
        return full.toByteArray();
    }

    /** @throws IllegalArgumentException عند أي تلف أو مخالفة للحدود (لا قبول جزئي أبداً) */
    public static Decoded decode(byte[] data) {
        try {
            if (data == null || data.length < 4 + 4 + 4 + 4 + 4 * STATS_LEN + 4) {
                throw new IllegalArgumentException("too short");
            }
            int bodyLen = data.length - 4;
            CRC32 crc = new CRC32();
            crc.update(data, 0, bodyLen);
            int stored = ((data[bodyLen] & 0xFF) << 24) | ((data[bodyLen + 1] & 0xFF) << 16)
                    | ((data[bodyLen + 2] & 0xFF) << 8) | (data[bodyLen + 3] & 0xFF);
            if (stored != (int) crc.getValue()) throw new IllegalArgumentException("checksum mismatch");

            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 0, bodyLen));
            byte[] magic = new byte[4];
            in.readFully(magic);
            for (int i = 0; i < 4; i++) {
                if (magic[i] != MAGIC[i]) throw new IllegalArgumentException("bad magic");
            }
            if (in.readInt() != VERSION) throw new IllegalArgumentException("unsupported version");
            int dim = in.readInt();
            int count = in.readInt();
            if (dim < MIN_DIM || dim > MAX_DIM) throw new IllegalArgumentException("bad dim");
            if (count < 1 || count > MAX_TEMPLATES) throw new IllegalArgumentException("bad count");

            long expected = 4L + 4 + 4 + 4 + 4L * STATS_LEN + (long) count * (8L + 4L * dim);
            if (expected != bodyLen) throw new IllegalArgumentException("length mismatch");

            float[] stats = new float[STATS_LEN];
            for (int i = 0; i < STATS_LEN; i++) stats[i] = readFinite(in);

            List<Entry> list = new ArrayList<Entry>(count);
            for (int i = 0; i < count; i++) {
                int pose = in.readInt();
                float nose = readFinite(in);
                float[] emb = new float[dim];
                double norm = 0;
                for (int k = 0; k < dim; k++) {
                    emb[k] = readFinite(in);
                    norm += (double) emb[k] * emb[k];
                }
                norm = Math.sqrt(norm);
                if (norm < 0.9 || norm > 1.1) throw new IllegalArgumentException("embedding not normalized");
                list.add(new Entry(pose, nose, emb));
            }
            return new Decoded(list, stats);
        } catch (IOException e) {
            throw new IllegalArgumentException("corrupt template data", e);
        }
    }

    private static float readFinite(DataInputStream in) throws IOException {
        float f = in.readFloat();
        requireFinite(f);
        return f;
    }

    private static void requireFinite(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) throw new IllegalArgumentException("non-finite value");
    }
}

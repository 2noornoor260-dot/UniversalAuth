package ax.nd.faceunlock.backend.core;

/**
 * يتحقق من أن نموذج TFLite المحمَّل يطابق العقد الذي يفترضه الكود، بدل استخدامه بصمت وتفسير مخرجاته خطأً.
 *
 * مشكلة سابقة: المحرّك كان يقرأ حجم المدخل من shape()[1] ويفسّر logits[1] كـ"حقيقي" لأي مخرج بثلاث قيم أو أكثر،
 * فلو حُمِّل نموذج آخر (مدخل 256×256 أو NCHW أو مخرجان) لَظهرت درجات عشوائية: إمّا رفض كل الوجوه أو قبول ما لا يجب.
 *
 * العقد المقبول:
 *  - التعرّف: مدخل [1,S,S,3] (NHWC, float32) و S بين 96 و160، ومخرج واحد [1,D] و D بين 64 و1024.
 *  - مكافحة التزييف (MiniFASNet): مدخل [1,S,S,3] أو [1,3,S,S] و S بين 64 و160، ومخرج واحد [1,3]
 *    (0 = تزييف، 1 = حقيقي، 2 = تزييف). أي عدد فئات آخر غير مقبول لأن معناه غير معروف.
 */
public final class ModelContract {

    public static final class Result {
        public final boolean ok;
        public final boolean nchw;
        public final int size;
        /** فئات مكافحة التزييف، أو بُعد البصمة للتعرّف. */
        public final int outputDim;
        public final String reason;

        private Result(boolean ok, boolean nchw, int size, int outputDim, String reason) {
            this.ok = ok;
            this.nchw = nchw;
            this.size = size;
            this.outputDim = outputDim;
            this.reason = reason;
        }

        static Result ok(boolean nchw, int size, int outputDim) {
            return new Result(true, nchw, size, outputDim, "ok");
        }

        static Result bad(String reason) {
            return new Result(false, false, 0, 0, reason);
        }
    }

    private ModelContract() {
    }

    public static Result checkRecognizer(int[] in, int[] out, int outputCount) {
        if (in == null || out == null) return Result.bad("missing tensor shapes");
        if (outputCount != 1) return Result.bad("recognizer must have exactly 1 output, found " + outputCount);
        if (in.length != 4) return Result.bad("recognizer input must be rank 4");
        if (in[0] != 1 || in[3] != 3 || in[1] != in[2]) {
            return Result.bad("recognizer input must be NHWC [1,S,S,3], found " + fmt(in));
        }
        int s = in[1];
        if (s < 96 || s > 160) return Result.bad("recognizer input size " + s + " outside 96..160");
        if (out.length != 2 || out[0] != 1) return Result.bad("recognizer output must be [1,D], found " + fmt(out));
        int d = out[1];
        if (d < 64 || d > 1024) return Result.bad("recognizer embedding dim " + d + " outside 64..1024");
        return Result.ok(false, s, d);
    }

    public static Result checkSpoof(int[] in, int[] out, int outputCount) {
        if (in == null || out == null) return Result.bad("missing tensor shapes");
        if (outputCount != 1) return Result.bad("anti-spoof must have exactly 1 output, found " + outputCount);
        if (in.length != 4 || in[0] != 1) return Result.bad("anti-spoof input must be rank 4 with batch 1");

        boolean nhwc = in[3] == 3 && in[1] == in[2];
        boolean nchw = in[1] == 3 && in[2] == in[3];
        if (!nhwc && !nchw) {
            return Result.bad("anti-spoof input must be [1,S,S,3] or [1,3,S,S], found " + fmt(in));
        }
        int s = nhwc ? in[1] : in[2];
        if (s < 64 || s > 160) return Result.bad("anti-spoof input size " + s + " outside 64..160");

        if (out.length != 2 || out[0] != 1 || out[1] != 3) {
            return Result.bad("anti-spoof output must be [1,3] (MiniFASNet 3-class), found " + fmt(out));
        }
        return Result.ok(nchw && !nhwc, s, 3);
    }

    private static String fmt(int[] shape) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(shape[i]);
        }
        return sb.append(']').toString();
    }
}

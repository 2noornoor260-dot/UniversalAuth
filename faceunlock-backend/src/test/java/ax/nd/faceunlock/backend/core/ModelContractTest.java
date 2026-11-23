package ax.nd.faceunlock.backend.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelContractTest {

    @Test
    public void recognizerContract() {
        ModelContract.Result r = ModelContract.checkRecognizer(new int[]{1, 112, 112, 3}, new int[]{1, 192}, 1);
        assertTrue(r.reason, r.ok);
        assertEquals(112, r.size);
        assertEquals(192, r.outputDim);
        assertTrue(ModelContract.checkRecognizer(new int[]{1, 160, 160, 3}, new int[]{1, 512}, 1).ok);
        assertFalse(ModelContract.checkRecognizer(new int[]{1, 112, 112, 3}, new int[]{1, 192}, 2).ok);
        assertFalse(ModelContract.checkRecognizer(new int[]{1, 3, 112, 112}, new int[]{1, 192}, 1).ok);
        assertFalse(ModelContract.checkRecognizer(new int[]{1, 64, 64, 3}, new int[]{1, 192}, 1).ok);
        assertFalse(ModelContract.checkRecognizer(null, null, 1).ok);
    }

    @Test
    public void spoofContractAcceptsMiniFasnetLayouts() {
        ModelContract.Result nhwc = ModelContract.checkSpoof(new int[]{1, 80, 80, 3}, new int[]{1, 3}, 1);
        assertTrue(nhwc.reason, nhwc.ok);
        assertFalse(nhwc.nchw);
        assertEquals(80, nhwc.size);
        ModelContract.Result nchw = ModelContract.checkSpoof(new int[]{1, 3, 80, 80}, new int[]{1, 3}, 1);
        assertTrue(nchw.reason, nchw.ok);
        assertTrue(nchw.nchw);
        assertEquals(80, nchw.size);
    }

    @Test
    public void spoofContractRejectsForeignModels() {
        // نموذج DTN (256x256، مخرجان) مثلاً: يجب ألا يُستخدم أبداً بتفسير MiniFASNet
        assertFalse(ModelContract.checkSpoof(new int[]{1, 256, 256, 3}, new int[]{1, 8}, 2).ok);
        assertFalse(ModelContract.checkSpoof(new int[]{1, 256, 256, 3}, new int[]{1, 8}, 1).ok);
        assertFalse(ModelContract.checkSpoof(new int[]{1, 80, 80, 3}, new int[]{1, 2}, 1).ok);
        assertFalse(ModelContract.checkSpoof(new int[]{1, 80, 80, 3}, new int[]{1, 3}, 2).ok);
        assertFalse(ModelContract.checkSpoof(new int[]{1, 80, 80, 1}, new int[]{1, 3}, 1).ok);
        assertFalse(ModelContract.checkSpoof(new int[]{2, 80, 80, 3}, new int[]{1, 3}, 1).ok);
    }
}

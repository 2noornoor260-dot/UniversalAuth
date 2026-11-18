package ax.nd.faceunlock.backend;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.megvii.facepp.sdk.Lite;
import com.megvii.facepp.sdk.UnlockEncryptor;

public class FaceUnlockVendorImpl extends Lite {
    private static final String TAG = "FaceUnlockVendorImpl";
    private static FaceUnlockVendorImpl sInstance;
    private SmartFaceEngine smartEngine;
    private String storagePath;
    private UnlockEncryptor customEncryptor;

    private FaceUnlockVendorImpl() {
    }

    public static synchronized FaceUnlockVendorImpl getInstance() {
        if (sInstance == null) {
            sInstance = new FaceUnlockVendorImpl();
        }
        return sInstance;
    }

    @SuppressLint("PrivateApi")
    private Context getAppContext() {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (app != null) {
                return app.getApplicationContext();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to obtain Application context", e);
        }
        return null;
    }

    private synchronized void ensureEngine() {
        if (smartEngine == null) {
            Context ctx = getAppContext();
            if (ctx != null) {
                smartEngine = new SmartFaceEngine(ctx);
                smartEngine.initStorage(storagePath, customEncryptor);
            }
        }
    }

    @Override
    public void initHandle(String str, UnlockEncryptor unlockEncryptor) {
        this.storagePath = str;
        this.customEncryptor = unlockEncryptor;
        ensureEngine();
        if (smartEngine != null) {
            smartEngine.initStorage(str, unlockEncryptor);
        }
    }

    @Override
    public void initHandle(String str) {
        initHandle(str, new CustomUnlockEncryptor());
    }

    @Override
    public int initAll(String str, String str2, byte[] bArr) {
        ensureEngine();
        return 0;
    }

    @Override
    public int initAllWithPath(String str, String str2, String str3) {
        ensureEngine();
        return 0;
    }

    @Override
    public int initLive(String str, String str2) {
        return 0;
    }

    @Override
    public int initDetect(byte[] bArr) {
        return 0;
    }

    @Override
    public int initDetectWithPath(String str) {
        return 0;
    }

    @Override
    public int releaseLive() {
        return 0;
    }

    @Override
    public int releaseDetect() {
        return 0;
    }

    @Override
    public void release() {
        // الإبقاء على المحرك جاهزاً في الذاكرة لفتح القفل بأقصى سرعة
    }

    @Override
    public int prepare() {
        ensureEngine();
        return 0;
    }

    @Override
    public int prepare(MGULKPowerMode mGULKPowerMode) {
        ensureEngine();
        return 0;
    }

    @Override
    public int reset() {
        return 0;
    }

    @Override
    public int setDetectArea(int i, int i2, int i3, int i4) {
        return 0;
    }

    @Override
    public int setConfig(float f, float f2, float f3) {
        return 0;
    }

    @Override
    public int setConfig(float f, float f2, float f3, float f4) {
        return 0;
    }

    @Override
    public int setConfig(float f, float f2, float f3, float f4, boolean z, boolean z2) {
        return 0;
    }

    @Override
    public int setConfig(LiteConfig liteConfig) {
        return 0;
    }

    @Override
    public int compare(byte[] bArr, int i, int i2, int i3, boolean z, boolean z2, int[] iArr) {
        ensureEngine();
        if (smartEngine == null) return 12;
        int result = smartEngine.verifyFrame(bArr, i, i2, i3);
        if (iArr != null && iArr.length > 0) {
            iArr[0] = result;
        }
        return result;
    }

    @Override
    public int compare(byte[] bArr, int i, int i2, int i3, int[] iArr) {
        return compare(bArr, i, i2, i3, false, false, iArr);
    }

    @Override
    public int saveFeature(byte[] bArr, int i, int i2, int i3, boolean z, byte[] bArr2, byte[] bArr3, int[] iArr) {
        ensureEngine();
        if (smartEngine == null) return 3;
        int result = smartEngine.enrollFrame(bArr, i, i2, i3);
        if (result == 0 && iArr != null && iArr.length > 0) {
            iArr[0] = 0;
        }
        return result;
    }

    @Override
    public int saveFeature(byte[] bArr, int i, int i2, int i3, byte[] bArr2, byte[] bArr3, int[] iArr) {
        return saveFeature(bArr, i, i2, i3, true, bArr2, bArr3, iArr);
    }

    @Override
    public int saveFeature(byte[] bArr, int i, int i2, int i3, boolean z, byte[] bArr2, byte[] bArr3) {
        return saveFeature(bArr, i, i2, i3, z, bArr2, bArr3, new int[1]);
    }

    @Override
    public int saveFeature(byte[] bArr, int i, int i2, int i3, byte[] bArr2, byte[] bArr3) {
        return saveFeature(bArr, i, i2, i3, true, bArr2, bArr3, new int[1]);
    }

    @Override
    public int updateFeature(byte[] bArr, int i, int i2, int i3, boolean z, byte[] bArr2, byte[] bArr3, int i4) {
        return saveFeature(bArr, i, i2, i3, z, bArr2, bArr3, new int[]{i4});
    }

    @Override
    public int deleteFeature() {
        return deleteFeature(0);
    }

    @Override
    public int deleteFeature(int i) {
        ensureEngine();
        if (smartEngine != null) {
            smartEngine.deleteAllTemplates();
        }
        return 0;
    }

    @Override
    public int restoreFeature() {
        return 0;
    }

    @Override
    public int getFeatureCount() {
        ensureEngine();
        return (smartEngine != null && smartEngine.hasEnrolledFace()) ? 1 : 0;
    }

    @Override
    public int checkFeatureValid(int i) {
        ensureEngine();
        return (smartEngine != null && smartEngine.hasEnrolledFace()) ? 0 : 1;
    }

    @Override
    public String getVersion() {
        return "SmartFaceAI-2.0";
    }
    }

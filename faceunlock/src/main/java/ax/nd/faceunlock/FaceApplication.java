package ax.nd.faceunlock;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import ax.nd.faceunlock.backend.SmartFaceEngine;
import ax.nd.faceunlock.pref.Prefs;
import ax.nd.faceunlock.service.FacePPPreloader;
import ax.nd.faceunlock.util.NotificationUtils;
import ax.nd.faceunlock.util.SharedUtil;
import ax.nd.faceunlock.util.Util;

public class FaceApplication extends Application {
    private static final String TAG = FaceApplication.class.getSimpleName();
    public static FaceApplication mApp;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Util.DEBUG) {
                Log.d(FaceApplication.TAG, "mReceiver Received intent with action = " + action);
            }
            if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                NotificationUtils.checkAndShowNotification(context);
            }
        }
    };
    private FacePPPreloader facePreloader;
    private Prefs prefs;

    public static FaceApplication getApp() {
        return mApp;
    }

    public FacePPPreloader getFacePreloader() {
        return facePreloader;
    }

    public Prefs getPrefs() {
        return prefs;
    }

    @Override
    public void onCreate() {
        if (Util.DEBUG) {
            Log.d(TAG, "onCreate");
        }
        super.onCreate();
        mApp = this;
        migrateLegacyFaceData();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(mReceiver, intentFilter, null, null, RECEIVER_EXPORTED);
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, SetupFaceIntroActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        Util.setFaceUnlockAvailable(getApplicationContext());
        LibManager.INSTANCE.init(this);
        prefs = new Prefs(this);
        facePreloader = new FacePPPreloader(this);
    }

    /**
     * ترحيل: بصمات الإصدارات السابقة (v6 وما قبل) محسوبة بمحاذاة مختلفة فلا تُطابَق بالمحرّك الجديد.
     * تُحذف ويُمسح علم "مسجَّل" فتظهر للمستخدم حالة "لم يتم الإعداد" ويعيد التسجيل مرة واحدة.
     * يجب أن يسبق Util.setFaceUnlockAvailable كي تُكتب الحالة الصحيحة.
     */
    private void migrateLegacyFaceData() {
        try {
            java.io.File faceDir = getDir("faceunlock_data", 0);
            if (SmartFaceEngine.migrateLegacyFiles(faceDir)) {
                SharedUtil sharedUtil = new SharedUtil(this);
                sharedUtil.removeSharePreferences(AppConstants.SHARED_KEY_FACE_ID);
                sharedUtil.removeSharePreferences(AppConstants.SHARED_KEY_ENROLL_TOKEN);
                Log.w(TAG, "legacy face templates removed: re-enrollment required");
            }
        } catch (Exception e) {
            Log.e(TAG, "legacy face data migration failed", e);
        }
    }

    @Override
    public void onTerminate() {
        if (Util.DEBUG) {
            Log.d(TAG, "onTerminate");
        }
        super.onTerminate();
    }

    public void postRunnable(Runnable runnable) {
        mHandler.post(runnable);
    }
}

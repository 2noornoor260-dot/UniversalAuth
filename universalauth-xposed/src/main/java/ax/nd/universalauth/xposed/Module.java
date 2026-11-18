package ax.nd.universalauth.xposed;

import static ax.nd.universalauth.xposed.common.XposedConstants.EXTRA_BYPASS_KEYGUARD;
import static ax.nd.universalauth.xposed.common.XposedConstants.EXTRA_UNLOCK_MODE;
import static ax.nd.universalauth.xposed.common.XposedConstants.MODE_UNLOCK_FADING;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ax.nd.universalauth.xposed.common.XposedConstants;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import kotlin.Unit;

@SuppressLint("PrivateApi")
public class Module implements IXposedHookLoadPackage {
    private static final String STATUS_BAR_CLASS = "com.android.systemui.statusbar.phone.StatusBar";
    private static final String SYSTEM_UI_CLASS = "com.android.systemui.SystemUI";
    private static final String CORE_STARTABLE_CLASS = "com.android.systemui.CoreStartable";
    private static final String CENTRAL_SURFACES_CLASS = "com.android.systemui.statusbar.phone.CentralSurfaces";
    private static final String CENTRAL_SURFACES_IMPL_CLASS = "com.android.systemui.statusbar.phone.CentralSurfacesImpl";
    private static final String BIOMETRIC_UNLOCK_SOURCE_CLASS = "com.android.systemui.keyguard.shared.model.BiometricUnlockSource";
    private static final String KEYGUARD_UPDATE_MONITOR_CLASS = "com.android.keyguard.KeyguardUpdateMonitor";
    private static final String STATUS_BAR_STATE_CONTROLLER_CLASS = "com.android.systemui.plugins.statusbar.StatusBarStateController";
    private static final String STATUS_BAR_STATE_CONTROLLER_IMPL_CLASS = "com.android.systemui.statusbar.StatusBarStateControllerImpl";
    private static final String KEYGUARD_UPDATE_MONITOR_LAST_MODE = "ax.nd.universalauth.last-mode";

    private static final int SHADE_LOCKED = 2;
    private static final long INACTIVITY_PIN_TIMEOUT_MS = 45L * 60L * 1000L; // 45 دقيقة

    private Method isUserInLockdownMethod;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (Objects.equals(lpparam.packageName, "com.android.systemui")) {
            Class<?> kumClazz = lpparam.classLoader.loadClass(KEYGUARD_UPDATE_MONITOR_CLASS);
            isUserInLockdownMethod = getIsUserInLockdownMethod(kumClazz);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Class<?> statusBarClass = lpparam.classLoader.loadClass(CENTRAL_SURFACES_IMPL_CLASS);
                    hookStatusBar(lpparam, statusBarClass);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                    Class<?> statusBarClass = lpparam.classLoader.loadClass(CENTRAL_SURFACES_CLASS);
                    hookStatusBar(lpparam, statusBarClass);
                } else {
                    Class<?> statusBarClass = lpparam.classLoader.loadClass(STATUS_BAR_CLASS);
                    hookStatusBar(lpparam, statusBarClass);
                }
            } catch (Throwable th) {
                XposedBridge.log("failed to load status bar class");
                XposedBridge.log(th);
            }

            try {
                addHookEarlyUnlock(kumClazz, lpparam);
            } catch (Throwable th) {
                XposedBridge.log("Failed to hook early unlock, early unlock hook will not work:");
                XposedBridge.log(th);
            }
            try {
                TrustHook.INSTANCE.hookKum(kumClazz);
            } catch (Throwable th) {
                XposedBridge.log("Failed to hook trust hook, trust hook will not work:");
                XposedBridge.log(th);
            }
        }
    }

    private void hookStatusBar(XC_LoadPackage.LoadPackageParam lpparam, Class<?> statusBarClass) {
        XposedHelpers.findAndHookMethod(statusBarClass, "start", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                hookStatusBar(statusBarClass, lpparam.classLoader, param);
            }
        });
    }

    private Method getIsUserInLockdownMethod(Class<?> kumClazz) {
        try {
            return asAccessible(kumClazz.getDeclaredMethod("isUserInLockdown", int.class));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private boolean isUserInLockdown(Object kum) throws InvocationTargetException, IllegalAccessException {
        return isUserInLockdownMethod != null && (boolean) isUserInLockdownMethod.invoke(kum, Util.INSTANCE.getCurrentUser());
    }

    private Object cachedKum;
    private Object cachedStatusBar;
    private Class<?> cachedStatusBarClass;

    private void setLockscreenUnlocked(boolean unlocked) {
        if (cachedKum == null) return;
        try {
            int userId = 0;
            try {
                Method getCurrentUser = android.app.ActivityManager.class.getMethod("getCurrentUser");
                userId = (int) getCurrentUser.invoke(null);
            } catch (Throwable ignored) {
            }

            try {
                Field trustField = asAccessible(cachedKum.getClass().getDeclaredField("mUserHasTrust"));
                android.util.SparseBooleanArray trustArray = (android.util.SparseBooleanArray) trustField.get(cachedKum);
                if (trustArray != null) {
                    trustArray.put(userId, unlocked);
                }
            } catch (Throwable ignored) {
            }

            try {
                Method onTrustChanged = asAccessible(cachedKum.getClass().getDeclaredMethod(
                        "onTrustChanged", boolean.class, boolean.class, int.class, int.class, java.util.List.class));
                onTrustChanged.invoke(cachedKum, unlocked, unlocked, userId, 0, null);
            } catch (Throwable t1) {
                try {
                    Method sendUpdates = asAccessible(cachedKum.getClass().getDeclaredMethod("sendUpdates", int.class));
                    sendUpdates.invoke(cachedKum, userId);
                } catch (Throwable ignored) {
                }
            }

            if (cachedStatusBar != null) {
                try {
                    Field kscField = asAccessible(cachedStatusBarClass.getDeclaredField("mKeyguardStateController"));
                    Object ksc = kscField.get(cachedStatusBar);
                    if (ksc != null) {
                        try {
                            Method updateMethod = asAccessible(ksc.getClass().getDeclaredMethod("update", boolean.class));
                            updateMethod.invoke(ksc, true);
                        } catch (Throwable t2) {
                            Method notifyMethod = asAccessible(ksc.getClass().getDeclaredMethod(
                                    "notifyKeyguardState", boolean.class, boolean.class));
                            notifyMethod.invoke(ksc, true, false);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable th) {
            XposedBridge.log(th);
        }
    }

    private boolean isBouncerShowing() {
        if (cachedStatusBar == null || cachedStatusBarClass == null) return false;
        try {
            Field vmField = asAccessible(cachedStatusBarClass.getDeclaredField("mStatusBarKeyguardViewManager"));
            Object vm = vmField.get(cachedStatusBar);
            if (vm != null) {
                Method isShowing = asAccessible(vm.getClass().getMethod("isBouncerShowing"));
                return (boolean) isShowing.invoke(vm);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void addHookEarlyUnlock(Class<?> kumClazz, XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Field mStatusBarStateControllerField = asAccessible(kumClazz.getDeclaredField("mStatusBarStateController"));
        Field mKeyguardIsVisibleField;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mKeyguardIsVisibleField = asAccessible(kumClazz.getDeclaredField("mKeyguardShowing"));
        } else {
            mKeyguardIsVisibleField = asAccessible(kumClazz.getDeclaredField("mKeyguardIsVisible"));
        }
        Field mDeviceInteractiveField = asAccessible(kumClazz.getDeclaredField("mDeviceInteractive"));
        Field mGoingToSleepField = asAccessible(kumClazz.getDeclaredField("mGoingToSleep"));
        Field mContextField = asAccessible(kumClazz.getDeclaredField("mContext"));

        Class<?> sbscClazz;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sbscClazz = lpparam.classLoader.loadClass(STATUS_BAR_STATE_CONTROLLER_IMPL_CLASS);
        } else {
            sbscClazz = lpparam.classLoader.loadClass(STATUS_BAR_STATE_CONTROLLER_CLASS);
        }
        Method getStateMethod = asAccessible(sbscClazz.getDeclaredMethod("getState"));

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object kum = param.thisObject;
                cachedKum = kum;
                if (isUserInLockdown(kum)) {
                    return;
                }

                Object sbsc = mStatusBarStateControllerField.get(kum);
                boolean mKeyguardIsVisible = mKeyguardIsVisibleField.getBoolean(kum);
                boolean mDeviceInteractive = mDeviceInteractiveField.getBoolean(kum);
                boolean mGoingToSleep = mGoingToSleepField.getBoolean(kum);
                int sbscState = (int) getStateMethod.invoke(sbsc);

                final boolean statusBarShadeLocked = sbscState == SHADE_LOCKED;
                final boolean awakeKeyguard = mKeyguardIsVisible && mDeviceInteractive && !mGoingToSleep && !statusBarShadeLocked;

                Object prevAwakeKeyguard = XposedHelpers.setAdditionalInstanceField(kum, KEYGUARD_UPDATE_MONITOR_LAST_MODE, awakeKeyguard);

                if (!Objects.equals(prevAwakeKeyguard, awakeKeyguard)) {
                    Context mContext = (Context) mContextField.get(kum);
                    hookEarlyUnlock(mContext, awakeKeyguard);
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            try {
                XposedHelpers.findAndHookMethod(kumClazz, "updateFaceListeningState", int.class, hook);
            } catch (Throwable th) {
                XposedHelpers.findAndHookMethod(kumClazz, "updateFingerprintListeningState", int.class, hook);
            }
        } else {
            XposedHelpers.findAndHookMethod(kumClazz, "updateFaceListeningState", hook);
        }
    }

    private static final int LIGHT_LEVEL_LIT = 0;        // غرفة منيرة: لا إضاءة
    private static final int LIGHT_LEVEL_DIM = 1;        // خافتة جداً: إضاءة فقط إذا فشلت المحاولة الأولى
    private static final int LIGHT_LEVEL_PITCH_DARK = 2; // ظلام دامس: إضاءة فورية

    private int failedScanAttempts = 0;
    private boolean scanWasActive = false;
    private boolean unlockedInCurrentCycle = false;
    private int scanGeneration = 0;
    private int currentLightLevel = LIGHT_LEVEL_LIT;

    private long lastSuccessfulUnlockElapsedMs = 0L;
    private boolean pinRequiredByInactivity = false;

    private static final long FIRST_SCAN_WINDOW_MS = 2200L;
    private static final long RESCAN_RESET_GAP_MS = 600L;

    private android.os.Handler scanHandler;
    private Runnable secondScanRunnable;

    private boolean isTimedOutRequirePin() {
        if (pinRequiredByInactivity) {
            return true;
        }
        if (lastSuccessfulUnlockElapsedMs > 0L) {
            long elapsedSinceUnlock = SystemClock.elapsedRealtime() - lastSuccessfulUnlockElapsedMs;
            if (elapsedSinceUnlock >= INACTIVITY_PIN_TIMEOUT_MS) {
                pinRequiredByInactivity = true;
                return true;
            }
        }
        return false;
    }

    private void recordSuccessfulUnlock() {
        lastSuccessfulUnlockElapsedMs = SystemClock.elapsedRealtime();
        pinRequiredByInactivity = false;
        failedScanAttempts = 0;
    }

    private android.os.Handler getScanHandler() {
        if (scanHandler == null) {
            android.os.Looper looper = android.os.Looper.getMainLooper();
            if (looper != null) {
                scanHandler = new android.os.Handler(looper);
            }
        }
        return scanHandler;
    }

    private void cancelPendingSecondScan() {
        android.os.Handler h = getScanHandler();
        if (h != null && secondScanRunnable != null) {
            h.removeCallbacks(secondScanRunnable);
        }
        secondScanRunnable = null;
    }

    private interface DarknessCallback {
        void onResult(int lightLevel);
    }

    @SuppressLint("MissingPermission")
    private void checkDarknessViaFrontCamera(Context context, int generation, DarknessCallback callback) {
        android.os.Handler mainH = getScanHandler();
        if (context == null || mainH == null) {
            if (callback != null) callback.onResult(LIGHT_LEVEL_LIT);
            return;
        }

        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger frameCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean aeConverged = new java.util.concurrent.atomic.AtomicBoolean(false);
        final android.os.HandlerThread camThread = new android.os.HandlerThread("UA_DarkProbe");
        camThread.start();
        final android.os.Handler camHandler = new android.os.Handler(camThread.getLooper());

        final android.hardware.camera2.CameraDevice[] devRef = new android.hardware.camera2.CameraDevice[1];
        final android.media.ImageReader[] readerRef = new android.media.ImageReader[1];

        final Runnable cleanupAndFinish = () -> {
            try {
                if (devRef[0] != null) {
                    devRef[0].close();
                    devRef[0] = null;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (readerRef[0] != null) {
                    readerRef[0].close();
                    readerRef[0] = null;
                }
            } catch (Throwable ignored) {
            }
            try {
                camThread.quitSafely();
            } catch (Throwable ignored) {
            }
        };

        // مهلة أمان 900ms؛ عند انتهائها نفترض أن الإضاءة طبيعية حتى لا تضيء الشاشة بالخطأ
        camHandler.postDelayed(() -> {
            if (finished.compareAndSet(false, true)) {
                cleanupAndFinish.run();
                mainH.post(() -> {
                    if (scanGeneration == generation && scanWasActive) {
                        callback.onResult(LIGHT_LEVEL_LIT);
                    }
                });
            }
        }, 900L);

        camHandler.post(() -> {
            try {
                android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager)
                        context.getSystemService(Context.CAMERA_SERVICE);
                if (cm == null) {
                    if (finished.compareAndSet(false, true)) {
                        cleanupAndFinish.run();
                        mainH.post(() -> {
                            if (scanGeneration == generation && scanWasActive) callback.onResult(LIGHT_LEVEL_LIT);
                        });
                    }
                    return;
                }

                String frontId = null;
                for (String id : cm.getCameraIdList()) {
                    android.hardware.camera2.CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                    Integer facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                        frontId = id;
                        break;
                    }
                }

                if (frontId == null) {
                    if (finished.compareAndSet(false, true)) {
                        cleanupAndFinish.run();
                        mainH.post(() -> {
                            if (scanGeneration == generation && scanWasActive) callback.onResult(LIGHT_LEVEL_LIT);
                        });
                    }
                    return;
                }

                final int w = 160;
                final int h = 120;
                android.media.ImageReader reader = android.media.ImageReader.newInstance(
                        w, h, android.graphics.ImageFormat.YUV_420_888, 2);
                readerRef[0] = reader;

                reader.setOnImageAvailableListener(r -> {
                    android.media.Image img = null;
                    float meanLuma = 128f;
                    int p90Luma = 128;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;

                        int fNum = frameCounter.incrementAndGet();
                        // انتظار استقرار التعريض التلقائي (AE) للكاميرا قبل الحكم على الإضاءة
                        if (fNum < 3 || (!aeConverged.get() && fNum < 5)) {
                            img.close();
                            return;
                        }

                        android.media.Image.Plane yPlane = img.getPlanes()[0];
                        java.nio.ByteBuffer buf = yPlane.getBuffer();
                        int rowStride = yPlane.getRowStride();
                        int pixelStride = yPlane.getPixelStride();
                        int iw = img.getWidth();
                        int ih = img.getHeight();
                        int[] hist = new int[256];
                        long sum = 0;
                        int count = 0;
                        for (int row = ih / 4; row < (ih * 3) / 4; row += 2) {
                            int rowStart = row * rowStride;
                            for (int col = iw / 4; col < (iw * 3) / 4; col += 2) {
                                int idx = rowStart + col * pixelStride;
                                if (idx < buf.limit()) {
                                    int y = buf.get(idx) & 0xFF;
                                    sum += y;
                                    hist[y]++;
                                    count++;
                                }
                            }
                        }
                        if (count > 0) {
                            meanLuma = (float) sum / count;
                            int target90 = (int) (count * 0.90f);
                            int acc = 0;
                            for (int v = 0; v < 256; v++) {
                                acc += hist[v];
                                if (acc >= target90) {
                                    p90Luma = v;
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) {
                            try {
                                img.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    final int detectedLevel;
                    if (meanLuma < 22.0f && p90Luma < 34) {
                        detectedLevel = LIGHT_LEVEL_PITCH_DARK;
                    } else if (meanLuma < 34.0f && p90Luma < 50) {
                        detectedLevel = LIGHT_LEVEL_DIM;
                    } else {
                        detectedLevel = LIGHT_LEVEL_LIT;
                    }

                    if (finished.compareAndSet(false, true)) {
                        cleanupAndFinish.run();
                        mainH.post(() -> {
                            if (scanGeneration == generation && scanWasActive) {
                                callback.onResult(detectedLevel);
                            }
                        });
                    }
                }, camHandler);

                cm.openCamera(frontId, new android.hardware.camera2.CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(android.hardware.camera2.CameraDevice camera) {
                        if (finished.get()) {
                            camera.close();
                            return;
                        }
                        devRef[0] = camera;
                        try {
                            android.hardware.camera2.CaptureRequest.Builder req = camera.createCaptureRequest(
                                    android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW);
                            req.addTarget(reader.getSurface());
                            req.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON);

                            camera.createCaptureSession(
                                    java.util.Collections.singletonList(reader.getSurface()),
                                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(android.hardware.camera2.CameraCaptureSession session) {
                                            if (finished.get()) return;
                                            try {
                                                session.setRepeatingRequest(req.build(),
                                                        new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                                            @Override
                                                            public void onCaptureCompleted(
                                                                    android.hardware.camera2.CameraCaptureSession s,
                                                                    android.hardware.camera2.CaptureRequest request,
                                                                    android.hardware.camera2.TotalCaptureResult result) {
                                                                try {
                                                                    Integer aeState = result.get(
                                                                            android.hardware.camera2.CaptureResult.CONTROL_AE_STATE);
                                                                    if (aeState != null &&
                                                                            (aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
                                                                                    || aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                                                                        aeConverged.set(true);
                                                                    }
                                                                } catch (Throwable ignored) {
                                                                }
                                                            }
                                                        }, camHandler);
                                            } catch (Throwable ignored) {
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession session) {
                                        }
                                    },
                                    camHandler
                            );
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    public void onDisconnected(android.hardware.camera2.CameraDevice camera) {
                        camera.close();
                    }

                    @Override
                    public void onError(android.hardware.camera2.CameraDevice camera, int error) {
                        camera.close();
                    }
                }, camHandler);
            } catch (Throwable ignored) {
                if (finished.compareAndSet(false, true)) {
                    cleanupAndFinish.run();
                    mainH.post(() -> {
                        if (scanGeneration == generation && scanWasActive) callback.onResult(LIGHT_LEVEL_LIT);
                    });
                }
            }
        });
    }

    private void scheduleSecondRealScan(Context context, int generation) {
        cancelPendingSecondScan();
        android.os.Handler h = getScanHandler();
        if (h == null) return;

        secondScanRunnable = () -> {
            if (!scanWasActive || unlockedInCurrentCycle || context == null || scanGeneration != generation) return;
            if (isTimedOutRequirePin()) return;
            try {
                context.sendBroadcast(new Intent(XposedConstants.ACTION_EARLY_UNLOCK)
                        .putExtra(XposedConstants.EXTRA_EARLY_UNLOCK_MODE, false));

                FaceIdOverlay.triggerRetryTransition();

                // إذا كانت الإضاءة خافتة جداً وفشلت المحاولة الأولى، شغّل إضاءة الشاشة للمحاولة الثانية
                if (currentLightLevel == LIGHT_LEVEL_DIM) {
                    FaceIdOverlay.enableFillLight(context);
                }

                android.os.Handler innerH = getScanHandler();
                if (innerH != null) {
                    innerH.postDelayed(() -> {
                        if (scanWasActive && !unlockedInCurrentCycle && scanGeneration == generation && !isTimedOutRequirePin()) {
                            try {
                                context.sendBroadcast(new Intent(XposedConstants.ACTION_EARLY_UNLOCK)
                                        .putExtra(XposedConstants.EXTRA_EARLY_UNLOCK_MODE, true));
                            } catch (Throwable ignored) {
                            }
                        }
                    }, RESCAN_RESET_GAP_MS);
                }
            } catch (Throwable ignored) {
            }
        };
        h.postDelayed(secondScanRunnable, FIRST_SCAN_WINDOW_MS);
    }

    private void hookEarlyUnlock(Context context, boolean newAwakeKeyguard) throws Throwable {
        if (newAwakeKeyguard) {
            if (isTimedOutRequirePin()) {
                setLockscreenUnlocked(false);
                FaceIdOverlay.showPinRequired(context);
                return;
            }
            if (failedScanAttempts >= 3) {
                return;
            }
            scanWasActive = true;
            unlockedInCurrentCycle = false;
            currentLightLevel = LIGHT_LEVEL_LIT;
            final int currentGen = ++scanGeneration;

            FaceIdOverlay.showScanning(context);

            checkDarknessViaFrontCamera(context, currentGen, lightLevel -> {
                if (!scanWasActive || unlockedInCurrentCycle || scanGeneration != currentGen) return;
                if (isTimedOutRequirePin()) return;

                currentLightLevel = lightLevel;
                if (lightLevel == LIGHT_LEVEL_PITCH_DARK) {
                    FaceIdOverlay.enableFillLight(context);
                }
                context.sendBroadcast(new Intent(XposedConstants.ACTION_EARLY_UNLOCK)
                        .putExtra(XposedConstants.EXTRA_EARLY_UNLOCK_MODE, true));

                scheduleSecondRealScan(context, currentGen);
            });
        } else {
            scanGeneration++;
            cancelPendingSecondScan();
            if (scanWasActive && !unlockedInCurrentCycle) {
                failedScanAttempts++;
            }
            scanWasActive = false;
            FaceIdOverlay.hide();
            setLockscreenUnlocked(false);
            context.sendBroadcast(new Intent(XposedConstants.ACTION_EARLY_UNLOCK)
                    .putExtra(XposedConstants.EXTRA_EARLY_UNLOCK_MODE, false));
        }
    }

    private <T extends AccessibleObject> T asAccessible(T a) {
        a.setAccessible(true);
        return a;
    }

    private String dumpArray(Object[] obj) {
        return dumpStream(Arrays.stream(obj));
    }

    private String dumpStream(Stream<Object> stream) {
        return stream.map(Object::toString).collect(Collectors.joining(",\n"));
    }

    @SuppressLint("PrivateApi")
    private void hookStatusBar(Class<?> statusBarClass, ClassLoader classLoader, XC_MethodHook.MethodHookParam param) throws Throwable {
        Object statusBar = param.thisObject;
        cachedStatusBar = statusBar;
        cachedStatusBarClass = statusBarClass;

        if (lastSuccessfulUnlockElapsedMs == 0L) {
            lastSuccessfulUnlockElapsedMs = SystemClock.elapsedRealtime();
        }

        Class<?> systemUiClass;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            systemUiClass = classLoader.loadClass(CENTRAL_SURFACES_IMPL_CLASS);
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            systemUiClass = classLoader.loadClass(CORE_STARTABLE_CLASS);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2) {
            try {
                systemUiClass = classLoader.loadClass(SYSTEM_UI_CLASS);
            } catch (ClassNotFoundException ex) {
                systemUiClass = classLoader.loadClass(CORE_STARTABLE_CLASS);
            }
        } else {
            systemUiClass = classLoader.loadClass(SYSTEM_UI_CLASS);
        }
        Context context = (Context) asAccessible(systemUiClass.getDeclaredField("mContext")).get(statusBar);
        Object kum = asAccessible(statusBarClass.getDeclaredField("mKeyguardUpdateMonitor")).get(statusBar);
        cachedKum = kum;

        UnlockMethod method = hookStatusBarBiometricUnlock(classLoader, statusBar, statusBarClass);

        android.content.BroadcastReceiver livenessReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent resIntent) {
                String action = resIntent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    recordSuccessfulUnlock();
                    unlockedInCurrentCycle = false;
                    scanWasActive = false;
                    cancelPendingSecondScan();
                    return;
                }

                if (LivenessVerifierService.ACTION_LIVENESS_RESULT.equals(action)) {
                    boolean passed = resIntent.getBooleanExtra(LivenessVerifierService.EXTRA_PASSED, false);
                    String reason = resIntent.getStringExtra(LivenessVerifierService.EXTRA_REASON);

                    if ("NO_CAMERA_PERMISSION".equals(reason)) {
                        android.widget.Toast.makeText(context, "يرجى منح إذن الكاميرا لتطبيق UniversalAuth", android.widget.Toast.LENGTH_LONG).show();
                    }

                    try {
                        if (passed && !isUserInLockdown(kum) && !isTimedOutRequirePin()) {
                            unlockedInCurrentCycle = true;
                            recordSuccessfulUnlock();
                            FaceIdOverlay.showSuccess(context);
                            setLockscreenUnlocked(true);
                            if (isBouncerShowing()) {
                                method.unlock(new Intent());
                            }
                        } else {
                            failedScanAttempts++;
                            FaceIdOverlay.showFailure(context);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        };

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(LivenessVerifierService.ACTION_LIVENESS_RESULT);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(livenessReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(livenessReceiver, filter);
        }

        UnlockReceiver.INSTANCE.setup(context, statusBar, intent -> {
            try {
                if (isUserInLockdown(kum) || isTimedOutRequirePin() || failedScanAttempts >= 3) {
                    cancelPendingSecondScan();
                    setLockscreenUnlocked(false);
                    if (isTimedOutRequirePin()) {
                        FaceIdOverlay.showPinRequired(context);
                    } else {
                        FaceIdOverlay.showFailure(context);
                    }
                    return Unit.INSTANCE;
                }

                cancelPendingSecondScan();

                context.sendBroadcast(new Intent(XposedConstants.ACTION_EARLY_UNLOCK)
                        .putExtra(XposedConstants.EXTRA_EARLY_UNLOCK_MODE, false));

                Intent verifyIntent = new Intent(LivenessVerifierService.ACTION_VERIFY_LIVENESS);
                verifyIntent.setClassName("ax.nd.universalauth.xposed", LivenessVerifierService.class.getName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(verifyIntent);
                } else {
                    context.startService(verifyIntent);
                }
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
            return Unit.INSTANCE;
        });
    }

    public interface UnlockMethod {
        void unlock(Intent t) throws Throwable;
    }

    private Object getBiometricUnlockControllerFromStatusBar(Object statusBar, Class<?> statusBarClass) throws NoSuchFieldException, IllegalAccessException {
        return asAccessible(statusBarClass.getDeclaredField("mBiometricUnlockController")).get(statusBar);
    }

    private UnlockMethod hookStatusBarBiometricUnlock(ClassLoader classLoader, Object statusBar, Class<?> statusBarClass) throws Throwable {
        Object biometricUnlockController = getBiometricUnlockControllerFromStatusBar(statusBar, statusBarClass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Class<?> biometricUnlockScoreClass = classLoader.loadClass(BIOMETRIC_UNLOCK_SOURCE_CLASS);
            Method startWakeAndUnlock = asAccessible(biometricUnlockController.getClass().getDeclaredMethod("startWakeAndUnlock", int.class, biometricUnlockScoreClass));

            return intent -> {
                if (intent.getBooleanExtra(EXTRA_BYPASS_KEYGUARD, true)) {
                    int unlockMode = intent.getIntExtra(EXTRA_UNLOCK_MODE, MODE_UNLOCK_FADING);
                    startWakeAndUnlock.invoke(biometricUnlockController, unlockMode, null);
                }
            };
        }
        Method startWakeAndUnlock = asAccessible(biometricUnlockController.getClass().getDeclaredMethod("startWakeAndUnlock", int.class));

        return intent -> {
            if (intent.getBooleanExtra(EXTRA_BYPASS_KEYGUARD, true)) {
                int unlockMode = intent.getIntExtra(EXTRA_UNLOCK_MODE, MODE_UNLOCK_FADING);
                startWakeAndUnlock.invoke(biometricUnlockController, unlockMode);
            }
        };
    }
            }

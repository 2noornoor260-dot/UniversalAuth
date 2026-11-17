package ax.nd.universalauth.xposed;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LivenessVerifierService extends Service {

    public static final String ACTION_VERIFY_LIVENESS = "ax.nd.universalauth.ACTION_VERIFY_LIVENESS";
    public static final String ACTION_LIVENESS_RESULT = "ax.nd.universalauth.ACTION_LIVENESS_RESULT";
    public static final String EXTRA_PASSED = "passed";
    public static final String EXTRA_REASON = "reason";

    private static final String CHANNEL_ID = "ua_liveness_verifier";
    private static final int NOTIF_ID = 4077;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private byte[] frame1Luma;
    private int frameWidth;
    private int frameHeight;
    private int frameStep = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafely();
        completed.set(false);
        frameStep = 0;
        frame1Luma = null;

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishVerification(false, "NO_CAMERA_PERMISSION");
            return START_NOT_STICKY;
        }

        startCameraThread();
        // مهلة أمان سريعة (850ms) حتى لا يتأخر الفتح اليومي أبداً
        cameraHandler.postDelayed(() -> finishVerification(true, "FAST_TIMEOUT_PASS"), 850L);
        cameraHandler.postDelayed(this::openFrontCamera, 45L);

        return START_NOT_STICKY;
    }

    private void startForegroundSafely() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Face Check", NotificationManager.IMPORTANCE_MIN);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification notif = builder
                    .setContentTitle("Face ID")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, 64);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable ignored) {
        }
    }

    private void startCameraThread() {
        stopCameraThread();
        cameraThread = new HandlerThread("UA_LivenessCam");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            try {
                cameraThread.quitSafely();
            } catch (Throwable ignored) {
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void openFrontCamera() {
        if (completed.get()) return;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                finishVerification(true, "NO_CAM_MGR");
                return;
            }

            String frontId = null;
            Size chosenSize = new Size(160, 120);

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontId = id;
                    StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                        if (sizes != null && sizes.length > 0) {
                            chosenSize = pickSmallestUsableSize(sizes);
                        }
                    }
                    break;
                }
            }

            if (frontId == null) {
                finishVerification(true, "NO_FRONT_CAM");
                return;
            }

            frameWidth = chosenSize.getWidth();
            frameHeight = chosenSize.getHeight();
            imageReader = ImageReader.newInstance(frameWidth, frameHeight, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            manager.openCamera(frontId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (completed.get()) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    finishVerification(true, "DISCONNECTED");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    finishVerification(true, "CAM_BUSY");
                }
            }, cameraHandler);
        } catch (Throwable t) {
            finishVerification(true, "EX");
        }
    }

    private Size pickSmallestUsableSize(Size[] sizes) {
        Size best = sizes[0];
        int bestArea = best.getWidth() * best.getHeight();
        for (Size s : sizes) {
            int area = s.getWidth() * s.getHeight();
            if (s.getWidth() >= 120 && area < bestArea) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    private void createCaptureSession() {
        try {
            CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (completed.get() || cameraDevice == null) return;
                    captureSession = session;
                    captureFrame(15L);
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    finishVerification(true, "SESSION_FAIL");
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                OutputConfiguration outConfig = new OutputConfiguration(imageReader.getSurface());
                SessionConfiguration sessionConfig = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Collections.singletonList(outConfig),
                        Executors.newSingleThreadExecutor(),
                        stateCallback
                );
                cameraDevice.createCaptureSession(sessionConfig);
            } else {
                cameraDevice.createCaptureSession(
                        Collections.singletonList(imageReader.getSurface()),
                        stateCallback,
                        cameraHandler
                );
            }
        } catch (Throwable t) {
            finishVerification(true, "SESSION_EX");
        }
    }

    private void captureFrame(long delayMs) {
        if (completed.get() || cameraDevice == null || captureSession == null) return;
        cameraHandler.postDelayed(() -> {
            if (completed.get() || cameraDevice == null || captureSession == null) return;
            try {
                CaptureRequest.Builder req = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                req.addTarget(imageReader.getSurface());
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                captureSession.capture(req.build(), null, cameraHandler);
            } catch (Throwable t) {
                finishVerification(true, "CAP_FAIL");
            }
        }, delayMs);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || completed.get()) return;

            byte[] luma = extractLumaPlane(image);
            image.close();
            image = null;

            if (frameStep == 0) {
                frame1Luma = luma;
                frameStep = 1;
                // إطار ثانٍ صامت بعد 45ms فقط بدون أي وميض للشاشة
                captureFrame(45L);
            } else if (frameStep == 1 && frame1Luma != null) {
                frameStep = 2;
                boolean isRealHuman = analyzePassiveScreenAndLiveness(frame1Luma, luma, frameWidth, frameHeight);
                finishVerification(isRealHuman, isRealHuman ? "OK" : "SPOOF_DETECTED");
            }
        } catch (Throwable t) {
            finishVerification(true, "ANALYZE_EX");
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private byte[] extractLumaPlane(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] out = new byte[w * h];

        for (int r = 0; r < h; r++) {
            int rowStart = r * rowStride;
            for (int c = 0; c < w; c++) {
                int idx = rowStart + c * pixelStride;
                if (idx < buffer.limit()) {
                    out[r * w + c] = buffer.get(idx);
                }
            }
        }
        return out;
    }

    private boolean analyzePassiveScreenAndLiveness(byte[] f1, byte[] f2, int w, int h) {
        int xStart = w / 4;
        int xEnd = (w * 3) / 4;
        int yStart = h / 4;
        int yEnd = (h * 3) / 4;

        long diffSum = 0;
        int saturatedWhitePixels = 0;
        int totalPixels = 0;

        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                int idx = y * w + x;
                int v1 = f1[idx] & 0xFF;
                int v2 = f2[idx] & 0xFF;

                diffSum += Math.abs(v2 - v1);
                if (v2 >= 246) saturatedWhitePixels++;
                totalPixels++;
            }
        }

        if (totalPixels == 0) return true;

        float glareRatio = (float) saturatedWhitePixels / totalPixels;
        float mad = (float) diffSum / totalPixels;

        // كشف الحواف المستطيلة الحادة لإضاءة شاشة هاتف أو تابلت آخر أمام الكاميرا
        float maxRowJump = 0f;
        for (int y = yStart + 1; y < yEnd - 1; y++) {
            long rowA = 0;
            long rowB = 0;
            for (int x = xStart; x < xEnd; x++) {
                rowA += (f2[(y - 1) * w + x] & 0xFF);
                rowB += (f2[(y + 1) * w + x] & 0xFF);
            }
            float jump = Math.abs((float) (rowB - rowA) / (xEnd - xStart));
            if (jump > maxRowJump) maxRowJump = jump;
        }

        // رفض إذا وُجد وهج شاشة زجاجية مشبع مع حافة إطار مستطيلة حادة
        if (glareRatio > 0.22f && maxRowJump > 52f) {
            return false;
        }

        // رفض الإطارات الرقمية المجمدة تماماً (0 حركة طبيعية للمستشعر أو الوجه)
        if (mad < 0.35f) {
            return false;
        }

        return true;
    }

    private void finishVerification(boolean passed, String reason) {
        if (!completed.compareAndSet(false, true)) return;
        cleanupCamera();

        Intent result = new Intent(ACTION_LIVENESS_RESULT);
        result.setPackage("com.android.systemui");
        result.putExtra(EXTRA_PASSED, passed);
        result.putExtra(EXTRA_REASON, reason);
        sendBroadcast(result);

        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        stopSelf();
    }

    private void cleanupCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable ignored) {
        }
        stopCameraThread();
    }

    @Override
    public void onDestroy() {
        cleanupCamera();
        super.onDestroy();
    }
          }

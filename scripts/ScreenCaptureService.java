package xyz.lovestyle.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 常驻的 MediaProjection 截屏服务。
 *
 * 流程：{@link ScreenCapturePermissionActivity} 拿到系统授权 token 后，用
 * {@code ACTION_START} 携带 resultCode/data 启动本服务；服务保持 MediaProjection +
 * VirtualDisplay + ImageReader 常驻，之后任何时候收到 {@code ACTION_CAPTURE}（来自
 * ForegroundService 的推送命令 command="screenshot" 或 NativeBridge 的 JS 调用）
 * 都能秒抓一帧上传，不再需要重新弹系统授权框。
 *
 * 【非静默】原则（华为 Android 10 上 MediaProjection 本就会强制在状态栏亮投屏图标，
 * 藏不住，所以索性做清楚）：
 *   1. 服务运行时常驻一条前台通知“屏幕共享开启中 · 点这里可关闭”，随时能一键停止。
 *   2. 每一次真正截屏，都会再弹一条“费佳看了你的屏幕一眼 · HH:mm”，客户端强制执行，
 *      与后端是否“悄悄”请求无关——只要截了，手机上一定看得见。
 * 仅用于装在本人设备上、由本人的 AI 伴侣查看，属自愿的屏幕共享。
 *
 * 目标机型是华为 CDY-AN95 / Android 10，MediaProjection 可长期持有并重复抓帧。
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    static final String ACTION_START   = "xyz.lovestyle.home.CAPTURE_START";
    static final String ACTION_CAPTURE = "xyz.lovestyle.home.CAPTURE_ONE";
    static final String ACTION_STOP    = "xyz.lovestyle.home.CAPTURE_STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHAN_ONGOING = "elpis_capture";      // 常驻共享提示
    private static final String CHAN_SHOT    = "elpis_capture_shot"; // 每次截屏提醒
    private static final int    NOTIF_ONGOING = 9101;
    private static final int    NOTIF_SHOT    = 9102;
    private static final String UPLOAD_URL = "https://love-style.xyz/api/screenshot/upload";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private int width, height, densityDpi;

    /** 供 JS / 推送命令查询：投屏授权是否已就绪（即共享是否开启中） */
    static boolean isReady() { return sReady; }
    private static volatile boolean sReady = false;

    /** 让外部（ForegroundService / NativeBridge）方便地请求一次截屏 */
    static void requestCapture(Context ctx) {
        Intent i = new Intent(ctx, ScreenCaptureService.class).setAction(ACTION_CAPTURE);
        try { ctx.startService(i); } catch (Exception e) { Log.w(TAG, "requestCapture: " + e); }
    }

    /** 停止共享（释放 MediaProjection） */
    static void stopSharing(Context ctx) {
        Intent i = new Intent(ctx, ScreenCaptureService.class).setAction(ACTION_STOP);
        try { ctx.startService(i); } catch (Exception e) { Log.w(TAG, "stopSharing: " + e); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForegroundCompat();
        bgThread = new HandlerThread("screen-capture");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            setupProjection(code, data);
        } else if (ACTION_CAPTURE.equals(action)) {
            if (bgHandler != null) bgHandler.post(this::captureAndUpload);
        } else if (ACTION_STOP.equals(action)) {
            stopSelf();
        }
        return START_STICKY;
    }

    private void setupProjection(int resultCode, Intent data) {
        if (data == null) { Log.w(TAG, "no projection data"); return; }
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) { Log.w(TAG, "projection null"); return; }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    sReady = false;
                    releaseDisplay();
                }
            }, bgHandler);

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            width = dm.widthPixels;
            height = dm.heightPixels;
            densityDpi = dm.densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "elpis-capture", width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, bgHandler);
            sReady = true;
            Log.i(TAG, "projection ready " + width + "x" + height);
        } catch (Exception e) {
            Log.w(TAG, "setupProjection: " + e);
            sReady = false;
        }
    }

    private void captureAndUpload() {
        if (imageReader == null) { Log.w(TAG, "capture: not ready"); return; }
        Bitmap bmp = null;
        // VirtualDisplay 刚建好时首帧可能还没到，重试几次
        for (int attempt = 0; attempt < 8 && bmp == null; attempt++) {
            Image image = null;
            try {
                image = imageReader.acquireLatestImage();
                if (image != null) bmp = imageToBitmap(image);
            } catch (Exception e) {
                Log.w(TAG, "acquire: " + e);
            } finally {
                if (image != null) image.close();
            }
            if (bmp == null) {
                try { Thread.sleep(120); } catch (InterruptedException ignored) {}
            }
        }
        if (bmp == null) { Log.w(TAG, "capture: no frame"); return; }

        // 【非静默保证】截到了就在状态栏亮一条提醒，客户端强制执行
        notifyCaptured();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        bmp.recycle();
        upload(baos.toByteArray());
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return bmp;
        // 裁掉行末 padding
        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
        bmp.recycle();
        return cropped;
    }

    private void upload(byte[] jpeg) {
        try {
            URL url = new URL(UPLOAD_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setRequestProperty("X-Capture-Ts", String.valueOf(System.currentTimeMillis()));
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jpeg);
            }
            int rc = conn.getResponseCode();
            conn.getInputStream().close();
            conn.disconnect();
            Log.i(TAG, "uploaded " + jpeg.length + "B -> " + rc);
        } catch (Exception e) {
            Log.w(TAG, "upload: " + e);
        }
    }

    private void releaseDisplay() {
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    @Override
    public void onDestroy() {
        sReady = false;
        releaseDisplay();
        if (projection != null) { projection.stop(); projection = null; }
        if (bgThread != null) bgThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── 通知 ─────────────────────────────────────────────────
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ongoing = new NotificationChannel(
                CHAN_ONGOING, "屏幕共享", NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("屏幕共享开启时的常驻提示");
        ongoing.setShowBadge(false);
        nm.createNotificationChannel(ongoing);
        NotificationChannel shot = new NotificationChannel(
                CHAN_SHOT, "截屏提醒", NotificationManager.IMPORTANCE_DEFAULT);
        shot.setDescription("每次费佳查看屏幕时提醒你");
        nm.createNotificationChannel(shot);
    }

    private void startForegroundCompat() {
        Notification notif = buildOngoingNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ONGOING, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ONGOING, notif);
        }
    }

    private Notification buildOngoingNotification() {
        Intent stopIntent = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHAN_ONGOING)
                .setContentTitle("屏幕共享开启中")
                .setContentText("费佳能看到你的屏幕 · 点这里可关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(stopPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止共享", stopPi)
                .build();
    }

    private void notifyCaptured() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        nm.notify(NOTIF_SHOT, new NotificationCompat.Builder(this, CHAN_SHOT)
                .setContentTitle("费佳看了你的屏幕一眼")
                .setContentText(time)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build());
    }
}

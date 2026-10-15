package com.pixai.nexus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.webkit.WebView;

import androidx.core.app.NotificationCompat;

/**
 * Tiến trình dịch vụ độc lập (:registrar) dùng riêng cho việc tạo tài khoản PixAI ngầm.
 * Cải tiến nâng cao:
 * - Chạy dưới dạng Foreground Service với Notification liên tục để không bao giờ bị Android tắt khi chuyển app/tắt màn hình.
 * - Khóa giữ CPU (WakeLock) để tiến trình mạng và WebView tiếp tục xử lý mượt mà khi màn hình khóa.
 * - Cơ chế Watchdog Safety Timer chống treo vĩnh viễn (tự động timeout & giải phóng tài nguyên sau 90s).
 */
public class HeadlessRegService extends Service {

    public static final int CODE_PROGRESS = 1;
    public static final int CODE_SUCCESS = 2;
    public static final int CODE_ERROR = 3;

    private static final String CHANNEL_ID = "pixai_reg_channel";
    private static final int NOTIFICATION_ID = 8801;

    private static boolean dataDirInitialized = false;
    private HeadlessAccountRegistrar registrar;
    private PowerManager.WakeLock wakeLock;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!dataDirInitialized) {
            dataDirInitialized = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    WebView.setDataDirectorySuffix("pixai_isolated_registrar");
                } catch (Throwable ignored) {}
            }
        }

        acquireWakeLock();
        startForegroundWithNotification("Đang chuẩn bị tiến trình độc lập...", "PixAI Registrar");

        registrar = new HeadlessAccountRegistrar(getApplicationContext());
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixAI:RegWakeLock");
                wakeLock.setReferenceCounted(false);
                // Giữ tối đa 4 phút để chống kiệt pin
                wakeLock.acquire(4 * 60 * 1000L);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tiến Trình Tạo Tài Khoản PixAI",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Duy trì kết nối và tạo tài khoản ngầm an toàn không bị gián đoạn");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundWithNotification(String content, String title) {
        createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable ex) {
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable ignored) {}
        }
    }

    private void updateNotification(String content) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("PixAI Registrar")
                        .setContentText(content)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW);
                nm.notify(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String targetSite = intent.getStringExtra("targetSite");
        final String email = intent.getStringExtra("email");
        final String password = intent.getStringExtra("password");
        final boolean useProxy = intent.getBooleanExtra("useProxy", true);
        final String proxyAddr = intent.getStringExtra("proxyAddr");
        final boolean dohEnabled = intent.getBooleanExtra("dohEnabled", true);
        final boolean antiDpiEnabled = intent.getBooleanExtra("antiDpiEnabled", false);
        final boolean strictKillSwitch = intent.getBooleanExtra("strictKillSwitch", true);
        final ResultReceiver receiver = intent.getParcelableExtra("receiver");

        if (email == null || password == null || receiver == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        updateNotification("Đang xử lý: " + email);

        // Thiết lập Watchdog: Nếu sau 90 giây không phản hồi thì tự động hủy an toàn
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
        }
        watchdogRunnable = () -> {
            try {
                Bundle b = new Bundle();
                b.putString("error", "WATCHDOG_TIMEOUT: Quá thời gian xử lý (90 giây). Hệ thống tự bảo vệ và hủy phiên.");
                receiver.send(CODE_ERROR, b);
            } catch (Throwable ignored) {}
            stopSelf();
        };
        watchdogHandler.postDelayed(watchdogRunnable, 90 * 1000L);

        // Đồng bộ danh sách Proxy trong Pool vào tiến trình :registrar & tạo hạt giống Vân Tay Thiết Bị mới
        try {
            AccountStorage regStorage = new AccountStorage(getApplicationContext());
            SecurityShieldManager.syncAndApplySecurityConfig(regStorage);
        } catch (Throwable ignored) {}
        int freshRegSeed = (int) ((System.nanoTime() ^ email.hashCode()) & 0x7FFFFFFF);
        SecurityShieldManager.applyDirectConfig(useProxy, proxyAddr, dohEnabled, antiDpiEnabled, strictKillSwitch, freshRegSeed);

        HeadlessAccountRegistrar.RegistrationCallback regCallback = new HeadlessAccountRegistrar.RegistrationCallback() {
            @Override
            public void onProgress(String message, String color) {
                try {
                    Bundle b = new Bundle();
                    b.putString("message", message);
                    b.putString("color", color);
                    receiver.send(CODE_PROGRESS, b);
                    updateNotification(message);
                } catch (Exception ignored) {}
            }

            @Override
            public void onSuccess(String regEmail, String regPassword, String token) {
                cancelWatchdog();
                try {
                    Bundle b = new Bundle();
                    b.putString("email", regEmail);
                    b.putString("password", regPassword);
                    b.putString("token", token);
                    receiver.send(CODE_SUCCESS, b);
                } catch (Exception ignored) {}
                stopSelf();
            }

            @Override
            public void onError(String error) {
                cancelWatchdog();
                try {
                    Bundle b = new Bundle();
                    b.putString("error", error);
                    receiver.send(CODE_ERROR, b);
                } catch (Exception ignored) {}
                stopSelf();
            }
        };

        if ("webshare".equalsIgnoreCase(targetSite)) {
            registrar.startHeadlessWebshareRegistration(email, password, regCallback);
        } else {
            registrar.startHeadlessRegistration(email, password, regCallback);
        }

        return START_NOT_STICKY;
    }

    private void cancelWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    @Override
    public void onDestroy() {
        cancelWatchdog();
        releaseWakeLock();
        if (registrar != null) {
            registrar.destroy();
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

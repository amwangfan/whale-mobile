package com.wangfanlab.whale.voip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP long-poll client for Aliyun ring hub (sibling signaling/ring_hub.py).
 *
 * Poll:  GET {base}/v1/poll?device_id=magic8&wait=55[&token=]
 * Event: {"action":"ring","number":"...","name":"...","bridge_host":"...","bridge_port":18799}
 * Also accepts legacy {"cmd":"ring",...} from box mock hub.
 */
public class SignalService extends Service {
    public static final String ACTION_STATUS = "com.wangfanlab.whale.voip.SIGNAL_STATUS";
    private static final String CH_ID = "nova_signal";
    private static final int NOTIF_ID = 18800;
    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static volatile String sStatus = "未启动";

    private Thread mWorker;
    private final AtomicBoolean mStop = new AtomicBoolean(false);
    private PowerManager.WakeLock mWakeLock;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public static String statusText() {
        return sStatus;
    }

    public static boolean isRunning() {
        return sRunning.get();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, SignalService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, SignalService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        FileLog.init(this);
        ensureChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:signal");
                mWakeLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            FileLog.e("wakelock", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotif("远端待命中…"));
        if (mWorker == null || !mWorker.isAlive()) {
            mStop.set(false);
            mWorker = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "nova-signal-poll");
            mWorker.setDaemon(true);
            mWorker.start();
        }
        sRunning.set(true);
        setStatus("连接中…");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mStop.set(true);
        sRunning.set(false);
        setStatus("已停止");
        releaseWake();
        if (mWorker != null) {
            try { mWorker.interrupt(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        int backoffMs = 1000;
        while (!mStop.get()) {
            if (!SignalPrefs.isEnabled(this)) {
                setStatus("已关闭（设置里开启）");
                sleepQuiet(3000);
                continue;
            }
            acquireWake();
            try {
                setStatus("长轮询 " + SignalPrefs.getUrl(this));
                JSONObject resp = longPoll();
                backoffMs = 1000;
                if (resp == null) continue;

                // Sibling hub: {ok, messages:[...]}
                if (resp.has("messages")) {
                    JSONArray arr = resp.optJSONArray("messages");
                    if (arr == null || arr.length() == 0) {
                        setStatus("待命 · 心跳 OK");
                        continue;
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject ev = arr.optJSONObject(i);
                        if (ev != null) handleEvent(ev);
                    }
                    continue;
                }

                // Legacy single-event hub (box mock): {cmd:ring|timeout|...}
                handleEvent(resp);
            } catch (Throwable t) {
                FileLog.e("signal poll", t);
                setStatus("重连中: " + shortErr(t));
                sleepQuiet(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30000);
            } finally {
                releaseWake();
            }
        }
        sRunning.set(false);
    }

    private void handleEvent(JSONObject ev) {
        if (ev == null) return;
        String action = ev.optString("action", "");
        if (action.length() == 0) action = ev.optString("cmd", "");
        FileLog.i("signal event action=" + action + " " + ev);
        if ("timeout".equals(action) || "hello".equals(action)
                || "pong".equals(action) || "keepalive".equals(action)) {
            setStatus("待命 · 上次心跳刚才");
            return;
        }
        if ("ring".equals(action)) {
            handleRing(ev);
            setStatus("已触发来电");
            return;
        }
        if ("hangup".equals(action)) {
            mMain.post(new Runnable() {
                @Override public void run() {
                    try {
                        AudioBridge.stop();
                        FakeConnectionService.hangupAll();
                    } catch (Throwable t) {
                        FileLog.e("remote hangup", t);
                    }
                }
            });
            setStatus("远端挂断");
            return;
        }
        setStatus("待命 · action=" + action);
    }

    private JSONObject longPoll() throws Exception {
        String base = SignalPrefs.getUrl(this);
        String device = SignalPrefs.getDeviceId(this);
        StringBuilder ub = new StringBuilder();
        // Prefer sibling API; fall back path kept for local mock via prefs override.
        if (base.contains(":18800") || base.contains("v1") || true) {
            ub.append(base).append("/v1/poll?device_id=")
                    .append(java.net.URLEncoder.encode(device, "UTF-8"))
                    .append("&wait=50");
        }
        String token = SignalPrefs.getToken(this);
        String url = ub.toString();
        if (url.startsWith("http://") &&
                !(url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))) {
            throw new java.io.IOException("Remote signaling requires HTTPS");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(70000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "NovaFakeCall-Signal/1.6");
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setInstanceFollowRedirects(true);
        int code;
        try {
            code = conn.getResponseCode();
        } catch (java.net.SocketTimeoutException ste) {
            // treat as empty poll
            conn.disconnect();
            return new JSONObject("{\"ok\":true,\"messages\":[]}");
        }
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        conn.disconnect();

        // If sibling path 404, try legacy /wait once (box mock).
        if (code == 404) {
            return longPollLegacy();
        }
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + " " + trimBody(body));
        }
        if (body == null || body.trim().length() == 0) {
            return new JSONObject("{\"ok\":true,\"messages\":[]}");
        }
        return new JSONObject(body);
    }

    private JSONObject longPollLegacy() throws Exception {
        String base = SignalPrefs.getUrl(this);
        String device = SignalPrefs.getDeviceId(this);
        String url = base + "/wait?device=" + java.net.URLEncoder.encode(device, "UTF-8")
                + "&timeout=55";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(70000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("legacy HTTP " + code + " " + trimBody(body));
        }
        if (body == null || body.trim().length() == 0) return null;
        return new JSONObject(body);
    }

    private void handleRing(final JSONObject ev) {
        final String number = textOr(ev, "number", "***");
        final String name = textOr(ev, "name", "AI 助手");
        String host = textOr(ev, "bridge_host", null);
        if (host == null || host.length() == 0) {
            host = BridgePrefs.getHost(this);
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                host = "127.0.0.1";
            }
        }
        final String bridgeHost = host;
        int port = BridgePrefs.getPort(this);
        if (ev.has("bridge_port")) {
            port = ev.optInt("bridge_port", port);
        }
        if (port <= 0) port = 18799;
        final int bridgePort = port;

        try {
            BridgePrefs.set(this, bridgeHost, bridgePort);
        } catch (Throwable ignored) {}

        updateNotif("来电: " + name);
        mMain.post(new Runnable() {
            @Override public void run() {
                try {
                    CallAccountHelper.ring(SignalService.this, number, name, bridgeHost, bridgePort);
                    FileLog.i("remote ring ok " + number + " bridge=" + bridgeHost + ":" + bridgePort);
                } catch (Throwable t) {
                    FileLog.e("remote ring failed", t);
                    setStatus("来电失败: " + shortErr(t));
                }
            }
        });
    }

    private static String textOr(JSONObject o, String key, String def) {
        if (o == null || !o.has(key) || o.isNull(key)) return def;
        String v = o.optString(key, def);
        if (v == null || v.trim().length() == 0) return def;
        return v.trim();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String trimBody(String body) {
        if (body == null) return "";
        String t = body.trim();
        return t.length() > 180 ? t.substring(0, 180) : t;
    }

    private static String shortErr(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    private void setStatus(String s) {
        sStatus = s == null ? "" : s;
        try {
            Intent i = new Intent(ACTION_STATUS);
            i.setPackage(getPackageName());
            i.putExtra("status", sStatus);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
        updateNotif(sStatus);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CH_ID, "远端来电待命", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持与阿里云信令连接，以便远端触发来电");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif(String text) {
        Intent open = new Intent(this, BootstrapActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Nova 远端待命")
                .setContentText(text == null ? "" : text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void updateNotif(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif(text));
        } catch (Throwable ignored) {}
    }

    private void acquireWake() {
        try {
            if (mWakeLock != null && !mWakeLock.isHeld()) mWakeLock.acquire(60_000L * 3);
        } catch (Throwable ignored) {}
    }

    private void releaseWake() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
        } catch (Throwable ignored) {}
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

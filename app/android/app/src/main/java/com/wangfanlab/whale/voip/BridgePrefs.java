package com.wangfanlab.whale.voip;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configurable AudioBridge host/port. 契约 media_endpoint=(b)：默认东京固定单端口
 * mux 100.100.154.46:18799，会话靠媒体首帧 AUTH/session_id 区分；adb reverse
 * 开发形态经模块卡显式覆盖为 127.0.0.1。坑③修正：不再指向 N100 中继。
 */
public final class BridgePrefs {
    private static final String PREFS = "nova_bridge";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "media_token";

    /** 契约默认：东京单端口 mux（Tailscale 100.100.154.46:18799）。 */
    public static final String DEFAULT_HOST = "100.100.154.46";
    public static final int DEFAULT_PORT = 18799;

    private BridgePrefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getHost(Context ctx) {
        String h = sp(ctx).getString(KEY_HOST, DEFAULT_HOST);
        if (h == null || h.length() == 0) return DEFAULT_HOST;
        return h.trim();
    }

    public static int getPort(Context ctx) {
        int p = sp(ctx).getInt(KEY_PORT, DEFAULT_PORT);
        return p > 0 ? p : DEFAULT_PORT;
    }

    public static void set(Context ctx, String host, int port) {
        String h = (host == null || host.trim().length() == 0) ? DEFAULT_HOST : host.trim();
        int p = port > 0 ? port : DEFAULT_PORT;
        sp(ctx).edit().putString(KEY_HOST, h).putInt(KEY_PORT, p).apply();
    }

    /** Independent BRIDGE_TOKEN; never migrate/fall back to signaling credentials. */
    public static String getToken(Context ctx) {
        String token = sp(ctx).getString(KEY_TOKEN, "");
        return token == null ? "" : token;
    }

    /** Empty explicitly clears media authorization; callers must not log the value. */
    public static void setToken(Context ctx, String token) {
        String value = token == null ? "" : token;
        if (value.length() > 4096) throw new IllegalArgumentException("Invalid media token");
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("Invalid media token");
            }
        }
        sp(ctx).edit().putString(KEY_TOKEN, value).apply();
    }

    public static String summary(Context ctx) {
        return getHost(ctx) + ":" + getPort(ctx);
    }
}

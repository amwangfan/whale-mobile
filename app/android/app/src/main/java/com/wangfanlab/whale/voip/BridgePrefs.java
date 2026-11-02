package com.wangfanlab.whale.voip;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configurable AudioBridge host/port for non-root (adb reverse → 127.0.0.1)
 * or Tailscale / LAN. Defaults favor USB reverse for Magic8 Pro smoke tests.
 */
public final class BridgePrefs {
    private static final String PREFS = "nova_bridge";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";

    /** Dev default: phone → adb reverse → box :18799 */
    public static final String DEFAULT_HOST = "127.0.0.1";
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

    public static String summary(Context ctx) {
        return getHost(ctx) + ":" + getPort(ctx);
    }
}

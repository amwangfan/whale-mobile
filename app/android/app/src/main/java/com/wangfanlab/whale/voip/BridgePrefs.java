package com.wangfanlab.whale.voip;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configurable AudioBridge host/port. 占位符化（2026-11-27 Tob 裁决 c）：默认
 * 空=未配置语义（真实桥坐标——tailnet/公网 IP——不入仓，隐私纪律）；真实端点
 * 走设备配置注入（模块卡 UI / 零回显通道写本 prefs）。未配置时 AudioBridge
 * fail-closed 不开 socket（与媒体 token 空拒连同口径）。
 */
public final class BridgePrefs {
    private static final String PREFS = "nova_bridge";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "media_token";

    /** 占位默认：空 host + 端口 0 = 未配置（真实坐标只在设备 prefs）。 */
    public static final String DEFAULT_HOST = "";
    public static final int DEFAULT_PORT = 0;

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

    /** 桥端点是否已配置（host 非空且端口 1..65535）。 */
    public static boolean isConfigured(Context ctx) {
        return AudioBridge.isEndpointConfigured(getHost(ctx), getPort(ctx));
    }

    public static String summary(Context ctx) {
        return isConfigured(ctx) ? getHost(ctx) + ":" + getPort(ctx) : "未配置";
    }
}

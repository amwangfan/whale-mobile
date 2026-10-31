package com.wangfanlab.whale.voip;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remote ring signaling endpoint (HTTP long-poll / WS hub on Aliyun).
 */
public final class SignalPrefs {
    private static final String PREFS = "nova_signal";
    private static final String KEY_URL = "base_url";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TOKEN = "token";

    /** Public Aliyun ring hub (sibling audio bridge on :18799). */
    public static final String DEFAULT_URL = "http://127.0.0.1:18799";
    public static final String DEFAULT_DEVICE = "magic8";

    private SignalPrefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getUrl(Context ctx) {
        String u = sp(ctx).getString(KEY_URL, DEFAULT_URL);
        if (u == null || u.trim().length() == 0) return DEFAULT_URL;
        return u.trim().replaceAll("/+$", "");
    }

    public static void setUrl(Context ctx, String url) {
        String u = (url == null || url.trim().length() == 0) ? DEFAULT_URL : url.trim();
        u = u.replaceAll("/+$", "");
        sp(ctx).edit().putString(KEY_URL, u).apply();
    }

    public static String getDeviceId(Context ctx) {
        String d = sp(ctx).getString(KEY_DEVICE, DEFAULT_DEVICE);
        if (d == null || d.trim().length() == 0) return DEFAULT_DEVICE;
        return d.trim();
    }

    public static void setDeviceId(Context ctx, String id) {
        String d = (id == null || id.trim().length() == 0) ? DEFAULT_DEVICE : id.trim();
        sp(ctx).edit().putString(KEY_DEVICE, d).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static String getToken(Context ctx) {
        String tok = sp(ctx).getString(KEY_TOKEN, "");
        return tok == null ? "" : tok.trim();
    }

    public static void setToken(Context ctx, String token) {
        String tok = token == null ? "" : token.trim();
        sp(ctx).edit().putString(KEY_TOKEN, tok).apply();
    }

    public static String summary(Context ctx) {
        String tok = getToken(ctx);
        String tokHint = (tok.length() == 0) ? "token=空" : "token=已设";
        return (isEnabled(ctx) ? "开" : "关") + " · " + getUrl(ctx)
                + " · id=" + getDeviceId(ctx) + " · " + tokHint;
    }
}

package com.wangfanlab.whale.voip;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debuggable on-device log for Honor devices that encrypt logcat.
 *
 * C 线修复（2026-11-26，实证：ring 事件零落盘、nova.log 停 11-22）：
 * 旧版三宗罪——①init 只在各 Service/Activity.onCreate，WSS 信令路径
 * （VoipModule/CallAccountHelper）先于任何组件打点就静默丢弃；②init 失败仅
 * Log.w 进（被加密的）logcat；③append 写失败 catch 全吞。修复：
 * WhaleApp.onCreate 最早 init + append 惰性自举 + 失败一律上抛 logcat。
 */
public final class FileLog {
    private static final String TAG = "WhaleVoip";
    private static final Object LOCK = new Object();
    private static volatile File sFile;

    private FileLog() {}

    public static void init(Context ctx) {
        if (ctx == null) return;
        try {
            File dir = ctx.getApplicationContext().getFilesDir();
            sFile = new File(dir, "nova.log");
        } catch (Throwable t) {
            // 失败上抛 logcat（吞异常=排障失明）
            Log.e(TAG, "FileLog init failed", t);
        }
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        append("I", msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        append("E", msg + " :: " + (t == null ? "" : t.toString()));
    }

    private static void append(String level, String msg) {
        File f = sFile;
        if (f == null) {
            // 惰性自举：打点先于任何组件 init（WSS 信令路径）时经 ActivityThread
            // 取应用上下文补 init；仍失败 → logcat 可见的丢弃告警（不再静默）。
            Context c = appContext();
            if (c != null) init(c);
            f = sFile;
        }
        if (f == null) {
            Log.w(TAG, "FileLog dropped (uninitialized): " + msg);
            return;
        }
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + " " + msg + "\n";
        synchronized (LOCK) {
            try {
                FileOutputStream out = new FileOutputStream(f, true);
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable t) {
                // 写失败上抛 logcat（原来 catch 全吞）
                Log.e(TAG, "FileLog write failed: " + f, t);
            }
        }
    }

    /** 与 AudioBridge 同款：无 Context 场景经 ActivityThread 取当前 Application。 */
    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    @androidx.annotation.VisibleForTesting
    public static void setFileForTest(File f) {
        sFile = f;
    }
}

package com.wangfanlab.whale.voip;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Debuggable on-device log for Honor devices that encrypt logcat. */
public final class FileLog {
    private static final String TAG = "WhaleVoip";
    private static final Object LOCK = new Object();
    private static File sFile;

    private FileLog() {}

    public static void init(Context ctx) {
        if (ctx == null) return;
        try {
            File dir = ctx.getApplicationContext().getFilesDir();
            sFile = new File(dir, "nova.log");
        } catch (Throwable t) {
            Log.w(TAG, "FileLog init", t);
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
        if (f == null) return;
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + " " + msg + "\n";
        synchronized (LOCK) {
            try {
                FileOutputStream out = new FileOutputStream(f, true);
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Throwable ignored) {}
        }
    }
}

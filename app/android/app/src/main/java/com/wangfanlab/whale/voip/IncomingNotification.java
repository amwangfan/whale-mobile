package com.wangfanlab.whale.voip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * High-priority call-style notification with full-screen intent.
 * Required on Android 10+/Honor: ConnectionService startActivity is often BAL-blocked.
 */
public final class IncomingNotification {
    public static final String CH_ID = "nova_incoming_call";
    public static final int NOTIF_ID = 18801;

    private IncomingNotification() {}

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CH_ID, "来电", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Nova 来电全屏提示");
        ch.setSound(null, null);
        ch.enableVibration(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    public static void show(Context ctx, String number, String name,
                            String bridgeHost, int bridgePort) {
        if (ctx == null) return;
        ensureChannel(ctx);
        Context app = ctx.getApplicationContext();

        Intent full = new Intent(app, IncomingCallActivity.class);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        full.putExtra("number", number);
        full.putExtra("name", name);
        full.putExtra("bridge_host", bridgeHost);
        full.putExtra("bridge_port", bridgePort);
        full.putExtra("from_fsi", true);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent fullPi = PendingIntent.getActivity(app, 1, full, piFlags);

        // 宿主整合：RingReceiver 未并入（生产不走广播触发）；通知按钮改为显式
        // Activity intent（notification_action extra，IncomingCallActivity 处理）。
        Intent answer = new Intent(app, IncomingCallActivity.class);
        answer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        answer.putExtra("notification_action", "answer");
        answer.putExtra("number", number);
        answer.putExtra("name", name);
        answer.putExtra("bridge_host", bridgeHost);
        answer.putExtra("bridge_port", bridgePort);
        PendingIntent answerPi = PendingIntent.getActivity(app, 2, answer, piFlags);

        Intent decline = new Intent(app, IncomingCallActivity.class);
        decline.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        decline.putExtra("notification_action", "decline");
        decline.putExtra("number", number);
        decline.putExtra("name", name);
        PendingIntent declinePi = PendingIntent.getActivity(app, 3, decline, piFlags);

        String title = (name != null && name.length() > 0) ? name : "来电";
        String text = (number != null && number.length() > 0) ? number : "正在呼叫…";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(app, CH_ID);
        } else {
            b = new Notification.Builder(app);
            b.setPriority(Notification.PRIORITY_MAX);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(android.R.drawable.ic_menu_call, "接听", answerPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", declinePi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFF34A853);
        }

        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, b.build());
            FileLog.i("IncomingNotification.show FSI posted name=" + name + " number=" + number);
        }

        // Also try launching UI directly (often BAL-blocked from ConnectionService).
        tryLaunchUi(app, full);
    }

    public static void tryLaunchUi(Context ctx, Intent full) {
        if (ctx == null || full == null) return;
        try {
            ctx.startActivity(full);
            FileLog.i("IncomingNotification.startActivity OK");
        } catch (Throwable t) {
            FileLog.e("IncomingNotification.startActivity BAL/blocked", t);
            // Fallback: fire the full-screen PendingIntent itself.
            try {
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent.getActivity(ctx, 1, full, piFlags).send();
                FileLog.i("IncomingNotification.PendingIntent.send OK");
            } catch (Throwable t2) {
                FileLog.e("IncomingNotification.PendingIntent.send failed", t2);
            }
        }
        // Re-assert after a short delay (Honor sometimes drops the first launch).
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    ctx.startActivity(full);
                    FileLog.i("IncomingNotification.relaunch OK");
                } catch (Throwable t) {
                    FileLog.e("IncomingNotification.relaunch blocked", t);
                }
            }
        }, 400);
    }

    public static void cancel(Context ctx) {
        try {
            Context app = ctx != null ? ctx.getApplicationContext() : null;
            if (app == null) return;
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
            FileLog.i("IncomingNotification.cancel");
        } catch (Throwable t) {
            FileLog.e("IncomingNotification.cancel", t);
        }
    }
}

package com.wangfanlab.whale.voip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restart signal long-poll after reboot / app update when enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (a == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(a)) {
            return;
        }
        FileLog.init(context);
        if (!SignalPrefs.isEnabled(context)) {
            FileLog.i("BootReceiver skip (signal disabled)");
            return;
        }
        try {
            SignalService.start(context);
            FileLog.i("BootReceiver started SignalService");
        } catch (Throwable t) {
            FileLog.e("BootReceiver start", t);
        }
    }
}

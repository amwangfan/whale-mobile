package com.wangfanlab.whale.voip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RingReceiver extends BroadcastReceiver {
    private static final String TAG = "WhaleVoip";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "RingReceiver " + action);
        if ("com.wangfanlab.whale.voip.ACTION_RING".equals(action)) {
            String number = intent.getStringExtra("number");
            String name = intent.getStringExtra("name");
            if (number == null || number.length() == 0) number = "***";
            if (name == null || name.length() == 0) name = "***";
            String bridgeHost = intent.getStringExtra("bridge_host");
            if (bridgeHost == null || bridgeHost.length() == 0) {
                bridgeHost = BridgePrefs.getHost(context);
            }
            int bridgePort = BridgePrefs.getPort(context);
            String ps = intent.getStringExtra("bridge_port");
            if (ps != null) {
                try { bridgePort = Integer.parseInt(ps); } catch (NumberFormatException ignored) {}
            }
            CallAccountHelper.ring(context, number, name, bridgeHost, bridgePort);
        } else if ("com.wangfanlab.whale.voip.ACTION_HANGUP".equals(action)) {
            IncomingNotification.cancel(context);
            AudioBridge.stop();
            FakeConnectionService.hangupAll();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        } else if ("com.wangfanlab.whale.voip.ACTION_ANSWER".equals(action)) {
            IncomingNotification.cancel(context);
            FakeConnectionService.answerActive();
            Intent ui = new Intent(context, InCallActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            String number = intent.getStringExtra("number");
            String name = intent.getStringExtra("name");
            if (number != null) ui.putExtra("number", number);
            if (name != null) ui.putExtra("name", name);
            String bridgeHost = intent.getStringExtra("bridge_host");
            if (bridgeHost != null) ui.putExtra("bridge_host", bridgeHost);
            String ps = intent.getStringExtra("bridge_port");
            if (ps != null) ui.putExtra("bridge_port", ps);
            // also pull from active connection if extras missing
            FakeConnectionService.FakeConnection c = FakeConnectionService.sActive;
            if (c != null) {
                if (number == null) ui.putExtra("number", c.number);
                if (name == null) ui.putExtra("name", c.name);
                ui.putExtra("bridge_host", c.bridgeHost);
                ui.putExtra("bridge_port", c.bridgePort);
            }
            ui.putExtra("answered", true);
            try {
                context.startActivity(ui);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "answer startActivity", t);
            }
        }
    }
}

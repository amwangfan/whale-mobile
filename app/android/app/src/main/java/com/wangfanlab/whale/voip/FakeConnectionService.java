package com.wangfanlab.whale.voip;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

public class FakeConnectionService extends ConnectionService {
    private static final String TAG = "WhaleVoip";
    public static volatile FakeConnection sActive;
    private static volatile FakeConnectionService sInstance;

    /**
     * 宿主整合：通话状态回调钩子（VoipModule 注册，用于上行 sensor.event
     * call.state/call.answered/call.ended）。state ∈ ringing / active / ended；
     * reason 仅 ended 时有值（local / rejected）。
     */
    public interface CallStateListener {
        void onCallStateChanged(String state, String number, String name, String reason);
    }

    public static volatile CallStateListener sCallStateListener;

    static void notifyCallState(String state, FakeConnection c, String reason) {
        // VOICE-01：通话态前台服务随状态机进出（active→前台，ended→退出）
        FakeConnectionService svc = sInstance;
        if (svc != null) {
            if ("active".equals(state)) promoteCallForeground(svc);
            else if ("ended".equals(state)) dropCallForeground(svc);
        }
        CallStateListener l = sCallStateListener;
        if (l == null || c == null) return;
        try {
            l.onCallStateChanged(state, c.number, c.name, reason);
        } catch (Throwable t) {
            Log.w(TAG, "CallStateListener", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        FileLog.init(this);
        IncomingNotification.ensureChannel(this);
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle handle, ConnectionRequest request) {
        FileLog.i("onCreateIncomingConnection " + request);
        String number = "***";
        String name = "***";
        String bridgeHost = BridgePrefs.DEFAULT_HOST;
        int bridgePort = BridgePrefs.DEFAULT_PORT;
        try {
            bridgeHost = BridgePrefs.getHost(this);
            bridgePort = BridgePrefs.getPort(this);
        } catch (Throwable ignored) {}
        Uri address = request.getAddress();
        if (address == null && request.getExtras() != null) {
            address = request.getExtras().getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS);
        }
        if (address != null && address.getSchemeSpecificPart() != null
                && address.getSchemeSpecificPart().length() > 0) {
            number = address.getSchemeSpecificPart();
        }
        Bundle extras = request.getExtras();
        if (extras != null) {
            if (extras.getString("display_name") != null
                    && extras.getString("display_name").length() > 0) {
                name = extras.getString("display_name");
            }
            if (extras.getString("bridge_host") != null
                    && extras.getString("bridge_host").length() > 0) {
                bridgeHost = extras.getString("bridge_host");
            }
            if (extras.containsKey("bridge_port")) {
                Object bp = extras.get("bridge_port");
                if (bp instanceof Integer) bridgePort = (Integer) bp;
                else if (bp instanceof String) {
                    try { bridgePort = Integer.parseInt((String) bp); } catch (NumberFormatException ignored) {}
                }
            }
        }

        FakeConnection conn = new FakeConnection(number, name, bridgeHost, bridgePort, true);
        conn.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        conn.setAddress(Uri.fromParts("tel", number, null), TelecomManager.PRESENTATION_ALLOWED);
        conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED);
        conn.setAudioModeIsVoip(true);
        // Stay RINGING until user answers — never setActive / AudioBridge here.
        conn.setRinging();
        sActive = conn;
        RingToneHelper.start(this);
        FileLog.i("incoming STATE_RINGING number=" + number + " name=" + name
                + " (no bridge yet)");
        notifyCallState("ringing", conn, null);

        // Primary path for Honor/Android 10+: FSI notification (BAL-safe).
        IncomingNotification.show(this, number, name, bridgeHost, bridgePort);
        return conn;
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle handle, ConnectionRequest request) {
        FileLog.i("onCreateOutgoingConnection " + request + " handle=" + handle);
        Uri address = request.getAddress();
        String number = address != null ? address.getSchemeSpecificPart() : "1";
        String name = "AI 助手";
        String bridgeHost = BridgePrefs.DEFAULT_HOST;
        int bridgePort = BridgePrefs.DEFAULT_PORT;
        try {
            bridgeHost = BridgePrefs.getHost(this);
            bridgePort = BridgePrefs.getPort(this);
        } catch (Throwable ignored) {}
        boolean managed = false;
        Bundle extras = request.getExtras();
        if (extras != null) {
            if (extras.getString("display_name") != null
                    && extras.getString("display_name").length() > 0) {
                name = extras.getString("display_name");
            }
            if (extras.getString("bridge_host") != null
                    && extras.getString("bridge_host").length() > 0) {
                bridgeHost = extras.getString("bridge_host");
            }
            if (extras.containsKey("bridge_port")) {
                Object bp = extras.get("bridge_port");
                if (bp instanceof Integer) bridgePort = (Integer) bp;
                else if (bp instanceof String) {
                    try { bridgePort = Integer.parseInt((String) bp); } catch (NumberFormatException ignored) {}
                }
            }
            managed = extras.getBoolean("nova_ai_managed", false);
        }
        if (handle != null && CallAccountHelper.ACCOUNT_ID_AI_OUT.equals(handle.getId())) {
            managed = true;
        }

        String norm = normalize(number);
        if (managed && !"1".equals(norm)) {
            Log.w(TAG, "CallProvider rejecting non-1 number: " + number);
            FakeConnection bad = new FakeConnection(number, name, bridgeHost, bridgePort, false);
            bad.setAddress(Uri.fromParts("tel", number == null ? "" : number, null),
                    TelecomManager.PRESENTATION_ALLOWED);
            bad.setDisconnected(new DisconnectCause(DisconnectCause.ERROR, "仅支持拨 1"));
            bad.destroy();
            return bad;
        }

        FakeConnection conn = new FakeConnection(number, name, bridgeHost, bridgePort, !managed);
        if (!managed) {
            conn.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        }
        if (address != null) {
            conn.setAddress(address, TelecomManager.PRESENTATION_ALLOWED);
        } else {
            conn.setAddress(Uri.fromParts("tel", number, null), TelecomManager.PRESENTATION_ALLOWED);
        }
        conn.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED);
        conn.setAudioModeIsVoip(true);
        conn.setDialing();
        sActive = conn;

        if (!managed) {
            Intent ui = new Intent(this, InCallActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            ui.putExtra("number", number);
            ui.putExtra("name", name);
            ui.putExtra("bridge_host", bridgeHost);
            ui.putExtra("bridge_port", bridgePort);
            ui.putExtra("answered", true);
            try {
                startActivity(ui);
            } catch (Throwable t) {
                FileLog.e("outgoing InCallActivity start blocked", t);
            }
        }
        conn.setActive();
        AudioBridge.start(this, bridgeHost, bridgePort);
        FileLog.i("outgoing AI ACTIVE managed=" + managed + " bridge=" + bridgeHost + ":" + bridgePort);
        notifyCallState("active", conn, null);
        return conn;
    }

    public static void hangupAll() {
        RingToneHelper.stop();
        AudioBridge.stop();
        FakeConnectionService svc = sInstance;
        if (svc != null) IncomingNotification.cancel(svc);
        FakeConnection c = sActive;
        if (c != null) {
            try {
                c.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                c.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "hangup", t);
            }
            sActive = null;
            notifyCallState("ended", c, "local");
        }
        FileLog.i("hangupAll done");
    }

    /** VOICE-01：通话期间以 phoneCall 类型前台服务保活（API 29+ 带类型）。 */
    private static void promoteCallForeground(FakeConnectionService svc) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(svc, "whale_call")
                    .setContentTitle("Whale 通话中")
                    .setContentText("语音桥连接保持中")
                    .setSmallIcon(com.wangfanlab.whale.R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .build();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                svc.startForeground(42002, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                svc.startForeground(42002, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "promoteCallForeground", t);
        }
    }

    private static void dropCallForeground(FakeConnectionService svc) {
        try {
            svc.stopForeground(true);
        } catch (Throwable t) {
            Log.w(TAG, "dropCallForeground", t);
        }
    }

    public static void answerActive() {
        RingToneHelper.stop();
        FakeConnection c = sActive;
        FakeConnectionService svc = sInstance;
        if (svc != null) IncomingNotification.cancel(svc);
        if (c != null) {
            c.setActive();
            notifyCallState("active", c, null);
            FileLog.i("answerActive → STATE_ACTIVE bridge=" + c.bridgeHost + ":" + c.bridgePort);
            if (svc != null) {
                AudioBridge.start(svc, c.bridgeHost, c.bridgePort);
            } else {
                AudioBridge.start(c.bridgeHost, c.bridgePort);
            }
        } else {
            FileLog.i("answerActive: no active connection");
        }
    }

    /** True if current connection is already ACTIVE. */
    public static boolean isActiveCall() {
        FakeConnection c = sActive;
        if (c == null) return false;
        try {
            return c.getState() == Connection.STATE_ACTIVE;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRingingCall() {
        FakeConnection c = sActive;
        if (c == null) return false;
        try {
            return c.getState() == Connection.STATE_RINGING;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Number normalization (carried over from the dropped OutgoingCallReceiver). */
    private static String normalize(String number) {
        if (number == null) return null;
        String n = number.trim();
        n = n.replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        return n;
    }

    public static class FakeConnection extends Connection {
        final String number;
        final String name;
        final String bridgeHost;
        final int bridgePort;
        final boolean selfManaged;

        FakeConnection(String number, String name, String bridgeHost, int bridgePort,
                       boolean selfManaged) {
            this.number = number;
            this.name = name;
            this.bridgeHost = bridgeHost;
            this.bridgePort = bridgePort;
            this.selfManaged = selfManaged;
        }

        @Override
        public void onAnswer() {
            FileLog.i("Connection.onAnswer (Telecom) → ACTIVE");
            RingToneHelper.stop();
            IncomingNotification.cancel(sInstance != null ? sInstance : null);
            setActive();
            notifyCallState("active", this, null);
            FakeConnectionService svc = sInstance;
            if (svc != null) {
                AudioBridge.start(svc, bridgeHost, bridgePort);
            } else {
                AudioBridge.start(bridgeHost, bridgePort);
            }
            if (selfManaged && svc != null) {
                Intent ui = new Intent(svc, InCallActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                ui.putExtra("number", number);
                ui.putExtra("name", name);
                ui.putExtra("bridge_host", bridgeHost);
                ui.putExtra("bridge_port", bridgePort);
                ui.putExtra("answered", true);
                try {
                    svc.startActivity(ui);
                } catch (Throwable t) {
                    FileLog.e("onAnswer InCallActivity blocked", t);
                }
            }
        }

        @Override
        public void onAnswer(int videoState) {
            onAnswer();
        }

        @Override
        public void onReject() {
            FileLog.i("Connection.onReject");
            RingToneHelper.stop();
            AudioBridge.stop();
            IncomingNotification.cancel(sInstance != null ? sInstance : null);
            setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
            destroy();
            if (sActive == this) sActive = null;
            notifyCallState("ended", this, "rejected");
        }

        @Override
        public void onDisconnect() {
            FileLog.i("Connection.onDisconnect");
            RingToneHelper.stop();
            AudioBridge.stop();
            IncomingNotification.cancel(sInstance != null ? sInstance : null);
            setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            destroy();
            if (sActive == this) sActive = null;
            notifyCallState("ended", this, "local");
        }

        @Override
        public void onAbort() {
            onDisconnect();
        }

        @Override
        public void onCallAudioStateChanged(CallAudioState state) {
            if (state == null) return;
            int route = state.getRoute();
            boolean speaker = (route == CallAudioState.ROUTE_SPEAKER);
            Log.i(TAG, "onCallAudioStateChanged route=" + route + " speaker=" + speaker
                    + " supported=" + state.getSupportedRouteMask());
            // Only apply speaker while ACTIVE — avoid flipping audio during RINGING.
            if (getState() == STATE_ACTIVE) {
                AudioBridge.setSpeakerphone(speaker);
            }
        }

        @Override
        public void onShowIncomingCallUi() {
            FileLog.i("onShowIncomingCallUi → FSI + IncomingCallActivity");
            FakeConnectionService svc = sInstance;
            if (svc != null) RingToneHelper.start(svc);
            if (svc != null) {
                IncomingNotification.show(svc, number, name, bridgeHost, bridgePort);
            }
        }
    }
}

package com.wangfanlab.whale.voip;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Non-root launcher: place/simulate VoIP calls to AudioBridge without SSH/root.
 */
public class BootstrapActivity extends Activity {
    private static final String TAG = "WhaleVoip";
    private static final int REQ_PERMS = 1001;

    private TextView statusView;
    private Button speakerBtn;
    private boolean speakerOn;
    private TextView signalStatusView;
    private final BroadcastReceiver signalReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            refreshSignalStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.init(this);
        FileLog.i("Bootstrap onCreate");
        try {
            CallAccountHelper.ensureRegistered(this);
            FileLog.i("accounts registered");
        } catch (Throwable t) {
            FileLog.e("ensureRegistered", t);
        }
        requestNeededPermissions();
        setContentView(buildUi());
        refreshStatus();
        maybeRequestBatteryExempt();
        maybeRequestFullScreenIntent();
        IncomingNotification.ensureChannel(this);
        startSignalIfEnabled();
        refreshSignalStatus();
        handleLaunchExtras(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchExtras(intent);
    }

    private void handleLaunchExtras(android.content.Intent intent) {
        if (intent == null) return;
        String auto = intent.getStringExtra("auto");
        if (auto == null) auto = intent.getAction();
        if ("call_ai".equals(auto) || "com.wangfanlab.whale.voip.ACTION_CALL_AI".equals(auto)) {
            statusView.postDelayed(new Runnable() {
                @Override public void run() {
                    startAiCallWithFallback();
                }
            }, 600);
        } else if ("ring".equals(auto) || "com.wangfanlab.whale.voip.ACTION_SIM_RING".equals(auto)) {
            statusView.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        CallAccountHelper.ring(BootstrapActivity.this, "***", "AI 助手",
                                BridgePrefs.getHost(BootstrapActivity.this),
                                BridgePrefs.getPort(BootstrapActivity.this));
                    } catch (Throwable t) {
                        Log.e(TAG, "auto ring", t);
                    }
                }
            }, 600);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshSignalStatus();
        registerSignalReceiver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(signalReceiver); } catch (Throwable ignored) {}
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 72, 48, 72);
        root.setBackgroundColor(Color.parseColor("#FF121212"));

        TextView title = new TextView(this);
        title.setText("Nova AI Call");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("非 Root · 自管 VoIP · v1.7-user · 远端来电");
        sub.setTextColor(Color.parseColor("#FF9E9E9E"));
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 8, 0, 24);
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#FF90CAF9"));
        statusView.setTextSize(14);
        statusView.setPadding(16, 16, 16, 16);
        statusView.setBackgroundColor(Color.parseColor("#FF1E1E1E"));
        root.addView(statusView);

        root.addView(spacer(24));
        root.addView(makeBtn("拨打 AI", Color.parseColor("#FF1A73E8"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                startAiCallWithFallback();
            }
        }));

        root.addView(spacer(12));
        root.addView(makeBtn("模拟来电（响铃接听）", Color.parseColor("#FF34A853"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureMicGranted()) return;
                try {
                    String host = BridgePrefs.getHost(BootstrapActivity.this);
                    int port = BridgePrefs.getPort(BootstrapActivity.this);
                    CallAccountHelper.ring(BootstrapActivity.this, "***", "AI 助手", host, port);
                    Toast.makeText(BootstrapActivity.this, "来电中…", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.e(TAG, "ring", t);
                    Toast.makeText(BootstrapActivity.this,
                            "来电失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }));

        root.addView(spacer(12));
        root.addView(makeBtn("设置桥地址", Color.parseColor("#FF546E7A"), new View.OnClickListener() {
            @Override public void onClick(View v) { showBridgeDialog(); }
        }));

        root.addView(spacer(12));
        speakerBtn = makeBtn("扬声器：关", Color.parseColor("#FF546E7A"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                speakerOn = !speakerOn;
                AudioBridge.setSpeakerphone(speakerOn);
                refreshSpeakerBtn();
            }
        });
        root.addView(speakerBtn);

        root.addView(spacer(12));
        root.addView(makeBtn("远端待命：开/关", Color.parseColor("#FF7B1FA2"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !SignalPrefs.isEnabled(BootstrapActivity.this);
                SignalPrefs.setEnabled(BootstrapActivity.this, on);
                if (on) {
                    startSignalIfEnabled();
                    Toast.makeText(BootstrapActivity.this, "远端待命已开", Toast.LENGTH_SHORT).show();
                } else {
                    SignalService.stop(BootstrapActivity.this);
                    Toast.makeText(BootstrapActivity.this, "远端待命已关", Toast.LENGTH_SHORT).show();
                }
                refreshSignalStatus();
            }
        }));

        root.addView(spacer(12));
        root.addView(makeBtn("设置信令地址", Color.parseColor("#FF6A1B9A"), new View.OnClickListener() {
            @Override public void onClick(View v) { showSignalDialog(); }
        }));

        root.addView(spacer(12));
        signalStatusView = new TextView(this);
        signalStatusView.setTextColor(Color.parseColor("#FFCE93D8"));
        signalStatusView.setTextSize(13);
        signalStatusView.setPadding(16, 12, 16, 12);
        signalStatusView.setBackgroundColor(Color.parseColor("#FF1E1E1E"));
        root.addView(signalStatusView);

        root.addView(spacer(24));
        TextView tip = new TextView(this);
        tip.setText("远端来电：curl -X POST http://127.0.0.1:18799/v1/ring\\n"
                + "App 需开着/待命通知在；建议忽略电池优化\\n"
                + "桥默认可由信令下发 127.0.0.1:18799\\n"
                + "USB 开发仍可用 adb reverse + 127.0.0.1");
        tip.setTextColor(Color.parseColor("#FF757575"));
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        root.addView(tip);

        scroll.addView(root);
        return scroll;
    }

    private Button makeBtn(String text, int bg, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(bg);
        b.setPadding(32, 36, 32, 36);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private void refreshStatus() {
        if (statusView == null) return;
        String mic = hasMic() ? "已授权" : "未授权";
        String phone = hasCallPhone() ? "已授权" : "未授权";
        statusView.setText("桥: " + BridgePrefs.summary(this)
                + "\n信令: " + SignalPrefs.summary(this)
                + "\n麦克风: " + mic + "  ·  电话: " + phone
                + "\n点「拨打 AI」或等远端 curl /ring");
        speakerOn = AudioBridge.isSpeakerphoneOn();
        refreshSpeakerBtn();
    }

    private void refreshSpeakerBtn() {
        if (speakerBtn == null) return;
        if (speakerOn) {
            speakerBtn.setText("扬声器：开");
            speakerBtn.setBackgroundColor(Color.parseColor("#FF34A853"));
        } else {
            speakerBtn.setText("扬声器：关");
            speakerBtn.setBackgroundColor(Color.parseColor("#FF546E7A"));
        }
    }

    private void showBridgeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);

        final EditText hostEdit = new EditText(this);
        hostEdit.setHint("主机 (127.0.0.1 或 Tailscale IP)");
        hostEdit.setText(BridgePrefs.getHost(this));
        hostEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(hostEdit);

        final EditText portEdit = new EditText(this);
        portEdit.setHint("端口");
        portEdit.setText(String.valueOf(BridgePrefs.getPort(this)));
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(portEdit);

        new AlertDialog.Builder(this)
                .setTitle("音频桥地址")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String h = hostEdit.getText().toString().trim();
                        int port = BridgePrefs.DEFAULT_PORT;
                        try {
                            port = Integer.parseInt(portEdit.getText().toString().trim());
                        } catch (NumberFormatException ignored) {}
                        BridgePrefs.set(BootstrapActivity.this, h, port);
                        refreshStatus();
                        Toast.makeText(BootstrapActivity.this,
                                "已保存 " + BridgePrefs.summary(BootstrapActivity.this),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        BridgePrefs.set(BootstrapActivity.this,
                                BridgePrefs.DEFAULT_HOST, BridgePrefs.DEFAULT_PORT);
                        refreshStatus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }


    private void startAiCallWithFallback() {
        if (!ensureMicGranted()) return;
        final String host = BridgePrefs.getHost(this);
        final int port = BridgePrefs.getPort(this);
        FileLog.i("startAiCallWithFallback bridge=" + host + ":" + port);
        boolean placed = false;
        try {
            CallAccountHelper.placeOutgoingAi(this, host, port);
            placed = true;
            Toast.makeText(this, "正在拨打 AI…", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            FileLog.e("placeOutgoingAi", t);
        }
        // Honor/API35: if Telecom does not bind our CS quickly, still open App InCall + bridge.
        final boolean placedFinal = placed;
        statusView.postDelayed(new Runnable() {
            @Override public void run() {
                if (FakeConnectionService.sActive != null) {
                    FileLog.i("telecom connection active — no fallback needed");
                    return;
                }
                FileLog.i("fallback direct InCall+AudioBridge (placed=" + placedFinal + ")");
                try {
                    android.content.Intent ui = new android.content.Intent(
                            BootstrapActivity.this, InCallActivity.class);
                    ui.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    ui.putExtra("number", "1");
                    ui.putExtra("name", "AI 助手");
                    ui.putExtra("bridge_host", host);
                    ui.putExtra("bridge_port", port);
                    startActivity(ui);
                    AudioBridge.start(BootstrapActivity.this, host, port);
                    Toast.makeText(BootstrapActivity.this,
                            "已直连音频桥 " + host + ":" + port, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    FileLog.e("fallback failed", t);
                    Toast.makeText(BootstrapActivity.this,
                            "拨打失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }, 1200);
    }


    private void maybeRequestFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.canUseFullScreenIntent()) {
                FileLog.i("fullScreenIntent already allowed");
                return;
            }
            android.content.SharedPreferences sp =
                    getSharedPreferences("nova_boot", MODE_PRIVATE);
            if (sp.getBoolean("fsi_asked", false)) {
                FileLog.i("fullScreenIntent not allowed (already prompted)");
                return;
            }
            sp.edit().putBoolean("fsi_asked", true).apply();
            FileLog.i("requesting fullScreenIntent permission once");
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            FileLog.e("fullScreenIntent request", t);
        }
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CALL_PHONE);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= 26
                && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_NUMBERS);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    private boolean hasMic() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCallPhone() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureMicGranted() {
        if (hasMic()) return true;
        Toast.makeText(this, "请先授予麦克风权限", Toast.LENGTH_LONG).show();
        requestNeededPermissions();
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    private void startSignalIfEnabled() {
        if (!SignalPrefs.isEnabled(this)) return;
        try {
            SignalService.start(this);
            FileLog.i("SignalService start requested");
        } catch (Throwable t) {
            FileLog.e("SignalService start", t);
            Toast.makeText(this, "信令服务启动失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void registerSignalReceiver() {
        try {
            IntentFilter f = new IntentFilter(SignalService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(signalReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(signalReceiver, f);
            }
        } catch (Throwable t) {
            FileLog.e("registerSignalReceiver", t);
        }
    }

    private void refreshSignalStatus() {
        if (signalStatusView == null) return;
        String st = SignalService.statusText();
        boolean run = SignalService.isRunning();
        signalStatusView.setText("远端待命: " + (run ? "运行中" : "未运行")
                + "\n" + st
                + "\n" + SignalPrefs.summary(this));
    }

    private void showSignalDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);

        final EditText urlEdit = new EditText(this);
        urlEdit.setHint("信令 URL (http://host:18800)");
        urlEdit.setText(SignalPrefs.getUrl(this));
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(urlEdit);

        final EditText idEdit = new EditText(this);
        idEdit.setHint("设备 id");
        idEdit.setText(SignalPrefs.getDeviceId(this));
        idEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(idEdit);

        final EditText tokenEdit = new EditText(this);
        tokenEdit.setHint("鉴权 Token（与服务器 SIGNAL_TOKEN 一致）");
        tokenEdit.setText(SignalPrefs.getToken(this));
        tokenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(tokenEdit);

        new AlertDialog.Builder(this)
                .setTitle("远端信令")
                .setView(box)
                .setPositiveButton("保存并重连", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        SignalPrefs.setUrl(BootstrapActivity.this, urlEdit.getText().toString());
                        SignalPrefs.setDeviceId(BootstrapActivity.this, idEdit.getText().toString());
                        SignalPrefs.setToken(BootstrapActivity.this, tokenEdit.getText().toString());
                        SignalPrefs.setEnabled(BootstrapActivity.this, true);
                        SignalService.stop(BootstrapActivity.this);
                        statusView.postDelayed(new Runnable() {
                            @Override public void run() {
                                startSignalIfEnabled();
                                refreshStatus();
                                refreshSignalStatus();
                            }
                        }, 400);
                    }
                })
                .setNeutralButton("恢复默认", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        SignalPrefs.setUrl(BootstrapActivity.this, SignalPrefs.DEFAULT_URL);
                        SignalPrefs.setDeviceId(BootstrapActivity.this, SignalPrefs.DEFAULT_DEVICE);
                        SignalPrefs.setToken(BootstrapActivity.this, "");
                        refreshStatus();
                        refreshSignalStatus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void maybeRequestBatteryExempt() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            FileLog.e("battery exempt", t);
        }
    }
}

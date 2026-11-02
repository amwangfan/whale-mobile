package com.wangfanlab.whale.voip;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wangfanlab.whale.R; // 宿主 R（宿主内模块 R 为 com.wangfanlab.whale.R）

public class IncomingCallActivity extends Activity {
    private String number = "***";
    private String name = "***";
    private String bridgeHost = BridgePrefs.DEFAULT_HOST;
    private int bridgePort = BridgePrefs.DEFAULT_PORT;
    private PowerManager.WakeLock wakeLock;
    private TextView nameView;
    private TextView numView;
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.init(this);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "nova:fakecall");
            wakeLock.acquire(60_000);
        }

        applyExtras(getIntent());
        FileLog.i("IncomingCallActivity.onCreate RINGING name=" + name
                + " number=" + number
                + " from_fsi=" + (getIntent() != null && getIntent().getBooleanExtra("from_fsi", false))
                + " connRinging=" + FakeConnectionService.isRingingCall());

        // 宿主整合：通知栏「接听/挂断」按钮经 notification_action extra 到达
        // （原 RingReceiver 广播路径未并入宿主）。
        String notifAction = getIntent() != null
                ? getIntent().getStringExtra("notification_action") : null;
        if ("decline".equals(notifAction)) {
            doDecline();
            return;
        }
        if ("answer".equals(notifAction)) {
            doAnswer();
            return;
        }

        RingToneHelper.start(this);

        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1F2E, 0xFF0B0E14, 0xFF121212});
        root.setBackground(bg);
        root.setPadding(dp(24), dp(96), dp(24), dp(48));

        TextView label = new TextView(this);
        label.setText("来电");
        label.setTextColor(0xFFB0B8C4);
        label.setTextSize(15);
        label.setGravity(Gravity.CENTER);
        root.addView(label);

        TextView avatar = new TextView(this);
        String letter = name != null && name.length() > 0 ? name.substring(0, 1) : "N";
        avatar.setText(letter);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(40);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF3D5A80);
        avatar.setBackground(circle);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(112), dp(112));
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.topMargin = dp(36);
        root.addView(avatar, ap);

        nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(34);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setGravity(Gravity.CENTER);
        nameView.setPadding(0, dp(28), 0, dp(8));
        root.addView(nameView);

        numView = new TextView(this);
        numView.setText(number);
        numView.setTextColor(0xFFCFD8DC);
        numView.setTextSize(18);
        numView.setGravity(Gravity.CENTER);
        root.addView(numView);

        TextView carrier = new TextView(this);
        carrier.setText("中国联通 · VoIP");
        carrier.setTextColor(0xFF90A4AE);
        carrier.setTextSize(13);
        carrier.setGravity(Gravity.CENTER);
        carrier.setPadding(0, dp(12), 0, 0);
        root.addView(carrier);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(dp(24), dp(16), dp(24), dp(24));

        LinearLayout declineCol = roundAction("挂断", 0xFFE53935,
                R.drawable.ic_call_end, new View.OnClickListener() {
            @Override public void onClick(View v) { doDecline(); }
        });
        LinearLayout answerCol = roundAction("接听", 0xFF43A047,
                R.drawable.ic_call_answer, new View.OnClickListener() {
            @Override public void onClick(View v) { doAnswer(); }
        });

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(declineCol, bp);
        buttons.addView(answerCol, bp);
        root.addView(buttons);

        setContentView(root);
    }

    private LinearLayout roundAction(String label, int color, int iconRes,
                                     View.OnClickListener click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout btn = new FrameLayout(this);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        btn.setBackground(oval);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(click);
        int pad = dp(18);
        btn.setPadding(pad, pad, pad, pad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setColorFilter(Color.WHITE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        btn.addView(icon, ilp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
        col.addView(btn, lp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFE0E0E0);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(10), 0, 0);
        col.addView(tv);
        return col;
    }

    private void doDecline() {
        if (finishing) return;
        finishing = true;
        FileLog.i("IncomingCallActivity decline");
        RingToneHelper.stop();
        IncomingNotification.cancel(this);
        AudioBridge.stop();
        FakeConnectionService.hangupAll();
        finish();
    }

    private void doAnswer() {
        if (finishing) return;
        finishing = true;
        FileLog.i("IncomingCallActivity answer → InCall");
        RingToneHelper.stop();
        IncomingNotification.cancel(this);
        FakeConnectionService.answerActive();
        Intent ui = new Intent(this, InCallActivity.class);
        ui.putExtra("number", number);
        ui.putExtra("name", name);
        ui.putExtra("bridge_host", bridgeHost);
        ui.putExtra("bridge_port", bridgePort);
        ui.putExtra("answered", true);
        ui.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(ui);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyExtras(intent);
        // 宿主整合：通知按钮 action 也可能经 onNewIntent 到达（Activity 已在前台）。
        String notifAction = intent != null
                ? intent.getStringExtra("notification_action") : null;
        if ("decline".equals(notifAction)) {
            doDecline();
            return;
        }
        if ("answer".equals(notifAction)) {
            doAnswer();
            return;
        }
        refreshUi();
        FileLog.i("IncomingCallActivity.onNewIntent still RINGING name=" + name);
        RingToneHelper.start(this);
    }

    private void applyExtras(Intent in) {
        if (in == null) return;
        String n = in.getStringExtra("number");
        String m = in.getStringExtra("name");
        if (n != null && n.length() > 0) number = n;
        else number = "***";
        if (m != null && m.length() > 0) name = m;
        else name = "***";
        String h = in.getStringExtra("bridge_host");
        if (h != null && h.length() > 0) bridgeHost = h;
        int p = in.getIntExtra("bridge_port", -1);
        if (p > 0) bridgePort = p;
        else {
            String ps = in.getStringExtra("bridge_port");
            if (ps != null) {
                try { bridgePort = Integer.parseInt(ps); } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void refreshUi() {
        if (nameView != null) nameView.setText(name);
        if (numView != null) numView.setText(number);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    @Override
    public void onBackPressed() {
        // Stay on incoming UI — user must answer or decline.
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}

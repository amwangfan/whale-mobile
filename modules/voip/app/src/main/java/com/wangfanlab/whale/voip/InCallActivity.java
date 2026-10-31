package com.wangfanlab.whale.voip;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.telecom.CallAudioState;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class InCallActivity extends Activity {
    private final Handler handler = new Handler();
    private int seconds = 0;
    private TextView timer;
    private TextView nameView;
    private TextView numView;
    private Button speakerBtn;
    private String number = "***";
    private String name = "***";
    private String bridgeHost = BridgePrefs.DEFAULT_HOST;
    private int bridgePort = BridgePrefs.DEFAULT_PORT;
    private boolean answered;
    private boolean bridgeStarted;
    private boolean speakerOn;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            seconds++;
            if (timer != null) {
                int m = seconds / 60;
                int s = seconds % 60;
                timer.setText(String.format("%02d:%02d", m, s));
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileLog.init(this);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        applyExtras(getIntent());
        FileLog.i("InCallActivity.onCreate answered=" + answered
                + " connActive=" + FakeConnectionService.isActiveCall()
                + " name=" + name);

        // If opened while still ringing (should not happen), bounce to Incoming UI.
        if (!answered && !FakeConnectionService.isActiveCall()
                && FakeConnectionService.isRingingCall()) {
            FileLog.i("InCallActivity opened while RINGING → redirect Incoming");
            Intent ui = new Intent(this, IncomingCallActivity.class);
            ui.putExtra("number", number);
            ui.putExtra("name", name);
            ui.putExtra("bridge_host", bridgeHost);
            ui.putExtra("bridge_port", bridgePort);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(ui);
            finish();
            return;
        }

        buildUi();
        handler.postDelayed(tick, 1000);
        maybeStartBridge();
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

        nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(30);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setGravity(Gravity.CENTER);
        root.addView(nameView);

        numView = new TextView(this);
        numView.setText(number);
        numView.setTextColor(0xFFCFD8DC);
        numView.setTextSize(16);
        numView.setGravity(Gravity.CENTER);
        numView.setPadding(0, dp(8), 0, 0);
        root.addView(numView);

        timer = new TextView(this);
        timer.setText("00:00");
        timer.setTextColor(0xFFB0BEC5);
        timer.setTextSize(18);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, dp(20), 0, 0);
        root.addView(timer);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), 0, dp(8), dp(28));

        speakerOn = AudioBridge.isSpeakerphoneOn();
        speakerBtn = circleCtrl(speakerOn ? "扬声器开" : "扬声器", speakerOn ? 0xFF43A047 : 0xFF455A64,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        speakerOn = !speakerOn;
                        AudioBridge.setSpeakerphone(speakerOn);
                        FakeConnectionService.FakeConnection c = FakeConnectionService.sActive;
                        if (c != null) {
                            try {
                                c.setAudioRoute(speakerOn
                                        ? CallAudioState.ROUTE_SPEAKER
                                        : CallAudioState.ROUTE_EARPIECE);
                            } catch (Throwable ignored) {}
                        }
                        refreshSpeakerButton();
                    }
                });
        controls.addView(circleCtrl("静音", 0xFF455A64, null), ctrlLp());
        controls.addView(speakerBtn, ctrlLp());
        controls.addView(circleCtrl("键盘", 0xFF455A64, null), ctrlLp());
        root.addView(controls);

        FrameLayout hangup = new FrameLayout(this);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(0xFFE53935);
        hangup.setBackground(oval);
        hangup.setClickable(true);
        hangup.setFocusable(true);
        int hPad = dp(20);
        hangup.setPadding(hPad, hPad, hPad, hPad);
        ImageView hangIcon = new ImageView(this);
        hangIcon.setImageResource(R.drawable.ic_call_end);
        hangIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hangIcon.setColorFilter(Color.WHITE);
        hangup.addView(hangIcon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        hangup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FileLog.i("InCallActivity hangup");
                AudioBridge.setSpeakerphone(false);
                AudioBridge.stop();
                IncomingNotification.cancel(InCallActivity.this);
                FakeConnectionService.hangupAll();
                finish();
            }
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(76), dp(76));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(16);
        root.addView(hangup, hp);

        TextView hangLabel = new TextView(this);
        hangLabel.setText("挂断");
        hangLabel.setTextColor(0xFFE0E0E0);
        hangLabel.setTextSize(14);
        hangLabel.setGravity(Gravity.CENTER);
        root.addView(hangLabel);

        setContentView(root);
    }

    private LinearLayout.LayoutParams ctrlLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        return p;
    }

    private Button circleCtrl(String label, int color, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        b.setBackground(oval);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (click != null) b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
        lp.gravity = Gravity.CENTER;
        // Button will get weight from parent addView params; size set when added with ctrlLp — override:
        b.setMinWidth(dp(72));
        b.setMinHeight(dp(72));
        return b;
    }

    private void refreshSpeakerButton() {
        if (speakerBtn == null) return;
        speakerBtn.setText(speakerOn ? "扬声器开" : "扬声器");
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(speakerOn ? 0xFF43A047 : 0xFF455A64);
        speakerBtn.setBackground(oval);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyExtras(intent);
        if (nameView != null) nameView.setText(name);
        if (numView != null) numView.setText(number);
        maybeStartBridge();
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
        answered = in.getBooleanExtra("answered", false)
                || FakeConnectionService.isActiveCall();
    }

    /**
     * Only start AudioBridge after the call is answered / ACTIVE.
     * Never start from a premature InCallActivity open.
     */
    private void maybeStartBridge() {
        if (bridgeStarted) return;
        if (!answered && !FakeConnectionService.isActiveCall()) {
            FileLog.i("InCallActivity skip bridge (not answered yet)");
            return;
        }
        FileLog.i("InCallActivity start bridge " + bridgeHost + ":" + bridgePort);
        AudioBridge.start(this, bridgeHost, bridgePort);
        bridgeStarted = true;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}

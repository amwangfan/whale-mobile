package com.wangfanlab.whale.voip;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Plays bundled domestic-style ringtone + vibrate for self-managed incoming calls.
 * Telecom PROPERTY_SELF_MANAGED does not play ringtone for us.
 */
public final class RingToneHelper {
    private static final String TAG = "WhaleVoip";
    private static final long[] VIBRATE_PATTERN = new long[]{0, 1000, 1000};

    private static final Object LOCK = new Object();
    private static MediaPlayer sPlayer;
    private static Ringtone sRingtone; // fallback only
    private static Vibrator sVibrator;
    private static boolean sPlaying;

    private RingToneHelper() {}

    public static void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (sPlaying) {
                Log.i(TAG, "ringtone already playing");
                return;
            }
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int vol = am.getStreamVolume(AudioManager.STREAM_RING);
                Log.i(TAG, "STREAM_RING volume=" + vol
                        + "/" + am.getStreamMaxVolume(AudioManager.STREAM_RING));
            }

            boolean started = startRaw(app);
            if (!started) {
                started = startDefaultFallback(app);
            }

            try {
                Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0));
                    } else {
                        v.vibrate(VIBRATE_PATTERN, 0);
                    }
                    sVibrator = v;
                    Log.i(TAG, "vibrate started");
                }
            } catch (Throwable t) {
                Log.w(TAG, "vibrate start failed", t);
            }
            sPlaying = started || sVibrator != null;
        }
    }

    /** Prefer bundled 国内常用铃 (res/raw/nova_ring). */
    private static boolean startRaw(Context app) {
        try {
            MediaPlayer mp = new MediaPlayer();
            Uri uri = Uri.parse("android.resource://" + app.getPackageName() + "/raw/nova_ring");
            mp.setDataSource(app, uri);
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .build());
            } else {
                mp.setAudioStreamType(AudioManager.STREAM_RING);
            }
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            sPlayer = mp;
            Log.i(TAG, "ringtone started asset=raw/nova_ring (国内清脆铃)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "raw ringtone failed, will fallback", t);
            releasePlayerLocked();
            return false;
        }
    }

    private static boolean startDefaultFallback(Context app) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            Ringtone r = RingtoneManager.getRingtone(app, uri);
            if (r == null) {
                Log.w(TAG, "no default ringtone");
                return false;
            }
            if (Build.VERSION.SDK_INT >= 28) r.setLooping(true);
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.play();
            sRingtone = r;
            Log.i(TAG, "ringtone started fallback default uri=" + uri);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "fallback ringtone failed", t);
            return false;
        }
    }

    private static void releasePlayerLocked() {
        try {
            if (sPlayer != null) {
                if (sPlayer.isPlaying()) sPlayer.stop();
                sPlayer.release();
            }
        } catch (Throwable ignored) {}
        sPlayer = null;
    }

    /** Idempotent; safe from answer/reject/hangup/onDestroy. */
    public static void stop() {
        synchronized (LOCK) {
            if (!sPlaying && sPlayer == null && sRingtone == null && sVibrator == null) {
                return;
            }
            try {
                if (sPlayer != null) {
                    if (sPlayer.isPlaying()) sPlayer.stop();
                    sPlayer.release();
                    Log.i(TAG, "ringtone stopped (raw)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "ringtone stop raw", t);
            }
            sPlayer = null;
            try {
                if (sRingtone != null) {
                    if (sRingtone.isPlaying()) sRingtone.stop();
                    Log.i(TAG, "ringtone stopped (fallback)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "ringtone stop fallback", t);
            }
            sRingtone = null;
            try {
                if (sVibrator != null) {
                    sVibrator.cancel();
                    Log.i(TAG, "vibrate stopped");
                }
            } catch (Throwable t) {
                Log.w(TAG, "vibrate stop", t);
            }
            sVibrator = null;
            sPlaying = false;
        }
    }
}

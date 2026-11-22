package com.wangfanlab.whale.voip;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import java.io.File;

/**
 * 演示语音（案二「接通后主人听到预生成人声」）。
 * 播放预生成的 files/demo_voice.wav（云端 AgentScope TTS 生成、adb push 入位），
 * 接通后在通话音频流（STREAM_VOICE_CALL → 通话中即听筒）播放。文件缺席则静默跳过。
 */
public final class DemoVoice {
    private static final String TAG = "WhaleVoipVoice";
    private static final String FILE_NAME = "demo_voice.wav";

    private static final Object LOCK = new Object();
    private static MediaPlayer sPlayer;

    private DemoVoice() {}

    /** 响铃时调用：确认预生成语音在位（日志用）。 */
    public static void prepareAsync(Context ctx) {
        if (ctx == null) return;
        File f = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
        FileLog.i("DemoVoice 预生成语音" + (f.exists() ? "在位 " + f.length() + "B" : "缺席"));
    }

    /** 接通时调用：播放预生成语音到听筒；文件缺席则跳过。 */
    public static void playOnAnswer(Context ctx) {
        if (ctx == null) return;
        File f = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
        if (!f.exists()) {
            FileLog.i("DemoVoice 无预生成语音，跳过播放");
            return;
        }
        stop();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    .build());
            mp.setOnCompletionListener(m -> stop());
            mp.prepare();
            mp.start();
            sPlayer = mp;
            FileLog.i("DemoVoice 播放预生成语音（STREAM_VOICE_CALL→听筒）");
        } catch (Throwable t) {
            Log.w(TAG, "playback failed", t);
            FileLog.e("DemoVoice 播放失败", t);
            stop();
        }
    }

    /** 挂断/拒接/结束时停。幂等。 */
    public static void stop() {
        synchronized (LOCK) {
            try {
                if (sPlayer != null) {
                    if (sPlayer.isPlaying()) sPlayer.stop();
                    sPlayer.release();
                }
            } catch (Throwable ignored) {}
            sPlayer = null;
        }
    }
}

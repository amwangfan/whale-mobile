package com.wangfanlab.whale.voip;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bidirectional PCM bridge over TCP to the Grok Bot box (Tailscale).
 * Frame: [uint32 BE length][uint8 type][payload]
 * type 0 = PCM s16le mono 16kHz; type 1 = control UTF-8 text.
 */
public final class AudioBridge {
    private static final String TAG = "WhaleVoip";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 18799;
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;
    public static final byte TYPE_PCM = 0;
    public static final byte TYPE_CTRL = 1;

    private static volatile AudioBridge sInstance;

    private final String host;
    private final int port;
    private final String authToken;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private Socket socket;
    private AudioRecord recorder;
    private AudioTrack player;

    public static synchronized void start(String host, int port) {
        start(null, host, port);
    }

    public static synchronized void start(Context ctx, String host, int port) {
        stop();
        Context safeCtx = ctx != null ? ctx : appContext();
        String auth = safeCtx == null ? "" : BridgePrefs.getToken(safeCtx);
        if (auth.isEmpty()) {
            FileLog.i("AudioBridge refused: configure independent media token locally");
            return; // Fail closed before opening a socket or starting microphone capture.
        }
        // Defense in depth: call extras/remote payloads never select a credential recipient.
        String h = BridgePrefs.getHost(safeCtx);
        int p = BridgePrefs.getPort(safeCtx);
        applyVoipAudio(safeCtx);
        AudioBridge b = new AudioBridge(h, p, auth);
        sInstance = b;
        b.startInternal();
    }


    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    public static void setSpeakerphone(boolean on) {
        try {
            Context c = appContext();
            if (c == null) return;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(on);
            Log.i(TAG, "AudioBridge speakerphone=" + on);
        } catch (Throwable t) {
            Log.w(TAG, "setSpeakerphone", t);
        }
    }

    public static boolean isSpeakerphoneOn() {
        try {
            Context c = appContext();
            if (c == null) return false;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isSpeakerphoneOn();
        } catch (Throwable t) {
            return false;
        }
    }

    /** MODE_IN_COMMUNICATION + voice-call focus so earpiece / VoIP path works. */
    private static void applyVoipAudio(Context ctx) {
        try {
            Context c = ctx;
            if (c == null) {
                try {
                    Class<?> at = Class.forName("android.app.ActivityThread");
                    Object app = at.getMethod("currentApplication").invoke(null);
                    if (app instanceof Context) c = (Context) app;
                } catch (Throwable ignored) {}
            }
            if (c == null) return;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            am.setSpeakerphoneOn(false);
            Log.i(TAG, "AudioBridge VoIP mode MODE_IN_COMMUNICATION");
        } catch (Throwable t) {
            Log.w(TAG, "applyVoipAudio", t);
        }
    }

    public static synchronized void stop() {
        AudioBridge b = sInstance;
        sInstance = null;
        if (b != null) b.stopInternal();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                AudioManager am = (AudioManager) ((Context) app).getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    try { am.setSpeakerphoneOn(false); } catch (Throwable ignored) {}
                    am.setMode(AudioManager.MODE_NORMAL);
                    am.abandonAudioFocus(null);
                }
            }
        } catch (Throwable ignored) {}
    }

    private AudioBridge(String host, int port, String authToken) {
        this.host = host;
        this.port = port;
        this.authToken = authToken == null ? "" : authToken;
    }

    private void startInternal() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "NovaAudioBridge");
        worker.setDaemon(true);
        worker.start();
        FileLog.i("AudioBridge starting -> " + host + ":" + port);
    }

    private void stopInternal() {
        running.set(false);
        closeQuietly();
        if (worker != null) {
            try { worker.join(1500); } catch (InterruptedException ignored) {}
            worker = null;
        }
        Log.i(TAG, "AudioBridge stopped");
    }

    private void runLoop() {
        int tries = 0;
        while (running.get() && tries < 8) {
            tries++;
            try {
                connectAndStream();
            } catch (Throwable t) {
                FileLog.e("AudioBridge attempt " + tries, t);
            }
            closeQuietly();
            if (!running.get()) break;
            try { Thread.sleep(400L * tries); } catch (InterruptedException ignored) {}
        }
    }

    private void connectAndStream() throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), 5000);
        socket = s;
        FileLog.i("AudioBridge connected " + host + ":" + port);

        final DataOutputStream out = new DataOutputStream(s.getOutputStream());
        final DataInputStream in = new DataInputStream(s.getInputStream());

        writeFrame(out, TYPE_CTRL, ("AUTH " + authToken).getBytes("UTF-8"));
        writeFrame(out, TYPE_CTRL, ("HELLO nova sample=" + SAMPLE_RATE).getBytes("UTF-8"));
        // Authentication must finish before opening the microphone or sending PCM.
        // Current server sends AUTH_OK first; older handlers may send HELLO first.
        s.setSoTimeout(8000);
        String earlyHello = awaitAuth(in);
        s.setSoTimeout(0);

        int minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int minPlay = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int recBuf = Math.max(minRec, SAMPLE_RATE / 5 * 2); // ~100ms
        int playBuf = Math.max(minPlay, SAMPLE_RATE / 5 * 2);

        int audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        try {
            recorder = new AudioRecord(audioSource, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "VOICE_COMMUNICATION failed, fallback MIC", t);
            recorder = null;
        }
        if (recorder == null) {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf);
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("AudioRecord not initialized");
        }

        player = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                playBuf, AudioTrack.MODE_STREAM);
        if (player.getState() != AudioTrack.STATE_INITIALIZED) {
            player.release();
            player = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    playBuf, AudioTrack.MODE_STREAM);
        }
        player.play();
        recorder.startRecording();

        Thread rx = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (running.get() && !s.isClosed()) {
                        int len = in.readInt();
                        if (len <= 0 || len > 1_000_000) break;
                        byte type = in.readByte();
                        byte[] payload = new byte[len];
                        in.readFully(payload);
                        if (type == TYPE_PCM && player != null) {
                            player.write(payload, 0, payload.length);
                        } else if (type == TYPE_CTRL) {
                            Log.i(TAG, "ctrl from box: " + new String(payload, "UTF-8"));
                        }
                    }
                } catch (Throwable t) {
                    if (running.get()) Log.w(TAG, "rx end: " + t);
                } finally {
                    running.set(false);
                    closeQuietly();
                }
            }
        }, "NovaAudioRx");
        rx.setDaemon(true);
        rx.start();

        byte[] pcm = new byte[SAMPLE_RATE / 50 * 2]; // 20ms
        while (running.get() && !s.isClosed()) {
            int n = recorder.read(pcm, 0, pcm.length);
            if (n > 0) {
                byte[] chunk = n == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, n);
                writeFrame(out, TYPE_PCM, chunk);
            } else if (n < 0) {
                throw new IOException("AudioRecord read " + n);
            }
        }
    }

    /** Bounded control-only pre-auth handshake; HELLO is not proof of authorization. */
    private static String awaitAuth(DataInputStream in) throws IOException {
        String hello = null;
        for (int frame = 0; frame < 8; frame++) {
            int len = in.readInt();
            byte type = in.readByte();
            if (len <= 0 || len > 4096 || type != TYPE_CTRL) {
                throw new IOException("Invalid media authentication frame");
            }
            byte[] payload = new byte[len];
            in.readFully(payload);
            String line = new String(payload, "UTF-8");
            if ("AUTH_OK".equals(line)) return hello;
            if (line.startsWith("HELLO ")) {
                hello = line;
                continue;
            }
            throw new IOException("Media authentication rejected");
        }
        throw new IOException("Media authentication acknowledgement missing");
    }

    private static synchronized void writeFrame(DataOutputStream out, byte type, byte[] payload)
            throws IOException {
        out.writeInt(payload.length);
        out.writeByte(type);
        out.write(payload);
        out.flush();
    }

    private void closeQuietly() {
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Throwable ignored) {}
                try { recorder.release(); } catch (Throwable ignored) {}
                recorder = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (player != null) {
                try { player.stop(); } catch (Throwable ignored) {}
                try { player.release(); } catch (Throwable ignored) {}
                player = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
                socket = null;
            }
        } catch (Throwable ignored) {}
    }
}

"""Offline JVM regression tests for the shipped Android host VoIP implementation.

Uses only Python stdlib + JDK, generated Android fakes and a loopback mock TCP
bridge. No SDK, Gradle download, device, real signaling/media service or secrets.
"""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
COPIES = [ROOT / "app/android/app/src/main/java/com/wangfanlab/whale/voip"]

STUBS = {
    "android/content/SharedPreferences.java": '''package android.content;
import java.util.*;
public class SharedPreferences {
 private final Map<String,Object> data = new HashMap<>();
 public String getString(String k,String d){return (String)data.getOrDefault(k,d);}
 public int getInt(String k,int d){return (Integer)data.getOrDefault(k,d);}
 public Editor edit(){return new Editor();}
 public class Editor {
  public Editor putString(String k,String v){data.put(k,v);return this;}
  public Editor putInt(String k,int v){data.put(k,v);return this;}
  public void apply(){}
 }
}''',
    "android/content/Context.java": '''package android.content;
import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;
 public static final String AUDIO_SERVICE="audio";
 private final Map<String,SharedPreferences> prefs=new HashMap<>();
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String n,int mode){return prefs.computeIfAbsent(n,k->new SharedPreferences());}
 public Object getSystemService(String n){return null;}
}''',
    "android/util/Log.java": '''package android.util;
public class Log {
 public static int i(String t,String m){return 0;}
 public static int w(String t,String m){return 0;}
 public static int w(String t,String m,Throwable e){return 0;}
}''',
    "android/media/AudioFormat.java": '''package android.media;
public class AudioFormat {public static final int CHANNEL_IN_MONO=1,CHANNEL_OUT_MONO=2,ENCODING_PCM_16BIT=3;}''',
    "android/media/AudioManager.java": '''package android.media;
public class AudioManager {
 public static final int MODE_IN_COMMUNICATION=1,MODE_NORMAL=0,STREAM_VOICE_CALL=1,STREAM_MUSIC=3,AUDIOFOCUS_GAIN_TRANSIENT=1;
 public void setMode(int m){} public void setSpeakerphoneOn(boolean b){}
 public boolean isSpeakerphoneOn(){return false;}
 public void requestAudioFocus(Object l,int s,int g){} public void abandonAudioFocus(Object l){}
}''',
    "android/media/MediaRecorder.java": '''package android.media;
public class MediaRecorder {public static class AudioSource {public static final int VOICE_COMMUNICATION=1,MIC=2;}}''',
    "android/media/AudioRecord.java": '''package android.media;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1; public static int created=0;
 public AudioRecord(int a,int b,int c,int d,int e){created++;}
 public static int getMinBufferSize(int a,int b,int c){return 640;}
 public int getState(){return 1;} public void release(){} public void stop(){}
 public void startRecording(){} public int read(byte[] b,int o,int n){return -1;}
}''',
    "android/media/AudioTrack.java": '''package android.media;
public class AudioTrack {
 public static final int STATE_INITIALIZED=1,MODE_STREAM=1;
 public AudioTrack(int a,int b,int c,int d,int e,int f){}
 public static int getMinBufferSize(int a,int b,int c){return 640;}
 public int getState(){return 1;} public void release(){} public void stop(){}
 public void play(){} public int write(byte[] b,int o,int n){return n;}
}''',
    "com/wangfanlab/whale/voip/FileLog.java": '''package com.wangfanlab.whale.voip;
public class FileLog {public static void i(String m){} public static void e(String m,Throwable t){}}''',
    "org/json/JSONObject.java": '''package org.json;
import java.util.*;
public class JSONObject extends HashMap<String,Object> {
 public JSONObject put(String k,Object v){super.put(k,v);return this;}
 public boolean has(String k){return containsKey(k);} public boolean isNull(String k){return get(k)==null;}
 public String optString(String k,String d){return has(k)?String.valueOf(get(k)):d;}
 public int optInt(String k,int d){return has(k)?((Number)get(k)).intValue():d;}
}''',
}

HARNESS = '''package com.wangfanlab.whale.voip;
import android.content.Context;
import android.media.AudioRecord;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import org.json.JSONObject;
public class MediaSecurityHarness {
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 static void send(DataOutputStream out,String text)throws Exception{
  byte[] p=text.getBytes("UTF-8");out.writeInt(p.length);out.writeByte(1);out.write(p);out.flush();
 }
 static String frame(DataInputStream in)throws Exception{
  int len=in.readInt(); check(in.readByte()==1,"control frame");
  byte[] p=new byte[len];in.readFully(p);return new String(p,"UTF-8");
 }
 public static void main(String[] args)throws Exception {
  Context c=new Context();
  c.getSharedPreferences("nova_signal",0).edit().putString("token","SIGNAL_TEST_ONLY").apply();
  check(BridgePrefs.getToken(c).isEmpty(),"no signal fallback/migration");
  AudioBridge.start(c,"127.0.0.2",1);
  java.lang.reflect.Field instance=AudioBridge.class.getDeclaredField("sInstance");instance.setAccessible(true);
  check(instance.get(null)==null,"missing media token must fail closed");
  check(AudioRecord.created==0,"missing token must not open microphone");
  BridgePrefs.setToken(c,"MEDIA_TEST_ONLY");
  check(c.getSharedPreferences("nova_bridge",0).getString("media_token","").equals("MEDIA_TEST_ONLY"),"persist independent media token");
  check(c.getSharedPreferences("nova_signal",0).getString("token","").equals("SIGNAL_TEST_ONLY"),"signal unchanged");
  for(String bad:new String[]{"x y","x\\ny","x\\ry","x\\u0000y",new String(new char[4097]).replace('\\0','a')}){
   try {BridgePrefs.setToken(c,bad);throw new AssertionError("invalid token accepted");}
   catch(IllegalArgumentException expected){}
   check(BridgePrefs.getToken(c).equals("MEDIA_TEST_ONLY"),"invalid token must not overwrite");
  }
  for(boolean helloFirst:new boolean[]{false,true}) {
  try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
   server.setSoTimeout(3000);
   BridgePrefs.set(c,"127.0.0.1",server.getLocalPort());
   ExecutorService pool=Executors.newSingleThreadExecutor();
   Future<String> received=pool.submit(()->{
    try(Socket socket=server.accept()){
     socket.setSoTimeout(3000);DataInputStream in=new DataInputStream(socket.getInputStream());
     String auth=frame(in);check(frame(in).startsWith("HELLO nova"),"hello follows auth");
     DataOutputStream out=new DataOutputStream(socket.getOutputStream());
     if(helloFirst) send(out,"HELLO box mode=echo sample=16000");
     send(out,"AUTH_OK");return auth;
    }
   });
   try {
    // Deliberately hostile per-call extras: only local endpoint may receive credentials.
    AudioBridge.start(c,"127.0.0.2",1);
    check(received.get(4,TimeUnit.SECONDS).equals("AUTH MEDIA_TEST_ONLY"),"wire must use media token, local endpoint");
   } finally {AudioBridge.stop();pool.shutdownNow();}
  }
  }
  for(String response:new String[]{"AUTH_FAIL","HELLO box mode=echo sample=16000","UNEXPECTED"}){
   int before=AudioRecord.created;
   try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
    server.setSoTimeout(3000);BridgePrefs.set(c,"127.0.0.1",server.getLocalPort());
    ExecutorService pool=Executors.newSingleThreadExecutor();
    Future<Void> rejected=pool.submit(()->{
     try(Socket socket=server.accept()){
      socket.setSoTimeout(3000);DataInputStream in=new DataInputStream(socket.getInputStream());
      frame(in);frame(in);send(new DataOutputStream(socket.getOutputStream()),response);
      // HELLO alone is not authorization; EOF must not open mic either.
      if(response.startsWith("HELLO"))socket.shutdownOutput();
      check(in.read()==-1,"no PCM may precede AUTH_OK");return null;
     }
    });
    try {
     AudioBridge.start(c,"127.0.0.2",1);rejected.get(4,TimeUnit.SECONDS);
    } finally {AudioBridge.stop();pool.shutdownNow();}
    check(AudioRecord.created==before,"wrong token / HELLO-only / unexpected response must not open microphone");
   }
  }
  BridgePrefs.set(c,"local.test",18888);
  check(BridgePrefs.getToken(c).equals("MEDIA_TEST_ONLY"),"endpoint edits preserve credential");
  BridgePrefs.setToken(c,"");
  AudioBridge.start(c,"127.0.0.2",1);
  check(instance.get(null)==null,"explicit clear refuses next connection");
  LegacyRingHarness legacy=new LegacyRingHarness();
  BridgePrefs.set(legacy,"local.test",18888);
  BridgePrefs.setToken(legacy,"MEDIA_TEST_ONLY");
  legacy.ring(new JSONObject().put("number","10086").put("name","test")
       .put("bridge_host","malicious.invalid").put("bridge_port",9));
  check(LegacyRingHarness.host.equals("local.test")&&LegacyRingHarness.port==18888,"remote endpoint ignored");
  check(BridgePrefs.getHost(legacy).equals("local.test")&&BridgePrefs.getPort(legacy)==18888,"remote ring must not persist endpoint");
  check(BridgePrefs.getToken(legacy).equals("MEDIA_TEST_ONLY"),"remote ring must not mutate token");
  legacy.ring(new JSONObject());
  check(LegacyRingHarness.host.equals("local.test")&&LegacyRingHarness.port==18888,"ring without endpoints uses local config");
  System.out.println("PASS: independent persistence, fail-closed, validation, local-only wire AUTH, AUTH_OK/HELLO ordering, auth rejection before microphone, hostile legacy ring");
 }
}'''


class MediaSecurityTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_host_java_source(self):
        for source in COPIES:
            with self.subTest(source=str(source.relative_to(ROOT))), tempfile.TemporaryDirectory() as tmp:
                base = pathlib.Path(tmp)
                files = dict(STUBS)
                for name in ("BridgePrefs.java", "AudioBridge.java"):
                    files["com/wangfanlab/whale/voip/" + name] = (source / name).read_text()
                # Compile the production legacy handler verbatim with a synchronous UI fake.
                signal = (source / "SignalService.java").read_text()
                handler = signal[signal.index("    private void handleRing("):signal.index("    private static String readAll(")]
                files["com/wangfanlab/whale/voip/LegacyRingHarness.java"] = '''package com.wangfanlab.whale.voip;
import android.content.Context;
import org.json.JSONObject;
public class LegacyRingHarness extends Context {
 static String host;static int port;
 final LegacyRingHarness mMain=this;
 void post(Runnable r){r.run();} void updateNotif(String s){} void setStatus(String s){}
 static String shortErr(Throwable t){return "error";}
 void ring(JSONObject ev){handleRing(ev);}
''' + handler.replace("SignalService.this", "LegacyRingHarness.this") + "}\n"
                files["com/wangfanlab/whale/voip/CallAccountHelper.java"] = '''package com.wangfanlab.whale.voip;
import android.content.Context;
public class CallAccountHelper {public static void ring(Context c,String n,String name,String h,int p){LegacyRingHarness.host=h;LegacyRingHarness.port=p;}}'''
                files["com/wangfanlab/whale/voip/MediaSecurityHarness.java"] = HARNESS
                for name, content in files.items():
                    path = base / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(content)
                subprocess.run(["javac", "-d", str(base / "classes"), *[str(base / name) for name in files]], check=True, capture_output=True, text=True)
                result = subprocess.run(["java", "-cp", str(base / "classes"), "com.wangfanlab.whale.voip.MediaSecurityHarness"], check=True, capture_output=True, text=True, timeout=15)
                self.assertIn("PASS:", result.stdout)
                print(source.relative_to(ROOT), result.stdout.strip())


if __name__ == "__main__":
    unittest.main()

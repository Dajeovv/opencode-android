package com.opencode.launcher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * API del dispositivo para el agente, al estilo de las Termux:API pero sin la
 * app acompanion: la propia app hace de servicio y el sandbox la llama por el
 * mismo mecanismo que el instalador de paquetes.
 *
 * Solo se expone lo que no exige permisos delicados: bateria, portapapeles,
 * voz, avisos, vibracion, brillo, volumen, red y sensores. SMS, contactos,
 * camara, microfono, ubicacion o notificaciones del sistema se dejan fuera a
 * proposito.
 */
public final class DeviceApi {
    private DeviceApi() {
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static TextToSpeech tts;

    /** Ejecuta una accion. Devuelve el JSON de respuesta o null si no existe. */
    public static String run(Context ctx, String action, List<String> args) {
        try {
            switch (action == null ? "" : action) {
                case "battery":
                    return battery(ctx);
                case "clipboard-get":
                    return clipboardGet(ctx);
                case "clipboard-set":
                    return clipboardSet(ctx, arg(args, 0));
                case "tts-speak":
                    return ttsSpeak(ctx, arg(args, 0));
                case "tts-engines":
                    return ttsEngines(ctx);
                case "toast":
                    return toast(ctx, arg(args, 0));
                case "vibrate":
                    return vibrate(ctx, arg(args, 0));
                case "brightness":
                    return brightness(ctx, args);
                case "volume":
                    return volume(ctx, args);
                case "wifi-info":
                    return wifiInfo(ctx);
                case "sensor-list":
                    return sensorList(ctx);
                case "sensor":
                    return sensorSample(ctx, args);
                default:
                    return null;
            }
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", String.valueOf(e.getMessage())).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"unknown\"}";
            }
        }
    }

    private static String err(String msg) {
        try {
            return new JSONObject().put("error", msg == null ? "unknown" : msg).toString();
        } catch (Exception e) {
            return "{\"error\":\"json\"}";
        }
    }

    private static String arg(List<String> args, int i) {
        if (args == null || args.size() <= i) return "";
        return args.get(i);
    }

    // ------------------------------------------------------------ bateria

    private static String battery(Context ctx) throws Exception {
        Intent i = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject o = new JSONObject();
        if (i == null) return o.toString();
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        o.put("battery", level);
        o.put("scale", scale);
        o.put("percentage", scale > 0 ? level * 100 / scale : -1);
        o.put("status", status);
        o.put("is_charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        o.put("temperature", (double) i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        o.put("voltage", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
        o.put("technology", i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
        o.put("health", i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1));
        o.put("plugged", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
        return o.toString();
    }

    // ------------------------------------------------------- portapapeles

    private static String clipboardGet(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                o.put("text", "");
                return o.toString();
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                o.put("text", "");
                return o.toString();
            }
            CharSequence text = clip.getItemAt(0).coerceToText(ctx);
            o.put("text", text == null ? "" : text.toString());
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    private static String clipboardSet(Context ctx, String text) throws Exception {
        JSONObject o = new JSONObject();
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                o.put("error", "no clipboard");
                return o.toString();
            }
            cm.setPrimaryClip(ClipData.newPlainText("opencode", text == null ? "" : text));
            o.put("status", "ok");
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    // ---------------------------------------------------------------- voz

    private static synchronized TextToSpeech tts(Context ctx) {
        if (tts == null) {
            tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts.setLanguage(Locale.getDefault());
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        return tts;
    }

    private static String ttsSpeak(Context ctx, String text) throws Exception {
        JSONObject o = new JSONObject();
        TextToSpeech t = tts(ctx);
        if (t == null) {
            o.put("error", "no tts");
            return o.toString();
        }
        if (text == null || text.isEmpty()) {
            o.put("error", "no text");
            return o.toString();
        }
        // El motor puede tardar en iniciarse: se espera un poco la primera vez.
        for (int i = 0; i < 20 && t.isLanguageAvailable(Locale.getDefault()) < 0; i++) {
            Thread.sleep(150);
        }
        int lang = t.setLanguage(Locale.getDefault());
        int r = t.speak(text, TextToSpeech.QUEUE_ADD, null,
                "opencode-" + System.currentTimeMillis());
        o.put("status", r == TextToSpeech.SUCCESS ? "speaking" : "error");
        o.put("engine", t.getDefaultEngine());
        o.put("language", lang >= 0 ? Locale.getDefault().toString() : "unsupported");
        return o.toString();
    }

    private static String ttsEngines(Context ctx) throws Exception {
        JSONArray arr = new JSONArray();
        try {
            TextToSpeech t = tts(ctx);
            for (TextToSpeech.EngineInfo info : t.getEngines()) {
                JSONObject o = new JSONObject();
                o.put("name", info.name);
                o.put("label", info.label);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject().put("engines", arr).toString();
        } catch (Exception e) {
            return "{\"error\":\"json\"}";
        }
    }

    // -------------------------------------------------------------- avisos

    private static String toast(Context ctx, String text) throws Exception {
        JSONObject o = new JSONObject();
        try {
            final String t = text == null ? "" : text;
            MAIN.post(() -> Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show());
            o.put("status", "ok");
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    private static String vibrate(Context ctx, String ms) throws Exception {
        JSONObject o = new JSONObject();
        try {
            long duration = 500;
            try {
                if (ms != null && !ms.isEmpty()) duration = Long.parseLong(ms.trim());
            } catch (Exception ignored) {
            }
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                o.put("error", "no vibrator");
                return o.toString();
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(duration,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(duration);
            }
            o.put("status", "ok");
            o.put("duration", duration);
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    // ------------------------------------------------------------- pantalla

    private static String brightness(Context ctx, List<String> args) throws Exception {
        JSONObject o = new JSONObject();
        try {
            String value = arg(args, 0);
            if (value == null || value.isEmpty()) {
                int sys = Settings.System.getInt(ctx.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, -1);
                o.put("brightness", sys);
                o.put("max", 255);
                o.put("can_write", Settings.System.canWrite(ctx));
                return o.toString();
            }
            int v = Integer.parseInt(value.trim());
            if (!Settings.System.canWrite(ctx)) {
                o.put("error", "sin permiso WRITE_SETTINGS");
                return o.toString();
            }
            Settings.System.putInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, v);
            o.put("status", "ok");
            o.put("brightness", v);
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    private static String volume(Context ctx, List<String> args) throws Exception {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                o.put("error", "no audio");
                return o.toString();
            }
            String stream = arg(args, 0);
            int type = AudioManager.STREAM_MUSIC;
            if (stream != null) {
                if (stream.startsWith("alarm")) type = AudioManager.STREAM_ALARM;
                else if (stream.startsWith("ring")) type = AudioManager.STREAM_RING;
                else if (stream.startsWith("notification")) type = AudioManager.STREAM_NOTIFICATION;
                else if (stream.startsWith("voice")) type = AudioManager.STREAM_VOICE_CALL;
                else if (stream.startsWith("system")) type = AudioManager.STREAM_SYSTEM;
            }
            int max = am.getStreamMaxVolume(type);
            int cur = am.getStreamVolume(type);
            String value = arg(args, 1);
            if (value != null && !value.isEmpty()) {
                int v = Integer.parseInt(value.trim());
                if (v < 0) v = max + v;   // "-1" = bajar un paso
                v = Math.max(0, Math.min(max, v));
                am.setStreamVolume(type, v, 0);
                cur = v;
                o.put("status", "ok");
            }
            o.put("stream", type);
            o.put("volume", cur);
            o.put("max", max);
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    // ----------------------------------------------------------------- red

    private static String wifiInfo(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                o.put("error", "no connectivity");
                return o.toString();
            }
            Network net = cm.getActiveNetwork();
            if (net == null) {
                o.put("connected", false);
                return o.toString();
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            o.put("connected", caps != null);
            if (caps != null) {
                o.put("transport", transportName(caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI)));
                o.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                o.put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                o.put("validated", caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                o.put("type", info.getTypeName());
                o.put("connected", info.isConnected());
            }
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    private static String transportName(boolean wifi) {
        return wifi ? "WIFI" : "OTHER";
    }

    // ------------------------------------------------------------ sensores

    private static String sensorList(Context ctx) throws Exception {
        JSONArray arr = new JSONArray();
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm != null) {
                for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                    JSONObject o = new JSONObject();
                    o.put("name", s.getName());
                    o.put("type", s.getStringType());
                    o.put("vendor", s.getVendor());
                    o.put("version", s.getVersion());
                    o.put("resolution", s.getResolution());
                    o.put("power", s.getPower());
                    arr.put(o);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject().put("sensors", arr).toString();
        } catch (Exception e) {
            return "{\"error\":\"json\"}";
        }
    }

    /** Una muestra de los sensores pedidos: type=accelerometer,gyroscope… */
    private static String sensorSample(Context ctx, List<String> args) throws Exception {
        JSONArray arr = new JSONArray();
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                return new JSONObject().put("error", "no sensors").toString();
            }
            List<Sensor> wanted = new ArrayList<>();
            String spec = arg(args, 0);
            if (spec == null || spec.isEmpty()) {
                wanted.add(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
            } else {
                for (String t : spec.split(",")) {
                    int type = sensorType(t.trim());
                    if (type >= 0) {
                        Sensor s = sm.getDefaultSensor(type);
                        if (s != null) wanted.add(s);
                    }
                }
            }
            if (wanted.isEmpty()) {
                return new JSONObject().put("error", "no sensor").toString();
            }
            final Object lock = new Object();
            final boolean[] done = {false};
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (done[0]) return;
                    done[0] = true;
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", event.sensor.getName());
                        o.put("type", event.sensor.getStringType());
                        JSONArray values = new JSONArray();
                        for (float f : event.values) values.put((double) f);
                        o.put("values", values);
                        arr.put(o);
                    } catch (Exception ignored) {
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    try {
                        sm.unregisterListener(this);
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sm.registerListener(listener, wanted.get(0),
                    SensorManager.SENSOR_DELAY_NORMAL);
            for (int i = 1; i < wanted.size(); i++) {
                sm.registerListener(listener, wanted.get(i), SensorManager.SENSOR_DELAY_NORMAL);
            }
            long end = System.currentTimeMillis() + 3000;
            synchronized (lock) {
                while (!done[0] && System.currentTimeMillis() < end) {
                    lock.wait(300);
                }
            }
            try {
                sm.unregisterListener(listener);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            try {
                return new JSONObject().put("error", String.valueOf(e.getMessage())).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"json\"}";
            }
        }
        try {
            return new JSONObject().put("samples", arr).toString();
        } catch (Exception e) {
            return "{\"error\":\"json\"}";
        }
    }

    private static int sensorType(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("accel")) return Sensor.TYPE_ACCELEROMETER;
        if (n.startsWith("gyro")) return Sensor.TYPE_GYROSCOPE;
        if (n.startsWith("mag")) return Sensor.TYPE_MAGNETIC_FIELD;
        if (n.startsWith("light")) return Sensor.TYPE_LIGHT;
        if (n.startsWith("prox")) return Sensor.TYPE_PROXIMITY;
        if (n.startsWith("press")) return Sensor.TYPE_PRESSURE;
        if (n.startsWith("humid")) return Sensor.TYPE_RELATIVE_HUMIDITY;
        if (n.startsWith("temp")) return Sensor.TYPE_AMBIENT_TEMPERATURE;
        if (n.startsWith("step")) return Sensor.TYPE_STEP_COUNTER;
        if (n.startsWith("grav")) return Sensor.TYPE_GRAVITY;
        if (n.startsWith("linear")) return Sensor.TYPE_LINEAR_ACCELERATION;
        return -1;
    }
}

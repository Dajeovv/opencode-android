package com.opencode.launcher;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escucha el SSE de v2 y avisa cuando termina una ejecucion del agente.
 *
 * La UI v2 cierra su propio EventSource cuando la pagina queda oculta, asi
 * que su aviso nunca llega en segundo plano. Este vigilante vive en el
 * proceso del servicio en primer plano y mantiene la conexion abierta.
 */
final class AgentEventWatcher {
    private static final String TAG = "OpenCodeAgent";
    private static final String CHANNEL = "oc-agent";
    private static final int BASE_ID = 3100;

    private static final Object LOCK = new Object();
    private static final Map<String, String> lastUser = new ConcurrentHashMap<>();
    private static final Map<String, String> lastReply = new ConcurrentHashMap<>();
    private static Context app;
    private static int port;
    private static String password;
    private static volatile boolean running;
    private static volatile boolean connected;
    private static volatile HttpURLConnection connection;
    private static volatile int generation;
    private static Thread thread;

    private AgentEventWatcher() {
    }

    static void start(Context ctx, int serverPort) {
        stop();
        synchronized (LOCK) {
            app = ctx.getApplicationContext();
            port = serverPort;
            password = LocalRuntime.serverPassword(app);
            running = true;
            final int gen = ++generation;
            thread = new Thread(() -> loop(gen), "agent-events");
            thread.setDaemon(true);
            thread.start();
        }
    }

    static void stop() {
        Thread t;
        synchronized (LOCK) {
            running = false;
            connected = false;
            generation++;
            t = thread;
            thread = null;
        }
        HttpURLConnection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (t != null) t.interrupt();
    }

    /** True solo mientras el SSE esta realmente conectado (para no duplicar avisos). */
    static boolean isConnected() {
        return running && connected;
    }

    private static void loop(int gen) {
        long backoff = 1000;
        while (running && generation == gen) {
            HttpURLConnection c = null;
            try {
                URL url = new URL("http://127.0.0.1:" + port + "/api/event");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(0);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "text/event-stream");
                c.setRequestProperty("Cache-Control", "no-cache");
                String raw = "opencode:" + password;
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
                if (generation != gen) break;
                connection = c;
                int code = c.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                if (generation != gen) break;
                connected = true;
                backoff = 1000;
                Log.d(TAG, "SSE conectado en 127.0.0.1:" + port);
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && generation == gen && (line = r.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String json = line.substring(5).trim();
                        if (!json.isEmpty()) handle(json);
                    }
                }
            } catch (Exception e) {
                if (running && generation == gen) Log.d(TAG, "SSE: " + e);
            } finally {
                if (generation == gen) {
                    connected = false;
                    connection = null;
                }
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!running || generation != gen) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 15000);
        }
    }

    private static void handle(String json) {
        try {
            JSONObject event = new JSONObject(json);
            String type = event.optString("type", "");
            JSONObject data = event.optJSONObject("data");
            if (data == null) return;
            String sessionId = data.optString("sessionID", "");
            if (sessionId.isEmpty()) return;

            if ("session.inbox.enqueued".equals(type)) {
                JSONObject item = data.optJSONObject("item");
                JSONObject payload = item == null ? null : item.optJSONObject("payload");
                String text = payload == null ? "" : payload.optString("text", "");
                if (!text.isEmpty()) lastUser.put(sessionId, snippet(text, 70));
                return;
            }
            if ("session.execution.started".equals(type)) {
                lastReply.remove(sessionId);
                return;
            }
            if ("session.text.ended".equals(type)) {
                String text = data.optString("text", "");
                if (!text.isEmpty()) lastReply.put(sessionId, snippet(text, 180));
                return;
            }
            if (!"session.execution.succeeded".equals(type)
                    && !"session.execution.failed".equals(type)) return;
            if (MainActivity.isAgentUiVisible()) {
                Log.d(TAG, "fin de agente visible, sin aviso: " + sessionId);
                return;
            }
            notifyDone(sessionId, "session.execution.failed".equals(type));
            lastUser.remove(sessionId);
            lastReply.remove(sessionId);
        } catch (Exception e) {
            Log.d(TAG, "evento SSE: " + e);
        }
    }

    /** Texto de una linea y recortado, para que quepa en el aviso. */
    private static String snippet(String text, int max) {
        String s = text.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) return s;
        return s.substring(0, max - 1).trim() + "…";
    }

    private static void notifyDone(String sessionId, boolean failed) {
        Context ctx = app;
        if (ctx == null) return;
        try {
            ensureChannel(ctx);
            String sessionTitle = fetchSessionTitle(sessionId);
            String userText = lastUser.get(sessionId);
            String reply = lastReply.get(sessionId);
            String title;
            if (failed) {
                title = ServerWatch.t(ctx, "El agente falló", "Agent failed");
            } else if (!sessionTitle.isEmpty()) {
                title = sessionTitle;
            } else if (userText != null && !userText.isEmpty()) {
                title = userText;
            } else {
                title = ServerWatch.t(ctx, "Respuesta lista", "Response ready");
            }
            String body;
            if (reply != null && !reply.isEmpty()) {
                body = reply;
            } else if (!sessionTitle.isEmpty()) {
                body = sessionTitle;
            } else {
                body = failed
                        ? ServerWatch.t(ctx, "La sesión tuvo un error. Toca para abrir.",
                                "The session hit an error. Tap to open.")
                        : ServerWatch.t(ctx, "La sesión está lista. Toca para abrir.",
                                "The session is ready. Tap to open.");
            }

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("session_id", sessionId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx,
                    sessionId.hashCode(), open, flags);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_opencode)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int id = BASE_ID + (sessionId.hashCode() & 0x7fffffff) % 1000;
                nm.notify(id, b.build());
                Log.d(TAG, "aviso agente: " + sessionId + " (" + title + ")");
            }
        } catch (Exception e) {
            Log.d(TAG, "aviso fallo: " + e);
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                ServerWatch.t(ctx, "Agente", "Agent"),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(ServerWatch.t(ctx,
                "Avisos cuando el agente termina",
                "Alerts when the agent finishes"));
        nm.createNotificationChannel(ch);
    }

    private static String fetchSessionTitle(String sessionId) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/session/" + sessionId);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            c.setUseCaches(false);
            String raw = "opencode:" + password;
            c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            if (c.getResponseCode() != 200) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            JSONObject d = o.optJSONObject("data");
            return d == null ? "" : d.optString("title", "");
        } catch (Exception e) {
            Log.d(TAG, "titulo sesion: " + e);
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

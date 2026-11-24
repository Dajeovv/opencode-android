package com.opencode.launcher;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
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

    /** Deja en logcat el resultado de las acciones WARP pedidas por el agente. */
    private static final WarpClient.Callback WARP_LOG = (ok, message, before, after) ->
            Log.i("OpenCodeWarp", "device: " + message
                    + (before == null || before.isEmpty() ? "" : " " + before)
                    + (after == null || after.isEmpty() ? "" : " -> " + after));

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
                case "open-url":
                    return openUrl(ctx, arg(args, 0));
                case "open-app":
                    return openApp(ctx, args);
                case "open-file":
                    return openFile(ctx, arg(args, 0));
                case "list-apps":
                    return listApps(ctx);
                case "install-apk":
                    return installApk(ctx, args);
                case "install-status":
                    return installStatus();
                case "alias":
                    return saveAlias(ctx, args);
                case "install-mode":
                    return installMode(ctx);
                case "warp-status":
                    return WarpClient.status();
                case "warp-start":
                    WarpClient.init(ctx);
                    WarpClient.setEnabled(ctx, true);
                    WarpClient.startAsync(ctx, WARP_LOG);
                    return new JSONObject().put("ok", true).toString();
                case "warp-stop":
                    WarpClient.setEnabled(ctx, false);
                    WarpClient.stopAsync(WARP_LOG);
                    return new JSONObject().put("ok", true).toString();
                case "warp-rotate":
                    WarpClient.init(ctx);
                    WarpClient.setEnabled(ctx, true);
                    WarpClient.rotateAsync(ctx, WARP_LOG);
                    return new JSONObject().put("ok", true).toString();
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

    /** Cadena lista para incrustar en un JSON. */
    private static String jsonString(String s) {
        try {
            return JSONObject.quote(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
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
        // Ciclos de carga: solo el kernel los sabe y normalmente piden root.
        try {
            if ("on".equals(RootShell.state(ctx))) {
                String c = RootShell.run(ctx,
                        "cat /sys/class/power_supply/battery/cycle_count", 5000);
                if (c != null && c.matches("\\d+")) o.put("cycle_count", Integer.parseInt(c));
            }
        } catch (Exception ignored) {
        }
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
                putWifiState(ctx, o, false);
                return o.toString();
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            o.put("connected", caps != null);
            if (caps != null) {
                o.put("transport", transportName(caps));
                o.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                o.put("cellular", caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                o.put("ethernet", caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                o.put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                o.put("bluetooth", caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
                o.put("metered", !caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                o.put("validated", caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                o.put("internet", caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET));
                o.put("captive_portal", caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
            }
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp != null) {
                putLinkProperties(o, lp);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                o.put("type", info.getTypeName());
                o.put("connected", info.isConnected());
            }
            putWifiState(ctx, o, caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            putNetworks(cm, o);
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
        return o.toString();
    }

    /** Direcciones, DNS, rutas, gateway y proxy de la red activa. */
    private static void putLinkProperties(JSONObject o, LinkProperties lp) throws Exception {
        if (lp.getInterfaceName() != null) {
            o.put("interface", lp.getInterfaceName());
        }
        if (lp.getMtu() > 0) {
            o.put("mtu", lp.getMtu());
        }
        JSONArray addresses = new JSONArray();
        String ipv4 = null;
        String ipv6 = null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            JSONObject a = new JSONObject();
            String host = la.getAddress().getHostAddress();
            boolean v6 = la.getAddress() instanceof java.net.Inet6Address;
            a.put("address", host);
            a.put("prefix_length", la.getPrefixLength());
            a.put("family", v6 ? "IPv6" : "IPv4");
            addresses.put(a);
            if (!v6 && ipv4 == null) ipv4 = host;
            if (v6 && ipv6 == null) ipv6 = host;
        }
        o.put("ip_addresses", addresses);
        if (ipv4 != null) o.put("ipv4", ipv4);
        if (ipv6 != null) o.put("ipv6", ipv6);

        JSONArray dns = new JSONArray();
        for (java.net.InetAddress address : lp.getDnsServers()) {
            dns.put(address.getHostAddress());
        }
        o.put("dns_servers", dns);
        if (lp.getDomains() != null && !lp.getDomains().isEmpty()) {
            o.put("domains", lp.getDomains());
        }

        JSONArray routes = new JSONArray();
        String gateway = null;
        for (RouteInfo route : lp.getRoutes()) {
            JSONObject r = new JSONObject();
            r.put("destination", route.getDestination() == null
                    ? "" : route.getDestination().toString());
            r.put("gateway", route.getGateway() == null
                    ? "" : route.getGateway().getHostAddress());
            r.put("interface", route.getInterface());
            r.put("default", route.isDefaultRoute());
            routes.put(r);
            if (gateway == null && route.isDefaultRoute()
                    && route.getGateway() != null
                    && !route.getGateway().isAnyLocalAddress()) {
                gateway = route.getGateway().getHostAddress();
            }
        }
        o.put("routes", routes);
        if (gateway != null) o.put("gateway", gateway);

        ProxyInfo proxy = lp.getHttpProxy();
        if (proxy != null) {
            JSONObject p = new JSONObject();
            if (proxy.getHost() != null) p.put("host", proxy.getHost());
            p.put("port", proxy.getPort());
            if (proxy.getPacFileUrl() != null) {
                p.put("pac", proxy.getPacFileUrl().toString());
            }
            o.put("http_proxy", p);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            o.put("private_dns_active", lp.isPrivateDnsActive());
            if (lp.getPrivateDnsServerName() != null) {
                o.put("private_dns_server", lp.getPrivateDnsServerName());
            }
        }
        if (Build.VERSION.SDK_INT >= 30 && lp.getDhcpServerAddress() != null) {
            o.put("dhcp_server", lp.getDhcpServerAddress().getHostAddress());
        }
    }

    /** SSID, BSSID, señal y enlaces de la conexión Wi-Fi, si la hay. */
    private static void putWifiState(Context ctx, JSONObject o, boolean activeWifi)
            throws Exception {
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        try {
            o.put("wifi_enabled", wm.isWifiEnabled());
        } catch (Exception ignored) {
        }
        WifiInfo wi;
        try {
            wi = wm.getConnectionInfo();
        } catch (Exception e) {
            return;
        }
        if (wi == null) return;
        boolean connected = wi.getSupplicantState() == SupplicantState.COMPLETED
                && wi.getNetworkId() != -1;
        if (!connected && !activeWifi) return;

        JSONObject w = new JSONObject();
        String ssid = wi.getSSID();
        if (ssid != null) {
            ssid = ssid.trim();
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            // Sin permiso de ubicación Android devuelve "<unknown ssid>".
            if (!ssid.isEmpty() && !ssid.equals("<unknown ssid>")
                    && !ssid.startsWith("0x")) {
                w.put("ssid", ssid);
            }
        }
        String bssid = wi.getBSSID();
        if (bssid != null && !bssid.isEmpty() && !bssid.equals("02:00:00:00:00:00")) {
            w.put("bssid", bssid);
        }
        int rssi = wi.getRssi();
        w.put("rssi", rssi);
        w.put("signal_level", WifiManager.calculateSignalLevel(rssi, 5));
        w.put("frequency", wi.getFrequency());
        w.put("link_speed", wi.getLinkSpeed());
        if (Build.VERSION.SDK_INT >= 29) {
            w.put("tx_link_speed_mbps", wi.getTxLinkSpeedMbps());
            w.put("rx_link_speed_mbps", wi.getRxLinkSpeedMbps());
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.put("wifi_standard", wifiStandard(wi.getWifiStandard()));
            w.put("max_tx_link_speed_mbps", wi.getMaxSupportedTxLinkSpeedMbps());
            w.put("max_rx_link_speed_mbps", wi.getMaxSupportedRxLinkSpeedMbps());
        }
        int ip = wi.getIpAddress();
        if (ip != 0) w.put("ip_address", ipv4(ip));
        if (wi.getSupplicantState() != null) {
            w.put("supplicant_state", wi.getSupplicantState().toString());
        }
        o.put("wifi_info", w);

        try {
            android.net.DhcpInfo dhcp = wm.getDhcpInfo();
            if (dhcp != null) {
                JSONObject d = new JSONObject();
                if (dhcp.ipAddress != 0) d.put("ip_address", ipv4(dhcp.ipAddress));
                if (dhcp.gateway != 0) d.put("gateway", ipv4(dhcp.gateway));
                if (dhcp.netmask != 0) d.put("netmask", ipv4(dhcp.netmask));
                if (dhcp.dns1 != 0) d.put("dns1", ipv4(dhcp.dns1));
                if (dhcp.dns2 != 0) d.put("dns2", ipv4(dhcp.dns2));
                if (dhcp.serverAddress != 0) d.put("server", ipv4(dhcp.serverAddress));
                d.put("lease_seconds", dhcp.leaseDuration);
                o.put("dhcp", d);
            }
        } catch (Exception ignored) {
        }
    }

    /** Resumen de todas las redes para diagnosticar VPN, Wi-Fi y datos. */
    private static void putNetworks(ConnectivityManager cm, JSONObject o) {
        try {
            JSONArray networks = new JSONArray();
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) continue;
                JSONObject net = new JSONObject();
                net.put("transport", transportName(caps));
                net.put("validated", caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                net.put("metered", !caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                net.put("wifi", caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                net.put("cellular", caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_CELLULAR));
                net.put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    if (lp.getInterfaceName() != null) {
                        net.put("interface", lp.getInterfaceName());
                    }
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof java.net.Inet4Address) {
                            net.put("ipv4", la.getAddress().getHostAddress());
                            break;
                        }
                    }
                }
                networks.put(net);
            }
            o.put("networks", networks);
        } catch (Exception ignored) {
        }
    }

    private static String transportName(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "BLUETOOTH";
        return "OTHER";
    }

    private static String wifiStandard(int standard) {
        switch (standard) {
            case ScanResult.WIFI_STANDARD_LEGACY:
                return "legacy";
            case ScanResult.WIFI_STANDARD_11N:
                return "11n";
            case ScanResult.WIFI_STANDARD_11AC:
                return "11ac";
            case ScanResult.WIFI_STANDARD_11AX:
                return "11ax";
            case ScanResult.WIFI_STANDARD_11AD:
                return "11ad";
            case 8:
                return "11be";
            default:
                return "unknown";
        }
    }

    /** DHCP devuelve IPv4 en orden little-endian. */
    private static String ipv4(int value) {
        return (value & 0xff) + "." + ((value >> 8) & 0xff) + "."
                + ((value >> 16) & 0xff) + "." + ((value >> 24) & 0xff);
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

    // ---------------------------------------------------------- abrir apps

    /**
     * El comando "am" del sistema no vale para el agente: se presenta como
     * com.android.shell (UID 2000) y el framework exige que el UID que lo
     * llama sea ese mismo (assertPackageMatchesCallingUid). La app si puede
     * lanzar actividades: lo hace con sus propios permisos y en su nombre.
     */

    /** Canal de avisos para abrir apps cuando la app no esta en primer plano. */
    private static final String CHANNEL = "opencode-open";

    /** La app esta visible: se puede arrancar otra actividad directamente. */
    private static volatile boolean foreground = false;

    /** Lo llama MainActivity al ir y volver del primer plano. */
    public static void setForeground(boolean f) {
        foreground = f;
    }

    private static String openUrl(Context ctx, String url) throws Exception {
        JSONObject o = new JSONObject();
        try {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) {
                o.put("error", "no url");
                return o.toString();
            }
            if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) u = "http://" + u;
            Uri uri = Uri.parse(u);
            if (uri.getScheme() == null) {
                o.put("error", "bad url");
                return o.toString();
            }
            Intent i;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                // Android 10+ no deja pasar URIs file:// a otra app: se
                // sirve el fichero con el FileProvider de la app.
                String path = uri.getPath();
                File f = path == null ? null : new File(path);
                if (f == null || !f.isFile()) {
                    o.put("error", "not found: " + path);
                    return o.toString();
                }
                Uri content = FileProvider.getUriForFile(ctx,
                        ctx.getPackageName() + ".fileprovider", f);
                i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(content, "*/*");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i = new Intent(Intent.ACTION_VIEW, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            o.put("status", launch(ctx, i, "OpenCode", u));
            return o.toString();
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Nombres que dice la gente y los paquetes que pueden ser. El agente
     * busca "firefox" y en F-Droid el paquete se llama org.mozilla.fennec_fdroid
     * (el nombre "Fennec" no contiene "firefox"), asi que sin esta tabla
     * conclude que la app no esta instalada.
     *
     * Es solo un atajo: la busqueda general (ver findApp) y los alias
     * aprendidos (ver saveAlias) resuelven cualquier otra app.
     */
    private static final String[][] APP_ALIASES = {
            {"firefox", "org.mozilla.firefox", "org.mozilla.fennec_fdroid", "org.mozilla.klar"},
            {"chrome", "com.android.chrome"},
            {"brave", "com.brave.browser"},
            {"opera", "com.opera.browser"},
            {"edge", "com.microsoft.emmx"},
            {"yandex", "com.yandex.browser"},
            {"duckduckgo", "com.duckduckgo.mobile.android"},
            {"telegram", "org.telegram.messenger"},
            {"whatsapp", "com.whatsapp"},
            {"gmail", "com.google.android.gm"},
            {"maps", "com.google.android.apps.maps"},
            {"youtube", "com.google.android.youtube"},
            {"spotify", "com.spotify.music"},
            {"calculator", "com.android.calculator2"},
            {"clock", "com.google.android.deskclock"},
            {"settings", "com.android.settings"},
            {"files", "com.android.documentsui"},
            {"play store", "com.android.vending"},
            {"playstore", "com.android.vending"},
    };

    /**
     * Busca una app instalada por nombre: coincide con el paquete, con la
     * etiqueta que se ve en el launcher o con las palabras de las dos.
     * Devuelve null si no hay coincidencias claras.
     */
    private static String findApp(Context ctx, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) return null;
        String qFlat = q.replaceAll("[^a-z0-9]", "");
        List<ResolveInfo> ris = ctx.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        String best = null;
        int bestScore = 0;
        int second = 0;
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            String label = "";
            try {
                label = ri.loadLabel(ctx.getPackageManager()).toString();
            } catch (Exception ignored) {
            }
            int score = 0;
            String pl = pkg.toLowerCase(Locale.US);
            String ll = label.toLowerCase(Locale.US);
            if (pl.equals(q) || ll.equals(q)) score += 10;
            if (pl.contains(q) || ll.contains(q)) score += 5;
            if (ll.replaceAll("[^a-z0-9]", "").equals(qFlat)) score += 6;
            // palabras: "reloj" casa con DeskClock? no, pero "clocks" si.
            for (String tok : q.split("[^a-z0-9]+")) {
                if (tok.length() < 3) continue;
                if (pl.contains(tok) || ll.contains(tok)) score += 4;
            }
            // cada palabra de la consulta dentro de la app (en cualquier orden)
            String hay = pl + " " + ll.replaceAll("[^a-z0-9]", "");
            boolean todas = true;
            for (String tok : q.split("[^a-z0-9]+")) {
                if (tok.length() < 3) continue;
                if (!hay.contains(tok)) {
                    todas = false;
                    break;
                }
            }
            if (todas) score += 3;
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = pkg;
            } else if (score > second) {
                second = score;
            }
        }
        // Con empate claro no se decide: se devuelven los candidatos.
        if (best == null || bestScore < 4 || bestScore <= second) return null;
        return best;
    }

    /** true si lo que dice el agente es ese nombre (o un paquete que lo lleva). */
    private static boolean aliasMatches(String alias, String query) {
        if (alias.equals(query)) return true;
        // "org.mozilla.firefox" -> tokens {org, mozilla, firefox}: casa.
        for (String tok : query.split("[^a-z0-9]+")) {
            if (tok.equals(alias)) return true;
        }
        return false;
    }

    /** Alias aprendidos: "firefox=org.mozilla.fennec_fdroid". */
    private static File aliasFile(Context ctx) {
        return new File(ctx.getFilesDir(), "home/.config/opencode/app-aliases");
    }

    /** Guarda un alias para que el nombre que dice el usuario valga siempre. */
    private static String saveAlias(Context ctx, List<String> args) throws Exception {
        JSONObject o = new JSONObject();
        String name = arg(args, 0).trim().toLowerCase(Locale.US);
        String pkg = arg(args, 1).trim();
        if (name.isEmpty() || pkg.isEmpty()) {
            o.put("error", "usage: alias <name> <package>");
            return o.toString();
        }
        File f = aliasFile(ctx);
        File dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            o.put("error", "no dir");
            return o.toString();
        }
        StringBuilder sb = new StringBuilder();
        try {
            if (f.isFile()) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
                for (String line : lines) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    int eq = t.indexOf('=');
                    if (eq <= 0) continue;
                    if (t.substring(0, eq).trim().equals(name)) continue;
                    sb.append(t).append('\n');
                }
            }
        } catch (Exception ignored) {
        }
        sb.append(name).append('=').append(pkg).append('\n');
        try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
            w.write(sb.toString());
        }
        o.put("ok", true);
        o.put("alias", name);
        o.put("package", pkg);
        return o.toString();
    }

    /** Alias aprendidos (los que el agente ha ido guardando). */
    private static String learnedAlias(Context ctx, String query) {
        File f = aliasFile(ctx);
        if (!f.isFile()) return null;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                if (t.substring(0, eq).trim().equals(q)) {
                    return t.substring(eq + 1).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String openApp(Context ctx, List<String> args) throws Exception {
        JSONObject o = new JSONObject();
        try {
            String spec = arg(args, 0);
            if (spec == null || spec.trim().isEmpty()) {
                o.put("error", "no package");
                return o.toString();
            }
            spec = spec.trim();
            Intent i = null;
            if (spec.contains("/")) {
                ComponentName c = ComponentName.unflattenFromString(spec);
                if (c == null) {
                    o.put("error", "bad component");
                    return o.toString();
                }
                i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(c);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                i = ctx.getPackageManager().getLaunchIntentForPackage(spec);
                if (i == null) {
                    // 1) alias aprendido (el agente lo guardo antes).
                    String learned = learnedAlias(ctx, spec);
                    if (learned != null) {
                        Intent cand = ctx.getPackageManager()
                                .getLaunchIntentForPackage(learned);
                        if (cand != null) {
                            i = cand;
                            spec = learned;
                        }
                    }
                }
                if (i == null) {
                    // 2) alias que ya conoce la app.
                    String low = spec.toLowerCase(Locale.US);
                    for (String[] row : APP_ALIASES) {
                        if (!aliasMatches(row[0], low)) continue;
                        for (int k = 1; k < row.length; k++) {
                            Intent cand = ctx.getPackageManager()
                                    .getLaunchIntentForPackage(row[k]);
                            if (cand != null) {
                                i = cand;
                                spec = row[k];
                                break;
                            }
                        }
                        if (i != null) break;
                    }
                }
                if (i == null) {
                    // 3) busqueda general por nombre o etiqueta.
                    String found = findApp(ctx, spec);
                    if (found != null) {
                        spec = found;
                        i = ctx.getPackageManager().getLaunchIntentForPackage(found);
                    }
                }
                if (i == null) {
                    // Sin actividad de inicio: se busca la principal a mano.
                    try {
                        List<ResolveInfo> ris = ctx.getPackageManager()
                                .queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_LAUNCHER), 0);
                        for (ResolveInfo ri : ris) {
                            if (spec.equals(ri.activityInfo.packageName)) {
                                i = new Intent(Intent.ACTION_MAIN);
                                i.setClassName(ri.activityInfo.packageName,
                                        ri.activityInfo.name);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (i == null) {
                o.put("error", "not launchable: " + spec);
                return o.toString();
            }
            if (i.getComponent() == null) {
                i.setComponent(i.resolveActivity(ctx.getPackageManager()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            String label = spec;
            try {
                android.content.pm.ApplicationInfo ai =
                        ctx.getPackageManager().getApplicationInfo(spec, 0);
                label = ctx.getPackageManager().getApplicationLabel(ai).toString();
            } catch (Exception ignored) {
            }
            String status = launch(ctx, i,
                    ServerWatch.t(ctx, "Abrir ", "Open ") + label, label);
            o.put("status", status);
            // Que paquete se ha resuelto: asi el agente aprende el nombre
            // real y no vuelve a buscarlo.
            o.put("package", spec);
            o.put("name", label);
            if ("notification".equals(status)) {
                o.put("message", "OpenCode is in the background: the user has to "
                        + "tap the notification to open " + label
                        + ". The app was found.");
            } else if ("opened".equals(status)) {
                o.put("message", label + " is open");
            }
            return o.toString();
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    /** Abre un fichero del telefono con la app que lo pueda mostrar. */
    private static String openFile(Context ctx, String path) throws Exception {
        JSONObject o = new JSONObject();
        try {
            if (path == null || path.trim().isEmpty()) {
                o.put("error", "no path");
                return o.toString();
            }
            File f = new File(path.trim());
            if (!f.isFile()) {
                o.put("error", "not found: " + path);
                return o.toString();
            }
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", f);
            String mime = ctx.getContentResolver().getType(uri);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null ? "*/*" : mime);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            o.put("status", launch(ctx, i,
                    ServerWatch.t(ctx, "Abrir ", "Open ") + f.getName(), f.getAbsolutePath()));
            return o.toString();
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    /** Apps que se pueden abrir: el agente las necesita por nombre. */
    private static String listApps(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> ris = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            JSONArray arr = new JSONArray();
            for (ResolveInfo ri : ris) {
                JSONObject o2 = new JSONObject();
                o2.put("package", ri.activityInfo.packageName);
                o2.put("activity", ri.activityInfo.name);
                try {
                    o2.put("name", ri.loadLabel(pm).toString());
                } catch (Exception ignored) {
                }
                arr.put(o2);
            }
            o.put("apps", arr);
            return o.toString();
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Arranca una actividad. Android 10+ no deja lanzar actividades desde
     * segundo plano: si la app no esta visible se avisa con una notificacion
     * que el usuario toca (y ahi si se abre).
     */
    private static String launch(Context ctx, Intent intent, String title, String text) {
        try {
            if (foreground) {
                MAIN.post(() -> {
                    try {
                        ctx.startActivity(intent);
                    } catch (Exception e) {
                        Log.w("OpenCodeDevice", "no se pudo abrir: " + e);
                    }
                });
                return "opened";
            }
            ensureChannel(ctx);
            int id = 4200 + Math.abs(title.hashCode() + text.hashCode()) % 500;
            PendingIntent p = PendingIntent.getActivity(ctx, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_opencode)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(p)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "error: no notifications";
            nm.notify(id, b.build());
            return "notification";
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "OpenCode",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(ServerWatch.t(ctx,
                "Abrir apps y enlaces desde el agente",
                "Open apps and links from the agent"));
        nm.createNotificationChannel(ch);
    }

    // ------------------------------------------------------- instalar APK

    /**
     * Instala un APK con la API de PackageInstaller, que es la via que el
     * sistema reserva para las apps (no hace falta root ni shell).
     *
     * El comando "pm install" no sirve aqui: el servidor del sistema no
     * puede leer los ficheros privados de la app y el modo "reverse" esta
     * reservado a shell/root. Pero la app que crea la sesion es su
     * propietaria, asi que puede escribir en ella con openWrite().
     *
     * Android 8+ pide confirmacion del usuario: sale el instalador del
     * sistema y el usuario pulsa "Instalar". Si el sistema no logra mostrar
     * esa pantalla (algunos ROM lo tienen roto), se reintenta sin dialogo:
     * el usuario ya concedio a esta app el permiso de "instalar apps
     * desconocidas", que es ese consentimiento.
     */
    private static final String INSTALL_RESULT = "com.opencode.android.INSTALL_RESULT";

    private static BroadcastReceiver installReceiver;

    /** Datos de la instalacion en curso, para poder reintentar. */
    private static String pendingPath = "";
    private static String pendingSha = "";
    private static String pendingPkg = "";
    private static boolean pendingRetried = false;
    /** Abrir la app en cuanto termine de instalarse. */
    private static boolean pendingOpen = false;

    /** Como termino la ultima instalacion (para que el agente lo pregunte). */
    private static volatile String lastInstall = "{\"ok\":true,\"status\":\"none\"}";

    /** Estado de la ultima instalacion: installed, failed o pending. */
    private static String installStatus() {
        return lastInstall;
    }

    private static String installApk(Context ctx, List<String> args) throws Exception {
        JSONObject o = new JSONObject();
        try {
            String path = arg(args, 0);
            String shaRaw = arg(args, 1).trim();
            // El segundo argumento puede ser un sha256 o el flag "open" (si el
            // agente llama `termux-install-apk <ruta> open`). Solo se trata
            // como hash si parece un sha256 de 64 hex; si no, se ignora para
            // no fallar con "sha256 mismatch" inesperado.
            boolean shaLooksLikeHash = shaRaw.matches("[0-9a-fA-F]{64}");
            String sha = shaLooksLikeHash ? shaRaw.toLowerCase(Locale.US) : "";
            String openArg = arg(args, 2).trim();
            if (!shaLooksLikeHash && shaRaw.equalsIgnoreCase("open")) {
                openArg = "open";
            }
            if (path == null || path.trim().isEmpty()) {
                o.put("error", "no path");
                return o.toString();
            }
            File f = new File(path.trim());
            if (!f.isFile()) {
                o.put("error", "not found: " + path);
                return o.toString();
            }
            // Hash opcional: el agente lo verifica contra el indice oficial
            // antes de instalar, y aqui se comprueba otra vez.
            if (!sha.isEmpty()) {
                String real = sha256(f);
                if (!real.equals(sha)) {
                    o.put("error", "sha256 mismatch");
                    o.put("expected", sha);
                    o.put("got", real);
                    return o.toString();
                }
                o.put("sha256", real);
            }
            PackageManager pm = ctx.getPackageManager();
            // Permiso "instalar apps desconocidas": sin el, el sistema no
            // deja instalar nada. Se pide yendo a los ajustes de la app.
            if (!pm.canRequestPackageInstalls()) {
                o.put("error", "need_unknown_sources");
                o.put("needs_unknown_sources", true);
                o.put("hint", "the user must grant Install unknown apps for "
                        + "this app in Settings > Apps > Special access");
                try {
                    Intent si = new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + ctx.getPackageName()));
                    si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    MAIN.post(() -> {
                        try {
                            ctx.startActivity(si);
                        } catch (Exception ignored) {
                        }
                    });
                    o.put("status", "settings");
                } catch (Exception ignored) {
                }
                return o.toString();
            }
            // Nombre del paquete que se va a instalar (lo lee del propio APK).
            String pkg = "";
            try {
                android.content.pm.PackageInfo pi =
                        pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi != null) pkg = pi.packageName == null ? "" : pi.packageName;
            } catch (Exception ignored) {
            }
            pendingPath = f.getAbsolutePath();
            pendingSha = sha;
            pendingPkg = pkg;
            pendingRetried = false;
            // Tercer argumento "open": al terminar de instalarse, se abre.
            pendingOpen = openArg.equalsIgnoreCase("open");

            // 1) Con root: instalacion silenciosa, sin que el usuario pulse
            //    nada. El APK se copia a /data/local/tmp porque el servidor
            //    del sistema no puede leer el directorio de datos de la app.
            String rootOut = installWithRoot(ctx, f);
            if (rootOut != null) {
                o.put("mode", "root");
                o.put("output", rootOut);
                boolean ok = rootOut.contains("Success");
                o.put("ok", ok);
                o.put("status", ok ? "installed" : "failed");
                if (!pkg.isEmpty()) o.put("package", pkg);
                if (ok) {
                    lastInstall = "{\"ok\":true,\"status\":\"installed\",\"package\":"
                            + jsonString(pkg) + ",\"mode\":\"root\"}";
                    if (pendingOpen) openInstalled(ctx, pkg);
                } else {
                    lastInstall = "{\"ok\":false,\"status\":\"failed\",\"package\":"
                            + jsonString(pkg) + ",\"output\":" + jsonString(rootOut) + "}";
                }
                return o.toString();
            }

            ensureInstallReceiver(ctx);
            // En Android 12+ se pide que la sesion no exija accion del
            // usuario: el permiso de "instalar apps desconocidas" ya es el
            // consentimiento. Si el sistema aun asi la pide, el receptor
            // lanza la pantalla de confirmacion.
            int sessionId = startSession(ctx, f, true);
            o.put("status", "installing");
            o.put("session", sessionId);
            if (!pkg.isEmpty()) o.put("package", pkg);
            o.put("size", f.length());
            return o.toString();
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Como se instalara: con root (silencioso, sin dialogo) o con el dialogo
     * del sistema (el usuario tiene que pulsar "Instalar"). Android 8+ exige
     * esa confirmacion siempre que la instalacion la pide una app.
     */
    private static String installMode(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        boolean root = RootShell.enabled(ctx) && RootShell.available()
                && RootShell.isGranted(ctx);
        o.put("ok", true);
        o.put("mode", root ? "root" : "dialog");
        o.put("silent", root);
        o.put("root", RootShell.state(ctx));
        if (!root) {
            o.put("message", "the user has to press Install in the system "
                    + "dialog; the app does everything else (download, check, "
                    + "stage and open the app afterwards)");
        }
        return o.toString();
    }

    /**
     * Instalacion silenciosa con root (su): el APK se copia a
     * /data/local/tmp, que es donde el servidor del sistema puede leerlo, y se
     * instala con pm. Sin esto hay que pedirle al usuario que pulse
     * "Instalar" en el dialogo del sistema.
     *
     * Devuelve null si no hay root concedido.
     */
    private static String installWithRoot(Context ctx, File f) {
        try {
            if (!RootShell.enabled(ctx) || !RootShell.available()) return null;
            String q = "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
            String cmd = "cp " + q + " /data/local/tmp/oc-install.apk && "
                    + "pm install -r -g /data/local/tmp/oc-install.apk; "
                    + "R=$?; rm -f /data/local/tmp/oc-install.apk; exit $R";
            String out = RootShell.run(ctx, cmd, 300000);
            if (out == null) return null;
            return out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Abre una app ya instalada (tras instalarla). */
    private static void openInstalled(Context ctx, String pkg) {
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setPackage(pkg);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MAIN.post(() -> {
                try {
                    ctx.startActivity(launch);
                } catch (Exception e) {
                    Log.i("OpenCodeDevice", "no se pudo abrir: " + e);
                }
            });
        } catch (Exception e) {
            Log.i("OpenCodeDevice", "no se pudo abrir: " + e);
        }
    }

    /**
     * Crea la sesion, escribe el APK y hace el commit.
     *
     * @param sinDialogo true para no pedir la pantalla de confirmacion
     *                   (solo Android 12+; el usuario ya concedio el permiso)
     */
    private static int startSession(Context ctx, File f, boolean sinDialogo)
            throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallerPackageName(ctx.getPackageName());
        params.setSize(f.length());
        if (sinDialogo && Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = pm.getPackageInstaller().createSession(params);
        lastInstall = "{\"ok\":true,\"status\":\"pending\",\"session\":" + sessionId
                + ",\"package\":" + jsonString(pendingPkg) + "}";
        try (PackageInstaller.Session session =
                     pm.getPackageInstaller().openSession(sessionId)) {
            OutputStream out = session.openWrite("base.apk", 0, f.length());
            try {
                copy(f, out);
                session.fsync(out);
            } finally {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            session.commit(installSender(ctx, sessionId));
        }
        return sessionId;
    }

    /** Copia el APK a la sesion de instalacion. */
    private static void copy(File f, OutputStream out) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static String sha256(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        byte[] d = md.digest();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** PendingIntent que recibe el resultado de la instalacion. */
    private static IntentSender installSender(Context ctx, int sessionId) {
        Intent i = new Intent(INSTALL_RESULT);
        i.setPackage(ctx.getPackageName());
        i.putExtra("session", sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(ctx, sessionId, i, flags).getIntentSender();
    }

    private static void ensureInstallReceiver(Context ctx) {
        if (installReceiver != null) return;
        installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, -99);
                String msg = intent.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE);
                Log.i("OpenCodeDevice", "instalacion: status=" + status
                        + (msg == null ? "" : " (" + msg + ")"));
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    lastInstall = "{\"ok\":true,\"status\":\"installed\",\"package\":"
                            + jsonString(pendingPkg) + "}";
                    // Con "open" al final: al terminar de instalarse, se abre.
                    if (pendingOpen && pendingPkg != null && !pendingPkg.isEmpty()) {
                        openInstalled(ctx, pendingPkg);
                    }
                } else if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    lastInstall = "{\"ok\":false,\"status\":\"failed\",\"code\":" + status
                            + ",\"message\":" + jsonString(msg == null ? "" : msg)
                            + ",\"package\":" + jsonString(pendingPkg) + "}";
                }
                // Android 12+: el sistema pide accion del usuario y manda el
                // intent de confirmacion. Hay que lanzarlo para que aparezca
                // el instalador del sistema (el usuario pulsa "Instalar").
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = null;
                    try {
                        Object o = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (o instanceof Intent) confirm = (Intent) o;
                    } catch (Exception ignored) {
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        launchConfirm(c, confirm);
                        return;
                    }
                    // Sin intent de confirmacion: el sistema no puede
                    // mostrarla. Se reintenta sin pedirla (el usuario ya
                    // concedio el permiso de instalar apps desconocidas).
                    if (!pendingRetried && Build.VERSION.SDK_INT >= 31
                            && pendingPath != null && !pendingPath.isEmpty()) {
                        pendingRetried = true;
                        final Context app = c.getApplicationContext();
                        new Thread(() -> {
                            try {
                                Log.i("OpenCodeDevice", "reintento sin dialogo");
                                startSession(app, new File(pendingPath), true);
                            } catch (Exception e) {
                                Log.i("OpenCodeDevice", "reintento fallo: " + e);
                            }
                        }).start();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(INSTALL_RESULT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(installReceiver, filter);
            }
        } catch (Exception ignored) {
            installReceiver = null;
        }
    }

    /**
     * Abre la pantalla de confirmacion del instalador del sistema. Si la app
     * no esta delante, Android 10+ no deja abrirla: se avisa con una
     * notificacion que el usuario toca (y ahi si se abre).
     */
    private static void launchConfirm(Context ctx, Intent confirm) {
        if (foreground) {
            MAIN.post(() -> {
                try {
                    ctx.startActivity(confirm);
                } catch (Exception e) {
                    Log.i("OpenCodeDevice", "no se pudo abrir el instalador: " + e);
                }
            });
            return;
        }
        try {
            ensureChannel(ctx);
            int id = 4300 + Math.abs(confirm.hashCode()) % 500;
            PendingIntent pi = PendingIntent.getActivity(ctx, id, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b =
                    new NotificationCompat.Builder(ctx, CHANNEL)
                            .setSmallIcon(R.drawable.ic_stat_opencode)
                            .setContentTitle(ServerWatch.t(ctx,
                                    "Instalar aplicacion", "Install app"))
                            .setContentText(ServerWatch.t(ctx,
                                    "Toca para instalarla", "Tap to install it"))
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(id, b.build());
        } catch (Exception e) {
            Log.i("OpenCodeDevice", "aviso de instalacion fallo: " + e);
        }
    }
}

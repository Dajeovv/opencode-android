package com.opencode.launcher;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Genera la configuracion extra que se carga con OPENCODE_CONFIG_DIR.
 *
 * Solo fija "shell" (el trampolin nativo) y desactiva autoupdate. No define
 * proveedores ni modelo: el proveedor oficial "opencode" ya trae los modelos
 * free y rota la lista, y el usuario elige el suyo en la UI.
 *
 * El archivo se mantiene separado de opencode.json/opencode.jsonc para no
 * pisar la configuracion del usuario. "shell" se escribe siempre: en Android
 * el valor correcto es el trampolin del APK y la UI v2 no debe poder
 * cambiarlo a "sh" (rompe el resolvedor de comandos y ralentiza el agente).
 */
public final class NativeRuntimeConfig {
    private static final String TAG = "OpenCodeLocal";

    public static final String FILE_NAME = "opencode.json";

    private NativeRuntimeConfig() {
    }

    /**
     * Escribe la configuracion del shell en {@code destDir}, que el servidor
     * recibe como OPENCODE_CONFIG_DIR. El directorio va separado del global
     * (OPENCODE_CONFIG/OPENCODE_CONFIG_CONTENT no se aplican en "serve"
     * 1.19.x) y se mezcla al final. "shell" se impone siempre para que un
     * cambio hecho desde la UI (o un archivo editado a mano) no deje al
     * agente usando /system/bin/sh.
     */
    public static File write(File destDir, String shellPath) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("no se pudo crear " + destDir);
        }
        boolean withShell = shellPath != null && !shellPath.isEmpty();
        byte[] body = build(withShell ? shellPath : null)
                .getBytes(StandardCharsets.UTF_8);
        File dest = new File(destDir, FILE_NAME);
        File tmp = new File(destDir, FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
            out.getFD().sync();
        }
        if (!tmp.renameTo(dest)) {
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(body);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dest.setReadable(true, false);
        Log.i(TAG, "config nativa escrita en " + dest + " (shell forzado="
                + withShell + ")");
        return dest;
    }

    private static String build(String shellPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"$schema\": \"https://opencode.ai/config.json\",\n");
        sb.append("  \"autoupdate\": false");
        if (shellPath != null) {
            sb.append(",\n  \"shell\": ").append(JSONObject.quote(shellPath));
        }
        sb.append("\n}\n");
        return sb.toString();
    }

}

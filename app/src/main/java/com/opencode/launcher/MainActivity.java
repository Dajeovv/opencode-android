package com.opencode.launcher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    /** Repo donde caen los reportes (issues): el proyecto propio. */
    private static final String REPORT_REPO = "Dajeovv/opencode-android";

    private static final String HOST = "127.0.0.1";
    private static final int PORT = BuildConfig.SERVER_PORT;
    private static final String URL = "http://" + HOST + ":" + PORT;
    private static final String MANUAL_CMD = "opencode serve --hostname 127.0.0.1 --port " + PORT;
    private static final long MAX_SHARE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_SHARE_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final int MAX_SHARE_FILES = 10;
    private static final String SETUP_BLOCK =
            ": oc-setup; echo '[1/3] Permiso externo...'; echo \"HOME=$HOME\"; mkdir -p ~/.termux ~/.opencode-android;"
            + " grep -qi '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null"
            + " || echo 'allow-external-apps = true' >> ~/.termux/termux.properties;"
            + " echo setup-ok; grep -i '^allow-external-apps' ~/.termux/termux.properties;"
            + " echo '[2/3] Config autoupdate...'; F=~/.config/opencode/opencode.jsonc; mkdir -p ~/.config/opencode;"
            + " [ -f \"$F\" ] || echo '{}' > \"$F\"; cp \"$F\" \"$F.bak\";"
            + " ls -l \"$F\" && echo config-ok || echo config-FALLO-no-se-creo;"
            + " if grep -q '\"autoupdate\"' \"$F\"; then sed -i -E 's/\"autoupdate\"([[:space:]]*:[[:space:]]*)(true|false|\"[^\"]*\"|[0-9.]+)/\"autoupdate\"\\1false/' \"$F\";"
            + " elif grep -q '{[[:space:]]*}' \"$F\"; then sed -i -E 's/\\{[[:space:]]*\\}/{\\n  \"autoupdate\": false\\n}/' \"$F\";"
            + " else sed -i '1s/{/&\\n  \"autoupdate\": false,/' \"$F\"; fi;"
            + " grep '\"autoupdate\"' \"$F\"";
    /** Setup para moviles SIN root: igual que el normal + instala opencode si falta.
     *  Sin termux-reload-settings dentro: reinicia Termux y mataria este mismo script.
     *  Al acabar: cerrar Termux del todo y reabrirlo para aplicar el permiso. */
    private static final String SETUP_NOROOT = SETUP_BLOCK
            + "; echo '[2/3b] almacenamiento (acceso a archivos de Termux, te lo pedira)...';"
            + " command -v termux-setup-storage >/dev/null 2>&1"
            + " && (termux-setup-storage 2>&1 | tail -2; echo storage-pedido)"
            + " || echo storage-saltado-sin-comando;"
            + " ls ~/storage 2>/dev/null | head -3"
            + "; echo '[3/3] opencode actual (espejo auto + instala, tarda minutos)...';"
            + " S=$PREFIX/etc/apt/sources.list; [ -f \"$S\" ] && cp \"$S\" \"$S.bak\" 2>/dev/null;"
            + " for M in https://packages.termux.dev/apt/termux-main https://packages-cf.termux.dev/apt/termux-main https://grimler.se/termux/termux-main https://mirror.fcix.net/termux/termux-main;"
            + " do echo \"probando espejo $M ...\"; echo \"deb $M stable main\" > \"$S\";"
            + " if apt-get update >/dev/null 2>&1; then echo \"espejo-ok: $M\"; break; fi; done;"
            + " grep -h \"^deb \" \"$S\";"
            + " echo '[3/3b] reparando base (corte anterior dejo curl roto)...';"
            + " export DEBIAN_FRONTEND=noninteractive;"
            + " dpkg --configure -a;"
            + " apt-get install -y -f -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold;"
            + " apt-get install -y curl openssl ca-certificates -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold;"
            + " curl --version 2>&1 | head -1;"
            + " dpkg -s opencode >/dev/null 2>&1 && apt-get remove -y opencode;"
            + " curl -fSL https://raw.githubusercontent.com/Thr45hx/opencode-termux-native/main/install.sh -o ~/.opencode-android/oc-install.sh && ls -l ~/.opencode-android/oc-install.sh;"
            + " sed -i 's/pkg install -y/pkg install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold/g' ~/.opencode-android/oc-install.sh;"
            + " grep -c force-confdef ~/.opencode-android/oc-install.sh; bash ~/.opencode-android/oc-install.sh;"
            + " command -v opencode; opencode --version 2>&1 | head -1; echo setup-noroot-ok\n";

    private WebView web;
    private View boxContent;
    private MaterialCardView cardWeb;
    private TextView txtStatus;
    private TextView txtLog;
    private View statusDot;
    private LinearProgressIndicator progress;
    private MaterialButton btnStart;
    private MaterialButton btnOpen;
    private FloatingActionButton fabControls;
    private int fabRestoreTries = 0;
    private View splash;
    private TextView splashStatus;
    private boolean splashDone = false;
    private long splashStart = 0;
    private String pendingSplashSnack;
    private boolean fullscreen = false;
    private boolean urlLoading = false;
    private boolean advancedOpen = false;
    private boolean serverOpen = false;
    /** Modo sin root: true hasta que la comprobacion de arranque diga lo contrario. */
    private volatile boolean hasRoot = true;
    private volatile boolean rootChecked = false;
    /** Descarga del APK de Termux (paso 1 sin root). */
    private long termuxDownloadId = -1;
    private BroadcastReceiver termuxDownloadReceiver = null;
    private String termuxApkName = "termux.apk";
    private boolean termuxInstalling = false;
    private final android.os.Handler termuxDlHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable termuxDlTick;
    private int lastDlPct = -1;
    /** Avisos del agente: solo suenan si NO miras el chat. */
    private boolean appForeground = false;
    private int agentNotifId = 100;
    private PendingShare pendingShare;
    private BottomSheetDialog shareDialog;
    private BottomSheetDialog shareSessionPicker;
    private boolean shareOpening = false;
    private boolean shareFlowActive = false;
    private final Object shareServerLock = new Object();
    private volatile boolean shareServerStarting = false;
    private volatile boolean shareServerStartFailed = false;
    private volatile String shareTargetSessionId;
    private String shareTargetSessionTitle;
    private TextView shareSessionNameView;
    private TextView shareSessionSubView;
    private EditText shareInstructionView;
    private MaterialButton shareSendButton;
    private final List<ShareSession> shareSessions = new ArrayList<>();
    private boolean shareSessionsLoading = false;
    private volatile int shareSessionsGeneration = 0;
    private ActivityResultLauncher<String> notifPermLauncher;
    private boolean notifPermTried = false;
    /** El aviso de notificaciones ya tiene respuesta (para encadenar el salto a
     *  Ajustes sin que los dos dialogos se pisen). */
    private boolean notifPermAnswered = false;
    /** Arranque del servidor en curso (evita lanzar dos a la vez). */
    private boolean starting = false;
    /** Se acaba de abrir Ajustes a pedir el permiso de archivos: al volver
     *  hay que mirar si lo han dado o no. */
    private boolean filesAskPending = false;
    /** Permiso de "todos los archivos": en Android 11+ no hay dialogo, hay que
     *  llevar al usuario al ajuste de la app. Se pide solo una vez por sesion. */
    private boolean filesPermTried = false;
    /** Arranque del servidor en espera: el usuario esta en Ajustes dando el
     *  permiso de archivos; al volver se arranca solo. */
    private boolean pendingStartAfterPerms = false;
    private ActivityResultLauncher<Intent> lockLauncher;
    /** Desbloqueo ya pasado en esta vida de la app (el bloqueo se pide una vez). */
    private boolean unlockedRun = false;
    private Runnable pendingUnlock;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressTick;
    private boolean updating = false;
    private long updateStart = 0;
    private int lastStage = -1;
    private int lastMbQ = -1;
    /** Aviso de update: el boton Actualizar solo existe si hay update real
     *  (instalada < ultima de GitHub). Sin update, desaparece. */
    private boolean updatePending = false;
    private boolean updateInstallable = true;
    private String updInstalled = "";
    private String updLatest = "";
    /** Aviso de update del launcher: release con APK mas nuevo que esta version. */
    private boolean appUpdatePending = false;
    private String appUpdTag = "";
    private String appUpdUrl = "";
    private long appDownloadId = -1;
    private BroadcastReceiver appDownloadReceiver = null;
    /** Diagnostico del ultimo chequeo (solo al Registro, nunca aviso). */
    private final StringBuilder updDbg = new StringBuilder();
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Inmersivo desde el primer fotograma: si las barras se ocultan despues,
        // la ventana cambia de tamano y se ve un "mini zoom" al entrar al chat.
        enterImmersive();
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.webView);
        boxContent = findViewById(R.id.boxContent);
        cardWeb = findViewById(R.id.cardWeb);
        txtStatus = findViewById(R.id.txtStatus);
        txtLog = findViewById(R.id.txtLog);
        statusDot = findViewById(R.id.statusDot);
        progress = findViewById(R.id.progress);
        fabControls = findViewById(R.id.fabControls);
        fabControls.setOnClickListener(v -> showControlsMenu(fabControls));
        // Mini-boton arrastrable: un toque abre Controles, arrastrar lo mueve
        // y la posicion se guarda.
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        fabControls.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            boolean dragging;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dx = v.getX() - e.getRawX();
                        dy = v.getY() - e.getRawY();
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float nx = e.getRawX() + dx;
                        float ny = e.getRawY() + dy;
                        android.view.View p = (android.view.View) v.getParent();
                        nx = Math.max(0, Math.min(nx, p.getWidth() - v.getWidth()));
                        ny = Math.max(0, Math.min(ny, p.getHeight() - v.getHeight()));
                        if (Math.abs(nx - v.getX()) + Math.abs(ny - v.getY()) > slop) {
                            dragging = true;
                        }
                        if (dragging) {
                            v.setX(nx);
                            v.setY(ny);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (dragging) {
                            getSharedPreferences("app", MODE_PRIVATE).edit()
                                    .putFloat("fab_x", v.getX())
                                    .putFloat("fab_y", v.getY()).apply();
                        } else {
                            v.performClick();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            }
        });
        fabControls.post(() -> restoreFabPos());

        // Splash de entrada: logo con pop + contenido que entra desde abajo.
        // Tapa el arranque (servidor frio, primera pintura del WebView) para
        // que no se vea el salto brusco del panel al chat.
        splash = findViewById(R.id.splash);
        splashStatus = findViewById(R.id.splashStatus);
        splashStart = SystemClock.uptimeMillis();
        boxContent.setAlpha(0f);
        boxContent.setTranslationY(24f);
        ImageView splashLogo = findViewById(R.id.splashLogo);
        splashLogo.setScaleX(0.7f);
        splashLogo.setScaleY(0.7f);
        splashLogo.setAlpha(0f);
        splashLogo.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(450).setInterpolator(new OvershootInterpolator(1.2f)).start();
        findViewById(R.id.splashTitle).setAlpha(0f);
        findViewById(R.id.splashTitle).animate().alpha(1f).setStartDelay(120)
                .setDuration(350).start();
        Handler splashHandler = new Handler(Looper.getMainLooper());
        if (LocalRuntime.available(this)) {
            // Runtime local: el splash (logo + cargando) aguanta hasta que el
            // chat este dentro. Si el servidor no llega, se destapa el panel
            // para que se pueda reintentar a mano.
            splashHandler.postDelayed(() -> {
                if (!splashDone && !serverOpen) {
                    log("El servidor local no respondio a tiempo: destapo el panel.");
                    hideSplash();
                }
            }, 120000);
        } else {
            // Sin servidor: entra al panel con animacion (no se queda colgado).
            splashHandler.postDelayed(() -> {
                if (!splashDone && web.getUrl() == null) hideSplash();
            }, 2500);
            // Seguridad: nunca dejar el splash clavado.
            splashHandler.postDelayed(() -> {
                if (!splashDone) hideSplash();
            }, 9000);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) setFullscreen(false);
                else if (web.canGoBack()) web.goBack();
                else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        com.google.android.material.appbar.MaterialToolbar toolbar =
                findViewById(R.id.toolbar);
        View boxAdvanced = findViewById(R.id.boxAdvanced);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_controls) {
                advancedOpen = !advancedOpen;
                boxAdvanced.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
                return true;
            }
            if (item.getItemId() == R.id.action_lang) {
                toggleLang();
                return true;
            }
            return false;
        });

        // La caja de herramientas viaja dentro del APK: se instala sola, sin
        // que el usuario tenga que hacer nada (no depende de los permisos ni
        // de que arranque el servidor).
        if (LocalRuntime.available(this) && !LocalToolbox.complete(this)) {
            ToolboxService.installBundled(this);
        }
        pendingShare = parseShareIntent(getIntent());
        if (pendingShare == null) {
            autoOpenIfRunning();
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(this::showShareDialog, 700);
        }

        applyOnboarding();
        applyLang();

        findViewById(R.id.cardStatus).setOnClickListener(v -> enterChatBlocking());

        new Thread(() -> {
            String rid = RootUtil.requestRoot();
            boolean ok = RootUtil.isRooted(rid);
            hasRoot = ok;
            rootChecked = true;
            android.util.Log.d("OC-Update", "root-check: ok=" + ok
                    + " rid=" + (rid == null ? "null"
                    : rid.replace("\n", "|").substring(0, Math.min(120, rid.replace("\n", "|").length()))));
            String stab = ok ? RootUtil.stabilizeAndroid() : "sin root: modo sin root activo";
            runOnUiThread(() -> {
                log("Arranque auto:\n" + rid.split("\n")[0] + "\n" + stab);
                applyOnboarding();
                refreshStepStatus();
            });
        }).start();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // La UI de opencode es de escritorio: permitir pellizcar para ampliar
        // vistas apretadas (ajustes, diffs). Sin botones en pantalla.
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Servimos desde localhost: la cache no ahorra nada y una entrada a
        // medias dejaba la pagina en negro. Red siempre + purga unica.
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.clearCache(true);
        // Avisos del agente: la web cree que crea Notification; el puente avisa a Android.
        web.addJavascriptInterface(new NotifBridge(), "ocNotify");
        // Depuracion remota del WebView solo en APK debuggable (nunca en release).
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                urlLoading = false;
                hideSplash();
                injectEdgeCss();
                flattenViewportFrame();
                boostSidebarFooter();
                bridgeNotifications();
                redirectHelpToIssues();
                rebrandDesktopToMobile();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // La web es SPA: al navegar (lista -> chat) no hay recarga.
                injectEdgeCss();
                flattenViewportFrame();
                boostSidebarFooter();
                bridgeNotifications();
                redirectHelpToIssues();
                rebrandDesktopToMobile();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && !request.isForMainFrame()) return;
                // Sin esto, entrar con el servidor a medias dejaba la pagina en blanco
                // para siempre (getUrl ya no es null y nada reintentaba).
                urlLoading = false;
                serverOpen = false;
                refreshActionButtons();
                setDot(false);
                setFullscreen(false);
                txtStatus.setText(T("Servidor no responde: pulsa Abrir chat para reintentar",
                        "Server not answering: press Open chat to retry"));
                log("WebView error (vuelvo al panel, sin blanco): "
                        + (error == null ? "?" : String.valueOf(error.getDescription())));
                snack(T("Servidor no responde", "Server not answering"));
                hideSplash();
            }
            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                    String host, String realm) {
                if (host != null && (host.equals(HOST)
                        || host.equalsIgnoreCase("localhost")
                        || host.startsWith(HOST + ":"))) {
                    handler.proceed("opencode", LocalRuntime.serverPassword(MainActivity.this));
                } else {
                    super.onReceivedHttpAuthRequest(view, handler, host, realm);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                boolean local = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
                boolean httpOk = "http".equalsIgnoreCase(u.getScheme())
                        && (u.getPort() == PORT || u.getPort() == -1);
                if (local && httpOk) return false;
                Uri target = u;
                try {
                    android.util.Log.d("OpenCode", "ext-url: " + u);
                } catch (Exception ignored) {
                }
                String h = host != null ? host.toLowerCase(java.util.Locale.ROOT) : "";
                String p = u.getPath() != null
                        ? u.getPath().toLowerCase(java.util.Locale.ROOT) : "";
                boolean discordHost = h.equals("discord.gg") || h.endsWith(".discord.gg")
                        || h.equals("discord.com") || h.endsWith(".discord.com");
                // El Help de la web no va directo a Discord: pasa por opencode.ai
                // (p. ej. /discord), que redirige al invite. Se pilla por el path.
                boolean discordViaSite = (h.equals("opencode.ai") || h.endsWith(".opencode.ai"))
                        && (p.contains("discord") || p.contains("feedback"));
                if (discordHost || discordViaSite) {
                    // El Help de la web apunta al Discord de opencode (upstream):
                    // el soporte del launcher vive en nuestros issues.
                    target = Uri.parse("https://github.com/" + REPORT_REPO + "/issues");
                    log("Help redirigido a issues del launcher");
                }
                try {
                    Intent ext = new Intent(Intent.ACTION_VIEW, target);
                    ext.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(ext);
                    log("Abierta en navegador: " + u);
                } catch (Exception e) {
                    log("URL externa bloqueada (sin navegador): " + u);
                }
                return true;
            }
        });

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] uris = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (data.getClipData() != null) {
                            int n = data.getClipData().getItemCount();
                            uris = new Uri[n];
                            for (int i = 0; i < n; i++) {
                                uris[i] = data.getClipData().getItemAt(i).getUri();
                            }
                        } else if (data.getData() != null) {
                            uris = new Uri[]{data.getData()};
                        }
                    }
                    filePathCallback.onReceiveValue(uris);
                    filePathCallback = null;
                });

        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    notifPermAnswered = true;
                    log(granted ? "Avisos activados: sonara al terminar el agente."
                            : "Avisos denegados: no sonara al terminar el agente.");
                    // Encadenado: primero el aviso de notificaciones, despues el
                    // salto a Ajustes para el permiso de archivos.
                    if (LocalRuntime.available(this) && !hasAllFilesAccess()) {
                        askAllFilesAccess();
                    }
                });
        lockLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == RESULT_OK) {
                        unlockedRun = true;
                        if (pendingUnlock != null) {
                            Runnable p = pendingUnlock;
                            pendingUnlock = null;
                            p.run();
                        }
                    } else {
                        snack(T("Bloqueado: sin desbloqueo no hay chat",
                                "Locked: no unlock, no chat"));
                    }
                });
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("app", MODE_PRIVATE).getBoolean("notif_perm_asked", false)) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("notif_perm_asked", true).apply();
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        // Runtime local: se pide tambien el acceso a archivos la primera vez, en
        // vez de dejar que el usuario lo descubra cuando algo falla. Se abre
        // ya, sin esperar: el hilo principal se ocupa en un par de segundos.
        // Si el aviso de notificaciones sigue en pantalla, el salto a Ajustes
        // espera a que el usuario conteste (si no, se pisan los dos dialogos).
        if (LocalRuntime.available(this) && !hasAllFilesAccess()
                && !getSharedPreferences("app", MODE_PRIVATE).getBoolean("files_perm_asked", false)) {
            getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("files_perm_asked", true).apply();
            if (!notifDialogPending()) askAllFilesAccess();
        }

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent content = new Intent(Intent.ACTION_GET_CONTENT);
                    content.addCategory(Intent.CATEGORY_OPENABLE);
                    String[] accept = params.getAcceptTypes();
                    content.setType((accept != null && accept.length > 0 && accept[0] != null
                            && !accept[0].isEmpty()) ? accept[0] : "*/*");
                    content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    content.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                    fileChooserLauncher.launch(Intent.createChooser(content, "Elegir archivo"));
                } catch (Exception e) {
                    filePathCallback = null;
                    log("Selector de archivos no disponible: " + e.getMessage());
                    return false;
                }
                return true;
            }
        });

        btnStart = findViewById(R.id.btnStart);
        btnOpen = findViewById(R.id.btnOpen);
        // Hasta saber si hay root no se arranca (evita la via equivocada).
        btnStart.setEnabled(false);
        btnOpen.setOnClickListener(v -> enterChatBlocking());
        MaterialButton btnClear = findViewById(R.id.btnClear);
        MaterialButton btnTermux = findViewById(R.id.btnTermux);

        btnClear.setOnClickListener(v -> txtLog.setText(""));

        MaterialButton btnCopy = findViewById(R.id.btnCopy);
        btnCopy.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                String all = txtLog.getText().toString();
                cm.setPrimaryClip(ClipData.newPlainText("opencode-log", all));
                snack(all.trim().isEmpty() ? T("Registro vacio, nada que copiar", "Log empty, nothing to copy")
                        : T("Registro copiado (", "Log copied (") + all.length() + T(" caracteres), pegalo aqui", " chars), paste it here"));
            } catch (Exception e) {
                snack(T("No se pudo copiar", "Could not copy"));
            }
        });

        // El boton va oscurecido hasta que los 5 pasos esten en verde
        // (refreshStepStatus): deshabilitado no responde al toque, sin dialogos.
        btnStart.setOnClickListener(v -> startServerSequence());

        btnTermux.setOnClickListener(v -> {
            copyManualCommand();
            log(TermuxRunner.openTermuxApp(this)
                    + "\nComando copiado, pegalo en Termux:\n" + MANUAL_CMD);
            snack(T("Comando manual copiado", "Manual command copied"));
        });

        MaterialButton btnSetup = findViewById(R.id.btnSetup);
        btnSetup.setOnClickListener(v -> copySetupAndOpenTermux());

        MaterialButton btnStabilize = findViewById(R.id.btnStabilize);
        btnStabilize.setOnClickListener(v -> {
            setBusy(true);
            new Thread(() -> {
                String out = RootUtil.stabilizeAndroid();
                runOnUiThread(() -> {
                    setBusy(false);
                    log("$ estabilizar Android:\n" + out
                            + "\nAhora permite a Termux ignorar la optimizacion de bateria.");
                    snack(T("Ajuste aplicado, abre Bateria", "Setting applied, open Battery"));
                    requestBatteryExemption();
                });
            }).start();
        });

        MaterialButton btnFdroid = findViewById(R.id.btnFdroid);
        btnFdroid.setOnClickListener(v -> showTermuxDownloadGuide());

        MaterialButton btnBattery = findViewById(R.id.btnBattery);
        btnBattery.setOnClickListener(v -> {
            log("Pidiendo que Android no suspenda a Termux en segundo plano...");
            requestBatteryExemption();
        });

        MaterialButton btnAppUpdate = findViewById(R.id.btnAppUpdate);
        btnAppUpdate.setOnClickListener(v -> {
            if (!appUpdatePending || appUpdUrl.isEmpty()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !getPackageManager().canRequestPackageInstalls()) {
                log("Permite 'instalar apps desconocidas' para esta app y vuelve a pulsar Descargar.");
                snack(T("Permite instalar apps y repite", "Allow installing apps and retry"));
                openUnknownSourcesSettings();
                return;
            }
            downloadAppUpdate(appUpdUrl, appUpdTag);
        });

        MaterialButton btnUpdate = findViewById(R.id.btnUpdate);
        btnUpdate.setOnClickListener(v -> {
            setBusy(true);
            btnUpdate.setEnabled(false);
            if (useRoot() && !hasAllFilesAccess()) {
                log("Falta permiso de archivos: te llevo a Ajustes, activalo y pulsa Actualizar de nuevo.");
                snack(T("Activa 'todos los archivos' y repite", "Enable 'All files' and retry"));
                try {
                    Intent si = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(si);
                } catch (Exception e) {
                    log("No se pudo abrir Ajustes: " + e.getMessage());
                }
                setBusy(false);
                btnUpdate.setEnabled(true);
                return;
            }
            // Sin doble toque: si ya hay un update en marcha, avisar y salir.
            if (updating) {
                snack(T("Ya se está actualizando…", "Already updating…"));
                setBusy(false);
                btnUpdate.setEnabled(true);
                return;
            }
            updating = true;
            updateStart = System.currentTimeMillis();
            lastStage = -1;
            lastMbQ = -1;
            // UI inmediata (hilo principal): barra + estado + poll + aviso.
            // El intent va despues en otro hilo: aunque el binder se atasque,
            // la UI ya corre y el poll informa.
            progress.setIndeterminate(true);
            progress.setProgressCompat(0, false);
            progress.setVisibility(View.VISIBLE);
            txtStatus.setText(T("Actualizando opencode… 00:00", "Updating opencode… 00:00"));
            log("Actualizando opencode en Termux (backup + installer, tarda minutos)...");
            setFullscreen(false);
            startProgressPoll();
            try {
                com.google.android.material.snackbar.Snackbar.make(web,
                        T("Actualizando opencode, espera…", "Updating opencode, wait…"),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setAction(T("Ver Termux", "See Termux"),
                                vv -> TermuxRunner.openTermuxApp(this))
                        .show();
            } catch (Exception e) {
                snack(T("Actualizacion lanzada", "Update launched"));
            }
            // El intent en hilo aparte (no bloquea la UI).
            new Thread(() -> {
                StringBuilder t = new StringBuilder();
                if (useRoot()) {
                    String r1 = RootUtil.startUpdateViaAmBg();
                    t.append("1/2 FGS por root:\n").append(r1).append("\n");
                    if (r1.contains("Error") || r1.contains("fallo") || r1.contains("ERROR")) {
                        t.append("2/2 servicio directo:\n").append(TermuxRunner.startUpdateViaService(this)).append("\n");
                    }
                } else {
                    t.append(TermuxRunner.startUpdateViaService(this)).append("\n");
                }
                String transcript = t.toString();
                // Solo el parte del envio va diferido; el poll ya corre.
                runOnUiThread(() -> log(transcript + "Actualizando en Termux: mira el % y el estado aqui arriba (no cierres la app)."));
            }).start();
        });

        MaterialButton btnRestart = findViewById(R.id.btnRestart);
        btnRestart.setOnClickListener(v -> {
            setBusy(true);
            btnRestart.setEnabled(false);
            log("Reiniciando servidor...");
            new Thread(() -> {
                String out = stopRuntime();
                boolean gone = waitForPortGone(8000);
                runOnUiThread(() -> {
                    setBusy(false);
                    btnRestart.setEnabled(true);
                    log("$ reiniciar (fase 1 detener):\n" + out
                            + (gone ? "\nDetenido, arrancando..." : "\nAviso: puerto seguia abierto, arranco igual..."));
                    btnStart.performClick();
                });
            }).start();
        });

        MaterialButton btnStop = findViewById(R.id.btnStop);        btnStop.setOnClickListener(v -> {
            setBusy(true);
            btnStop.setEnabled(false);
            log("Deteniendo servidor (manual o de la app)...");
            new Thread(() -> {
                String out = stopRuntime();
                boolean open = !waitForPortGone(6000);
                runOnUiThread(() -> {
                    setBusy(false);
                    btnStop.setEnabled(true);
                    serverOpen = open;
                    refreshActionButtons();
                    setDot(open);
                    txtStatus.setText(open ? T("Servidor abierto (sigue vivo)", "Server open (still alive)") : T("Servidor detenido", "Server stopped"));
                    log("$ detener\n" + out + (open
                            ? "\nSIGUE ABIERTO: mira el holder arriba. En Termux: pkill -9 -f opencode"
                            : "\nOK detenido"));
                    snack(open ? T("Sigue abierto, mira el registro", "Still open, check the log") : T("Servidor detenido", "Server stopped"));
                    // Si se detuvo desde el chat, volver al panel (ahi esta Iniciar).
                    if (!open) setFullscreen(false);
                    if (!open) {
                        // Parado a mano: el vigía no toca nada hasta el próximo arranque.
                        getSharedPreferences("app", MODE_PRIVATE).edit()
                                .putBoolean(ServerWatch.PREF_WD_HOLD, true).apply();
                        log(T("Vigía en pausa hasta el próximo arranque.",
                                "Watchdog paused until next start."));
                    }
                    if (!open) {
                        new Thread(() -> {
                            String woff = useRoot() ? RootUtil.fireTermuxCmd(RootUtil.WAKE_OFF)
                                    : TermuxRunner.wakeViaService(this, false);
                            runOnUiThread(() -> log("wake-lock liberado."));
                        }).start();
                    }
                });
            }).start();
        });

        log("Pulsa Iniciar y abrir. Si el servidor ya corre, entras directo al chat.");
        // El vigía sobrevive solo hasta reiniciar: rearmarlo siempre (si esta OFF no hace nada).
        ServerWatch.arm(this);
        // Chequeo diario de updates aunque la app este cerrada (solo avisa).
        ServerWatch.armUpdateCheck(this);
        // Descarga de Termux a medias (cierre en mitad): se retoma la vigilancia.
        long pendingDl = getSharedPreferences("app", MODE_PRIVATE).getLong("termux_dl_id", -1);
        if (pendingDl > 0) {
            termuxApkName = getSharedPreferences("app", MODE_PRIVATE)
                    .getString("termux_apk", termuxApkName);
            log("Hay una descarga de Termux pendiente, retomando...");
            watchTermuxDownload(pendingDl);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        PendingShare share = parseShareIntent(intent);
        if (share != null) {
            pendingShare = share;
            showShareDialog();
        }
    }

    @Override
    protected void onPause() {
        appForeground = false;
        saveFabPos();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appForeground = true;
        web.onResume();
        web.resumeTimers();
        checkStatus(false);
        refreshGuide();
        checkForUpdate(false);
        checkLauncherUpdate(false);
        // Al volver de Ajustes/Termux se recalculan los pasos: la tira de
        // estados y el oscurecido de Iniciar quedan al dia solos.
        refreshStepStatus();
        // Si el usuario fue a Ajustes a dar el permiso de archivos, el arranque
        // que se quedo esperando continua solo.
        if (filesAskPending) {
            filesAskPending = false;
            if (!hasAllFilesAccess()) {
                // Denegado: se destapa el panel para poder reintentar a mano.
                pendingStartAfterPerms = false;
                starting = false;
                hideSplash();
            }
        }
        if (pendingStartAfterPerms && hasAllFilesAccess()) onAllFilesAccessReady();
        // Runtime local: si el servidor no esta, se levanta solo al abrir la app
        // y se entra al chat en cuanto responde.
        if (LocalRuntime.available(this) && pendingShare == null && !serverOpen && !starting
                && !isPortOpen() && hasAllFilesAccess()) {
            autoOpenIfRunning();
        }
    }

    /** Paso 1 automatico: baja de GitHub el APK de Termux para esta ABI y abre el instalador. */
    private void downloadAndInstallTermux(String suffix) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            log("Permite 'instalar apps desconocidas' para esta app y vuelve a pulsar Descargar.");
            snack(T("Permite instalar apps y repite", "Allow installing apps and retry"));
            openUnknownSourcesSettings();
            return;
        }
        setBusy(true);
        log("Buscando el APK de Termux (" + suffix + ") en GitHub...");
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/termux/termux-app/releases/latest")
                                .openConnection();
                c.setRequestProperty("User-Agent", "opencode-android");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject rel = new JSONObject(sb.toString());
                org.json.JSONArray assets = rel.getJSONArray("assets");
                String url = null;
                String name = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String n = a.optString("name", "");
                    if (n.startsWith("termux-app_") && n.contains("_" + suffix + ".apk")) {
                        name = n;
                        url = a.optString("browser_download_url", null);
                        break;
                    }
                }
                if (url == null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String n = a.optString("name", "");
                        if (n.startsWith("termux-app_") && n.contains("universal.apk")) {
                            name = n;
                            url = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (url == null) {
                    throw new Exception("no hay asset termux-app *_" + suffix + ".apk en "
                            + rel.optString("tag_name", "?"));
                }
                termuxApkName = "termux-" + suffix + ".apk";
                // Descarga en la carpeta privada de la app: en Download/ el
                // fichero es del MediaProvider y sin "todos los archivos" la
                // app no puede leerlo (el instalador daba "error al analizar
                // el paquete"). En la privada siempre es legible.
                java.io.File stale = new java.io.File(getExternalFilesDir("updates"), termuxApkName);
                if (stale.exists()) stale.delete();
                android.app.DownloadManager dm =
                        (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                android.app.DownloadManager.Request req =
                        new android.app.DownloadManager.Request(Uri.parse(url));
                req.setTitle("Termux (" + suffix + ")");
                req.setDescription(name);
                req.setMimeType("application/vnd.android.package-archive");
                req.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalFilesDir(this, "updates", termuxApkName);
                termuxDownloadId = dm.enqueue(req);
                getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putString("termux_apk", termuxApkName).apply();
                final long idF = termuxDownloadId;
                final String nameF = name;
                runOnUiThread(() -> {
                    watchTermuxDownload(idF);
                    log("Descargando " + nameF + " ...");
                    snack(T("Descargando Termux...", "Downloading Termux..."));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    log("Descarga automatica fallo: " + e.getMessage()
                            + "\nUsa 'Abrir GitHub' y elige el _" + suffix + ".apk a mano.");
                    snack(T("Fallo la descarga, mira el registro", "Download failed, check the log"));
                });
            }
        }).start();
    }

    /** Vigila la descarga: barra con % real, reanuda tras reinicios y avisa fallos. */
    private void watchTermuxDownload(long id) {
        termuxDownloadId = id;
        termuxInstalling = false;
        getSharedPreferences("app", MODE_PRIVATE).edit().putLong("termux_dl_id", id).apply();
        if (termuxDownloadReceiver == null) {
            termuxDownloadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long rid = intent.getLongExtra(
                            android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    // El aviso salta tambien si fallo: se consulta el estado
                    // real y solo se instala si la descarga esta completa.
                    if (rid == termuxDownloadId && termuxDlTick != null) {
                        pollTermuxDownloadOnce();
                    }
                }
            };
            ContextCompat.registerReceiver(this, termuxDownloadReceiver,
                    new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        stopTermuxDlPoll();
        lastDlPct = -1;
        progress.setIndeterminate(false);
        progress.setProgressCompat(0, false);
        termuxDlTick = new Runnable() {
            @Override
            public void run() {
                if (!pollTermuxDownloadOnce()) termuxDlHandler.postDelayed(this, 1000);
            }
        };
        termuxDlHandler.post(termuxDlTick);
    }

    private void stopTermuxDlPoll() {
        if (termuxDlTick != null) termuxDlHandler.removeCallbacks(termuxDlTick);
        termuxDlTick = null;
    }

    /** Devuelve true si la descarga termino (bien o mal). */
    private boolean pollTermuxDownloadOnce() {
        try {
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Query q =
                    new android.app.DownloadManager.Query().setFilterById(termuxDownloadId);
            try (android.database.Cursor c = dm.query(q)) {
                if (c == null || !c.moveToFirst()) {
                    finishTermuxDownload("Descarga desaparecida (id ya no existe). Reintenta.");
                    return true;
                }
                int status = c.getInt(c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS));
                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    stopTermuxDlPoll();
                    setBusy(false);
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                            .remove("termux_dl_id").apply();
                    long totalSz = c.getLong(c.getColumnIndex(
                            android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    log("Descarga completa (" + mbText(totalSz) + "). Abriendo instalador...");
                    txtStatus.setText(T("Descarga completa, confirma la instalacion",
                            "Download complete, confirm the install"));
                    installTermuxApk();
                    return true;
                }
                if (status == android.app.DownloadManager.STATUS_FAILED) {
                    int reason = c.getInt(c.getColumnIndex(android.app.DownloadManager.COLUMN_REASON));
                    finishTermuxDownload("Descarga fallida: " + dlReasonText(reason));
                    return true;
                }
                long sofar = c.getLong(c.getColumnIndex(
                        android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndex(
                        android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int pct = total > 0 ? (int) (sofar * 100 / total) : 0;
                progress.setIndeterminate(false);
                progress.setProgressCompat(pct, true);
                String dlText = total > 0
                        ? T("Descargando Termux: ", "Downloading Termux: ") + mbText(sofar) + " / " + mbText(total)
                                + " (" + pct + "%)"
                        : T("Descargando Termux: ", "Downloading Termux: ") + mbText(sofar) + " ...";
                txtStatus.setText(dlText);
                if (pct / 25 != lastDlPct / 25) {
                    lastDlPct = pct;
                    final String dlTextF = dlText;
                    runOnUiThread(() -> log(dlTextF));
                }
                return false;
            }
        } catch (Exception e) {
            finishTermuxDownload("No se pudo leer el progreso: " + e.getMessage());
            return true;
        }
    }

    private void finishTermuxDownload(String msg) {
        stopTermuxDlPoll();
        setBusy(false);
        progress.setIndeterminate(true);
        getSharedPreferences("app", MODE_PRIVATE).edit().remove("termux_dl_id").apply();
        log(msg);
        txtStatus.setText(T("Descarga fallida", "Download failed"));
        snack(T("Fallo la descarga, mira el registro", "Download failed, check the log"));
    }

    private String mbText(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private String dlReasonText(int reason) {
        switch (reason) {
            case android.app.DownloadManager.ERROR_FILE_ERROR:
                return "no se pudo guardar el archivo (espacio o permiso)";
            case android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "sin espacio en el movil";
            case android.app.DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "almacenamiento no disponible";
            case android.app.DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "corte de red durante la descarga";
            case android.app.DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "GitHub respondio raro (http)";
            case android.app.DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "demasiadas redirecciones";
            case android.app.DownloadManager.ERROR_CANNOT_RESUME:
                return "descarga interrumpida y no reanudable: reintenta";
            default:
                if (reason >= 400 && reason < 600) return "HTTP " + reason + " en GitHub";
                return "motivo " + reason;
        }
    }

    /** Abre el instalador del sistema con el APK bajado (via FileProvider). */
    private void installTermuxApk() {
        if (termuxInstalling) return;
        termuxInstalling = true;        try {
            java.io.File apk = new java.io.File(getExternalFilesDir("updates"), termuxApkName);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            Intent in = new Intent(Intent.ACTION_VIEW);
            in.setDataAndType(uri, "application/vnd.android.package-archive");
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(in);
            log("Instalador abierto: confirma Instalar, luego abre Termux UNA vez.");
            snack(T("Confirma la instalacion", "Confirm the install"));
        } catch (Exception e) {
            log("No se pudo abrir el instalador: " + e.getMessage());
            snack(T("Fallo el instalador, mira el registro", "Installer failed, check the log"));
        }
    }

    /** Guia de descarga segun el chip del movil: cada APK de GitHub vale solo para su ABI. */
    private void showTermuxDownloadGuide() {
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        String suffix;
        if (abi.contains("arm64")) suffix = "arm64-v8a";
        else if (abi.contains("x86_64")) suffix = "x86_64";
        else if (abi.contains("x86")) suffix = "x86";
        else suffix = "armeabi-v7a";
        String msg = T("Tu chip es ", "Your chip is ") + abi + T(": en GitHub descarga el archivo que termina en _",
                    ": on GitHub download the file ending in _")
                + suffix + T(".apk (los otros no te sirven).\n\nTras instalarlo, abre Termux UNA vez",
                    ".apk (the others will not work for you).\n\nAfter installing it, open Termux ONCE")
                + T(" y vuelve aqui al paso 2 (Setup).", " and come back here to step 2 (Setup).");
        final String suffixF = suffix;
        new android.app.AlertDialog.Builder(this)
                .setTitle(T("Instalar Termux", "Install Termux"))
                .setMessage(msg)
                .setPositiveButton(T("Abrir GitHub", "Open GitHub"), (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/termux/termux-app/releases")));
                    } catch (Exception e) {
                        snack(T("No se pudo abrir el navegador", "Could not open the browser"));
                    }
                })
                .setNeutralButton(T("Descargar e instalar", "Download and install"), (d, w) -> downloadAndInstallTermux(suffixF))
                .setNegativeButton(T("Cerrar", "Close"), null)
                .show();
    }

    /** Paso 2 de la guia: copia el setup (instala opencode si falta) y abre Termux. */
    private void copySetupAndOpenTermux() {
        // El setup instala opencode si falta (con y sin root): es idempotente.
        final String block = SETUP_NOROOT;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("opencode-setup", block));
            getSharedPreferences("app", MODE_PRIVATE).edit()
                    .putBoolean("setup_tried", true).apply();
            log("Setup copiado. Pegalo UNA vez en Termux y espera a que acabe;"
                    + " luego cierra Termux del todo, reabrelo y vuelve aqui:"
                    + " al completar los puntos, Iniciar y abrir se enciende:\n" + block);
            snack(T("Setup copiado: pegalo en Termux", "Setup copied: paste it in Termux"));
        } catch (Exception e) {
            snack(T("No se pudo copiar", "Could not copy"));
        }
        TermuxRunner.openTermuxApp(this);
    }

    /** Lleva al permiso de overlay DE TERMUX (sesiones visibles por intent).
     *  OJO: Termux sideloaded trae "ajustes restringidos" y el overlay no se deja
     *  activar hasta liberarlo en su info (menu tres puntos). Se guia en orden. */
    private void requestTermuxOverlay() {
        String msg = T("1) INFO DE TERMUX: toca arriba los tres puntos y elige",
                    "1) TERMUX INFO: tap the three dots above and choose")
                + T(" 'Permitir ajustes restringidos'.\n", " 'Allow restricted settings'.\n")
                + T("2) AJUSTE OVERLAY: activalo.\n", "2) OVERLAY SETTING: enable it.\n")
                + T("3) Vuelve aqui y pulsa el boton grande.", "3) Come back here and press the big button.");
        new android.app.AlertDialog.Builder(this)
                .setTitle(T("Mostrar encima (Termux)", "Display over apps (Termux)"))
                .setMessage(msg)
                .setPositiveButton(T("Info Termux", "Termux info"), (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:com.termux")));
                    } catch (Exception e) {
                        log("Abre manual: Ajustes > Apps > Termux.");
                    }
                })
                .setNeutralButton(T("Ajuste overlay", "Overlay setting"), (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:com.termux")));
                    } catch (Exception e) {
                        log("Abre manual: Ajustes > Apps > Acceso especial > Mostrar sobre otras apps > Termux.");
                    }
                })
                .setNegativeButton(T("Cerrar", "Close"), null)
                .show();
        log("Termux pide overlay: sigue los pasos del dialogo (primero ajustes restringidos).");
    }

    /** Lleva a la info de ESTA app para activar "Run commands in Termux environment". */
    private void openAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            snack(T("Activa el permiso y vuelve", "Enable the permission and come back"));
            log("Activa: Permisos > Run commands in Termux environment. Luego vuelve y pulsa el boton grande.");
        } catch (Exception e) {
            log("Abre manual: Ajustes > Apps > OpenCode Android > Permisos.");
        }
    }

    /** Permiso externo con UNA linea corta (solo si el automatico dice "sin permiso"). */
    private void copyPermAndOpenTermux() {
        final String line = "mkdir -p ~/.termux; grep -qi '^allow-external-apps *= *true'"
                + " ~/.termux/termux.properties 2>/dev/null"
                + " || echo 'allow-external-apps = true' >> ~/.termux/termux.properties";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("opencode-perm", line));
            log("Linea copiada. Pegala en Termux + enter, luego CIERRA Termux del todo,"
                    + " reabrelo y vuelve aqui para pulsar el boton grande:\n" + line);
            snack(T("Linea copiada: pegala en Termux", "Line copied: paste it in Termux"));
        } catch (Exception e) {
            snack(T("No se pudo copiar", "Could not copy"));
        }
        TermuxRunner.openTermuxApp(this);
    }

    /** Pide a Android no matar a Termux (reemplazo sin root de Estabilizar). */
    private void requestBatteryExemption() {
        try {
            Intent bi = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:com.termux"));
            startActivity(bi);
        } catch (Exception e) {
            log("Abre manual: Ajustes > Apps > Termux > Bateria > Sin restricciones.");
        }
    }

    /** Interruptor PROPIO de permisos automaticos (el de la web de opencode no hace
     *  nada en modo serve). Por defecto SI, como lo pidio el usuario. */
    private boolean autoPerm() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("auto_perm", true);
    }

    private String stopRuntime() {
        if (LocalRuntime.available(this)) {
            LocalServerService.stop(this);
            LocalRuntime.stop();
            return "runtime local detenido";
        }
        return useRoot() ? RootUtil.stopServer() : TermuxRunner.stopViaService(this);
    }

    private void startLocalServer() {
        new Thread(() -> {
            StringBuilder t = new StringBuilder();
            try {
                t.append("0) runtime local (proot)...\n");
                LocalServerService.start(this);
                t.append("Servicio lanzado; esperando el puerto ").append(PORT).append("...\n");
                boolean open = waitForPort(20000);
                boolean httpOk = false;
                if (open) {
                    t.append("Puerto abierto; esperando el primer HTTP 200...\n");
                    httpOk = waitForHttp(45000);
                }
                final boolean done = open;
                final boolean httpOkF = httpOk;
                String transcript = t.toString();
                runOnUiThread(() -> {
                    setBusy(false);
                    starting = false;
                    refreshStepStatus();
                    serverOpen = done;
                    refreshActionButtons();
                    setDot(done);
                    txtStatus.setText(done ? T("Servidor abierto", "Server open")
                            : T("Servidor sigue cerrado", "Server still closed"));
                    log(transcript + (done
                            ? "OK: runtime local en " + URL
                            : "FALLO: revisa logcat con etiqueta OpenCodeLocal"));
                    if (done) {
                        getSharedPreferences("app", MODE_PRIVATE).edit()
                                .putBoolean("onboarding_done", true)
                                .putBoolean("wizard_done", true)
                                .putBoolean("setup_ok", true).apply();
                        applyOnboarding();
                    } else {
                        refreshGuide();
                        // Si no arranco, el splash se destapa: el panel muestra el
                        // error y el boton de reintentar.
                        hideSplash();
                    }
                    snack(done ? T("Servidor abierto", "Server open")
                            : T("No arranco: mira el registro", "Did not start: check the log"));
                    if (done && !shareBlocksAutoOpen()) {
                        if (httpOkF) {
                            setSplashStatus(T("Abriendo el chat...", "Opening the chat..."));
                            enterChatLocked(() -> {
                                webLoad(URL);
                                setFullscreen(true);
                            });
                        } else {
                            snack(T("Servidor aun arrancando, pulsa Abrir chat en unos segundos",
                                    "Server still starting, press Open chat in a few seconds"));
                        }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    starting = false;
                    refreshStepStatus();
                    refreshActionButtons();
                    setDot(false);
                    txtStatus.setText(T("Runtime local fallo", "Local runtime failed"));
                    log(t.toString() + "ERROR: " + e + "\nRevisa logcat con etiqueta OpenCodeLocal.");
                    snack(T("Runtime local fallo, mira el registro", "Local runtime failed, check the log"));
                    hideSplash();
                });
            }
        }).start();
    }

    /** Arranque SIN root: reparar config + serve, todo por intents RUN_COMMAND
     *  (se ejecutan dentro de Termux; exige allow-external-apps). */
    private void startServerNoRoot() {
        openGen++;
        clearWatchHold();
        new Thread(() -> {
            StringBuilder t = new StringBuilder();
            if (!TermuxRunner.isTermuxInstalled(this)) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refreshStepStatus();
                    setDot(false);
                    txtStatus.setText(T("Falta Termux", "Termux missing"));
                    log("Falta com.termux: instalalo desde GitHub (boton de abajo),"
                            + " abrelo una vez y pulsa Iniciar de nuevo.");
                    snack(T("Instala Termux primero", "Install Termux first"));
                });
                return;
            }
            t.append("0) Sin root: reparo config dentro de Termux...\n")
                    .append(TermuxRunner.repairViaService(this)).append("\n");
            t.append("0b) Sin root: aplico interruptor permisos-auto...\n")
                    .append(TermuxRunner.applyPermViaService(this, autoPerm())).append("\n");
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            t.append("1/2 arranque por intent...\n")
                    .append(TermuxRunner.startViaService(this)).append("\n");
            boolean open = waitForPort(8000);
            if (!open) {
                t.append("2/2 reintento por intent...\n")
                        .append(TermuxRunner.startViaService(this)).append("\n");
                open = waitForPort(6000);
            }
            boolean done = open;
            boolean httpOk = false;
            if (done) {
                t.append("Puerto abierto, esperando primer HTTP 200 (el servidor frio tarda)...\n");
                httpOk = waitForHttp(30000);
                t.append(httpOk ? "HTTP 200 listo, abriendo UI\n"
                        : "HTTP tardo mas de 30s, no abro solo: pulsa Abrir chat en unos segundos\n");
            }
            final boolean httpOkF = httpOk;
            String transcript = t.toString();
            runOnUiThread(() -> {
                setBusy(false);
                refreshStepStatus();
                serverOpen = done;
                refreshActionButtons();
                setDot(done);
                txtStatus.setText(done ? T("Servidor abierto", "Server open") : T("Servidor sigue cerrado", "Server still closed"));
                log(transcript + (done
                        ? "OK: puerto " + PORT + " abierto"
                        : "FALLO. Sigue la guia de abajo (paso 2: pega el comando UNA vez"
                        + " en Termux), cierra Termux del todo, abrelo y pulsa Iniciar de nuevo."));
                if (done) {
                    getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("onboarding_done", true).apply();
                    getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("wizard_done", true).putBoolean("setup_ok", true).apply();
                    applyOnboarding();
                } else {
                    refreshGuide();
                }
                snack(done ? T("Servidor abierto", "Server open") : T("No arranco: mira el registro", "Did not start: check the log"));
                if (done) {
                    if (!shareBlocksAutoOpen()) {
                        if (httpOkF) {
                            enterChatLocked(() -> {
                                webLoad(URL);
                                setFullscreen(true);
                            });
                            new Thread(() -> {
                                String w = TermuxRunner.wakeViaService(this, true);
                                String first = w.split("\n")[0];
                                runOnUiThread(() -> log("wake-lock: " + first + " (requiere app Termux:API)"));
                            }).start();
                        } else {
                            snack(T("Servidor aun arrancando, pulsa Abrir chat en unos segundos", "Server still starting, press Open chat in a few seconds"));
                        }
                    }
                }
            });
        }).start();
    }

    /** Secuencia: script -> FGS root -> startservice root -> directo -> demonio -> diagnostico. */
    private void startServerSequence() {
        openGen++;
        clearWatchHold();
        setBusy(true);
        btnStart.setEnabled(false);
        if (LocalRuntime.available(this)) {
            // El runtime local necesita leer/escribir archivos del movil: sin el
            // permiso de archivos se pide ahora y el arranque sigue al volver.
            if (!hasAllFilesAccess()) {
                pendingStartAfterPerms = true;
                if (filesPermTried) {
                    // Ya se pidio en esta sesion: se destapa el panel con el aviso
                    // en vez de dejar el splash esperando.
                    pendingStartAfterPerms = false;
                    starting = false;
                    hideSplash();
                    snack(T("Falta 'todos los archivos' para arrancar el agente",
                            "All files access is needed to start the agent"));
                } else {
                    askAllFilesAccess();
                }
                return;
            }
            startLocalServer();
            return;
        }
        // Sin Termux no hay donde correr: igual con root que sin root, a descargarlo.
        if (!TermuxRunner.isTermuxInstalled(this)) {
            setBusy(false);
            refreshStepStatus();
            setDot(false);
            txtStatus.setText(T("Falta Termux", "Termux missing"));
            log("Falta com.termux: instalalo (abajo te digo cual), abrelo una vez"
                    + " y pulsa Iniciar de nuevo.");
            snack(T("Instala Termux primero", "Install Termux first"));
            showTermuxDownloadGuide();
            return;
        }
        // Sin root no hay su ni ficheros directos: todo via intents RUN_COMMAND.
        if (rootChecked && !useRoot()) {
            startServerNoRoot();
            return;
        }
        new Thread(() -> {
            StringBuilder t = new StringBuilder();
            String rid = RootUtil.requestRoot();
            if (!RootUtil.isRooted(rid)) {
                runOnUiThread(() -> {
                    setBusy(false);
                    refreshStepStatus();
                    setDot(false);
                    txtStatus.setText(T("Root: denegado", "Root: denied"));
                    log("$ su -c id\n" + rid
                            + "\nConcede root en Magisk y pulsa Iniciar y abrir de nuevo.");
                    snack(T("Root denegado: concede en Magisk", "Root denied: grant it in Magisk"));
                });
                return;
            }
            t.append("Root OK, escribiendo script...\n");
            if (!TermuxRunner.isTermuxInstalled(this)) {
                t.append("AVISO: com.termux no detectado en este dispositivo.\n");
            }
            String pre = RootUtil.preflight();
            t.append("Preflight (vista root, en este movil puede ser fantasma):\n").append(pre).append("\n");
            if (pre.contains("PREFLIGHT=EMPTY")) {
                t.append("AVISO: root no ve el disco de Termux (namespace aislado)."
                        + " Sigo igual: el arranque corre DENTRO de Termux y no necesita esa vista.\n");
            }
            t.append(RootUtil.ensureServeScript()).append("\n");
            t.append("0c) auto-reparar config (doble ::):\n").append(RootUtil.repairConfig()).append("\n");
            t.append("0d) interruptor permisos-auto (sincrono):\n").append(RootUtil.applyPermSync(autoPerm())).append("\n");
            t.append("0b) permiso externo Termux:\n").append(RootUtil.ensureTermuxSetup()).append("\n");
            t.append("1/4 FGS por root...\n").append(RootUtil.startViaSuForeground()).append("\n");
            boolean open = waitForPort(8000);
            if (!open) {
                t.append("2/4 startservice por root...\n").append(RootUtil.startServerViaAm()).append("\n");
                open = waitForPort(6000);
            }
            if (!open) {
                t.append("3/4 servicio directo...\n").append(TermuxRunner.startViaService(this)).append("\n");
                open = waitForPort(6000);
            }
            if (!open) {
                t.append("4/4 demonio directo por root...\n").append(RootUtil.startDaemonDirect()).append("\n");
                open = waitForPort(6000);
            }
            if (!open) {
                t.append("Diagnostico:\n").append(RootUtil.diagnoseEnv()).append("\n");
                t.append("Namespaces:\n").append(RootUtil.namespaceViews()).append("\n");
            }
            boolean done = open;
            boolean httpOk = false;
            if (done) {
                t.append("Puerto abierto, esperando primer HTTP 200 (el servidor frio tarda)...\n");
                httpOk = waitForHttp(30000);
                t.append(httpOk ? "HTTP 200 listo, abriendo UI\n"
                        : "HTTP tardo mas de 30s, no abro solo: pulsa Abrir UI en unos segundos\n");
            }
            final boolean httpOkF = httpOk;
            String transcript = t.toString();
            runOnUiThread(() -> {
                setBusy(false);
                refreshStepStatus();
                serverOpen = done;
                refreshActionButtons();
                setDot(done);
                txtStatus.setText(done ? T("Servidor abierto", "Server open") : T("Servidor sigue cerrado", "Server still closed"));
                log(transcript + (done
                        ? "OK: puerto " + PORT + " abierto"
                        : "FALLO. Sigue la guia de abajo (paso 2: pega el comando UNA vez en Termux),"
                        + " cierra Termux del todo, abrelo, y pulsa Iniciar de nuevo."));
                if (done) {
                    getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("onboarding_done", true).apply();
                    getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("wizard_done", true).putBoolean("setup_ok", true).apply();
                    applyOnboarding();
                } else {
                    refreshGuide();
                }
                snack(done ? T("Servidor abierto", "Server open") : T("No arranco: mira el registro", "Did not start: check the log"));
                if (done) {
                    if (!shareBlocksAutoOpen()) {
                        if (httpOkF) {
                            enterChatLocked(() -> {
                                webLoad(URL);
                                setFullscreen(true);
                            });
                            new Thread(() -> {
                                String w = useRoot() ? RootUtil.fireTermuxCmd(RootUtil.WAKE_ON)
                                        : TermuxRunner.wakeViaService(this, true);
                                String first = w.split("\n")[0];
                                runOnUiThread(() -> log("wake-lock: " + first + " (requiere app Termux:API)"));
                            }).start();
                        } else {
                            snack(T("Servidor aun arrancando, pulsa Abrir UI en unos segundos", "Server still starting, press Open UI in a few seconds"));
                        }
                    }
                }
            });
        }).start();
    }

    private PendingShare parseShareIntent(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return null;
        PendingShare share = new PendingShare();
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) share.text = text.toString();
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject != null) share.subject = subject;
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Uri uri : streams) addShareUri(share, uri);
            }
        } else {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) addShareUri(share, (Uri) stream);
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) addShareUri(share, clip.getItemAt(i).getUri());
        }
        clearShareIntent(intent);
        if ((share.text == null || share.text.trim().isEmpty()) && share.uris.isEmpty()) return null;
        return share;
    }

    private void clearShareIntent(Intent intent) {
        intent.removeExtra(Intent.EXTRA_TEXT);
        intent.removeExtra(Intent.EXTRA_STREAM);
        intent.setClipData(null);
    }

    private void addShareUri(PendingShare share, Uri uri) {
        if (uri == null || share.uris.size() >= MAX_SHARE_FILES) return;
        String key = uri.toString();
        for (Uri existing : share.uris) {
            if (existing.toString().equals(key)) return;
        }
        share.uris.add(uri);
    }

    private void showShareDialog() {
        PendingShare share = pendingShare;
        if (share == null) return;
        hideSplash();
        if (shareDialog != null && shareDialog.isShowing()) shareDialog.dismiss();
        shareFlowActive = true;
        shareTargetSessionId = null;
        shareTargetSessionTitle = null;
        shareSessions.clear();
        shareSessionsLoading = true;
        shareSessionsGeneration++;
        final int generation = shareSessionsGeneration;

        View content = getLayoutInflater().inflate(R.layout.share_sheet, null);
        ((TextView) content.findViewById(R.id.shareTitle))
                .setText(T("Compartir con opencode", "Share with opencode"));
        ((TextView) content.findViewById(R.id.shareSubtitle))
                .setText(T("Elige dónde enviarlo", "Choose where to send it"));
        ((TextView) content.findViewById(R.id.shareSessionLabel))
                .setText(T("Enviar a", "Send to"));
        ((TextView) content.findViewById(R.id.sharePreview))
                .setText(sharePreview(share));
        shareSessionNameView = content.findViewById(R.id.shareSessionName);
        shareSessionSubView = content.findViewById(R.id.shareSessionSub);
        shareSessionNameView.setText(T("Cargando sesiones…", "Loading sessions…"));
        shareSessionSubView.setText(T("Comprobando servidor", "Checking server"));
        shareInstructionView = content.findViewById(R.id.shareInstruction);
        shareInstructionView.setHint(T("Instrucción opcional", "Optional instruction"));
        shareSendButton = content.findViewById(R.id.shareSend);
        shareSendButton.setText(T("Enviar", "Send"));
        ((MaterialButton) content.findViewById(R.id.shareCancel))
                .setText(T("Cancelar", "Cancel"));
        ((TextView) content.findViewById(R.id.shareFootnote))
                .setText(T("Los archivos se copian a Descargas/opencode-shares",
                        "Files are copied to Downloads/opencode-shares"));

        content.findViewById(R.id.shareSessionRow)
                .setOnClickListener(v -> showShareSessionPicker());
        shareSendButton.setOnClickListener(v -> {
            String instruction = shareInstructionView.getText().toString();
            String target = shareTargetSessionId;
            pendingShare = null;
            shareFlowActive = false;
            BottomSheetDialog dialog = shareDialog;
            if (dialog != null) dialog.dismiss();
            startShareSend(share, instruction, target);
        });
        ((MaterialButton) content.findViewById(R.id.shareCancel)).setOnClickListener(v -> {
            pendingShare = null;
            shareFlowActive = false;
            if (shareDialog != null) shareDialog.dismiss();
        });

        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        shareDialog = dialog;
        dialog.setContentView(content);
        styleShareSheet(dialog);
        dialog.setOnDismissListener(d -> {
            if (shareDialog != dialog) return;
            shareSessionsGeneration++;
            shareFlowActive = false;
            pendingShare = null;
            shareDialog = null;
        });
        dialog.show();
        loadShareSessions(generation);
    }

    private void styleShareSheet(BottomSheetDialog dialog) {
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            sheet.setBackgroundColor(Color.TRANSPARENT);
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            sheet.setLayoutParams(lp);
        });
    }

    private void showShareSessionPicker() {
        if (shareDialog == null || !shareDialog.isShowing()) return;
        if (shareSessionPicker != null && shareSessionPicker.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.share_session_picker, null);
        ((TextView) content.findViewById(R.id.sharePickerTitle))
                .setText(T("Enviar a", "Send to"));
        ((TextView) content.findViewById(R.id.sharePickerSub))
                .setText(T("Elige una conversación nueva o existente",
                        "Choose a new or existing conversation"));
        LinearLayout list = content.findViewById(R.id.sharePickerList);
        addShareSessionOption(list, null,
                T("Nueva sesión", "New session"),
                T("Crear una conversación nueva", "Create a new conversation"));
        if (shareSessionsLoading) {
            addShareSessionInfo(list, T("Cargando sesiones…", "Loading sessions…"));
        } else if (shareSessions.isEmpty()) {
            addShareSessionInfo(list, T("No hay sesiones todavía", "No sessions yet"));
        } else {
            for (ShareSession session : shareSessions) {
                String title = session.title == null || session.title.trim().isEmpty()
                        ? T("Sesión sin título", "Untitled session") : session.title.trim();
                addShareSessionOption(list, session.id, title,
                        T("Sesión existente", "Existing session")
                                + (session.updated > 0
                                ? " · " + shareRelativeTime(session.updated) : ""));
            }
        }
        MaterialButton cancel = content.findViewById(R.id.sharePickerCancel);
        cancel.setText(T("Cancelar", "Cancel"));
        final BottomSheetDialog picker = new BottomSheetDialog(this);
        picker.setContentView(content);
        styleShareSheet(picker);
        picker.setOnDismissListener(d -> {
            if (shareSessionPicker == picker) shareSessionPicker = null;
        });
        cancel.setOnClickListener(v -> picker.dismiss());
        shareSessionPicker = picker;
        picker.show();
    }

    private void addShareSessionInfo(LinearLayout list, String text) {
        TextView info = new TextView(this);
        info.setText(text);
        info.setTextSize(13);
        info.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF9E9E9E));
        info.setPadding(dp(4), dp(12), dp(4), dp(12));
        list.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addShareSessionOption(LinearLayout list, String id, String title,
            String subtitle) {
        boolean selected = id == null
                ? shareTargetSessionId == null
                : id.equals(shareTargetSessionId);
        int surface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant, 0xFF1B1820);
        int outline = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutlineVariant, 0xFF49454F);
        int primary = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, 0xFFD0BCFF);
        int onSurface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface, 0xFFE6E1E5);
        int onSurfaceVariant = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCAC4D0);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(surface);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setStrokeColor(selected ? primary : outline);
        card.setStrokeWidth(dp(selected ? 2 : 1));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        ImageView icon = new ImageView(this);
        icon.setBackgroundResource(R.drawable.bg_share_icon);
        icon.setImageResource(id == null ? R.drawable.ic_add : R.drawable.ic_chat);
        icon.setColorFilter(primary);
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(14.5f);
        name.setTextColor(onSurface);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView sub = new TextView(this);
        sub.setText(subtitle);
        sub.setTextSize(12);
        sub.setTextColor(onSurfaceVariant);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(primary);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));

        card.addView(row);
        card.setOnClickListener(v -> {
            shareTargetSessionId = id;
            shareTargetSessionTitle = id == null ? null : title;
            updateShareSessionRow();
            if (shareSessionPicker != null) shareSessionPicker.dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        list.addView(card, lp);
    }

    private void updateShareSessionRow() {
        if (shareSessionNameView == null || shareSessionSubView == null) return;
        if (shareTargetSessionId == null) {
            shareSessionNameView.setText(T("Nueva sesión", "New session"));
            shareSessionSubView.setText(T("Se creará al enviar", "Created when you send"));
        } else {
            shareSessionNameView.setText(shareTargetSessionTitle == null
                    ? T("Sesión seleccionada", "Selected session") : shareTargetSessionTitle);
            shareSessionSubView.setText(T("Sesión existente", "Existing session"));
        }
    }

    private void setShareSessionStatus(String name, String sub) {
        runOnUiThread(() -> {
            if (shareTargetSessionId != null) return;
            if (shareSessionNameView == null || shareSessionSubView == null) return;
            shareSessionNameView.setText(name);
            shareSessionSubView.setText(sub);
        });
    }

    private void loadShareSessions(int generation) {
        new Thread(() -> {
            boolean ready = ensureShareServerReady(90000);
            if (generation != shareSessionsGeneration) return;
            if (!ready) {
                runOnUiThread(() -> {
                    if (generation != shareSessionsGeneration) return;
                    shareSessionsLoading = false;
                    if (shareTargetSessionId == null) {
                        shareSessionNameView.setText(T("Nueva sesión", "New session"));
                        shareSessionSubView.setText(T("Servidor no disponible: se creará al enviar",
                                "Server unavailable: will be created on send"));
                    }
                });
                return;
            }
            try {
                List<ShareSession> sessions = fetchShareSessions();
                runOnUiThread(() -> {
                    if (generation != shareSessionsGeneration) return;
                    shareSessions.clear();
                    shareSessions.addAll(sessions);
                    shareSessionsLoading = false;
                    if (shareTargetSessionId == null) {
                        updateShareSessionRow();
                    }
                    if (shareSessionPicker != null && shareSessionPicker.isShowing()) {
                        shareSessionPicker.dismiss();
                        showShareSessionPicker();
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    if (generation != shareSessionsGeneration) return;
                    shareSessionsLoading = false;
                    if (shareTargetSessionId == null) {
                        shareSessionNameView.setText(T("Nueva sesión", "New session"));
                        shareSessionSubView.setText(T("No se pudieron cargar las sesiones",
                                "Could not load sessions"));
                    }
                    log(T("Share sesiones: ", "Share sessions: ") + msg);
                });
            }
        }).start();
    }

    private boolean shareBlocksAutoOpen() {
        return shareOpening || shareFlowActive || (shareDialog != null && shareDialog.isShowing());
    }

    private boolean ensureShareServerReady(long timeoutMs) {
        if (waitForHttp(1500)) return true;
        synchronized (shareServerLock) {
            if (!shareServerStarting) {
                if (!isPortOpen()) {
                    shareServerStarting = true;
                    shareServerStartFailed = false;
                    for (int i = 0; i < 24 && !rootChecked; i++) {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            shareServerStarting = false;
                            return false;
                        }
                    }
                    runOnUiThread(() -> {
                        if (btnStart != null && btnStart.isEnabled()) {
                            setShareSessionStatus(T("Arrancando servidor…", "Starting server…"),
                                    T("Puede tardar unos segundos", "This may take a few seconds"));
                            startServerSequence();
                        }
                    });
                }
            }
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (waitForHttp(1000)) {
                synchronized (shareServerLock) {
                    shareServerStarting = false;
                }
                return true;
            }
            if (shareServerStartFailed) {
                synchronized (shareServerLock) {
                    shareServerStarting = false;
                }
                return false;
            }
        }
        synchronized (shareServerLock) {
            shareServerStarting = false;
        }
        return false;
    }

    private List<ShareSession> fetchShareSessions() throws Exception {
        JSONArray array = new JSONArray(httpGetText("/session"));
        List<ShareSession> sessions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            if (obj.has("parentID") && !obj.isNull("parentID")
                    && !obj.optString("parentID", "").isEmpty()) continue;
            if (obj.has("parentId") && !obj.isNull("parentId")
                    && !obj.optString("parentId", "").isEmpty()) continue;
            String id = obj.optString("id", "");
            if (id.isEmpty()) continue;
            long updated = 0;
            JSONObject time = obj.optJSONObject("time");
            if (time != null) updated = time.optLong("updated", time.optLong("created", 0));
            sessions.add(new ShareSession(id, obj.optString("title", ""), updated));
        }
        sessions.sort((a, b) -> {
            int byTime = Long.compare(b.updated, a.updated);
            return byTime != 0 ? byTime : a.title.compareToIgnoreCase(b.title);
        });
        if (sessions.size() > 20) return new ArrayList<>(sessions.subList(0, 20));
        return sessions;
    }

    private String shareRelativeTime(long millis) {
        long diff = System.currentTimeMillis() - millis;
        if (diff < 0) diff = 0;
        if (diff < 60_000L) return T("ahora", "now");
        if (diff < 3_600_000L) return (diff / 60_000L) + T(" min", " min");
        if (diff < 86_400_000L) return (diff / 3_600_000L) + T(" h", " h");
        if (diff < 604_800_000L) return (diff / 86_400_000L) + T(" d", " d");
        return android.text.format.DateFormat.getDateFormat(this)
                .format(new java.util.Date(millis));
    }

    private String sharePreview(PendingShare share) {
        StringBuilder sb = new StringBuilder();
        if (share.subject != null && !share.subject.trim().isEmpty()) {
            sb.append(share.subject.trim()).append("\n\n");
        }
        if (share.text != null && !share.text.trim().isEmpty()) {
            String text = share.text.trim();
            if (text.length() > 1200) text = text.substring(0, 1200) + "...";
            sb.append(text);
        }
        if (!share.uris.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(T("Archivos: ", "Files: ")).append(share.uris.size());
            for (int i = 0; i < share.uris.size() && i < 3; i++) {
                sb.append("\n- ").append(displayName(share.uris.get(i)));
            }
            if (share.uris.size() > 3) sb.append("\n- ...");
        }
        return sb.toString();
    }

    private void startShareSend(PendingShare share, String instruction, String targetSessionId) {
        pendingShare = null;
        if (!share.uris.isEmpty() && !hasAllFilesAccess()) {
            snack(T("Activa 'Todos los archivos' para compartir archivos",
                    "Enable 'All files' to share files"));
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                log("Abre manual: Ajustes > Apps > OpenCode Android > Todos los archivos.");
            }
            return;
        }
        enterChatLocked(() -> {
            shareOpening = true;
            setBusy(true);
            log(T("Compartido: preparando envio...", "Share: preparing..."));
            new Thread(() -> {
                try {
                    List<File> files = copyShareFiles(share);
                    if (!ensureShareServerReady(90000)) {
                        throw new Exception(T("el servidor no arranco", "the server did not start"));
                    }
                    String sid = targetSessionId;
                    if (sid == null || sid.isEmpty()) {
                        sid = createShareSession(share);
                    }
                    final String sessionId = sid;
                    String sessionUrl = shareSessionUrl(sessionId);
                    String message = buildShareMessage(share, files, instruction);
                    runOnUiThread(() -> {
                        setBusy(false);
                        setFullscreen(true);
                        webLoad(sessionUrl);
                    });
                    sendSharePrompt(sessionId, message);
                    runOnUiThread(() -> snack(T("Compartido con opencode", "Shared with opencode")));
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    runOnUiThread(() -> {
                        setBusy(false);
                        log(T("Share fallo: ", "Share failed: ") + msg);
                        snack(T("No se pudo compartir: ", "Could not share: ") + msg);
                    });
                } finally {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> shareOpening = false, 8000);
                }
            }).start();
        });
    }

    private List<File> copyShareFiles(PendingShare share) throws Exception {
        List<File> out = new ArrayList<>();
        if (share.uris.isEmpty()) return out;
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "opencode-shares");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception(T("no pude crear la carpeta de archivos", "could not create the files folder"));
        }
        cleanupOldShares(dir);
        long total = 0;
        for (int index = 0; index < share.uris.size(); index++) {
            Uri uri = share.uris.get(index);
            File dest = new File(dir, sanitizeShareName(displayName(uri), uri, index));
            long copied = 0;
            try (InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream os = new FileOutputStream(dest)) {
                if (in == null) throw new Exception(T("no pude leer el archivo", "could not read the file"));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    copied += n;
                    total += n;
                    if (copied > MAX_SHARE_BYTES) {
                        throw new Exception(T("archivo demasiado grande", "file too large"));
                    }
                    if (total > MAX_SHARE_TOTAL_BYTES) {
                        throw new Exception(T("archivos demasiado grandes", "files too large"));
                    }
                    os.write(buf, 0, n);
                }
            } catch (Exception e) {
                if (dest.exists()) dest.delete();
                throw e;
            }
            out.add(dest);
        }
        return out;
    }

    private String displayName(Uri uri) {
        if (uri == null) return "shared";
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "shared" : last;
    }

    private String sanitizeShareName(String raw, Uri uri, int index) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) name = "shared";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot) : "";
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.isEmpty()) base = "shared";
        if (base.length() > 60) base = base.substring(0, 60);
        if (ext.length() > 16) ext = "";
        if (ext.isEmpty()) {
            String mime = getContentResolver().getType(uri);
            if (mime != null) {
                String guessed = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (guessed != null) ext = "." + guessed;
            }
        }
        return index + "-" + base + "-" + System.currentTimeMillis() + ext;
    }

    private void cleanupOldShares(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
        }
    }

    private String buildShareMessage(PendingShare share, List<File> files, String instruction) {
        StringBuilder sb = new StringBuilder();
        if (share.subject != null && !share.subject.trim().isEmpty()) {
            sb.append(share.subject.trim()).append("\n\n");
        }
        if (share.text != null && !share.text.trim().isEmpty()) {
            sb.append(share.text.trim()).append("\n\n");
        }
        if (!files.isEmpty()) {
            sb.append(T("Archivos compartidos:", "Shared files:")).append("\n");
            for (File file : files) sb.append("- ").append(file.getAbsolutePath()).append("\n");
            sb.append("\n");
        }
        String extra = instruction == null ? "" : instruction.trim();
        if (!extra.isEmpty()) sb.append(extra);
        else sb.append(T("Revisa lo que te comparto.", "Take a look at what I'm sharing."));
        return sb.toString().trim();
    }

    private String shareTitle(PendingShare share) {
        String title = share.subject;
        if (title == null || title.trim().isEmpty()) {
            if (share.text != null) {
                title = share.text.trim();
                int nl = title.indexOf('\n');
                if (nl >= 0) title = title.substring(0, nl);
            }
        }
        if ((title == null || title.trim().isEmpty()) && !share.uris.isEmpty()) {
            title = displayName(share.uris.get(0));
        }
        if (title == null || title.trim().isEmpty()) {
            title = T("Compartido desde Android", "Shared from Android");
        }
        title = title.trim();
        return title.length() > 80 ? title.substring(0, 80) : title;
    }

    private String createShareSession(PendingShare share) throws Exception {
        JSONObject body = new JSONObject();
        body.put("title", shareTitle(share));
        JSONObject session = httpJson("POST", "/session", body, 20000);
        String id = session.optString("id", "");
        if (id.isEmpty()) throw new Exception(T("respuesta sin sesion", "response without a session"));
        return id;
    }

    private void sendSharePrompt(String sessionId, String text) throws Exception {
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("type", "text").put("text", text));
        JSONObject body = new JSONObject().put("parts", parts);
        String path = "/session/" + Uri.encode(sessionId);
        try {
            httpJson("POST", path + "/prompt_async", body, 30000);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.contains("404")) throw e;
            httpJson("POST", path + "/message", body, 600000);
        }
    }

    private JSONObject httpJson(String method, String path, JSONObject body, int readTimeout)
            throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(URL + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "opencode-android");
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + text);
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String httpGetText(String path) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(URL + path).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "opencode-android");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " " + text);
            return text;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String shareSessionUrl(String sessionId) {
        String key = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(URL.getBytes(StandardCharsets.UTF_8));
        return URL + "/server/" + key + "/session/" + Uri.encode(sessionId);
    }

    private static class PendingShare {
        String text;
        String subject;
        final List<Uri> uris = new ArrayList<>();
    }

    private static class ShareSession {
        final String id;
        final String title;
        final long updated;

        ShareSession(String id, String title, long updated) {
            this.id = id;
            this.title = title;
            this.updated = updated;
        }
    }

    /** Puente: la web cree que crea Notification; en realidad avisa a Android. */
    private class NotifBridge {
        @JavascriptInterface
        public void post(String title, String body) {
            final String t = (title == null || title.isEmpty()) ? "opencode" : title;
            final String b = body == null ? "" : body;
            runOnUiThread(() -> notifyAgentDone(t, b));
        }
    }

    /** Instala el puente en la pagina (la SPA lo conserva; se repone en cada carga). */
    private void bridgeNotifications() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                            + "if(window.__ocNotifBridged)return 'ya';"
                            + "window.__ocNotifBridged=true;"
                            + "function N(t,o){o=o||{};"
                            + "try{ocNotify.post(String(t),String(o.body||''));}catch(e){}"
                            + "this.title=String(t);this.body=String(o.body||'');"
                            + "this.onclick=null;this.close=function(){};}"
                            + "N.permission='granted';"
                            + "N.requestPermission=function(){return Promise.resolve('granted');};"
                            + "window.Notification=N;return 'puente-ok';}"
                            + "catch(e){return 'ERR:'+e;}})()",
                    v -> Log.d("OpenCode", "notif-bridge: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "notif-bridge fallo: " + e.getMessage());
        }
    }

    private void ensureAgentChannel() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    "oc-agent", "Agente",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT));
        } catch (Exception ignored) {
        }
    }

    /** Aviso de agente: solo si NO estas mirando el chat (si no, ya lo ves). */
    private void notifyAgentDone(String title, String body) {
        try {
            if (appForeground && fullscreen) return;
            ensureAgentChannel();
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = new android.app.Notification.Builder(this, "oc-agent")
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(R.drawable.ic_logo)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(agentNotifId++, n);
            Log.d("OpenCode", "notif agente: " + title);
        } catch (Exception e) {
            Log.d("OpenCode", "notif fallo: " + e.getMessage());
        }
    }

    /**
     * Ajustes/Ayuda van enterrados al fondo de la lista: los MUEVE a una barra
     * fija al final del scroll (siempre visibles) con texto agrandado/avivado.
     * La barra vive dentro del sidebar: si cambia de ruta, muere con el.
     */
    private int footTries = 0;

    private void boostSidebarFooter() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "var old=document.getElementById('oc-footbar');"
                    + "var labels=['Ajustes','Settings','Ayuda','Help'];"
                    + "function isLabel(e){if(!e)return false;"
                    + "var t=(e.textContent||'').trim();return labels.indexOf(t)>=0;}"
                    + "var holders=[];"
                    + "Array.prototype.forEach.call(document.querySelectorAll('*'),function(e){"
                    + "if(holders.length<4&&isLabel(e))holders.push(e);});"
                    + "if(!holders.length){if(old)old.remove();return 'none';}"
                    + "function hideDupes(){"
                    + "Array.prototype.forEach.call(document.querySelectorAll('*'),function(e){"
                    + "if(e.closest&&e.closest('#oc-footbar'))return;"
                    + "var t=(e.textContent||'').trim();"
                    + "if(labels.indexOf(t)>=0){"
                    + "var r=e.closest?(e.closest('button')||e.closest('a')||e.closest('[role=\"button\"]')):null;"
                    + "(r||e).style.setProperty('display','none','important');}});}"
                    + "if(old){hideDupes();return 'done';}"
                    + "var rows=[];"
                    + "holders.forEach(function(h){"
                    + "var r=h.closest? (h.closest('button')||h.closest('a')||h.closest('[role=\"button\"]')):null;"
                    + "if(!r)r=h;"
                    + "if(rows.indexOf(r)<0)rows.push(r);});"
                    + "if(rows.length<1)return 'none';"
                    + "var sc=rows[0];while(sc&&sc!==document.body){"
                    + "if(sc.scrollHeight>sc.clientHeight+50)break;sc=sc.parentElement;}"
                    + "if(!sc||sc===document.body)return 'noscroll';"
                    + "var bg=getComputedStyle(sc).backgroundColor;"
                    + "var bar=document.createElement('div');bar.id='oc-footbar';"
                    + "bar.style.cssText='position:sticky;bottom:0;z-index:5;display:flex;gap:8px;"
                    + "padding:10px 12px;background:'+bg+';';"
                    + "rows.forEach(function(r){"
                    + "r.style.removeProperty('display');"
                    + "r.style.setProperty('flex','1','important');"
                    + "r.style.setProperty('justify-content','center','important');"
                    + "r.style.setProperty('color','#ffffff','important');"
                    + "var all=r.querySelectorAll('*');"
                    + "Array.prototype.forEach.call(all,function(x){var t=(x.textContent||'').trim();"
                    + "if(labels.indexOf(t)>=0){x.style.setProperty('font-size','17px','important');"
                    + "x.style.setProperty('font-weight','600','important');"
                    + "x.style.setProperty('color','#ffffff','important');}});"
                    + "bar.appendChild(r);});"
                    + "sc.appendChild(bar);"
                    + "try{var bh=bar.offsetHeight||64;"
                    + "sc.style.setProperty('padding-bottom',(bh+8)+'px','important');}"
                    + "catch(e2){}"
                    + "hideDupes();"
                    + "return rows.length+'barok';"
                    + "}catch(err){return 'FERR';}})()",
                    v -> {
                        Log.d("OpenCode", "foot-fix: " + v);
                        // La lista hidrata tarde: reintentar acotado si aun no existe.
                        if (v != null && (v.contains("none") || v.contains("FERR")) && footTries < 4) {
                            footTries++;
                            new Handler(Looper.getMainLooper())
                                    .postDelayed(() -> boostSidebarFooter(), 3000);
                        } else if (v != null && (v.contains("ok") || v.contains("done"))) {
                            footTries = 0;
                        }
                    });
        } catch (Exception e) {
            Log.d("OpenCode", "foot-fix fallo: " + e.getMessage());
        }
    }
    private String basicAuth() {
        String raw = "opencode:" + LocalRuntime.serverPassword(this);
        return "Basic " + Base64.getEncoder().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Carga con Basic auth para el servidor local (la UI v2 la exige). */
    private void webLoad(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth());
        web.loadUrl(url, headers);
    }

    /** Una sola carga de la URL (evita recargas dobles que se ven como saltos). */
    private synchronized void loadChatUrl() {
        if (!urlLoading && web.getUrl() == null) {
            urlLoading = true;
            webLoad(URL);
        }
    }

    /** Generacion de la peticion de entrar: una nueva anula la espera anterior. */
    private int openGen = 0;
    /** Dialogos que se reabren al refrescar: se cierran antes para no apilarse
     *  (al Cerrar salian los viejos con los borrados). */
    private androidx.appcompat.app.AlertDialog usageDlg;
    private androidx.appcompat.app.AlertDialog logDlg;

    /** Chat-first: al abrir la app, si el servidor ya responde, directo al chat sin toques.
     *  Si esta caido, lo arranca solo y la secuencia abre el chat al acabar. */
    private void autoOpenIfRunning() {
        if (LocalRuntime.available(this)) {
            // Runtime local: arranca solo y no se repite si ya esta arrancando.
            if (serverOpen || starting) return;
            starting = true;
            new Handler(Looper.getMainLooper()).post(() -> {
                setSplashStatus(T("Arrancando servidor...", "Starting server..."));
                startServerSequence();
            });
            return;
        }
        setSplashStatus(T("Comprobando servidor...", "Checking server..."));
        new Thread(() -> {
            if (waitForHttp(15000)) {
                runOnUiThread(() -> {
                    setSplashStatus(T("Abriendo el chat...", "Opening the chat..."));
                    // Si responde, el setup esta probado: asistente completado.
                    markWizardDone();
                    enterChatLocked(() -> {
                        loadChatUrl();
                        setFullscreen(true);
                    });
                });
            } else if (!isPortOpen()) {
                runOnUiThread(() -> {
                    setSplashStatus(T("Arrancando servidor...", "Starting server..."));
                    if (btnStart.isEnabled()) btnStart.performClick();
                });
            } else {
                setSplashStatus(T("Preparando controles...", "Preparing controls..."));
            }
        }).start();
    }

    /** Interruptor de bloqueo (Opciones, apagado por defecto). */
    private boolean lockOn() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("app_lock", false);
    }

    /** Puerta del chat: con bloqueo activo pide el desbloqueo del movil una vez;
     *  sin bloqueo (o ya pasado) sigue directo. */
    private void enterChatLocked(Runnable after) {
        if (!lockOn() || unlockedRun) {
            after.run();
            return;
        }
        android.app.KeyguardManager km = (android.app.KeyguardManager)
                getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            snack(T("Pon bloqueo al móvil primero (Ajustes > Seguridad)",
                    "Set a screen lock first (Settings > Security)"));
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception e) {
                log("Abre manual: Ajustes > Seguridad > Bloqueo de pantalla.");
            }
            return;
        }
        try {
            Intent auth = km.createConfirmDeviceCredentialIntent(
                    T("Desbloquea para entrar al chat", "Unlock to open the chat"), "");
            if (auth == null) {
                after.run();
                return;
            }
            pendingUnlock = after;
            lockLauncher.launch(auth);
        } catch (Exception e) {
            log("Bloqueo no disponible, entro igual: " + e.getMessage());
            after.run();
        }
    }

    /** Entrar al chat esperando respuesta real (nunca pantalla en blanco):
     *  si esta caido lo arranca solo; espera HTTP hasta 60s con cuenta atras;
     *  sin respuesta deja panel + reintento. */
    private void enterChatBlocking() {
        enterChatLocked(this::enterChatBlockingCore);
    }

    private void enterChatBlockingCore() {
        final int gen = ++openGen;
        setBusy(true);
        txtStatus.setText(T("Comprobando servidor…", "Checking server…"));
        new Thread(() -> {
            if (!isPortOpen()) {
                runOnUiThread(() -> {
                    if (gen != openGen) return;
                    if (!btnStart.isEnabled()) {
                        // Boton oscurecido = pasos a medias: no se arranca a
                        // ciegas, la tira de puntos dice que falta.
                        txtStatus.setText(T("Faltan pasos: completa los puntos",
                                "Steps missing: complete the dots"));
                        log("Abrir chat: aun faltan pasos del primer arranque."
                                + " Completa la lista de puntos y pulsa Iniciar.");
                        setBusy(false);
                        return;
                    }
                    txtStatus.setText(T("Servidor parado: arrancando…", "Server stopped: starting…"));
                    log("Abrir chat con servidor parado: arranco solo.");
                    btnStart.performClick();
                    setBusy(false);
                });
                return; // la secuencia de arranque abre el chat sola al acabar
            }
            for (int waited = 0; waited < 60; waited += 5) {
                if (gen != openGen || isFinishing()) return;
                if (waitForHttp(5000)) break;
                final int s = waited + 5;
                runOnUiThread(() -> {
                    if (gen == openGen) txtStatus.setText(
                            T("Esperando respuesta… " + s + "s", "Waiting for answer… " + s + "s"));
                });
            }
            if (gen != openGen || isFinishing()) return;
            final boolean ready = waitForHttp(3000);
            runOnUiThread(() -> {
                setBusy(false);
                if (gen != openGen) return;
                if (ready) {
                    loadChatUrl();
                    web.onResume();
                    web.resumeTimers();
                    setFullscreen(true);
                } else if (isPortOpen()) {
                    // Puerto abierto pero sin HTTP en 60s: servidor colgado
                    // (escucha pero no responde). Reinicio solo, sin pedir nada.
                    txtStatus.setText(T("Servidor colgado: reiniciando solo…",
                            "Hung server: restarting alone…"));
                    log("Puerto abierto sin HTTP 200 en 60s: servidor colgado,"
                            + " reinicio solo (equivale a Reiniciar).");
                    snack(T("Servidor colgado, reiniciando…", "Hung server, restarting…"));
                    findViewById(R.id.btnRestart).performClick();
                } else {
                    txtStatus.setText(T("Sin respuesta: pulsa Abrir chat para reintentar",
                            "No answer: press Open chat to retry"));
                    log("Chat sin HTTP 200 en 60s: no abro (evita blanco)."
                            + " Pulsa Abrir chat para reintentar o Reiniciar servidor.");
                    snack(T("Sin respuesta del servidor", "No answer from server"));
                }
            });
        }).start();
    }

    /** La tarjeta abre el chat si hay servidor (sustituye al boton Abrir UI). */
    private void openChatIfRunning() {
        enterChatBlocking();
    }

    private boolean isOnboarded() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("onboarding_done", false);
    }

    private void applyOnboarding() {
        boolean onb = isOnboarded() || LocalRuntime.available(this);
        View guide = findViewById(R.id.cardGuide);
        if (guide != null) guide.setVisibility(onb ? View.GONE : View.VISIBLE);
        // Tira de estados de los 5 pasos: solo mientras no este todo hecho.
        View strip = findViewById(R.id.stepStatus);
        if (strip != null) strip.setVisibility(onb ? View.GONE : View.VISIBLE);
        // Botones sueltos antiguos: la guia los sustituye (quedan ocultos siempre).
        findViewById(R.id.lblSteps).setVisibility(View.GONE);
        findViewById(R.id.btnTermux).setVisibility(View.GONE);
        findViewById(R.id.btnSetup).setVisibility(View.GONE);
        findViewById(R.id.btnStabilize).setVisibility(View.GONE);
        applyRootMode();
        if (!onb) refreshGuide();
    }

    /** Guia paso a paso: detecta en que paso vas y te pone el siguiente delante. */
    private void refreshGuide() {
        if (isOnboarded() || LocalRuntime.available(this)) {
            runOnUiThread(this::applyOnboarding);
            return;
        }
        new Thread(() -> {
            int step;
            if (!TermuxRunner.isTermuxInstalled(this)) step = 1;
            else if (serverOpen || isPortOpen()) step = 3;
            else step = 2;
            final int s = step;
            runOnUiThread(() -> showGuideStep(s));
        }).start();
    }

    private void showGuideStep(int s) {
        View card = findViewById(R.id.cardGuide);
        if (card == null || isOnboarded()) {
            applyOnboarding();
            return;
        }
        card.setVisibility(View.VISIBLE);
        TextView title = findViewById(R.id.guideStep);
        TextView desc = findViewById(R.id.guideDesc);
        MaterialButton go = findViewById(R.id.guideGo);
        MaterialButton go2 = findViewById(R.id.guideGo2);
        if (go2 != null) go2.setVisibility(View.GONE);
        TextView dots = findViewById(R.id.guideDots);
        if (dots != null) dots.setText(s == 1 ? "● ○ ○" : s == 2 ? "○ ● ○" : "○ ○ ●");
        View back = findViewById(R.id.guideBack);
        if (back != null) {
            back.setVisibility(s > 1 ? View.VISIBLE : View.GONE);
            if (s > 1) {
                ((MaterialButton) back).setText(T("< Atrás", "< Back"));
                back.setOnClickListener(v -> showGuideStep(s - 1));
            }
        }
        if (s == 1) {
            title.setText(T("PASO 1 de 3 · Instalar Termux", "STEP 1 of 3 · Install Termux"));
            desc.setText(T("Es donde corre el servidor, sin el no hay chat."
                    + "\n\nVa a pasar:\n1. Se descarga el APK para tu chip\n2. Lo instalas con un toque\n3. Vuelves aqui solo",
                    "The server runs inside it, no chat without it."
                    + "\n\nWhat happens:\n1. The APK for your chip downloads\n2. You install it with one tap\n3. You come back here alone"));
            go.setText(T("Descargar Termux", "Download Termux"));
            go.setOnClickListener(v -> showTermuxDownloadGuide());
        } else if (s == 2) {
            title.setText(T("PASO 2 de 3 · Preparar Termux", "STEP 2 of 3 · Set up Termux"));
            desc.setText(T("Abre Termux una vez primero (su instalacion inicial)."
                    + " Un toque lo instala todo, lo veras correr."
                    + "\n\nVa a pasar:\n1. Te pedira los permisos que falten (comandos, mostrar encima, archivos y batería)"
                    + "\n2. Veras [1/3] [2/3] [3/3] en Termux\n3. Cierra Termux del todo, reabrelo y vuelve"
                    + "\nSi no te pide archivos: en Termux escribe termux-setup-storage y acepta.",
                    "Open Termux once first (its first-time setup)."
                    + " One tap installs everything, you will see it run."
                    + "\n\nWhat happens:\n1. It will ask for the missing permissions (commands, draw over apps, files and battery)"
                    + "\n2. You will see [1/3] [2/3] [3/3] in Termux\n3. Fully close Termux, reopen it and come back"
                    + "\nIf it does not ask for files: in Termux type termux-setup-storage and accept."));
            final boolean tried = setupTried();
            go.setText(tried ? T("Iniciar y abrir", "Start and open")
                    : T("Instalar todo en Termux", "Install everything in Termux"));
            go.setOnClickListener(v -> {
                if (tried) {
                    // Setup ya lanzado: el paso 2 queda hecho; ahora toca arrancar.
                    if (btnStart.isEnabled()) {
                        startServerSequence();
                    } else {
                        refreshStepStatus();
                        snack(T("Completa los puntos rojos y pulsa Iniciar",
                                "Complete the red dots and press Start"));
                    }
                    return;
                }
                if (ContextCompat.checkSelfPermission(this, "com.termux.permission.RUN_COMMAND")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    log("Falta el permiso de Android 'Run commands in Termux environment'."
                            + " Te abro la info de la app: entra en Permisos y activalo.");
                    openAppInfo();
                    return;
                }
                if (!TermuxRunner.isOverlayGranted(this)) {
                    log("A Termux le falta 'mostrar sobre otras apps' (lo pide para sesiones visibles)."
                            + " Te abro su ajuste: activalo y vuelve.");
                    requestTermuxOverlay();
                    return;
                }
                if (!hasAllFilesAccess()) {
                    log("A esta app le falta 'todos los archivos' (lo necesita para"
                            + " reparar la config de opencode). Te abro el ajuste: activalo y vuelve.");
                    snack(T("Activa 'todos los archivos' y repite", "Enable 'All files' and retry"));
                    openAllFilesSettings();
                    return;
                }
                if (!batteryExempt()) {
                    log("Termux sin exencion de bateria (Android podria suspenderlo)."
                            + " Te pido el ajuste: acepta y vuelve a pulsar.");
                    requestBatteryExemption();
                    return;
                }
                setBusy(true);
                new Thread(() -> {
                    StringBuilder t = new StringBuilder();
                    if (useRoot()) {
                        String b = RootUtil.bootstrapExternalApps();
                        t.append("Permiso externo (root, automatico):\n").append(b).append("\n");
                        if (b.contains("prop-escrita")) {
                            // Reinicio en frio: rearrancar Termux y esperar a que asiente
                            // antes del setup (si no, la sesion muere al nacer).
                            TermuxRunner.openTermuxApp(this);
                            boolean alive = false;
                            for (int k = 0; k < 20; k++) {
                                if (RootUtil.termuxAlive()) { alive = true; break; }
                                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                            }
                            try { Thread.sleep(3000); } catch (InterruptedException e) { }
                            t.append(alive ? "Termux rearrancado, lanzo setup.\n"
                                    : "Termux no arranco: abrelo una vez y reintenta.\n");
                        }
                    }
                    String r = TermuxRunner.runInTermux(this, SETUP_NOROOT, false, "Setup");
                    if (!r.contains("sin permiso")) {
                        getSharedPreferences("app", MODE_PRIVATE).edit()
                                .putBoolean("setup_tried", true).apply();
                    }
                    final String transcript = t + "$ setup automatico:\n" + r
                            + "\nMira Termux: veras [1/3] [2/3] [3/3].";
                    runOnUiThread(() -> {
                        setBusy(false);
                        log(transcript);
                        snack(r.contains("sin permiso")
                                ? T("Falta el permiso: usa el boton de abajo", "Permission missing: use the button below")
                                : T("Instalando en Termux...", "Installing in Termux..."));
                    });
                }).start();
            });
            if (go2 != null) {
                go2.setVisibility(View.VISIBLE);
                boolean needPerm = ContextCompat.checkSelfPermission(this,
                        "com.termux.permission.RUN_COMMAND")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean needOverlay = !TermuxRunner.isOverlayGranted(this);
                if (needPerm) {
                    go2.setText(T("Activar permiso (info de la app)", "Enable permission (app info)"));
                    go2.setOnClickListener(v -> openAppInfo());
                } else if (needOverlay) {
                    go2.setText(T("Permitir mostrar encima (Termux)", "Allow display over apps (Termux)"));
                    go2.setOnClickListener(v -> requestTermuxOverlay());
                } else {
                    go2.setText(T("¿Sin permiso? Pegar linea manual", "No permission? Paste line manually"));
                    go2.setOnClickListener(v -> copyPermAndOpenTermux());
                }
            }
            // Con root se verifica solo si opencode quedo instalado (fin del misterio).
            if (useRoot()) {
                new Thread(() -> {
                    String chk = RootUtil.checkOpencodeInstalled();
                    runOnUiThread(() -> {
                        TextView d2 = findViewById(R.id.guideDesc);
                        if (d2 != null) {
                            String base = d2.getText().toString();
                            if (!base.contains("Estado:") && !base.contains("Status:")) {
                                d2.setText(base + "\n" + T("Estado: ", "Status: ") + chk.split("\n")[0]
                                        + (chk.contains("opencode-ok")
                                                ? T(" → pulsa Iniciar.", " → press Start.")
                                                : T(" → pulsa el boton grande.\nTe pedira 3 permisos"
                                                  + " (comandos, mostrar encima y archivos): concedelos y vuelve a pulsar."
                                                  + " Mira Termux: veras [1/3] [2/3] [3/3]. Al acabar,"
                                                  + " cierra Termux del todo, reabrelo y vuelve aqui.",
                                                  " → press the big button.\nIt will ask for 3 permissions"
                                                  + " (commands, display over apps and files): grant them and press again."
                                                  + " Watch Termux: you will see [1/3] [2/3] [3/3]. When done,"
                                                  + " fully close Termux, reopen it and come back.")));
                            }
                        }
                        log("Verificacion opencode en Termux:\n" + chk);
                    });
                }).start();
            }
        } else {
            title.setText(T("PASO 3 de 3 · Abrir el chat", "STEP 3 of 3 · Open the chat"));
            desc.setText(T("Servidor detectado. Entra y listo.", "Server detected. Go in, done."));
            go.setText(T("Abrir chat", "Open chat"));
            go.setOnClickListener(v -> enterChatBlocking());
        }
    }

    /** Idioma: automatico (idioma del movil) salvo override manual ES/EN. */
    private boolean useEn() {
        String l = getSharedPreferences("app", MODE_PRIVATE).getString("lang", "auto");
        if ("es".equals(l)) return false;
        if ("en".equals(l)) return true;
        String dl = java.util.Locale.getDefault().getLanguage();
        return dl == null || !dl.startsWith("es");
    }
    private String T(String es, String en) { return useEn() ? en : es; }
    private void toggleLang() {
        boolean nowEn = !useEn();
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("lang", nowEn ? "en" : "es").apply();
        android.widget.TextView l = findViewById(R.id.txtLog);
        String ls = (l == null) ? "" : l.getText().toString();
        snack(nowEn ? "Language: English" : "Idioma: español");
        applyLang();
        // No restaurar el texto inicial en el idioma viejo; lo demas se conserva.
        String es0 = "Listo. Pulsa Iniciar y abrir.", en0 = "Ready. Press Start and open.";
        android.widget.TextView l2 = findViewById(R.id.txtLog);
        if (l2 != null && !ls.isEmpty() && !ls.equals(es0) && !ls.equals(en0)) l2.setText(ls);
        refreshGuide();
        refreshStepStatus();
        checkStatus(false);
    }
    /** Repinta todos los textos visibles de la app en el idioma actual. */
    private void applyLang() {
        com.google.android.material.appbar.MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null && tb.getMenu() != null) {
            if (tb.getMenu().findItem(R.id.action_lang) != null)
                tb.getMenu().findItem(R.id.action_lang).setTitle("ES/EN");
            if (tb.getMenu().findItem(R.id.action_controls) != null)
                tb.getMenu().findItem(R.id.action_controls).setTitle(T("Controles", "Controls"));
        }
        setLangText(R.id.btnStart, T("Iniciar y abrir", "Start and open"));
        setLangText(R.id.btnOpen, T("Abrir chat", "Open chat"));
        setLangText(R.id.btnUpdate, T("Actualizar opencode", "Update opencode"));
        setLangText(R.id.btnAppUpdate, T("Nueva versión de la app", "New app version"));
        setLangText(R.id.btnRestart, T("Reiniciar", "Restart"));
        setLangText(R.id.btnStop, T("Detener", "Stop"));
        setLangText(R.id.btnTermux, T("Abrir Termux y copiar comando manual", "Open Termux and copy manual command"));
        setLangText(R.id.btnSetup, T("Setup Termux: copiar comandos (una vez)", "Termux setup: copy commands (once)"));
        setLangText(R.id.btnStabilize, T("Estabilizar Android (root, una vez)", "Stabilize Android (root, once)"));
        setLangText(R.id.btnFdroid, T("1) Instalar Termux (GitHub)", "1) Install Termux (GitHub)"));
        setLangText(R.id.btnBattery, T("Batería: no suspender a Termux", "Battery: do not suspend Termux"));
        setLangText(R.id.btnCopy, T("Copiar", "Copy"));
        setLangText(R.id.btnClear, T("Limpiar", "Clear"));
        setLangText(R.id.logTitle, T("Registro", "Log"));
        setLangText(R.id.lblSteps, T("Un toque abre el chat: root → servidor → UI", "One tap opens the chat: root → server → UI"));
        setLangText(R.id.txtLog, T("Listo. Pulsa Iniciar y abrir.", "Ready. Press Start and open."));
        setLangText(R.id.txtStatus, T("Servidor ?", "Server ?"));
        refreshUpdateBtn();
    }
    private void setLangText(int id, String s) {
        android.widget.TextView v = findViewById(id);
        if (v != null) v.setText(s);
    }

    /** Root efectivo del movil. */
    private boolean useRoot() {
        return hasRoot;
    }

    /** Sin root: oculta Estabilizar. El boton de descarga lo maneja la guia. */
    private void applyRootMode() {
        if (!useRoot()) findViewById(R.id.btnStabilize).setVisibility(View.GONE);
    }

    private void checkStatus(boolean verbose) {
        setBusy(true);
        new Thread(() -> {
            boolean open = isPortOpen();
            runOnUiThread(() -> {
                setBusy(false);
                setDot(open);
                txtStatus.setText(open ? T("Servidor abierto", "Server open") : T("Servidor cerrado", "Server closed"));
                serverOpen = open;
                refreshActionButtons();
                if (!wizardDone() && open) {
                    // Servidor abierto: si dice su version, el setup esta
                    // probado y el asistente se completa solo (migra
                    // instalaciones que ya funcionaban sin mostrar nada).
                    new Thread(() -> {
                        if (queryHealthVersion() != null) runOnUiThread(this::markWizardDone);
                    }).start();
                }
                if (verbose) {
                    log(open ? "OK: puerto " + PORT + " abierto"
                            : "Puerto " + PORT + " cerrado. Pulsa Iniciar o usa el boton Termux.");
                    snack(open ? T("Servidor abierto", "Server open") : T("Servidor cerrado", "Server closed"));
                }
                // Solo calienta la pagina si responde de verdad (si no, el error
                // dejaba blanco y getUrl ya no nulo impedia reintentar).
                // En runtime local NO se precarga: el arranque local abre el
                // chat a pantalla completa y precargarlo dejaba el panel
                // delante de la web (parpadeo de la pantalla inicial).
                if (open && web.getUrl() == null && !LocalRuntime.available(this)
                        && waitForHttp(6000)) loadChatUrl();
            });
        }).start();
    }

    /** Aviso de update: compara instalada vs ultima de GitHub (cache 24h).
     *  Con runtime empaquetado solo avisa: el binario va dentro del APK. */
    private void checkForUpdate(boolean verbose) {
        final boolean local = LocalRuntime.available(this);
        final String activeVer = LocalRuntime.activeVersion(this);
        final String runtimeKey = local ? "local:" + activeVer : "termux";
        android.content.SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
        long ts = p.getLong("upd_ts", 0);
        boolean fresh = p.getInt("upd_v", 0) == 5
                && !p.getString("upd_installed", "").isEmpty()
                && runtimeKey.equals(p.getString("upd_runtime", ""))
                && System.currentTimeMillis() - ts < 24L * 3600 * 1000;
        if (fresh && !verbose) {
            updatePending = p.getBoolean("upd_pending", false);
            updateInstallable = !local;
            updInstalled = p.getString("upd_installed", "");
            updLatest = p.getString("upd_latest", "");
            refreshUpdateBtn();
            if (updatePending) snack(local
                    ? bundledUpdateText(updInstalled, updLatest)
                    : T("Actualización disponible: ", "Update available: ")
                            + updInstalled + " → " + updLatest);
            maybeNotifyOpencode(updatePending, updInstalled, updLatest);
            return;
        }
        new Thread(() -> {
            if (!local) {
                // La deteccion de root corre en otro hilo al arrancar: esperarla
                // (si no, se eligiria la via sin-root y fallaria en vano).
                long wEnd = System.currentTimeMillis() + 12000;
                while (!rootChecked && System.currentTimeMillis() < wEnd) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            synchronized (updDbg) {
                updDbg.setLength(0);
                if (local) {
                    updDbg.append("local=").append(activeVer);
                } else {
                    updDbg.append("root=").append(useRoot());
                }
                updDbg.append(" arch=").append(hasAllFilesAccess() ? "ok" : "sin-acceso");
            }
            android.util.Log.d("OC-Update", "check hilo: local=" + local
                    + " rootChecked=" + rootChecked
                    + " hasRoot=" + hasRoot + " useRoot=" + useRoot()
                    + " arch=" + hasAllFilesAccess());
            String installed = local ? activeVer : queryInstalledVersion();
            String latest = fetchLatestTag();
            boolean pending = installed != null && latest != null
                    && cmpVer(latest, installed) > 0;
            getSharedPreferences("app", MODE_PRIVATE).edit()
                    .putInt("upd_v", 5)
                    .putLong("upd_ts", System.currentTimeMillis())
                    .putString("upd_runtime", runtimeKey)
                    .putBoolean("upd_pending", pending)
                    .putString("upd_installed", installed != null ? installed : "")
                    .putString("upd_latest", latest != null ? latest : "").apply();
            runOnUiThread(() -> {
                updatePending = pending;
                updateInstallable = !local;
                updInstalled = installed != null ? installed : "";
                updLatest = latest != null ? latest : "";
                refreshUpdateBtn();
                maybeNotifyOpencode(pending, updInstalled, updLatest);
                synchronized (updDbg) {
                    log("Update-check: " + updDbg + " inst=[" + updInstalled
                            + "] latest=[" + updLatest + "] pendiente=" + pending);
                    // Espejo a /sdcard para depurar por adb (mismo patron que update.progress).
                    try {
                        java.io.File d = new java.io.File(
                                android.os.Environment.getExternalStorageDirectory(),
                                ".opencode-android");
                        d.mkdirs();
                        try (java.io.FileWriter w = new java.io.FileWriter(
                                new java.io.File(d, "update-check.log"), false)) {
                            w.write("Update-check: " + updDbg + " inst=[" + updInstalled
                                    + "] latest=[" + updLatest + "] pendiente=" + pending + "\n");
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (verbose) {
                    if (installed == null || latest == null)
                        snack(local
                                ? T("No se pudo comprobar (sin red)",
                                        "Could not check (no network)")
                                : T("No se pudo comprobar (sin red o sin Termux)",
                                        "Could not check (no network or no Termux)"));
                    else if (pending)
                        snack(local
                                ? bundledUpdateText(installed, latest)
                                : T("Actualización disponible: ", "Update available: ")
                                        + installed + " → " + latest);
                    else
                        snack(T("Estás al día (", "Up to date (") + installed + ")");
                } else if (pending) {
                    snack(local
                            ? bundledUpdateText(installed, latest)
                            : T("Actualización disponible: ", "Update available: ")
                                    + installed + " → " + latest);
                }
            });
        }).start();
    }

    private String bundledUpdateText(String installed, String latest) {
        return T("OpenCode ", "OpenCode ") + installed + " → " + latest
                + T(" disponible; toca la notificación para instalarla",
                        " available; tap the notification to install it");
    }

    /** Version instalada: 1) el propio servidor la dice en /global/health
     *  (vale con y sin root, sin Termux); 2) con servidor parado solo root
     *  puede preguntar directo por nsenter. Null = no se pudo (silencio). */
    private String queryInstalledVersion() {
        try {
            String hv = queryHealthVersion();
            if (hv != null) {
                synchronized (updDbg) {
                    updDbg.append(" health=").append(hv);
                }
                return hv;
            }
            if (useRoot()) {
                String out = RootUtil.nsenterTermux(
                        "command -v opencode >/dev/null 2>&1"
                                + " && opencode --version 2>&1 | head -1 || echo no-opencode");
                synchronized (updDbg) {
                    updDbg.append(" nsenter=").append(out == null ? "null"
                            : out.replace("\n", "|").substring(0, Math.min(80, out.replace("\n", "|").length())));
                }
                return parseVer(out);
            }
            synchronized (updDbg) {
                updDbg.append(" servidor-parado-sin-root");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** El servidor dice su version en /global/health: {"healthy":true,"version":"X.Y.Z"}.
     *  HTTP local, sin permisos especiales. Null si esta parado o no responde. */
    private String queryHealthVersion() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + PORT + "/global/health")
                            .openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String v = new JSONObject(sb.toString()).optString("version", "");
            return v.isEmpty() ? null : parseVer(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ultima release de sst/opencode en GitHub (10s; null si no hay red). */
    private String fetchLatestTag() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.github.com/repos/sst/opencode/releases/latest")
                            .openConnection();
            c.setRequestProperty("User-Agent", "opencode-android");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String tag = new JSONObject(sb.toString()).optString("tag_name", "");
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            return parseVer(tag);
        } catch (Exception e) {
            return null;
        }
    }

    /** Primera "X.Y[.Z]" del texto; null si no hay. */
    private String parseVer(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Compara versiones: >0 si a es mayor, 0 iguales, <0 si menor. */
    private int cmpVer(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int y = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    /** Espera hasta el primer HTTP 200 real (evita ERR_EMPTY_RESPONSE en frio). */
    private boolean waitForHttp(int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(HOST, PORT), 1500);
                sock.setSoTimeout(3000);
                OutputStream out = sock.getOutputStream();
                out.write(("GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n"
                        + "Authorization: " + basicAuth() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(sock.getInputStream()));
                String status = r.readLine();
                if (status != null && status.contains("200")) return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private boolean waitForPortGone(int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (!isPortOpen()) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !isPortOpen();
    }

    private boolean waitForPort(int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (isPortOpen()) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return isPortOpen();
    }

    private void copyManualCommand() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("opencode", MANUAL_CMD));
        } catch (Exception ignored) {
        }
    }

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /** Punto de estado: cabecera del panel (verde encendido / rojo apagado). */
    private void setDot(boolean open) {
        int col = android.graphics.Color.parseColor(open ? "#4ADE80" : "#F44336");
        statusDot.setBackgroundTintList(ColorStateList.valueOf(col));
    }

    /** Recoloca el mini-boton donde el usuario lo dejo (con limites de pantalla).
     *  Reintenta si aun no hay medidas; cada mostrar/ocultar del boton
     *  resetea su posicion del layout, asi que se llama tras cada VISIBLE. */
    private void restoreFabPos() {
        float x = getSharedPreferences("app", MODE_PRIVATE).getFloat("fab_x", Float.NaN);
        if (Float.isNaN(x)) return;
        float y = getSharedPreferences("app", MODE_PRIVATE).getFloat("fab_y", 0);
        android.view.View p = (android.view.View) fabControls.getParent();
        if (p.getWidth() == 0 || fabControls.getWidth() == 0) {
            if (fabRestoreTries++ < 20) fabControls.postDelayed(() -> restoreFabPos(), 100);
            return;
        }
        fabRestoreTries = 0;
        fabControls.setX(Math.max(0, Math.min(x, p.getWidth() - fabControls.getWidth())));
        fabControls.setY(Math.max(0, Math.min(y, p.getHeight() - fabControls.getHeight())));
    }

    /** Guarda la posicion actual del mini-boton (seguro ante cierre). */
    private void saveFabPos() {
        if (fabControls == null || fabControls.getWidth() == 0) return;
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putFloat("fab_x", fabControls.getX())
                .putFloat("fab_y", fabControls.getY()).apply();
    }

    /** La pausa manual del vigía se levanta al arrancar (si falla el arranque,
     *  el vigía en modo rearrancar puede intentarlo luego). */
    private void clearWatchHold() {
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putBoolean(ServerWatch.PREF_WD_HOLD, false).apply();
    }

    /** Vigía: permiso de avisos (Android 13+) antes de activarlo. */
    private void ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            } catch (Exception e) {
                log("Avisos: concede notificaciones a mano o el vigía no podrá avisar.");
            }
        }
    }

    /**
     * Menu del boton flotante: estado (punto + texto) y acciones,
     * sin salir del chat.
     */
    private void showControlsMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, serverOpen ? T("Servidor abierto", "Server open") : T("Servidor detenido", "Server stopped"))
                .setIcon(serverOpen ? R.drawable.dot_green : R.drawable.dot_red);
        menu.getMenu().add(0, 1, 1, T("Registro", "Log")).setIcon(android.R.drawable.ic_menu_info_details);
        menu.getMenu().add(0, 2, 2, T("Panel de controles", "Controls panel")).setIcon(android.R.drawable.ic_menu_manage);
        menu.getMenu().add(0, 3, 3, T("Reiniciar servidor", "Restart server")).setIcon(android.R.drawable.ic_popup_sync);
        menu.getMenu().add(0, 4, 4, T("Detener servidor", "Stop server")).setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        if (updatePending && updateInstallable)
            menu.getMenu().add(0, 5, 5, T("Actualizar opencode ", "Update opencode ")
                    + updInstalled + " → " + updLatest).setIcon(android.R.drawable.ic_menu_upload);
        if (appUpdatePending)
            menu.getMenu().add(0, 10, 5, T("Nueva versión de la app ", "New app version ")
                    + appUpdTag + T(" — Descargar", " — Download"))
                    .setIcon(android.R.drawable.stat_sys_download);
        else
            menu.getMenu().add(0, 11, 5, T("Buscar actualización de la app", "Check app update"))
                    .setIcon(android.R.drawable.ic_popup_sync);
        menu.getMenu().add(0, 6, 6, autoPerm()
                ? T("Permisos auto: SÍ ✓", "Auto-approve: ON ✓")
                : T("Permisos auto: NO", "Auto-approve: OFF")).setIcon(android.R.drawable.ic_menu_manage);
        menu.getMenu().add(0, 7, 7, T("Opciones", "Options")).setIcon(android.R.drawable.ic_menu_preferences);
        menu.getMenu().add(0, 8, 8, T("Consumo", "Usage")).setIcon(android.R.drawable.ic_menu_info_details);
        menu.getMenu().add(0, 9, 9, T("Reportar problema", "Report problem")).setIcon(android.R.drawable.ic_menu_send);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 0 || id == 2) {
                setFullscreen(false);
                return true;
            }
            if (id == 1) {
                showLogDialog();
                return true;
            }
            if (id == 3) {
                findViewById(R.id.btnRestart).performClick();
                return true;
            }
            if (id == 4) {
                findViewById(R.id.btnStop).performClick();
                return true;
            }
            if (id == 5) {
                findViewById(R.id.btnUpdate).performClick();
                return true;
            }
            if (id == 10) {
                findViewById(R.id.btnAppUpdate).performClick();
                return true;
            }
            if (id == 11) {
                checkLauncherUpdate(true);
                return true;
            }
            if (id == 6) {
                boolean now = !autoPerm();
                getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("auto_perm", now).apply();
                log(T("Interruptor permisos-auto: ", "Auto-approve switch: ") + (now ? "SÍ" : "NO"));
                snack(now ? T("Permisos automáticos activados", "Auto-approve enabled")
                        : T("Permisos automáticos desactivados", "Auto-approve disabled"));
                if (serverOpen) {
                    log(T("Servidor abierto: reinicio para aplicar...", "Server open: restarting to apply..."));
                    findViewById(R.id.btnRestart).performClick();
                } else {
                    log(T("Se aplicará al arrancar el servidor.", "Will apply on server start."));
                }
                return true;
            }
            if (id == 7) {
                showOptionsDialog();
                return true;
            }
            if (id == 8) {
                showUsageDialog();
                return true;
            }
            if (id == 9) {
                reportProblem();
                return true;
            }
            return false;
        });
        try {
            java.lang.reflect.Field f = menu.getClass().getDeclaredField("mPopup");
            f.setAccessible(true);
            Object p = f.get(menu);
            java.lang.reflect.Method m =
                    p.getClass().getDeclaredMethod("setForceShowIcon", boolean.class);
            m.invoke(p, true);
        } catch (Exception ignored) {
        }
        menu.show();
    }

    /** Opciones de arranque y vigía. Cada una trae su definición (ES/EN);
     *  tocar el valor la cambia. Todo apagado por defecto: no gasta nada. */
    private void showOptionsDialog() {
        android.content.SharedPreferences up = getSharedPreferences("app", MODE_PRIVATE);
        updatePending = up.getBoolean("upd_pending", false);
        updateInstallable = !LocalRuntime.available(this);
        updInstalled = up.getString("upd_installed", "");
        updLatest = up.getString("upd_latest", "");
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        // Con 5 filas ya no cabe en pantalla: scroll para llegar abajo.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        final androidx.appcompat.app.AlertDialog[] dlg = new androidx.appcompat.app.AlertDialog[1];
        Runnable refresh = () -> {
            if (dlg[0] != null) dlg[0].dismiss();
            showOptionsDialog();
        };
        addOptionRow(root,
                T("Arrancar al encender", "Start on boot"),
                T("Al encender el móvil arranca el servidor solo, sin abrir la app."
                        + " Apagado no gasta nada; encendido gasta lo mismo que tener el chat abierto.",
                        "On phone boot, starts the server alone without opening the app."
                        + " Off costs nothing; on costs the same as having the chat open."),
                ServerWatch.bootOn(this) ? T("SÍ", "ON") : T("NO", "OFF"),
                () -> {
                    boolean now = !ServerWatch.bootOn(this);
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                            .putBoolean(ServerWatch.PREF_BOOT, now).apply();
                    snack(now ? T("Arranque activado", "Boot start enabled")
                            : T("Arranque desactivado", "Boot start disabled"));
                    refresh.run();
                });
        String wm = ServerWatch.wdMode(this);
        addOptionRow(root,
                T("Vigía del servidor", "Server watchdog"),
                T("Mira cada X minutos si el servidor responde. Avisar: te notifica si cayó."
                        + " Rearrancar: lo levanta solo. Apagado no hace nada.",
                        "Checks every X minutes whether the server answers. Notify: tells you if it died."
                        + " Restart: brings it back up alone. Off does nothing."),
                "off".equals(wm) ? T("OFF", "OFF")
                        : "notify".equals(wm) ? T("Avisar", "Notify") : T("Rearrancar", "Restart"),
                () -> {
                    String cur = ServerWatch.wdMode(this);
                    String next = "off".equals(cur) ? "notify"
                            : "notify".equals(cur) ? "restart" : "off";
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                            .putString(ServerWatch.PREF_WD_MODE, next).apply();
                    ServerWatch.arm(this);
                    if (!"off".equals(next)) ensureNotifPerm();
                    snack(T("Vigía: ", "Watchdog: ") + next);
                    refresh.run();
                });
        addOptionRow(root,
                T("Intervalo del vigía", "Watchdog interval"),
                T("Cada cuánto mira el vigía. Usa la alarma inexacta del sistema (barata);"
                        + " en sueño profundo puede tardar más. Parar el servidor a mano lo pausa"
                        + " hasta el próximo arranque.",
                        "How often the watchdog checks. Uses the system's inexact alarm (cheap);"
                        + " in deep sleep it may lag. Stopping the server by hand pauses it"
                        + " until the next start."),
                ServerWatch.wdMin(this) + T(" min", " min"),
                () -> {
                    int cur = ServerWatch.wdMin(this);
                    int next = cur == 15 ? 30 : cur == 30 ? 60 : 15;
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                            .putInt(ServerWatch.PREF_WD_MIN, next).apply();
                    ServerWatch.arm(this);
                    snack(T("Intervalo: ", "Interval: ") + next + T(" min", " min"));
                    refresh.run();
                });
        addOptionRow(root,
                T("Bloqueo de app", "App lock"),
                T("Pide el desbloqueo del móvil (PIN, patrón, huella) una vez al entrar al chat."
                        + " Apagado por defecto; si tu móvil no tiene bloqueo, te manda a crearlo.",
                        "Asks for the phone unlock (PIN, pattern, fingerprint) once when opening the chat."
                        + " Off by default; if your phone has no lock, it sends you to create one."),
                lockOn() ? T("SÍ", "ON") : T("NO", "OFF"),
                () -> {
                    if (!lockOn()) {
                        android.app.KeyguardManager km = (android.app.KeyguardManager)
                                getSystemService(Context.KEYGUARD_SERVICE);
                        if (km == null || !km.isDeviceSecure()) {
                            snack(T("Tu móvil no tiene bloqueo: créalo primero",
                                    "Your phone has no lock: create one first"));
                            try {
                                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            } catch (Exception e) {
                                log("Abre manual: Ajustes > Seguridad > Bloqueo de pantalla.");
                            }
                            return;
                        }
                    }
                    boolean now = !lockOn();
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                            .putBoolean("app_lock", now).apply();
                    if (!now) unlockedRun = false;
                    snack(now ? T("Bloqueo activado", "Lock enabled")
                            : T("Bloqueo desactivado", "Lock disabled"));
                    refresh.run();
                });
        dlg[0] = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(T("Opciones", "Options"))
                .setView(scroll)
                .setNegativeButton(T("Cerrar", "Close"), null)
                .show();
    }

    private void addOptionRow(android.widget.LinearLayout root, String title,
            String def, String value, Runnable onTap) {

        TextView t = new TextView(this);
        t.setText(title);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextSize(15);
        root.addView(t);
        TextView d = new TextView(this);
        d.setText(def);
        d.setTextSize(12);
        root.addView(d);
        com.google.android.material.button.MaterialButton b =
                new com.google.android.material.button.MaterialButton(this);
        b.setText(value);
        b.setSingleLine(false);
        b.setMaxLines(3);
        b.setOnClickListener(v -> onTap.run());
        root.addView(b);
        View s = new View(this);
        s.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));
        root.addView(s);
    }

    /** Reportar problema: arma diagnostico + registro (claves tapadas) y abre
     *  un issue de GitHub ya relleno (el usuario solo pulsa Enviar). Sin
     *  navegador o sin red, lo deja en el portapapeles. Vale para cualquiera:
     *  no pide nada tecnico, y nunca manda claves (se tapan antes de salir). */
    private void reportProblem() {
        snack(T("Armando reporte…", "Building report…"));
        new Thread(() -> {
            String installed = queryHealthVersion();
            android.content.SharedPreferences p =
                    getSharedPreferences("app", MODE_PRIVATE);
            if (installed == null || installed.isEmpty())
                installed = p.getString("upd_installed", "?");
            String latest = p.getString("upd_latest", "?");
            String appVer;
            try {
                appVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                appVer = "?";
            }
            String lang = p.getString("lang",
                    java.util.Locale.getDefault().getLanguage());
            StringBuilder b = new StringBuilder();
            b.append("### App\n");
            b.append("- App: ").append(appVer).append("\n");
            b.append("- Android: ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
                    .append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            b.append("- Root: ").append(useRoot() ? "sí" : "no").append("\n");
            b.append("- Servidor: ").append(serverOpen ? "abierto" : "cerrado").append("\n");
            b.append("- opencode instalada: ").append(installed)
                    .append(" (última conocida: ").append(latest).append(")\n");
            b.append("### Opciones\n");
            b.append("- Arranque: ").append(ServerWatch.bootOn(this) ? "SÍ" : "NO")
                    .append(", Vigía: ").append(ServerWatch.wdMode(this))
                    .append(", Intervalo: ").append(ServerWatch.wdMin(this)).append(" min\n");
            b.append("- Bloqueo: ").append(lockOn() ? "SÍ" : "NO")
                    .append(", Permisos auto: ").append(autoPerm() ? "SÍ" : "NO")
                    .append(", Idioma: ").append(lang).append("\n");
            b.append("### Qué pasa (edítalo antes de enviar)\n");
            b.append("<describe aquí qué falla>\n");
            String rawLog = "";
            try {
                rawLog = txtLog.getText().toString();
            } catch (Exception ignored) {
            }
            String log = redactSecrets(rawLog);
            if (log.length() > 3000) log = "…(recortado)…\n" + log.substring(log.length() - 3000);
            b.append("### Registro (claves tapadas)\n```\n").append(log).append("\n```\n");
            final String title = "Reporte app v" + appVer;
            final String body = b.toString();
            runOnUiThread(() -> {
                try {
                    String url = "https://github.com/" + REPORT_REPO + "/issues/new?title="
                            + java.net.URLEncoder.encode(title, "UTF-8")
                            + "&body=" + java.net.URLEncoder.encode(body, "UTF-8");
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    try {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("opencode-reporte",
                                title + "\n\n" + body));
                        snack(T("Sin navegador: reporte copiado, pégalo donde quieras",
                                "No browser: report copied, paste it anywhere"));
                    } catch (Exception e2) {
                        snack(T("No se pudo abrir ni copiar", "Could not open nor copy"));
                    }
                }
            });
        }).start();
    }

    /** Tapa claves/tokens del texto antes de que salga del movil. */
    private String redactSecrets(String s) {
        if (s == null) return "";
        String r = s;
        r = r.replaceAll("(?i)\\bsk-[A-Za-z0-9\\-_]{8,}", "sk-***");
        r = r.replaceAll("\\bghp_[A-Za-z0-9_]{8,}", "ghp_***");
        r = r.replaceAll("(?i)Bearer\\s+[A-Za-z0-9\\-_.~+/=]{6,}", "Bearer ***");
        r = r.replaceAll("(?i)(\"(api[_-]?key|token|secret|password|authorization)\"\\s*:\\s*\")[^\"]+\"",
                "$1***\"");
        r = r.replaceAll("(?i)\\b(api[_-]?key|token|secret|password)\\s*=\\s*\\S+", "$1=***");
        return r;
    }

    /** Pasos del primer arranque: el boton grande va oscurecido hasta que los
     *  5 esten en verde; debajo se ve su estado (punto rojo/verde, sin botones).
     *  Todo se detecta solo al volver de Ajustes/Termux (refreshStepStatus). */
    private boolean wizardDone() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("wizard_done", false);
    }

    private boolean setupOk() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("setup_ok", false);
    }

    /** El comando de setup quedo pegado/lanzado (la prueba fina es el primer
     *  arranque con exito). Sin esto, tras pegar el setup el boton seguiria
     *  oscurecido esperando una prueba que aun no se puede pedir. */
    private boolean setupTried() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("setup_tried", false);
    }

    private void markWizardDone() {
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putBoolean("wizard_done", true)
                .putBoolean("setup_ok", true)
                .putBoolean("onboarding_done", true).apply();
        // La tarjeta (PASO x de 3) se esconde por onboarding_done: sin esto,
        // entrar al chat con el servidor ya abierto la dejaba visible para siempre.
        applyOnboarding();
        refreshStepStatus();
    }

    private boolean batteryExempt() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations("com.termux");
        } catch (Exception e) {
            return false;
        }
    }

    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            log("Abre manual: Ajustes > Apps > OpenCode Android > Todos los archivos.");
        }
    }

    /** Recalcula los 5 pasos del primer arranque: repinta la tira de puntos
     *  (rojo = falta, verde = hecho) y el oscurecido del boton grande. */
    private volatile boolean stepsGreen = false;

    private void refreshStepStatus() {
        if (LocalRuntime.available(this)) {
            stepsGreen = true;
            runOnUiThread(this::applyStepStatus);
            return;
        }
        if (wizardDone() || isOnboarded()) {
            stepsGreen = true;
            runOnUiThread(this::applyStepStatus);
            return;
        }
        new Thread(() -> {
            boolean hasTermux = TermuxRunner.isTermuxInstalled(this);
            boolean setup = setupOk() || setupTried();
            boolean ov = TermuxRunner.isOverlayGranted(this);
            boolean files = hasAllFilesAccess();
            boolean bat = batteryExempt();
            stepsGreen = hasTermux && setup && ov && files && bat;
            runOnUiThread(() -> applyStepStatus(hasTermux, setup, ov, files, bat));
        }).start();
    }

    /** Sin wizard completado el boton no se enciende hasta tener los 5 en verde. */
    private void applyStepStatus() {
        btnStart.setEnabled(stepsGreen || wizardDone());
    }

    private void applyStepStatus(boolean hasTermux, boolean setup, boolean ov,
            boolean files, boolean bat) {
        applyStepStatus();
        updateStepRow(R.id.stepRow1, hasTermux,
                T("1 · Termux instalado", "1 · Termux installed"));
        updateStepRow(R.id.stepRow2, setup,
                T("2 · Setup en Termux", "2 · Setup in Termux"));
        updateStepRow(R.id.stepRow3, ov,
                T("3a · Termux puede mostrarse encima", "3a · Termux can draw over apps"));
        updateStepRow(R.id.stepRow4, files, T("3b · Archivos de la app", "3b · App files"));
        updateStepRow(R.id.stepRow5, bat,
                T("3c · Batería sin restricciones", "3c · Unrestricted battery"));
    }

    /** Punto rojo (falta) / verde (hecho). Solo estado: sin botones. */
    private void updateStepRow(int id, boolean ok, String label) {
        TextView row = findViewById(id);
        if (row == null) return;
        row.setText("● " + label);
        row.setTextColor(ok ? 0xFF1E8E3E : 0xFFC62828);
    }

    /** Consumo de cuota: lee las sesiones de la API local (no depende del widget
     *  de la web, que en anonimo se queda girando). Muestra tokens, coste y fecha;
     *  permite borrar con confirmacion. */
    private void showUsageDialog() {
        snack(T("Leyendo consumo…", "Reading usage…"));
        new Thread(() -> {
            String body;
            try {
                java.net.URL u = new java.net.URL(URL + "/session");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                java.util.Scanner sc = new java.util.Scanner(c.getInputStream(), "UTF-8")
                        .useDelimiter("\\A");
                body = sc.hasNext() ? sc.next() : "[]";
                sc.close();
            } catch (Exception e) {
                final String err = String.valueOf(e.getMessage());
                runOnUiThread(() -> snack(T("Sin servidor: arráncalo primero", "No server: start it first")
                        + " (" + err + ")"));
                return;
            }
            final String b = body;
            runOnUiThread(() -> showUsageList(b));
        }).start();
    }

    private String fmtTok(long n) {
        try {
            return String.format(java.util.Locale.getDefault(), "%,d", n);
        } catch (Exception e) {
            return String.valueOf(n);
        }
    }

    private String fmtCost(double c) {
        try {
            return String.format(java.util.Locale.US, "$%.2f", c);
        } catch (Exception e) {
            return "$?";
        }
    }

    private String fmtDate(long ms) {
        try {
            return new java.text.SimpleDateFormat("dd/MM HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date(ms));
        } catch (Exception e) {
            return "";
        }
    }

    private void showUsageList(String body) {
        if (usageDlg != null) {
            try {
                usageDlg.dismiss();
            } catch (Exception ignored) {
            }
            usageDlg = null;
        }
        org.json.JSONArray arr;
        try {
            arr = new org.json.JSONArray(body);
        } catch (Exception e) {
            snack(T("Respuesta rara del servidor", "Odd server answer"));
            return;
        }
        java.util.ArrayList<org.json.JSONObject> rows = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o != null) rows.add(o);
        }
        java.util.Collections.sort(rows, (a, b) -> Long.compare(usageTokens(b), usageTokens(a)));
        long tIn = 0, tOut = 0, tRe = 0;
        double tCost = 0;
        for (org.json.JSONObject o : rows) {
            org.json.JSONObject tk = o.optJSONObject("tokens");
            if (tk != null) {
                tIn += tk.optLong("input", 0);
                tOut += tk.optLong("output", 0);
                tRe += tk.optLong("reasoning", 0);
            }
            tCost += o.optDouble("cost", 0);
        }
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        TextView total = new TextView(this);
        total.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        total.setTextSize(15);
        total.setText(T("Total: ", "Total: ") + fmtTok(tIn + tOut + tRe)
                + T(" tokens · ", " tokens · ") + fmtCost(tCost));
        root.addView(total);
        TextView sub = new TextView(this);
        sub.setTextSize(12);
        sub.setText(T("Entrada ", "In ") + fmtTok(tIn) + T(" · salida ", " · out ")
                + fmtTok(tOut) + T(" · razonamiento ", " · reasoning ") + fmtTok(tRe));
        root.addView(sub);
        View sep = new View(this);
        sep.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));
        root.addView(sep);
        if (rows.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(T("Sin sesiones.", "No sessions."));
            root.addView(none);
        }
        for (org.json.JSONObject o : rows) {
            final String sid = o.optString("id", "");
            String title = o.optString("title", T("Sin título", "Untitled"));
            org.json.JSONObject tk = o.optJSONObject("tokens");
            long tin = tk == null ? 0 : tk.optLong("input", 0);
            long tou = tk == null ? 0 : tk.optLong("output", 0);
            long tre = tk == null ? 0 : tk.optLong("reasoning", 0);
            double cost = o.optDouble("cost", 0);
            long upd = 0;
            org.json.JSONObject tm = o.optJSONObject("time");
            if (tm != null) upd = tm.optLong("updated", tm.optLong("created", 0));
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            TextView tt = new TextView(this);
            tt.setTextSize(14);
            tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tt.setMaxLines(1);
            tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tt.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            tt.setText(title);
            TextView ts = new TextView(this);
            ts.setTextSize(12);
            ts.setText(fmtTok(tin + tou + tre) + T(" tokens · ", " tokens · ")
                    + fmtCost(cost) + (upd > 0 ? " · " + fmtDate(upd) : ""));
            android.widget.LinearLayout col = new android.widget.LinearLayout(this);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(tt);
            col.addView(ts);
            row.addView(col);
            com.google.android.material.button.MaterialButton del =
                    new com.google.android.material.button.MaterialButton(this);
            del.setText(T("Borrar", "Delete"));
            final String ftitle = title;
            del.setOnClickListener(v -> confirmDeleteSession(sid, ftitle));
            row.addView(del);
            root.addView(row);
            View s2 = new View(this);
            s2.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));
            root.addView(s2);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(420)));
        sv.addView(root);
        usageDlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(T("Consumo", "Usage"))
                .setView(sv)
                .setNegativeButton(T("Cerrar", "Close"), null)
                .show();
    }

    private long usageTokens(org.json.JSONObject o) {
        org.json.JSONObject tk = o.optJSONObject("tokens");
        if (tk == null) return 0;
        return tk.optLong("input", 0) + tk.optLong("output", 0) + tk.optLong("reasoning", 0);
    }

    private void confirmDeleteSession(String sid, String title) {
        if (sid == null || sid.isEmpty()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(T("Borrar chat", "Delete chat"))
                .setMessage(T("¿Borrar del servidor «" + title + "»? No se puede deshacer.",
                        "Delete «" + title + "» from the server? Cannot be undone."))
                .setPositiveButton(T("Borrar", "Delete"), (d, w) -> deleteSession(sid))
                .setNegativeButton(T("Cancelar", "Cancel"), null)
                .show();
    }

    private void deleteSession(String sid) {
        snack(T("Borrando…", "Deleting…"));
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(URL + "/session/" + sid);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("DELETE");
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                runOnUiThread(() -> showUsageDialog());
            } catch (Exception e) {
                runOnUiThread(() -> snack(T("No se pudo borrar", "Could not delete")
                        + " (" + String.valueOf(e.getMessage()) + ")"));
            }
        }).start();
    }

    /** El registro en un dialogo (copiar / limpiar / cerrar) sin salir del chat. */
    private void showLogDialog() {
        if (logDlg != null) {
            try {
                logDlg.dismiss();
            } catch (Exception ignored) {
            }
            logDlg = null;
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(320)));
        TextView tv = new TextView(this);
        tv.setText(txtLog.getText().toString());
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        int p = dp(16);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        logDlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(T("Registro", "Log"))
                .setView(sv)
                .setPositiveButton(T("Copiar", "Copy"), (d, w) -> {
                    try {
                        ClipboardManager cm =
                                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText(
                                "opencode-log", txtLog.getText().toString()));
                        snack(T("Registro copiado", "Log copied"));
                    } catch (Exception e) {
                        snack(T("No se pudo copiar", "Could not copy"));
                    }
                })
                .setNegativeButton(T("Limpiar", "Clear"), (d, w) -> txtLog.setText(""))
                .setNeutralButton(T("Cerrar", "Close"), null)
                .show();
    }

    /** Funde el splash y hace entrar el contenido desde abajo (una sola vez). */
    private void hideSplash() {
        if (splashDone) return;
        splashDone = true;
        // Tiempo minimo en pantalla para que no parpadee.
        long wait = Math.max(0, 900 - (SystemClock.uptimeMillis() - splashStart));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (splash != null) {
                splash.animate().alpha(0f).setDuration(350)
                        .withEndAction(() -> {
                            splash.setVisibility(View.GONE);
                            fabControls.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
                            if (fullscreen) {
                                fabControls.post(() -> restoreFabPos());
                                fabControls.setScaleX(0f);
                                fabControls.setScaleY(0f);
                                fabControls.animate().scaleX(1f).scaleY(1f)
                                        .setStartDelay(100).setDuration(300).start();
                            }
                            if (pendingSplashSnack != null) {
                                String message = pendingSplashSnack;
                                pendingSplashSnack = null;
                                // El aviso de pantalla completa caduca si ya se volvio al panel.
                                if (message.startsWith("Pantalla completa") && !fullscreen) return;
                                new Handler(Looper.getMainLooper())
                                        .postDelayed(() -> Snackbar.make(web, message,
                                                Snackbar.LENGTH_SHORT).show(), 180);
                            }
                        }).start();
            }
            boxContent.animate().alpha(1f).translationY(0f)
                    .setDuration(400).setInterpolator(new DecelerateInterpolator()).start();
        }, wait);
    }

    private void setSplashStatus(String m) {
        runOnUiThread(() -> {
            if (!splashDone && splashStatus != null) splashStatus.setText(m);
        });
    }

    /**
     * Botones de accion segun estado: en el panel se muestra EXACTAMENTE uno:
     * "Iniciar y abrir" (servidor parado) o "Abrir chat" (servidor abierto).
     * En pantalla completa ninguno (el chat manda).
     */
    private void refreshActionButtons() {
        boolean panel = !fullscreen;
        btnStart.setVisibility(panel && !serverOpen ? View.VISIBLE : View.GONE);
        btnOpen.setVisibility(panel && serverOpen ? View.VISIBLE : View.GONE);
        refreshUpdateBtn();
    }

    /** El boton Actualizar solo existe si hay update: si no, desaparece.
     *  Con update muestra las versiones (instalada → ultima). */
    private void refreshUpdateBtn() {
        View b = findViewById(R.id.btnUpdate);
        if (b == null) return;
        b.setVisibility(updatePending && updateInstallable && !fullscreen
                ? View.VISIBLE : View.GONE);
        if (updatePending && updateInstallable && b instanceof android.widget.TextView) {
            ((android.widget.TextView) b).setText(
                    T("Actualizar opencode ", "Update opencode ")
                            + updInstalled + " → " + updLatest);
        }
        // El boton de la app va siempre con este (panel, idioma, pantalla completa).
        refreshAppUpdateBtn();
    }

    /** Notificacion de opencode nuevo (una vez por version). Vale en abierto
     *  y la alarma diaria la reutiliza con la app cerrada. */
    private void maybeNotifyOpencode(boolean pending, String installed, String latest) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (!pending || latest == null || latest.isEmpty()) {
                // Sin datos (red caida) no significa "no hay update": no borrar el aviso.
                if (!pending && latest != null && !latest.isEmpty() && nm != null)
                    nm.cancel(ServerWatch.NID_OC);
                return;
            }
            android.content.SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
            if (latest.equals(p.getString("upd_notified", ""))) return;
            String text = installed + " → " + latest + (LocalRuntime.available(this)
                    ? T(": toca para descargar e instalar",
                            ": tap to download and install")
                    : T(": abre la app para instalarla", ": open the app to install it"));
            if (ServerWatch.notifyOpenCodeUpdate(this,
                    T("Actualización de opencode", "opencode update"), text, latest)) {
                p.edit().putString("upd_notified", latest).apply();
            } else {
                askNotifPermission();
            }
        } catch (Exception ignored) {
        }
    }

    /** Notificacion de launcher nuevo (una vez por version; si el aviso no
     *  llego a mostrarse no se marca y se reintenta en la proxima apertura). */
    private void maybeNotifyApp(String tag) {
        try {
            android.content.SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
            if (tag.equals(p.getString("appupd_notified", ""))) return;
            if (ServerWatch.notifyId(this, ServerWatch.NID_APP,
                    T("Nueva versión de la app", "New app version"),
                    T("Disponible ", "Available ") + tag + T(": abre la app para descargarla",
                            ": open the app to download it"))) {
                p.edit().putString("appupd_notified", tag).apply();
            } else {
                askNotifPermission();
            }
        } catch (Exception ignored) {
        }
    }

    /** Pide POST_NOTIFICATIONS (una vez por sesion) si hace falta. */
    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (notifPermTried) return;
        notifPermTried = true;
        try {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        } catch (Exception ignored) {
        }
    }

    /** "Todos los archivos" no se puede pedir con un dialogo desde Android 11:
     *  hay que abrir el ajuste de la app. Se lleva al usuario directamente y, si
     *  el permiso ya esta dado, el arranque que estaba esperando continua solo. */
    private void askAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (hasAllFilesAccess()) {
            onAllFilesAccessReady();
            return;
        }
        if (filesPermTried) return;
        // Si el aviso de notificaciones sigue en pantalla, se espera a que el
        // usuario conteste (si no, los dos dialogos se pisan).
        if (notifDialogPending()) return;
        filesPermTried = true;
        filesAskPending = true;
        log("Abro Ajustes: esta app necesita 'todos los archivos' para que el agente"
                + " lea y escriba tus archivos. Activa el interruptor y vuelve.");
        openAllFilesSettings();
    }

    /** Al volver de Ajustes: si ya hay permiso, se arranca lo que estaba esperando. */
    private void onAllFilesAccessReady() {
        if (!pendingStartAfterPerms) return;
        pendingStartAfterPerms = false;
        startServerSequence();
    }

    /** El aviso del sistema de notificaciones esta todavia en pantalla. */
    private boolean notifDialogPending() {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (notifPermAnswered) return false;
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Chequeo del launcher: pinta la cache al instante y refresca contra
     *  GitHub en cada entrada (minimo 60 s entre intentos por la API). */
    private void checkLauncherUpdate(boolean verbose) {
        android.content.SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
        String cTag = p.getString("appupd_tag", "");
        String cUrl = p.getString("appupd_url", "");
        if (!cTag.isEmpty() && !cUrl.isEmpty()) applyLauncherCache(cTag, cUrl);
        long last = p.getLong("appupd_try", 0);
        if (!verbose && System.currentTimeMillis() - last < 60_000) return;
        p.edit().putLong("appupd_try", System.currentTimeMillis()).apply();
        new Thread(() -> {
            String[] rel = UpdateCheck.launcherRelease();
            if (rel == null) {
                runOnUiThread(() -> {
                    if (verbose) snack(T("No se pudo comprobar la actualización",
                            "Could not check for updates"));
                });
                return;
            }
            String tag = rel[0];
            String url = rel[1];
            getSharedPreferences("app", MODE_PRIVATE).edit()
                    .putLong("appupd_ts", System.currentTimeMillis())
                    .putString("appupd_tag", tag)
                    .putString("appupd_url", url)
                    .putString("appupd_ver", UpdateCheck.appVersion(this)).apply();
            runOnUiThread(() -> {
                applyLauncherCache(tag, url);
                if (verbose && !appUpdatePending)
                    snack(T("No hay actualizaciones", "No updates available"));
            });
        }).start();
    }

    /** El pendiente se deriva de la version instalada: tras instalar el APK
     *  nuevo el boton desaparece solo aunque la cache siga fresca. */
    private void applyLauncherCache(String tag, String url) {
        String cur = UpdateCheck.appVersion(this);
        appUpdatePending = tag != null && !tag.isEmpty() && !cur.isEmpty()
                && UpdateCheck.cmpVer(tag, cur) > 0 && url != null && !url.isEmpty();
        appUpdTag = appUpdatePending ? tag : "";
        appUpdUrl = appUpdatePending ? url : "";
        refreshAppUpdateBtn();
        if (appUpdatePending) {
            maybeNotifyApp(tag);
        } else {
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(ServerWatch.NID_APP);
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshAppUpdateBtn() {
        View b = findViewById(R.id.btnAppUpdate);
        if (b == null) return;
        b.setVisibility(appUpdatePending && !fullscreen ? View.VISIBLE : View.GONE);
        if (appUpdatePending && b instanceof android.widget.TextView) {
            ((android.widget.TextView) b).setText(
                    T("Nueva versión de la app ", "New app version ")
                            + appUpdTag + T(" — Descargar", " — Download"));
        }
    }

    /** Baja el APK del launcher con DownloadManager (avisa solo) e instala. */
    private void downloadAppUpdate(String url, String tag) {
        new Thread(() -> {
            try {
                android.app.DownloadManager dm =
                        (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                android.app.DownloadManager.Request req =
                        new android.app.DownloadManager.Request(Uri.parse(url));
                String name = "launcher-" + tag + ".apk";
                req.setTitle("OpenCode Android " + tag);
                req.setDescription(name);
                req.setMimeType("application/vnd.android.package-archive");
                req.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                java.io.File stale = new java.io.File(getExternalFilesDir("updates"), name);
                if (stale.exists()) stale.delete();
                req.setDestinationInExternalFilesDir(this, "updates", name);
                long id = dm.enqueue(req);
                appDownloadId = id;
                getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putString("app_apk", name).apply();
                if (appDownloadReceiver == null) {
                    appDownloadReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent intent) {
                            long rid = intent.getLongExtra(
                                    android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                            if (rid != appDownloadId) return;
                            // Tambien salta si fallo: instalar solo si acabo bien.
                            try (android.database.Cursor c = dm.query(
                                    new android.app.DownloadManager.Query().setFilterById(rid))) {
                                if (c != null && c.moveToFirst()
                                        && c.getInt(c.getColumnIndex(
                                                android.app.DownloadManager.COLUMN_STATUS))
                                                == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                    installAppUpdate();
                                } else {
                                    log("Descarga de la app incompleta o fallida. Reintenta.");
                                    snack(T("Fallo la descarga, reintenta",
                                            "Download failed, retry"));
                                }
                            }
                        }
                    };
                    ContextCompat.registerReceiver(this, appDownloadReceiver,
                            new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                            ContextCompat.RECEIVER_NOT_EXPORTED);
                }
                runOnUiThread(() -> {
                    log("Descargando nueva versión " + tag + " ...");
                    snack(T("Descargando nueva versión...", "Downloading new version..."));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    log("Descarga de la app falló: " + e.getMessage());
                    snack(T("Fallo la descarga, mira el registro",
                            "Download failed, check the log"));
                });
            }
        }).start();
    }

    /** Abre el permiso 'instalar apps desconocidas'. En algunos moviles la
     *  pantalla directa no existe: entonces abre los detalles de la app
     *  (ahi esta el mismo interruptor). Nunca falla en silencio. */
    private void openUnknownSourcesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                snack(T("Activa 'Instalar apps desconocidas' y vuelve",
                        "Enable 'Install unknown apps' and come back"));
            } catch (Exception e2) {
                log("Abre manual: Ajustes > Apps > OpenCode Android > Instalar apps desconocidas.");
            }
        }
    }

    /** Abre el instalador del sistema con el APK del launcher (via FileProvider). */
    private void installAppUpdate() {
        try {
            String name = getSharedPreferences("app", MODE_PRIVATE).getString("app_apk", "");
            java.io.File apk = new java.io.File(getExternalFilesDir("updates"), name);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            Intent in = new Intent(Intent.ACTION_VIEW);
            in.setDataAndType(uri, "application/vnd.android.package-archive");
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(in);
            log("Instalador abierto: confirma Instalar.");
            snack(T("Confirma la instalación", "Confirm the install"));
        } catch (Exception e) {
            log("No se pudo abrir el instalador: " + e.getMessage());
            snack(T("Fallo el instalador, mira el registro", "Installer failed, check the log"));
        }
    }

    private void setFullscreen(boolean full) {
        fullscreen = full;
        int vis = full ? View.GONE : View.VISIBLE;
        findViewById(R.id.appBar).setVisibility(vis);
        findViewById(R.id.toolbar).setVisibility(vis);
        findViewById(R.id.cardStatus).setVisibility(vis);
        findViewById(R.id.lblSteps).setVisibility(View.GONE);
        View guide = findViewById(R.id.cardGuide);
        if (guide != null) guide.setVisibility(full || isOnboarded() ? View.GONE : View.VISIBLE);
        if (!full) refreshGuide();
        refreshActionButtons();
        findViewById(R.id.boxAdvanced).setVisibility(
                full ? View.GONE : (advancedOpen ? View.VISIBLE : View.GONE));
        fabControls.setVisibility(full && splashDone ? View.VISIBLE : View.GONE);
        if (full && splashDone) fabControls.post(() -> restoreFabPos());
        // Chat-first radical: la web solo existe a pantalla completa; en el
        // panel no hay vista previa.
        cardWeb.setVisibility(full ? View.VISIBLE : View.GONE);
        applyChatChrome(full);
        if (full) {
            web.onResume();
            web.resumeTimers();
            restoreFabPos();
            if (web.getUrl() != null) {
                injectEdgeCss();
                flattenViewportFrame();
                boostSidebarFooter();
                redirectHelpToIssues();
                rebrandDesktopToMobile();
            }
            snack(T("Pantalla completa: el boton abre el menu", "Fullscreen: the button opens the menu"));
        }
    }

    /** En chat la web llena todo (sin tarjeta/margenes) + barras ocultas. En panel se restaura. */
    private void applyChatChrome(boolean full) {
        // La tarjeta del visor siempre sin stroke (el tema le pone un outline
        // de 1dp que se veia como marco). Radio 12dp en panel, plano en chat.
        cardWeb.setRadius(full ? 0 : dp(12));
        cardWeb.setCardElevation(0f);
        cardWeb.setStrokeWidth(0);
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) cardWeb.getLayoutParams();
        lp.topMargin = full ? 0 : dp(16);
        lp.leftMargin = 0;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        cardWeb.setLayoutParams(lp);
        int p = full ? 0 : dp(16);
        boxContent.setPadding(p, p, p, p);
        // Siempre inmersivo (panel y chat): entrar/salir del chat no debe
        // redimensionar la ventana nunca. Barra de estado con swipe.
        enterImmersive();
    }

    /** La pagina de opencode trae su propio marco (margen/borde). Lo aplanamos con CSS. */
    private static final String EDGE_CSS =
            "html,body{margin:0!important;padding:0!important;max-width:none!important;}"
            + "#root{padding:0!important;}"
            + "*::-webkit-scrollbar{display:none!important;}"
            + "@media (max-width:600px){"
            + ".settings-v2{flex-direction:row!important;}"
            + ".settings-v2>div:first-child{width:38%!important;max-width:180px!important;"
            + "min-width:0!important;flex-shrink:0!important;}"
            + ".settings-v2-panel{flex:1 1 auto!important;min-width:0!important;}"
            + ".settings-v2-tab-header{padding:16px 12px 8px!important;}"
            + ".settings-v2-tab-body{padding:0 12px 12px!important;}"
            + "}";

    private void injectEdgeCss() {
        try {
            web.evaluateJavascript(
                    "(function(){var s=document.getElementById('oc-edge');"
                    + "if(!s){s=document.createElement('style');s.id='oc-edge';"
                    + "s.textContent=" + JSONObject.quote(EDGE_CSS) + ";"
                    + "document.documentElement.appendChild(s);}return 1;})()",
                    null);
        } catch (Exception e) {
            Log.d("OpenCode", "edge-css fallo: " + e.getMessage());
        }
    }

    /** Censo (solo lectura) de elementos pegados al borde izq/sup: el filete en L. */
    private void censusPageEdges() {
        try {
            web.evaluateJavascript(
                    "(function(){try{var out=[];"
                    + "Array.prototype.forEach.call(document.querySelectorAll('*'),function(e){"
                    + "var r=e.getBoundingClientRect();"
                    + "var leftEdge=(r.left<=3&&r.height>300);"
                    + "var topEdge=(r.top<=3&&r.width>300);"
                    + "if(!(leftEdge||topEdge))return;"
                    + "var cs=getComputedStyle(e);"
                    + "out.push((leftEdge?'L':'')+(topEdge?'T':'')+' '+e.tagName.'.'"
                    + "+String(e.className).split(' ').slice(0,3).join('.')"
                    + "+' @'+Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height)"
                    + "+' blw='+cs.borderLeftWidth+' btw='+cs.borderTopWidth+' bs='+cs.borderStyle+' bg='+cs.backgroundColor"
                    + "+' sh='+cs.boxShadow);});"
                    + "return out.join(' ~ ');}catch(err){return 'ERR';}})()",
                    v -> Log.d("OpenCode", "edge-census: " + v));
            // Censo 2: barras angostas (<=12px) altas o anchas con su color de fondo.
            web.evaluateJavascript(
                    "(function(){try{var out=[];"
                    + "Array.prototype.forEach.call(document.querySelectorAll('*'),function(e){"
                    + "if(out.length>=10)return;"
                    + "var r=e.getBoundingClientRect();"
                    + "var vbar=(r.width<=12&&r.width>0&&r.height>200);"
                    + "var hbar=(r.height<=12&&r.height>0&&r.width>200);"
                    + "if(!(vbar||hbar))return;"
                    + "var cs=getComputedStyle(e);"
                    + "out.push((vbar?'V':'')+(hbar?'H':'')+' '+e.tagName.'.'"
                    + "+String(e.className).split(' ').slice(0,3).join('.')"
                    + "+' @'+Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height)"
                    + "+' bg='+cs.backgroundColor+' b='+cs.borderLeftWidth+'/'+cs.borderTopWidth"
                    + "+' flt='+cs.filter);});}catch(err){return 'BERR:'+err;}})()",
                    v -> Log.d("OpenCode", "edge-bars: " + v));
            web.evaluateJavascript(
                    "(function(){try{var out=[];"
                    + "Array.prototype.forEach.call(document.querySelectorAll('*'),function(e){"
                    + "if(out.length>=10)return;"
                    + "var ps=['::before','::after'];"
                    + "for(var k=0;k<2;k++){var cs=getComputedStyle(e,ps[k]);"
                    + "if(cs.content==='none'||!cs.content)continue;"
                    + "var bg=cs.backgroundColor;"
                    + "if(bg==='rgba(0, 0, 0, 0)'||bg==='transparent')continue;"
                    + "out.push(ps[k]+' '+e.tagName+'.'+String(e.className).split(' ').slice(0,2).join('.')"
                    + "+' pos='+cs.position+' w='+cs.width+' h='+cs.height+' bg='+bg);}});"
                    + "return out.join(' ~ ');}catch(err){return 'PERR:'+err;}})()",
                    v -> Log.d("OpenCode", "edge-pseudo: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "edge-census fallo: " + e.getMessage());
        }
    }
    private void dumpPageShell() {
        try {
            web.evaluateJavascript(
                    "(function(){function s(e){if(!e)return null;var c=getComputedStyle(e);"
                    + "return {t:e.tagName,i:e.id||'',c:(e.className&&e.className.baseVal!==undefined"
                    + "?e.className.baseVal:e.className)||'',m:c.margin,p:c.padding,"
                    + "b:(c.borderWidth+' '+c.borderStyle),r:c.borderRadius,"
                    + "sh:c.boxShadow,o:c.outlineStyle};}"
                    + "var k=[s(document.body)];"
                    + "var r=(document.getElementById('root')||document.body).firstElementChild;var n=0;"
                    + "while(r&&n<6){k.push(s(r));r=r.firstElementChild;n++;}"
                    + "return JSON.stringify(k).slice(0,3000);})()",
                    v -> Log.d("OpenCode", "DOM-shell: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "dom-dump fallo: " + e.getMessage());
        }
    }

    /**
     * Una sola pasada: aplana bordes/sombras/fondos-con-imagen de contenedores
     * grandes (el filete claro de los paneles). Sin observador ni repeticiones.
     */
    private void flattenViewportFrame() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "var vw=window.innerWidth,vh=window.innerHeight,n=0,who=[];"
                    + "Array.prototype.forEach.call(document.querySelectorAll('*'),function(e){"
                    + "var r=e.getBoundingClientRect();"
                    + "var full=(r.width>=vw-8&&r.height>=vh-8&&r.left<=8&&r.top<=8);"
                    + "var big=(r.width>=vw*0.8&&r.height>=vh*0.4);"
                    + "if(!(full||big))return;"
                    + "var cs=getComputedStyle(e);var t=false;"
                    + "if((cs.borderWidth!=='0px'&&cs.borderStyle!=='none')"
                    + "||cs.boxShadow!=='none'||cs.outlineStyle!=='none'){"
                    + "e.style.setProperty('border','0','important');"
                    + "e.style.setProperty('box-shadow','none','important');"
                    + "e.style.setProperty('outline','none','important');t=true;}"
                    + "var fl=cs.filter||'';"
                    + "if(fl&&fl!=='none'&&fl.indexOf('drop-shadow')>=0){"
                    + "e.style.setProperty('filter','none','important');"
                    + "e.style.setProperty('-webkit-filter','none','important');t=true;}"
                    + "var bi=cs.backgroundImage||'';"
                    + "if(bi&&bi!=='none'){"
                    + "e.style.setProperty('background-image','none','important');t=true;}"
                    + "if(t){n++;if(who.length<6)who.push(e.tagName+'.'+String(e.className).split(' ')[0]"
                    + "+'@'+Math.round(r.width)+'x'+Math.round(r.height));}});"
                    + "return n+'|'+who.join(',');}catch(err){return -1;}})()",
                    v -> { if (v != null && !v.equals("\"0|\"")) Log.d("OpenCode", "edge-fix: " + v); });
        } catch (Exception e) {
            Log.d("OpenCode", "edge-fix fallo: " + e.getMessage());
        }
    }

    /** La UI web dice "Desktop" (sección y pie "OpenCode Desktop"): en el
     *  móvil debe decir "Mobile". Solo texto exacto o el nombre compuesto. */
    private void rebrandDesktopToMobile() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "var n=0;"
                    + "function walk(e){"
                    + "if(e.nodeType===3){var t=e.nodeValue;"
                    + "if(t&&t.indexOf('OpenCode Desktop')>=0){"
                    + "e.nodeValue=t.split('OpenCode Desktop').join('OpenCode Mobile');n++;}}"
                    + "else if(e.nodeType===1){"
                    + "if(e.tagName==='SCRIPT'||e.tagName==='STYLE')return;"
                    + "var c=e.childNodes;for(var i=0;i<c.length;i++)walk(c[i]);"
                    + "if(e.childNodes.length===1&&e.childNodes[0].nodeType===3"
                    + "&&e.childNodes[0].nodeValue.trim()==='Desktop'){"
                    + "e.childNodes[0].nodeValue='Mobile';n++;}}}"
                    + "walk(document.body);"
                    + "if(!window.__ocBrandObs&&window.MutationObserver){"
                    + "window.__ocBrandObs=new MutationObserver(function(m){"
                    + "for(var i=0;i<m.length;i++)if(m[i].type==='childList'){walk(document.body);break;}});"
                    + "window.__ocBrandObs.observe(document.body,{childList:true,subtree:true});}"
                    + "return 'brand:'+n;}catch(err){return 'brand-ERR:'+err;}})()",
                    v -> Log.d("OpenCode", "brand-fix: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "brand-fix fallo: " + e.getMessage());
        }
    }

    /** El Help de la web apunta al Discord de opencode (upstream): lo redirigimos
     *  a los issues del launcher. Observador + click delegado porque la SPA
     *  renderiza tarde y puede usar window.open en vez de href. */
    private void redirectHelpToIssues() {
        try {
            String dest = "https://github.com/" + REPORT_REPO + "/issues";
            web.evaluateJavascript(
                    "(function(){try{"
                    + "var DEST='" + dest + "';"
                    + "function fix(){var n=0;"
                    + "Array.prototype.forEach.call(document.querySelectorAll('a[href*=\"discord\"],a[href*=\"feedback\"]'),"
                    + "function(a){if(a.__ocf)return;a.__ocf=1;a.setAttribute('href',DEST);n++;});"
                    + "return n;}"
                    + "var c=fix();"
                    + "if(!window.__ocHelpObs&&window.MutationObserver){"
                    + "window.__ocHelpObs=new MutationObserver(function(){fix();});"
                    + "window.__ocHelpObs.observe(document.documentElement,"
                    + "{childList:true,subtree:true,attributes:true,attributeFilter:['href']});}"
                    + "if(window.__ocHelpClk)document.removeEventListener('click',window.__ocHelpClk,true);"
                    + "window.__ocHelpClk=function(e){var t=(e.target&&e.target.closest)"
                    + "?e.target.closest('a[href*=\"discord\"],a[href*=\"feedback\"]'):null;"
                    + "if(t){e.preventDefault();e.stopPropagation();window.location.href=DEST;}};"
                    + "document.addEventListener('click',window.__ocHelpClk,true);"
                    + "return 'helpfix:'+c;}catch(err){return 'helpfix-ERR:'+err;}})()",
                    v -> Log.d("OpenCode", "help-fix: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "help-fix fallo: " + e.getMessage());
        }
    }
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void enterImmersive() {
        try {
            // Sin esto, Android pinta de negro la zona del agujero de la camara
            // aunque la barra de estado este oculta (letterbox del cutout).
            if (Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat c =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) {
                c.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsetsCompat.Type.systemBars());
            }
        } catch (Exception ignored) {
        }
    }

    private void exitImmersive() {
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            WindowInsetsControllerCompat c =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (c != null) c.show(WindowInsetsCompat.Type.systemBars());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) {
            web.resumeTimers();
            enterImmersive();
        }
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return true;
        try {
            // Refleja el interruptor "todos los archivos" de Ajustes: con el
            // apagado devuelve false y la app lo pide.
            return Environment.isExternalStorageManager();
        } catch (Exception e) {
            return false;
        }
    }

    private java.io.File progressFile() {
        return new java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "opencode-android/update.progress");
    }

    /** Lee "NN:texto" del marcador que escribe Termux en /sdcard. Devuelve null si no hay nada. */
    private int[] readProgressStage() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(progressFile()))) {
            String line = r.readLine();
            if (line == null) return null;
            int i = line.indexOf(':');
            if (i <= 0) return null;
            return new int[]{Integer.parseInt(line.substring(0, i).trim()), 0};
        } catch (Exception e) {
            return null;
        }
    }

    private String readProgressText() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(progressFile()))) {
            String line = r.readLine();
            if (line == null) return "";
            int i = line.indexOf(':');
            return i < 0 ? line : line.substring(i + 1).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void startProgressPoll() {
        stopProgressPoll();
        updating = true; // stop lo apaga; el poll nuevo necesita bandera activa
        progressTick = new Runnable() {
            @Override
            public void run() {
                if (!updating) return;
                long elapsed = System.currentTimeMillis() - updateStart;
                if (elapsed > 30 * 60 * 1000) {
                    updating = false;
                    progress.setIndeterminate(true);
                    progress.setVisibility(View.GONE);
                    findViewById(R.id.btnUpdate).setEnabled(true);
                    log("Progreso: timeout 30min, revisa Termux manual.");
                    snack(T("Timeout de progreso", "Progress timeout"));
                    return;
                }
                int[] st = readProgressStage();
                if (st != null) {
                    int stage = st[0];
                    String text = readProgressText();
                    // Descarga con MB leidos/total: la barra es la fraccion
                    // real de descarga (no se queda clavada en la fase 45).
                    java.util.regex.Matcher mbm = java.util.regex.Pattern.compile("(\\d+)\\s*M\\s*/\\s*(\\d+)\\s*M").matcher(text);
                    if (mbm.find()) {
                        try {
                            long down = Long.parseLong(mbm.group(1));
                            long tot = Long.parseLong(mbm.group(2));
                            if (tot > 0) {
                                int pct = (int) Math.min(100, Math.max(0, down * 100 / tot));
                                if (pct / 10 != lastMbQ) {
                                    lastMbQ = pct / 10;
                                    log("Progreso descarga " + down + "/" + tot + " MB (" + pct + "%)");
                                }
                                lastStage = stage;
                                progress.setIndeterminate(false);
                                progress.setVisibility(View.VISIBLE);
                                progress.setProgressCompat(pct, true);
                                long s2 = elapsed / 1000;
                                txtStatus.setText(T("Descargando: ", "Downloading: ") + down + "/" + tot + " MB (" + pct + "%) · "
                                        + String.format("%02d:%02d", s2 / 60, s2 % 60));
                                progressHandler.postDelayed(this, 3000);
                                return;
                            }
                        } catch (Exception ignored) { }
                    }
                    if (stage >= 100) {
                        // Solo vale un 100 posterior al toque: un 100 viejo es
                        // de un run anterior (no disparar finish fantasma).
                        long mm = 0;
                        try { mm = progressFile().lastModified(); } catch (Exception ignored) { }
                        if (mm > updateStart) {
                            // EXIT real del instalador: solo el 0 es exito verificado.
                            int ec = -1;
                            try {
                                java.util.regex.Matcher em = java.util.regex.Pattern.compile("EXIT:(\\d+)").matcher(text);
                                if (em.find()) ec = Integer.parseInt(em.group(1));
                            } catch (Exception ignored) { }
                            if (ec == 0) {
                                // Exito: la instalada ya es la ultima -> ocultar el
                                // boton YA (el re-chequeo posterior lo confirma por version).
                                updInstalled = updLatest;
                                updatePending = false;
                                getSharedPreferences("app", MODE_PRIVATE).edit()
                                        .putString("upd_installed", updInstalled)
                                        .putBoolean("upd_pending", false).apply();
                                refreshUpdateBtn();
                                log("Actualizacion instalada: reinicio el servidor para aplicarla.");
                                snack(T("Actualización terminada, reiniciando…",
                                        "Update finished, restarting…"));
                                finishUpdateFlow();
                            } else {
                                // Fallo (o sin codigo): no reiniciar a ciegas con un
                                // binario a medias; el boton sigue y se puede reintentar.
                                log("Update termino con EXIT:" + ec + ": no reinicio, revisa Termux y vuelve a pulsar Actualizar.");
                                snack(T("Terminó con errores (EXIT:" + ec + "), no se reinicia: reintenta",
                                        "Finished with errors (EXIT:" + ec + "), not restarting: retry"));
                                updating = false;
                                progress.setIndeterminate(true);
                                progress.setVisibility(View.GONE);
                                findViewById(R.id.btnUpdate).setEnabled(true);
                                getSharedPreferences("app", MODE_PRIVATE).edit()
                                        .putLong("upd_ts", 0).apply();
                            }
                            return;
                        }
                    }
                    if (stage != lastStage) {
                        lastStage = stage;
                        log("Progreso " + stage + "%: " + text);
                    }
                    if (stage < 100) {
                        progress.setIndeterminate(false);
                        progress.setVisibility(View.VISIBLE);
                        progress.setProgressCompat(Math.min(100, Math.max(0, stage)), true);
                        long s = elapsed / 1000;
                        txtStatus.setText(T("Actualizando: ", "Updating: ") + stage + "% " + text
                                + " · " + String.format("%02d:%02d", s / 60, s % 60));
                    } else {
                        // 100 anterior al toque: tratar como espera (el run
                        // nuevo aun no escribe su fin).
                        progress.setIndeterminate(true);
                        progress.setVisibility(View.VISIBLE);
                        long s = elapsed / 1000;
                        txtStatus.setText(T("Actualizando opencode… ", "Updating opencode… ")
                                + String.format("%02d:%02d", s / 60, s % 60));
                    }
                } else {
                    long s = elapsed / 1000;
                    // Sin marcador (script arrancando o sin permiso de archivos):
                    // spinner + tiempo siempre visibles; % cuando haya lectura.
                    progress.setIndeterminate(true);
                    progress.setVisibility(View.VISIBLE);
                    txtStatus.setText(T("Actualizando opencode… ", "Updating opencode… ")
                            + String.format("%02d:%02d", s / 60, s % 60));
                    // Sin marcador 16 min ≈ instalador agotado (timeout 900):
                    // cerrar por verdad de version (reinicia y reevalua).
                    if (elapsed > 16 * 60 * 1000) {
                        log("Actualizacion sin marcador 16min: reinicio y reevaluo por version.");
                        snack(T("Revisando version instalada…", "Checking installed version…"));
                        finishUpdateFlow();
                        return;
                    }
                }
                progressHandler.postDelayed(this, 3000);
            }
        };
        progressHandler.post(progressTick);
    }

    private void stopProgressPoll() {
        updating = false;
        if (progressTick != null) progressHandler.removeCallbacks(progressTick);
    }

    /** Cierre comun del update: para el poll, invalida cache y reinicia el
     *  servidor; el re-chequeo posterior decide por version real. */
    private void finishUpdateFlow() {
        updating = false;
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        findViewById(R.id.btnUpdate).setEnabled(true);
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putLong("upd_ts", 0).apply();
        findViewById(R.id.btnRestart).performClick();
    }

    @Override
    protected void onDestroy() {
        stopProgressPoll();
        try {
            if (termuxDownloadReceiver != null) unregisterReceiver(termuxDownloadReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void snack(String m) {
        if (splash != null && splash.getVisibility() == View.VISIBLE) {
            pendingSplashSnack = m;
            return;
        }
        Snackbar.make(web, m, Snackbar.LENGTH_SHORT).show();
    }

    private void log(String m) {
        txtLog.append("\n" + m + "\n");
    }

    private boolean isPortOpen() {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}

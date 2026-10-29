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
import android.webkit.WebResourceResponse;
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
import androidx.core.view.ViewCompat;
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
import java.io.ByteArrayInputStream;
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
    /** JS que se inyecta antes del bundle (cache de /provider y git de proyectos). */
    private String earlyScriptCache;
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
    private MaterialButton splashRetry;
    private Runnable pendingRetry;
    private int retryAttempt = 0;
    private boolean splashDone = false;
    private long splashStart = 0;
    private String pendingSplashSnack;
    private boolean fullscreen = false;
    /** Alto del teclado en px; 0 cuando esta oculto. */
    private int imeBottom = 0;
    /** Padding base del contenido: 0 en chat, 16dp en el panel. */
    private int chatPad = 0;
    private boolean urlLoading = false;
    /** Generacion de carga del documento v2: una nueva anula la anterior. */
    private int webLoadGen = 0;
    private boolean advancedOpen = false;
    private boolean serverOpen = false;
    /** Modo sin root: true hasta que la comprobacion de arranque diga lo contrario. */
    /** Descarga del APK de Termux (paso 1 sin root). */
    /** Avisos del agente: solo suenan si NO miras el chat. */
    private boolean appForeground = false;
    private static volatile boolean uiForeground = false;
    private static volatile boolean uiFullscreen = false;
    private int agentNotifId = 100;
    private String pendingSessionId;
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
    private ActivityResultLauncher<String> storagePermLauncher;
    /** Compartir en espera de que el usuario conceda acceso a archivos. */
    private PendingShare pendingStorageShare;
    private String pendingStorageInstruction;
    private String pendingStorageSessionId;
    /** Ya se pidio el permiso legacy en este arranque (si lo deniega, se
     *  comparte igual desde la carpeta privada de la app). */
    private boolean storageShareAsked = false;
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
        // El teclado no redimensiona la ventana (edge-to-edge), asi que el
        // WebView se queda a altura completa y el compositor acaba detras del
        // IME hasta que se cierra y abre otra vez. Se resta el alto del teclado
        // al contenido y se avisa a la SPA para que recolque en el acto.
        ViewCompat.setOnApplyWindowInsetsListener(boxContent, (v, insets) -> {
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            if (ime != imeBottom) {
                imeBottom = ime;
                applyChatPadding();
                v.postDelayed(() -> {
                    if (web == null) return;
                    web.requestLayout();
                    try {
                        web.evaluateJavascript(
                                "(function(){try{window.dispatchEvent(new Event('resize'));"
                                        + "if(window.visualViewport){window.visualViewport.dispatchEvent(new Event('resize'));}"
                                        + "}catch(e){}})()", null);
                    } catch (Exception ignored) {
                    }
                }, 80);
            }
            return insets;
        });
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
        splashRetry = findViewById(R.id.splashRetry);
        splashRetry.setOnClickListener(v -> retryStartupNow());
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
            // chat este dentro. Si el servidor no llega, se queda en el splash
            // con Reintentar y reintentos automaticos (nunca el panel).
            splashHandler.postDelayed(() -> {
                if (!splashDone && !serverOpen) {
                    startupFailed(T("El agente no respondio. Seguimos intentando...",
                            "The agent did not answer. Still trying..."));
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
                if (fullscreen && !chatOnly()) setFullscreen(false);
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
        pendingSessionId = getIntent().getStringExtra("session_id");
        if (pendingShare == null) {
            autoOpenIfRunning();
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(this::showShareDialog, 700);
        }

        applyLang();

        // Si el movil tiene root, se pide su permiso de su al abrir (Magisk/
        // KernelSU muestran su dialogo normal). Denegado no se vuelve a pedir.
        if (RootShell.available() && RootShell.enabled(this)) {
            new Thread(() -> RootShell.request(this), "su-request").start();
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // Sin zoom por pellizco: en movil movia la vista y descolocaba el chat.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
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
        // El interruptor de permisos de la web (Settings > General) manda al nuestro global.
        web.addJavascriptInterface(new ConfigBridge(), "ocConfig");
        // Depuracion remota del WebView solo en APK debuggable (nunca en release).
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web.setWebViewClient(new WebViewClient() {
            /**
             * La SPA carga su bundle al final del body. Para que el cache de
             * /provider y el arreglo de git existan antes, se sirve el HTML
             * con un <script src="/oc-early.js"> extra. La CSP permite
             * scripts same-origin, asi que no hace falta 'unsafe-inline'.
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                try {
                    Uri u = request.getUrl();
                    String path = u.getPath();
                    if (path == null) return null;
                    if (BuildConfig.V2) {
                        // v2: la UI no trae shims de v1, pero si necesita el
                        // viewport bloqueado para que el pellizco no haga zoom.
                        if ("/sw.js".equals(path)) {
                            // Sin fetch handler: el service worker de v2 cachea
                            // el HTML sin el viewport bloqueado. Uno vacio deja
                            // pasar todo por shouldInterceptRequest.
                            byte[] js = ("self.addEventListener('install',function(e){self.skipWaiting()});"
                                    + "self.addEventListener('activate',function(e){"
                                    + "e.waitUntil(self.clients.claim())});")
                                    .getBytes(StandardCharsets.UTF_8);
                            Map<String, String> h = new HashMap<>();
                            h.put("Cache-Control", "no-store");
                            return new WebResourceResponse("application/javascript", "utf-8",
                                    200, "OK", h, new ByteArrayInputStream(js));
                        }
                        String host = u.getHost();
                        if (HOST.equals(host) || "localhost".equalsIgnoreCase(host)) {
                            if (("/api/fs/list".equals(path) || "/api/fs/find".equals(path))
                                    && "GET".equalsIgnoreCase(request.getMethod())) {
                                WebResourceResponse filtered = filterFsResponse(u);
                                if (filtered != null) return filtered;
                                return null;
                            }
                        }
                        if (!request.isForMainFrame()) return null;
                        if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                        if (!(HOST.equals(host) || "localhost".equalsIgnoreCase(host))) return null;
                        String last = path.substring(path.lastIndexOf('/') + 1);
                        if (last.contains(".")) return null;
                        String q = u.getEncodedQuery();
                        IndexDoc doc = fetchIndexDoc(path + (q == null ? "" : "?" + q));
                        if (doc == null) return null;
                        return htmlResponse(lockViewport(doc.html, doc.csp), doc.csp);
                    }
                    if ("/oc-early.js".equals(path)) {
                        byte[] js = earlyScript().getBytes(StandardCharsets.UTF_8);
                        Map<String, String> h = new HashMap<>();
                        h.put("Cache-Control", "no-store");
                        return new WebResourceResponse("application/javascript", "utf-8",
                                200, "OK", h, new ByteArrayInputStream(js));
                    }
                    if (!request.isForMainFrame()) return null;
                    if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                    String host = u.getHost();
                    if (!(HOST.equals(host) || "localhost".equalsIgnoreCase(host))) return null;
                    // Solo documentos HTML: "/" y rutas SPA sin extension.
                    String last = path.substring(path.lastIndexOf('/') + 1);
                    if (last.contains(".")) return null;
                    String q = u.getEncodedQuery();
                    IndexDoc doc = fetchIndexDoc(path + (q == null ? "" : "?" + q));
                    if (doc == null) return null;
                    String html = injectEarlyScript(doc.html);
                    return htmlResponse(html, doc.csp);
                } catch (Exception e) {
                    log("early intercept: " + e);
                    return null;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (BuildConfig.V2 && url != null && url.startsWith(URL)
                        && !url.contains("auth_token=")) {
                    // Un reload de la web pierde el token y el service worker
                    // sirve el HTML original (sin viewport bloqueado). Se
                    // recarga con token y el documento de memoria.
                    view.stopLoading();
                    webLoad(url);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                urlLoading = false;
                if (BuildConfig.V2) {
                    ensureV2Doc(url);
                    watchUiReady();
                    bridgeNotifications();
                    return;
                }
                watchUiReady();
                injectEdgeCss();
                flattenViewportFrame();
                scheduleFootbar();
                bridgeNotifications();
                redirectHelpToIssues();
                rebrandDesktopToMobile();
                shimAutoAccept();
                hideShortcutsSettings();
            shimUsageTap();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (BuildConfig.V2) return;
                // La web es SPA: al navegar (lista -> chat) no hay recarga.
                // Los demas shims viven en observadores; aqui solo hay que
                // reponer la barra del sidebar y el CSS (idempotente).
                injectEdgeCss();
                scheduleFootbar();
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
                txtStatus.setText(T("Servidor no responde: reintentando",
                        "Server not answering: retrying"));
                log("WebView error (sin blanco): "
                        + (error == null ? "?" : String.valueOf(error.getDescription())));
                if (chatOnly()) {
                    if (!splashDone) {
                        startupFailed(T("Sin conexion con el agente. Reintentando...",
                                "No connection to the agent. Retrying..."));
                    } else {
                        snack(T("Sin conexion con el agente", "No connection to the agent"));
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!isFinishing() && !urlLoading) webLoad(URL);
                        }, 2500);
                    }
                } else {
                    snack(T("Servidor no responde", "Server not answering"));
                    hideSplash();
                }
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
        storagePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    // Concedido: Descargas publicas. Denegado: se comparte
                    // igual desde la carpeta externa privada de la app.
                    if (pendingStorageShare != null) {
                        PendingShare share = pendingStorageShare;
                        String instruction = pendingStorageInstruction;
                        String sid = pendingStorageSessionId;
                        pendingStorageShare = null;
                        pendingStorageInstruction = null;
                        pendingStorageSessionId = null;
                        startShareSend(share, instruction, sid);
                    } else if (!granted) {
                        snack(T("Sin acceso a Descargas: se usa la carpeta privada",
                                "No Downloads access: using the private folder"));
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
        // Android 8-10: sin READ/WRITE_EXTERNAL_STORAGE el agente no puede
        // leer /sdcard y el envio a Descargas falla. En Android 11+ lo cubre
        // "todos los archivos"; en Android 10 se usa la carpeta privada si
        // el sistema no concede almacenamiento legacy.
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29
                && ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !getSharedPreferences("app", MODE_PRIVATE)
                        .getBoolean("storage_perm_asked", false)) {
            getSharedPreferences("app", MODE_PRIVATE).edit()
                    .putBoolean("storage_perm_asked", true).apply();
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
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
        MaterialButton btnRestart = findViewById(R.id.btnRestart);
        btnRestart.setOnClickListener(v -> restartServer());
        MaterialButton btnStop = findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> stopServer());



        log(T("Pulsa Iniciar y abrir. Si el servidor ya corre, entras directo al chat.",
                "Press Start and open. If the server is already running, you go straight to the chat."));
        // Next: no existe panel. Se oculta todo lo que no sea el chat; el
        // arranque vive en el splash y los fallos se reintentan ahi.
        if (chatOnly()) applyChatOnlyChrome();
        // El vig��a sobrevive solo hasta reiniciar: rearmarlo siempre (si esta OFF no hace nada).
        ServerWatch.arm(this);
        // Chequeo diario de updates aunque la app este cerrada (solo avisa).
        if (!BuildConfig.V2_LAB) ServerWatch.armUpdateCheck(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String sid = intent == null ? null : intent.getStringExtra("session_id");
        if (sid != null && !sid.isEmpty()) {
            pendingSessionId = sid;
            if (serverOpen && web != null && web.getUrl() != null) {
                pendingSessionId = null;
                setFullscreen(true);
                webLoad(shareSessionUrl(sid));
            } else {
                enterChatBlocking();
            }
        }
        PendingShare share = parseShareIntent(intent);
        if (share != null) {
            pendingShare = share;
            showShareDialog();
        }
    }

    @Override
    protected void onPause() {
        appForeground = false;
        uiForeground = false;
        saveFabPos();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appForeground = true;
        uiForeground = true;
        web.onResume();
        web.resumeTimers();
        checkStatus(false);
        if (!BuildConfig.V2_LAB) {
            // v2 lleva opencode dentro del APK: el updater del runtime no aplica.
            if (!BuildConfig.V2) checkForUpdate(false);
            checkLauncherUpdate(false);
        }
        // Al volver de Ajustes/Termux se recalculan los pasos: la tira de
        // estados y el oscurecido de Iniciar quedan al dia solos.
        // Si el usuario fue a Ajustes a dar el permiso de archivos, el arranque
        // que se quedo esperando continua solo.
        if (filesAskPending) {
            filesAskPending = false;
            if (!hasAllFilesAccess()) {
                // Denegado: el splash explica y ofrece reintentar.
                pendingStartAfterPerms = false;
                starting = false;
                startupFailed(T("Falta 'todos los archivos' para arrancar el agente",
                        "All files access is needed to start the agent"));
            }
        }
        if (pendingStartAfterPerms && hasAllFilesAccess()) onAllFilesAccessReady();
        // Si el usuario fue a Ajustes a dar acceso a archivos, el envio que
        // quedo esperando continua solo al volver.
        if (pendingStorageShare != null && hasShareStorage()) {
            PendingShare share = pendingStorageShare;
            String instruction = pendingStorageInstruction;
            String sid = pendingStorageSessionId;
            pendingStorageShare = null;
            pendingStorageInstruction = null;
            pendingStorageSessionId = null;
            startShareSend(share, instruction, sid);
        }
        // Runtime local: si el servidor no esta, se levanta solo al abrir la app
        // y se entra al chat en cuanto responde.
        if (LocalRuntime.available(this) && pendingShare == null && !serverOpen && !starting
                && !isPortOpen() && hasAllFilesAccess()) {
            autoOpenIfRunning();
        }
    }

    /** Paso 1 automatico: baja de GitHub el APK de Termux para esta ABI y abre el instalador. */
    /** Vigila la descarga: barra con % real, reanuda tras reinicios y avisa fallos. */
    /** Devuelve true si la descarga termino (bien o mal). */
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
    /** Guia de descarga segun el chip del movil: cada APK de GitHub vale solo para su ABI. */
    /** Paso 2 de la guia: copia el setup (instala opencode si falta) y abre Termux. */
    /** Lleva al permiso de overlay DE TERMUX (sesiones visibles por intent).
     *  OJO: Termux sideloaded trae "ajustes restringidos" y el overlay no se deja
     *  activar hasta liberarlo en su info (menu tres puntos). Se guia en orden. */
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
    /** Pide a Android no matar a Termux (reemplazo sin root de Estabilizar). */
    /** Interruptor PROPIO de permisos automaticos. Se aplica en el servidor
     *  escribiendo la config global de opencode: {"*":"allow"} o sin regla. */
    private boolean autoPerm() {
        return getSharedPreferences("app", MODE_PRIVATE).getBoolean("auto_perm", true);
    }

    /** Aplica el auto-aceptar en el servidor (config global, en vivo). */
    private void applyAutoPermissions(boolean allow) {
        try {
            JSONObject body = new JSONObject();
            if (allow) {
                body.put("permission", "allow");
            } else {
                body.put("permission", JSONObject.NULL);
            }
            httpJson("PATCH", "/global/config", body, 20000);
            final String msg = T("Permisos auto aplicados: ", "Auto-approve applied: ")
                    + (allow ? "allow" : "default");
            runOnUiThread(() -> log(msg));
        } catch (Exception e) {
            final String msg = T("No se pudo aplicar permisos auto: ",
                    "Could not apply auto-approve: ") + e.getMessage();
            runOnUiThread(() -> log(msg));
        }
    }
    /** Cambia el interruptor global: guarda, avisa y reinicia si el servidor corre. */
    private void setAutoPerm(boolean now) {
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("auto_perm", now).apply();
        log(T("Interruptor permisos-auto: ", "Auto-approve switch: ") + (now ? "SÍ" : "NO"));
        snack(now ? T("Permisos automáticos activados", "Auto-approve enabled")
                : T("Permisos automáticos desactivados", "Auto-approve disabled"));
        if (serverOpen) {
            log(T("Reinicio para aplicar el cambio...", "Restarting to apply the change..."));
            restartServer();
        } else {
            log(T("Se aplicará al arrancar el servidor.", "Will apply on server start."));
        }
    }
    private String stopRuntime() {
        LocalServerService.stop(this);
        LocalRuntime.stop();
        return "runtime local detenido";
    }
    /** Reinicia el runtime embebido: detener y volver a arrancar. */
    private void restartServer() {
        setBusy(true);
        log("Reiniciando servidor...");
        new Thread(() -> {
            String out = stopRuntime();
            boolean gone = waitForPortGone(8000);
            runOnUiThread(() -> {
                setBusy(false);
                log("$ reiniciar (fase 1 detener):\n" + out
                        + (gone ? "\nDetenido, arrancando..." : "\nAviso: puerto seguia abierto, arranco igual..."));
                startServerSequence();
            });
        }).start();
    }

    /** Detiene el runtime embebido. */
    private void stopServer() {
        setBusy(true);
        log("Deteniendo servidor...");
        new Thread(() -> {
            String out = stopRuntime();
            boolean open = !waitForPortGone(6000);
            runOnUiThread(() -> {
                setBusy(false);
                serverOpen = open;
                refreshActionButtons();
                setDot(open);
                txtStatus.setText(open ? T("Servidor abierto (sigue vivo)", "Server open (still alive)")
                        : T("Servidor detenido", "Server stopped"));
                log("$ detener\n" + out + (open ? "\nSIGUE ABIERTO" : "\nOK detenido"));
                snack(open ? T("Sigue abierto, mira el registro", "Still open, check the log")
                        : T("Servidor detenido", "Server stopped"));
                if (!open && !chatOnly()) setFullscreen(false);
                if (!open) {
                    getSharedPreferences("app", MODE_PRIVATE).edit()
                            .putBoolean(ServerWatch.PREF_WD_HOLD, true).apply();
                    log(T("Vigía en pausa hasta el próximo arranque.",
                            "Watchdog paused until next start."));
                }
            });
        }).start();
    }

    /** Updater oficial del runtime: descarga, verifica SHA-256, instala y reinicia. */
    private void startRuntimeUpdate() {
        // v2 trae el runtime dentro del APK: no se actualiza por separado.
        if (BuildConfig.V2) return;
        if (updLatest.isEmpty()) return;
        if (!hasAllFilesAccess()) {
            snack(T("Activa 'todos los archivos' y repite", "Enable 'All files' and retry"));
            openAllFilesSettings();
            return;
        }
        try {
            Intent i = new Intent(this, RuntimeUpdateService.class)
                    .setAction(RuntimeUpdateService.ACTION_INSTALL)
                    .putExtra(RuntimeUpdateService.EXTRA_VERSION, updLatest);
            startForegroundService(i);
            snack(T("Actualizando opencode…", "Updating opencode…"));
        } catch (Exception e) {
            log("No se pudo iniciar la actualización: " + e.getMessage());
            snack(T("No se pudo iniciar la actualización", "Could not start the update"));
        }
    }

    /** Descarga e instala la nueva version del launcher. */
    private void installAppUpdateNow() {
        if (BuildConfig.V2_LAB) return;
        if (!appUpdatePending || appUpdUrl.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            log("Permite 'instalar apps desconocidas' para esta app y vuelve a pulsar Descargar.");
            snack(T("Permite instalar apps y repite", "Allow installing apps and retry"));
            openUnknownSourcesSettings();
            return;
        }
        downloadAppUpdate(appUpdUrl, appUpdTag);
    }

    private void startLocalServer() {
        new Thread(() -> {
            StringBuilder t = new StringBuilder();
            try {
                t.append(T("0) runtime local (proot)...\n", "0) local runtime (proot)...\n"));
                LocalServerService.start(this);
                t.append(T("Servicio lanzado; esperando el puerto ", "Service started; waiting for port "))
                        .append(PORT).append("...\n");
                boolean open = waitForPort(20000);
                boolean httpOk = false;
                if (open) {
                    t.append(T("Puerto abierto; esperando el primer HTTP 200...\n",
                            "Port open; waiting for the first HTTP 200...\n"));
                    httpOk = waitForHttp(45000);
                    if (httpOk && !BuildConfig.V2 && !BuildConfig.V2_LAB) applyAutoPermissions(autoPerm());
                }
                final boolean done = open;
                final boolean httpOkF = httpOk;
                String transcript = t.toString();
                runOnUiThread(() -> {
                    setBusy(false);
                    starting = false;
                    serverOpen = done;
                    refreshActionButtons();
                    setDot(done);
                    txtStatus.setText(done ? T("Servidor abierto", "Server open")
                            : T("Servidor sigue cerrado", "Server still closed"));
                    log(transcript + (done
                            ? T("OK: runtime local en ", "OK: local runtime at ") + URL
                            : T("FALLO: revisa logcat con etiqueta OpenCodeLocal",
                                    "FAILED: check logcat tag OpenCodeLocal")));
                    if (done) {
                        getSharedPreferences("app", MODE_PRIVATE).edit()
                                .putBoolean("onboarding_done", true)
                                .putBoolean("wizard_done", true)
                                .putBoolean("setup_ok", true).apply();
                    } else {
                        // Si no arranco, el splash muestra Reintentar y la
                        // app reintenta sola; el panel ya no existe en Next.
                        startupFailed(T("No arranco. Reintentando...",
                                "Did not start. Retrying..."));
                    }
                    snack(done ? T("Servidor abierto", "Server open")
                            : T("No arranco: mira el registro", "Did not start: check the log"));
                    if (done && !shareBlocksAutoOpen()) {
                        if (httpOkF) {
                            setSplashStatus(T("Abriendo el chat...", "Opening the chat..."));
                            enterChatLocked(() -> {
                                webLoad(takeChatUrl());
                                setFullscreen(true);
                            });
                        } else {
                            // Puerto abierto pero HTTP lento: se sigue esperando
                            // dentro del splash, sin pedirle nada al usuario.
                            setSplashStatus(T("Abriendo el chat...", "Opening the chat..."));
                            new Handler(Looper.getMainLooper())
                                    .postDelayed(this::enterChatBlocking, 1500);
                        }
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    starting = false;
                    refreshActionButtons();
                    setDot(false);
                    txtStatus.setText(T("Runtime local fallo", "Local runtime failed"));
                    log(t.toString() + "ERROR: " + e + "\nRevisa logcat con etiqueta OpenCodeLocal.");
                    snack(T("Runtime local fallo, mira el registro", "Local runtime failed, check the log"));
                    startupFailed(T("Runtime local fallo. Reintentando...",
                            "Local runtime failed. Retrying..."));
                });
            }
        }).start();
    }

    /** Arranque SIN root: reparar config + serve, todo por intents RUN_COMMAND
     *  (se ejecutan dentro de Termux; exige allow-external-apps). */
    /** Secuencia: script -> FGS root -> startservice root -> directo -> demonio -> diagnostico. */
    private void startServerSequence() {
        openGen++;
        clearWatchHold();
        setBusy(true);
        if (!LocalRuntime.available(this)) {
            setBusy(false);
            snack(T("Runtime local no disponible", "Local runtime not available"));
            return;
        }
        // El runtime local necesita leer/escribir archivos del movil: sin el
        // permiso de archivos se pide ahora y el arranque sigue al volver.
        if (!hasAllFilesAccess()) {
            pendingStartAfterPerms = true;
            if (filesPermTried) {
                // Ya se pidio en esta sesion: se avisa y se reintenta solo.
                pendingStartAfterPerms = false;
                starting = false;
                startupFailed(T("Falta 'todos los archivos' para arrancar el agente",
                        "All files access is needed to start the agent"));
            } else {
                askAllFilesAccess();
            }
            return;
        }
        startLocalServer();
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
                .setText(shareLocationLabel());

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
        if (!share.uris.isEmpty() && !hasShareStorage()) {
            pendingStorageShare = share;
            pendingStorageInstruction = instruction;
            pendingStorageSessionId = targetSessionId;
            if (Build.VERSION.SDK_INT >= 30) {
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
            if (!storageShareAsked) {
                // Android 8-10: se pide una vez; si lo deniega, el envio
                // continua con la carpeta privada de la app.
                storageShareAsked = true;
                storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                return;
            }
        }
        pendingShare = null;
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
        File dir = shareDir();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
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

    /** Puente para el interruptor de permisos de la web. */
    private class ConfigBridge {
        @JavascriptInterface
        public String autoAccept() {
            return autoPerm() ? "1" : "0";
        }

        @JavascriptInterface
        public void toggleAutoAccept() {
            runOnUiThread(() -> setAutoPerm(!autoPerm()));
        }

        @JavascriptInterface
        public void debug(String msg) {
            Log.d("OpenCode", "auto-shim-js: " + msg);
        }

        /** Ruta canonica de HOME, para migrar rutas antiguas de la UI. */
        @JavascriptInterface
        public String homeDir() {
            return LocalRuntime.homeDir(MainActivity.this).getAbsolutePath();
        }

        /**
         * Prepara el repo git de un proyecto antes de que el servidor lo vea.
         * Devuelve la raiz del repo (o el propio directorio si no se pudo).
         */
        @JavascriptInterface
        public String ensureProjectGit(String dir) {
            try {
                return LocalRuntime.ensureProjectGit(MainActivity.this, dir);
            } catch (Exception e) {
                Log.w("OpenCode", "ensureProjectGit: " + e);
                return dir == null ? "" : dir;
            }
        }

        /** La SPA ya pinto contenido real: se puede retirar el splash. */
        @JavascriptInterface
        public void uiReady() {
            runOnUiThread(MainActivity.this::hideSplash);
        }
    }

    /**
     * onPageFinished llega con el HTML vacio: la SPA tarda varios segundos en
     * hidratar la instancia. Si se retira el splash ahi se ve un negro largo
     * (el "congelado" al abrir sesion). Se espera a que #root tenga contenido.
     */
    private void watchUiReady() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "if(window.__ocReadyObs)return 'ya';"
                    + "var root=document.getElementById('root');"
                    + "if(!root)return 'no-root';"
                    + "var done=false;"
                    + "function check(){if(done)return;"
                    + "var n=root.querySelectorAll('*').length;"
                    + "if(n<40)return;"
                    + "var sig=root.querySelector('textarea,[contenteditable=\"true\"],nav,[data-slot]');"
                    + "if(!sig&&n<120)return;"
                    + "done=true;"
                    + "if(window.__ocReadyObs)window.__ocReadyObs.disconnect();"
                    + "try{ocConfig.uiReady();}catch(e){}}"
                    + "window.__ocReadyObs=new MutationObserver(function(){"
                    + "if(window.__ocReadyT)return;"
                    + "window.__ocReadyT=setTimeout(function(){"
                    + "window.__ocReadyT=null;check();},120);});"
                    + "window.__ocReadyObs.observe(root,{childList:true,subtree:true});"
                    + "setTimeout(check,700);"
                    + "return 'ready-watch';}catch(e){return 'ERR:'+e;}})()",
                    v -> Log.d("OpenCode", "ui-ready: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "ui-ready fallo: " + e.getMessage());
            hideSplash();
        }
        // Red de seguridad: nunca dejar el splash eterno si la SPA no arranca.
        progressHandler.removeCallbacks(splashFallback);
        progressHandler.postDelayed(splashFallback, 15000);
    }

    private final Runnable splashFallback = this::hideSplash;

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

    /** El servicio consulta esto para no duplicar avisos si el chat esta a la vista. */
    static boolean isAgentUiVisible() {
        return uiForeground && uiFullscreen;
    }

    /** Aviso de agente: solo si NO estas mirando el chat (si no, ya lo ves). */
    private void notifyAgentDone(String title, String body) {
        try {
            // En v2 el servicio mantiene el SSE y avisa el solo; la web no
            // debe duplicarlo cuando su conexion sigue viva.
            if (BuildConfig.V2 && AgentEventWatcher.isConnected()) return;
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
        // La UI de v2 ya coloca Ajustes/Ayuda al final del sidebar.
        if (BuildConfig.V2) return;
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "var old=document.getElementById('oc-footbar');"
                    + "var labels=['Ajustes','Settings','Ayuda','Help'];"
                    // Recorrer nodos de texto evita el textContent de cada
                    // elemento (coste O(n^2) en paginas con muchos mensajes).
                    + "function scan(){var out=[],w=document.createTreeWalker("
                    + "document.body,NodeFilter.SHOW_TEXT,null),n;"
                    + "while((n=w.nextNode())&&out.length<12){"
                    + "var t=(n.nodeValue||'').trim();"
                    + "if(labels.indexOf(t)<0)continue;"
                    + "var e=n.parentElement;if(!e)continue;"
                    + "if(e.closest&&e.closest('#oc-footbar'))continue;"
                    + "var r=(e.closest('button')||e.closest('a')||e.closest('[role=\"button\"]')||e);"
                    + "var seen=false;"
                    + "for(var q=0;q<out.length;q++)if(out[q][1]===r){seen=true;break;}"
                    + "if(!seen)out.push([t,r]);}"
                    + "return out;}"
                    + "function hide(list){for(var i=0;i<list.length;i++)"
                    + "list[i][1].style.setProperty('display','none','important');}"
                    + "if(old){hide(scan());return 'done';}"
                    + "var all=scan();"
                    + "if(!all.length)return 'none';"
                    // La SPA pinta dos copias de Settings/Help: a la barra va
                    // solo la primera de cada etiqueta y el resto se oculta.
                    + "var list=[],dups=[];"
                    + "for(var i=0;i<all.length;i++){var lab=all[i][0],seen=false;"
                    + "for(var j=0;j<list.length;j++)if(list[j][0]===lab){seen=true;break;}"
                    + "if(seen)dups.push(all[i]);else list.push(all[i]);}"
                    + "var sc=list[0][1];while(sc&&sc!==document.body){"
                    + "if(sc.scrollHeight>sc.clientHeight+50)break;sc=sc.parentElement;}"
                    + "if(!sc||sc===document.body)return 'noscroll';"
                    // El viewport del scroll es transparente; el color real
                    // esta en un ancestro. Sin fondo la barra se solapa con
                    // las ultimas sesiones y se leen las dos capas.
                    + "function solid(c){if(!c||c==='transparent')return false;"
                    + "var m=/^rgba?\\(([^)]+)\\)$/.exec(c);if(!m)return false;"
                    + "var p=m[1].split(',');"
                    + "return p.length<4||parseFloat(p[3])>=0.95;}"
                    + "var bg='rgb(22,22,22)',bx=sc,panel=sc.parentElement||sc;"
                    + "while(bx&&bx!==document.body){var b=getComputedStyle(bx).backgroundColor;"
                    + "if(solid(b)){bg=b;panel=bx;break;}"
                    + "bx=bx.parentElement;}"
                    // La lista de sesiones es virtualizada y monta despues: si
                    // la barra va dentro del scroll acaba entre las filas. Se
                    // ancla al panel del sidebar, fuera del area que scrollea.
                    + "var bar=document.createElement('div');bar.id='oc-footbar';"
                    + "bar.style.cssText='position:absolute;left:0;right:0;bottom:0;z-index:5;"
                    + "display:flex;gap:8px;padding:10px 12px;background:'+bg+';';"
                    + "for(var i=0;i<list.length;i++){var r=list[i][1];"
                    + "r.style.removeProperty('display');"
                    + "r.style.setProperty('flex','1','important');"
                    + "r.style.setProperty('justify-content','center','important');"
                    + "r.style.setProperty('color','#ffffff','important');"
                    + "var xs=r.querySelectorAll('*');"
                    + "for(var j=0;j<xs.length;j++){var t=(xs[j].textContent||'').trim();"
                    + "if(labels.indexOf(t)>=0){xs[j].style.setProperty('font-size','17px','important');"
                    + "xs[j].style.setProperty('font-weight','600','important');"
                    + "xs[j].style.setProperty('color','#ffffff','important');}}"
                    + "bar.appendChild(r);}"
                    + "if(getComputedStyle(panel).position==='static')"
                    + "panel.style.setProperty('position','relative','important');"
                    + "panel.appendChild(bar);"
                    + "try{var bh=bar.offsetHeight||64;"
                    + "sc.style.setProperty('padding-bottom',(bh+8)+'px','important');}"
                    + "catch(e2){}"
                    + "hide(dups);"
                    + "return list.length+'barok';"
                    + "}catch(err){return 'FERR';}})()",
                    v -> {
                        Log.d("OpenCode", "foot-fix: " + v);
                        // La lista hidrata tarde: reintentar acotado si aun no existe.
                        if (v != null && (v.contains("none") || v.contains("FERR")) && footTries < 3) {
                            footTries++;
                            progressHandler.postDelayed(this::boostSidebarFooter, 1200);
                        } else if (v != null && (v.contains("ok") || v.contains("done"))) {
                            footTries = 0;
                        }
                    });
        } catch (Exception e) {
            Log.d("OpenCode", "foot-fix fallo: " + e.getMessage());
        }
    }

    /** La barra muere con el sidebar en cada navegacion SPA: una sola pasada
     *  acotada y con retardo, en vez de encadenar inyecciones completas. */
    private final Runnable footbarTask = this::boostSidebarFooter;

    private void scheduleFootbar() {
        progressHandler.removeCallbacks(footbarTask);
        progressHandler.postDelayed(footbarTask, 220);
    }

    private String basicAuth() {
        String raw = "opencode:" + LocalRuntime.serverPassword(this);
        return "Basic " + Base64.getEncoder().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * v2: la UI lee ?auth_token=<base64url(opencode:password)> al arrancar,
     * guarda el servidor en localStorage y borra el parametro de la URL.
     */
    private String authToken() {
        String raw = "opencode:" + LocalRuntime.serverPassword(this);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
    }

    private String withAuthToken(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (!(HOST.equals(host) || "localhost".equalsIgnoreCase(host))) return url;
            if (u.getQueryParameter("auth_token") != null) return url;
            int hash = url.indexOf('#');
            String base = hash >= 0 ? url.substring(0, hash) : url;
            String frag = hash >= 0 ? url.substring(hash) : "";
            return base + (base.contains("?") ? "&" : "?")
                    + "auth_token=" + authToken() + frag;
        } catch (Exception e) {
            return url;
        }
    }

    private String earlyScript() {
        if (earlyScriptCache != null) return earlyScriptCache;
        try (InputStream in = getAssets().open("oc-early.js")) {
            earlyScriptCache = readAll(in);
        } catch (Exception e) {
            log("oc-early.js no disponible: " + e);
            earlyScriptCache = "";
        }
        return earlyScriptCache;
    }

    private static final class IndexDoc {
        final String html;
        final String csp;

        IndexDoc(String html, String csp) {
            this.html = html;
            this.csp = csp;
        }
    }

    /** Descarga el HTML del servidor local con la misma auth que el WebView. */
    private IndexDoc fetchIndexDoc(String pathAndQuery) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL + pathAndQuery).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2500);
            c.setReadTimeout(4000);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", basicAuth());
            c.setRequestProperty("Accept", "text/html");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "opencode-android");
            int code = c.getResponseCode();
            if (code != 200) return null;
            String type = c.getContentType();
            if (type != null && !type.toLowerCase(Locale.ROOT).contains("text/html")) {
                return null;
            }
            return new IndexDoc(readAll(c.getInputStream()),
                    c.getHeaderField("Content-Security-Policy"));
        } catch (Exception e) {
            log("early html: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Filtra /api/fs/list y /api/fs/find para que el selector de proyectos no
     * muestre carpetas internas del runtime (.cache, .config, bin, lib...).
     * Tambien añade la carpeta de proyectos compartida, que el servidor no
     * lista porque es un enlace simbolico.
     */
    private WebResourceResponse filterFsResponse(Uri u) {
        HttpURLConnection c = null;
        try {
            String path = u.getPath();
            if (!"/api/fs/list".equals(path) && !"/api/fs/find".equals(path)) return null;
            String q = u.getEncodedQuery();
            URL url = new URL(URL + path + (q == null ? "" : "?" + q));
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2500);
            c.setReadTimeout(4000);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", basicAuth());
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "opencode-android");
            int code = c.getResponseCode();
            if (code != 200) return null;
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("application/json")) {
                return null;
            }
            String body = readAll(c.getInputStream());
            JSONObject obj = new JSONObject(body);
            JSONArray data = obj.optJSONArray("data");
            if (data == null) return null;

            String home = canonicalPath(LocalRuntime.homeDir(this).getAbsolutePath());
            boolean isFind = "/api/fs/find".equals(path);
            String current = u.getQueryParameter("path");
            if (current == null || current.isEmpty()) {
                current = u.getQueryParameter("location[directory]");
            }
            current = canonicalPath(current);
            if (current.isEmpty()) current = home;

            JSONArray out = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < data.length(); i++) {
                JSONObject e = data.optJSONObject(i);
                if (e == null) {
                    out.put(data.get(i));
                    continue;
                }
                if (!"directory".equals(e.optString("type"))) {
                    out.put(e);
                    continue;
                }
                String p = e.optString("path", "");
                String name = baseName(p);
                boolean hide = name.startsWith(".");
                if (!hide && isInternalDir(name)) {
                    hide = isFind
                            ? trimSlash(p).indexOf('/') < 0
                            : current.equals(home);
                }
                if (hide) {
                    changed = true;
                    continue;
                }
                out.put(e);
            }

            if (!isFind && current.equals(home)
                    && LocalRuntime.projectsDir(this).isDirectory()
                    && new File(LocalRuntime.homeDir(this),
                            LocalRuntime.PROJECTS_DIR_NAME).isDirectory()) {
                boolean present = false;
                for (int i = 0; i < out.length(); i++) {
                    JSONObject e = out.optJSONObject(i);
                    if (e != null && LocalRuntime.PROJECTS_DIR_NAME.equals(
                            baseName(e.optString("path", "")))) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    JSONObject e = new JSONObject();
                    e.put("path", LocalRuntime.PROJECTS_DIR_NAME + "/");
                    e.put("type", "directory");
                    out.put(e);
                    changed = true;
                }
            }

            if (!changed) return null;
            obj.put("data", out);
            // Sin cabecera Content-Type: WebView la duplicaria con el mimeType
            // ("application/json, application/json") y el SDK de v2 rechaza
            // esa respuesta. El mimeType del constructor ya la establece.
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/json", "utf-8", 200, "OK", h,
                    new ByteArrayInputStream(obj.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log("fs filter: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String trimSlash(String p) {
        if (p == null) return "";
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String canonicalPath(String p) {
        String s = trimSlash(p);
        if (s.startsWith("/data/user/0/")) {
            s = "/data/data/" + s.substring("/data/user/0/".length());
        }
        return s;
    }

    private static String baseName(String p) {
        String s = trimSlash(p);
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static boolean isInternalDir(String name) {
        return "bin".equals(name) || "lib".equals(name)
                || "usr".equals(name) || "tmp".equals(name);
    }

    private String injectEarlyScript(String html) {
        String tag = "<script src=\"/oc-early.js\"></script>";
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + tag + html.substring(at);
        }
        return tag + html;
    }

    /** Respuesta HTML del servidor local sin cache y con la CSP original. */
    private WebResourceResponse htmlResponse(String html, String csp) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        if (csp != null && !csp.isEmpty()) {
            h.put("Content-Security-Policy", csp);
        }
        return new WebResourceResponse("text/html", "utf-8",
                200, "OK", h,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    /** Bloquea el pellizco desde el documento: la meta viewport manda aunque
     *  el WebView ignore setSupportZoom(false). Se cambia antes de cargar. */
    private String lockViewport(String html, String csp) {
        StringBuilder meta = new StringBuilder();
        if (csp != null && !csp.isEmpty()) {
            meta.append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
                    .append(csp.replace("\"", "&quot;")).append("\">");
        }
        meta.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1,")
                .append(" maximum-scale=1, user-scalable=no, viewport-fit=cover,")
                .append(" interactive-widget=resizes-content\">");
        String tag = meta.toString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<meta[^>]*name=[\"']viewport[\"'][^>]*>",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            return html.substring(0, m.start()) + tag + html.substring(m.end());
        }
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + tag + html.substring(at);
        }
        return tag + html;
    }

    /** Carga con Basic auth para el servidor local (la UI v2 la exige). */
    private void webLoad(String url) {
        String full = withAuthToken(url);
        if (BuildConfig.V2) {
            // v2 registra un service worker que cachea el HTML original. Si
            // sirviera la pagina, el viewport bloqueado no llegaria nunca.
            // Cargando el documento desde memoria con la URL base, el HTML
            // es siempre el nuestro y la SPA conserva origen y rutas.
            final int gen = ++webLoadGen;
            new Thread(() -> {
                Uri u = Uri.parse(full);
                String path = u.getPath();
                if (path == null || path.isEmpty()) path = "/";
                String q = u.getEncodedQuery();
                final IndexDoc doc = fetchIndexDoc(path + (q == null ? "" : "?" + q));
                runOnUiThread(() -> {
                    if (gen != webLoadGen || isFinishing()) return;
                    if (doc != null) {
                        web.loadDataWithBaseURL(full, lockViewport(doc.html, doc.csp),
                                "text/html", "utf-8", null);
                    } else {
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Authorization", basicAuth());
                        web.loadUrl(full, headers);
                    }
                });
            }, "v2-doc").start();
            return;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth());
        web.loadUrl(full, headers);
    }

    /** Si el service worker sirvio el HTML original (o la pagina se recargo
     *  fuera de webLoad), vuelve a cargar el documento con el viewport
     *  bloqueado. Dos intentos como maximo para no entrar en bucle. */
    private int v2DocFixes = 0;

    private void ensureV2Doc(String url) {
        if (url == null || !url.startsWith(URL)) return;
        web.evaluateJavascript(
                "(function(){var m=document.querySelector('meta[name=viewport]');"
                        + "return !!(m&&m.content&&m.content.indexOf('user-scalable=no')>=0);})()",
                value -> {
                    if ("true".equals(value)) {
                        v2DocFixes = 0;
                        return;
                    }
                    if (v2DocFixes >= 2) return;
                    v2DocFixes++;
                    webLoad(url);
                });
    }

    /** Una sola carga de la URL (evita recargas dobles que se ven como saltos). */
    private synchronized void loadChatUrl() {
        if (!urlLoading && web.getUrl() == null) {
            urlLoading = true;
            webLoad(takeChatUrl());
        }
    }

    /**
     * URL de entrada al chat. Si un aviso de agente dejo una sesion pendiente,
     * se abre esa sesion una sola vez; despues se vuelve a la raiz.
     */
    private String takeChatUrl() {
        String sid = pendingSessionId;
        pendingSessionId = null;
        return (sid == null || sid.isEmpty()) ? URL : shareSessionUrl(sid);
    }

    /** Generacion de la peticion de entrar: una nueva anula la espera anterior. */
    private int openGen = 0;
    private androidx.appcompat.app.AlertDialog logDlg;

    /** Chat-first: al abrir la app, si el servidor ya responde, directo al chat sin toques.
     *  Si esta caido, lo arranca solo y la secuencia abre el chat al acabar. */
    /** Next: el panel no existe; solo el chat (o el splash mientras arranca). */
    private void applyChatOnlyChrome() {
        fullscreen = true;
        findViewById(R.id.appBar).setVisibility(View.GONE);
        findViewById(R.id.progress).setVisibility(View.GONE);
        findViewById(R.id.cardStatus).setVisibility(View.GONE);
        btnStart.setVisibility(View.GONE);
        btnOpen.setVisibility(View.GONE);
        findViewById(R.id.boxAdvanced).setVisibility(View.GONE);
        View logCard = findViewById(R.id.cardLog);
        if (logCard != null) logCard.setVisibility(View.GONE);
        cardWeb.setVisibility(View.VISIBLE);
        // onCreate deja boxContent transparente para la entrada del splash; en
        // Next no hay animacion de panel, asi que se muestra ya.
        boxContent.setAlpha(1f);
        boxContent.setTranslationY(0f);
        applyChatChrome(true);
        fabControls.setVisibility(View.GONE);
    }

    /** El arranque fallo: se queda el splash con un reintento (nunca el panel). */
    private void startupFailed(String message) {
        starting = false;
        setBusy(false);
        if (!chatOnly()) {
            hideSplash();
            return;
        }
        setSplashStatus(message);
        if (!splashDone && splashRetry != null) {
            splashRetry.setVisibility(View.VISIBLE);
        } else if (retryAttempt == 0) {
            snack(message);
        }
        scheduleStartupRetry();
    }

    private void scheduleStartupRetry() {
        if (!chatOnly() || pendingRetry != null || isFinishing()) return;
        int[] delays = {3000, 5000, 10000, 20000, 30000};
        int idx = Math.min(retryAttempt, delays.length - 1);
        retryAttempt++;
        pendingRetry = () -> {
            pendingRetry = null;
            if (isFinishing() || serverOpen || starting) return;
            if (!hasAllFilesAccess()) {
                startupFailed(T("Falta 'todos los archivos' para arrancar el agente",
                        "All files access is needed to start the agent"));
                return;
            }
            if (splashRetry != null) splashRetry.setVisibility(View.GONE);
            setSplashStatus(T("Reintentando...", "Retrying..."));
            startServerSequence();
        };
        new Handler(Looper.getMainLooper()).postDelayed(pendingRetry, delays[idx]);
    }

    private void retryStartupNow() {
        if (pendingRetry != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
        retryAttempt = 0;
        if (splashRetry != null) splashRetry.setVisibility(View.GONE);
        setSplashStatus(T("Reintentando...", "Retrying..."));
        startServerSequence();
    }

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
            if (ready && !BuildConfig.V2 && !BuildConfig.V2_LAB) applyAutoPermissions(autoPerm());
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
                    restartServer();
                } else {
                    log("Chat sin HTTP 200 en 60s: no abro (evita blanco)."
                            + " Reintento automatico.");
                    startupFailed(T("Sin respuesta del servidor. Reintentando...",
                            "No answer from the server. Retrying..."));
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
        setLangText(R.id.btnRestart, T("Reiniciar", "Restart"));
        setLangText(R.id.btnStop, T("Detener", "Stop"));
        setLangText(R.id.btnCopy, T("Copiar", "Copy"));
        setLangText(R.id.btnClear, T("Limpiar", "Clear"));
        setLangText(R.id.logTitle, T("Registro", "Log"));
        setLangText(R.id.txtLog, T("Listo. Pulsa Iniciar y abrir.", "Ready. Press Start and open."));
        setLangText(R.id.txtStatus, T("Servidor ?", "Server ?"));
        setLangText(R.id.splashRetry, T("Reintentar", "Retry"));
        refreshUpdateBtn();
    }
    private void setLangText(int id, String s) {
        android.widget.TextView v = findViewById(id);
        if (v != null) v.setText(s);
    }

    /** Root efectivo del movil. */
    /** Sin root: oculta Estabilizar. El boton de descarga lo maneja la guia. */
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
        if (BuildConfig.V2) return;
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
            synchronized (updDbg) {
                updDbg.setLength(0);
                updDbg.append("local=").append(activeVer);
                updDbg.append(" arch=").append(hasAllFilesAccess() ? "ok" : "sin-acceso");
            }
            android.util.Log.d("OC-Update", "check hilo: local=" + local
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
        if (!chatOnly()) {
            // El panel inicial solo existe en la linea con Termux.
            menu.getMenu().add(0, 0, 0, serverOpen ? T("Servidor abierto", "Server open")
                    : T("Servidor detenido", "Server stopped"))
                    .setIcon(serverOpen ? R.drawable.dot_green : R.drawable.dot_red);
            menu.getMenu().add(0, 2, 2, T("Panel de controles", "Controls panel"))
                    .setIcon(android.R.drawable.ic_menu_manage);
        }
        menu.getMenu().add(0, 1, 1, T("Registro", "Log")).setIcon(android.R.drawable.ic_menu_info_details);
        menu.getMenu().add(0, 3, 3, T("Reiniciar servidor", "Restart server")).setIcon(android.R.drawable.ic_popup_sync);
        menu.getMenu().add(0, 4, 4, T("Detener servidor", "Stop server")).setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        if (!BuildConfig.V2_LAB) {
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
        }
        menu.getMenu().add(0, 7, 7, T("Opciones", "Options")).setIcon(android.R.drawable.ic_menu_preferences);
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
                restartServer();
                return true;
            }
            if (id == 4) {
                stopServer();
                return true;
            }
            if (id == 5) {
                startRuntimeUpdate();
                return true;
            }
            if (id == 10) {
                installAppUpdateNow();
                return true;
            }
            if (id == 11) {
                checkLauncherUpdate(true);
                return true;
            }
            if (id == 7) {
                showOptionsDialog();
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
                T("Root (su)", "Root (su)"),
                T("Si el movil tiene root, el agente puede ejecutar comandos con su"
                        + " (Magisk/KernelSU piden permiso). Apagado no pide nada.",
                        "If the phone is rooted, the agent can run commands via su"
                                + " (Magisk/KernelSU ask). Off never prompts."),
                "no".equals(RootShell.state(this)) ? T("Sin su", "No su")
                        : !RootShell.enabled(this) ? T("OFF", "OFF")
                        : "on".equals(RootShell.state(this)) ? T("Si", "ON")
                        : "denied".equals(RootShell.state(this)) ? T("Denegado", "Denied")
                        : T("Pedir", "Ask"),
                () -> {
                    if (!RootShell.available()) {
                        snack(T("Este movil no tiene su", "This phone has no su"));
                        return;
                    }
                    boolean now = !RootShell.enabled(this);
                    RootShell.setEnabled(this, now);
                    if (now) {
                        snack(T("Pidiendo root...", "Asking for root..."));
                        new Thread(() -> {
                            boolean ok = RootShell.request(this);
                            runOnUiThread(() -> {
                                snack(ok ? T("Root concedido", "Root granted")
                                        : T("Root denegado", "Root denied"));
                                refresh.run();
                            });
                        }, "su-request").start();
                    } else {
                        snack(T("Root desactivado", "Root disabled"));
                        refresh.run();
                    }
                });
        addOptionRow(root,
                T("Motor del servidor", "Server engine"),
                T("Nativo: el servidor corre sin proot, mas rapido. Los comandos que"
                        + " necesitan pkg/apt se ejecutan solos dentro de proot."
                        + " Si el nativo no arranca, vuelve a proot automaticamente.",
                        "Native: the server runs without proot, faster. Commands that"
                        + " need pkg/apt run inside proot automatically."
                        + " If native does not start, it falls back to proot."),
                !LocalRuntime.nativeAvailable(this) ? T("Solo proot", "proot only")
                        : LocalRuntime.nativeEnabled(this) ? T("Nativo", "Native")
                        : T("proot", "proot"),
                () -> {
                    if (!LocalRuntime.nativeAvailable(this)) {
                        snack(T("Esta instalacion no trae el runtime nativo",
                                "This install has no native runtime"));
                        return;
                    }
                    boolean now = !LocalRuntime.nativeEnabled(this);
                    LocalRuntime.setNativeEnabled(this, now);
                    snack(now ? T("Motor nativo: reiniciando", "Native engine: restarting")
                            : T("Motor proot: reiniciando", "proot engine: restarting"));
                    refresh.run();
                    restartServer();
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
            String lang = p.getString("lang", "auto");
            if (lang == null || lang.isEmpty() || "auto".equals(lang)) {
                lang = java.util.Locale.getDefault().getLanguage();
            }
            StringBuilder b = new StringBuilder();
            b.append("### App\n");
            b.append("- App: ").append(appVer).append("\n");
            b.append("- Android: ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ")
                    .append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            b.append("- Server: ").append(serverOpen ? "running" : "stopped").append("\n");
            b.append("- opencode installed: ").append(installed)
                    .append(" (latest known: ").append(latest).append(")\n");
            b.append("### Options\n");
            b.append("- Boot start: ").append(ServerWatch.bootOn(this) ? "ON" : "OFF")
                    .append(", watchdog: ").append(ServerWatch.wdMode(this))
                    .append(", interval: ").append(ServerWatch.wdMin(this)).append(" min\n");
            b.append("- App lock: ").append(lockOn() ? "ON" : "OFF");
            // v2 gestiona los permisos en su propia UI: el interruptor v1 no aplica.
            if (!BuildConfig.V2) b.append(", auto permissions: ").append(autoPerm() ? "ON" : "OFF");
            b.append(", language: ").append(lang).append("\n");
            b.append("### What happens (edit before sending)\n");
            b.append("<describe what fails here>\n");
            String rawLog = "";
            try {
                rawLog = txtLog.getText().toString();
            } catch (Exception ignored) {
            }
            String log = redactSecrets(rawLog);
            if (log.length() > 3000) log = "…(trimmed)…\n" + log.substring(log.length() - 3000);
            b.append("### Log (secrets redacted)\n```\n").append(log).append("\n```\n");
            final String title = "App report v" + appVer;
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
                        cm.setPrimaryClip(ClipData.newPlainText("opencode-report",
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
    /** El comando de setup quedo pegado/lanzado (la prueba fina es el primer
     *  arranque con exito). Sin esto, tras pegar el setup el boton seguiria
     *  oscurecido esperando una prueba que aun no se puede pedir. */
    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            log("Abre manual: Ajustes > Apps > OpenCode Android > Todos los archivos.");
        }
    }
    /** Punto rojo (falta) / verde (hecho). Solo estado: sin botones. */

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
        retryAttempt = 0;
        if (pendingRetry != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
        if (splashRetry != null) splashRetry.setVisibility(View.GONE);
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
            if (!chatOnly()) {
                boxContent.animate().alpha(1f).translationY(0f)
                        .setDuration(400).setInterpolator(new DecelerateInterpolator()).start();
            }
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
        // Panel sin botones: el estado del update vive en el menu del FAB.
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
        if (BuildConfig.V2_LAB) return;
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
        // Sin boton en el panel: el aviso va en el menu del FAB/notificacion.
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

    /** Linea Next: el agente va embebido y la app es solo el chat (sin panel). */
    private boolean chatOnly() {
        return LocalRuntime.available(this);
    }

    private void setFullscreen(boolean full) {
        fullscreen = full;
        uiFullscreen = full;
        int vis = full ? View.GONE : View.VISIBLE;
        findViewById(R.id.appBar).setVisibility(vis);
        findViewById(R.id.toolbar).setVisibility(vis);
        findViewById(R.id.cardStatus).setVisibility(vis);
        refreshActionButtons();
        findViewById(R.id.boxAdvanced).setVisibility(
                full ? View.GONE : (advancedOpen ? View.VISIBLE : View.GONE));
        View logCard = findViewById(R.id.cardLog);
        if (logCard != null) logCard.setVisibility(vis);
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
                scheduleFootbar();
            }
            // En el arranque el splash sigue visible: no se tapa con el aviso.
            if (splashDone) {
                snack(T("Pantalla completa: el boton abre el menu",
                        "Fullscreen: the button opens the menu"));
            }
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
        chatPad = full ? 0 : dp(16);
        applyChatPadding();
        // Siempre inmersivo (panel y chat): entrar/salir del chat no debe
        // redimensionar la ventana nunca. Barra de estado con swipe.
        enterImmersive();
    }

    /** Padding del contenido + alto del teclado (el hueco queda bajo el IME). */
    private void applyChatPadding() {
        if (boxContent == null) return;
        boxContent.setPadding(chatPad, chatPad, chatPad, chatPad + imeBottom);
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
                    + "var vw=window.innerWidth,vh=window.innerHeight;"
                    // Solo la carcasa (hasta 5 niveles): recorrer todo el DOM
                    // y escribir estilos intercalados fuerza reflujo por nodo.
                    + "var root=document.getElementById('root')||document.body;"
                    + "var list=[document.body,root],level=[root];"
                    + "for(var d=0;d<5&&list.length<400;d++){var next=[];"
                    + "for(var i=0;i<level.length&&list.length<400;i++){"
                    + "var kids=level[i].children;"
                    + "for(var j=0;j<kids.length&&list.length<400;j++){"
                    + "next.push(kids[j]);list.push(kids[j]);}}"
                    + "level=next;if(!level.length)break;}"
                    + "var todo=[];"
                    + "for(var i=0;i<list.length;i++){var e=list[i],r=e.getBoundingClientRect();"
                    + "var full=(r.width>=vw-8&&r.height>=vh-8&&r.left<=8&&r.top<=8);"
                    + "var big=(r.width>=vw*0.8&&r.height>=vh*0.4);"
                    + "if(!(full||big))continue;"
                    + "var cs=getComputedStyle(e);"
                    + "var bs=((cs.borderWidth!=='0px'&&cs.borderStyle!=='none')"
                    + "||cs.boxShadow!=='none'||cs.outlineStyle!=='none');"
                    + "var fl=cs.filter||'';"
                    + "var fi=(fl&&fl!=='none'&&fl.indexOf('drop-shadow')>=0);"
                    + "var bi=cs.backgroundImage||'';"
                    + "var im=(bi&&bi!=='none');"
                    + "if(bs||fi||im)todo.push([e,bs,fi,im,r.width,r.height]);}"
                    // Lecturas terminadas: ahora se aplican los cambios de golpe.
                    + "for(var k=0;k<todo.length;k++){var e=todo[k][0];"
                    + "if(todo[k][1]){e.style.setProperty('border','0','important');"
                    + "e.style.setProperty('box-shadow','none','important');"
                    + "e.style.setProperty('outline','none','important');}"
                    + "if(todo[k][2]){e.style.setProperty('filter','none','important');"
                    + "e.style.setProperty('-webkit-filter','none','important');}"
                    + "if(todo[k][3])e.style.setProperty('background-image','none','important');}"
                    + "return todo.length+'|';}catch(err){return -1;}})()",
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
                    // TreeWalker de textos: no concatena subarboles ni recorre
                    // elementos, y el observador se agrupa para no repetirlo
                    // en cada mutacion del streaming.
                    + "function fix(){var n=0,w=document.createTreeWalker("
                    + "document.body,NodeFilter.SHOW_TEXT,null),t;"
                    + "while(t=w.nextNode()){var v=t.nodeValue;if(!v)continue;"
                    + "if(v.indexOf('OpenCode Desktop')>=0){"
                    + "t.nodeValue=v.split('OpenCode Desktop').join('OpenCode Mobile');n++;}"
                    + "else if(v.trim()==='Desktop'){t.nodeValue='Mobile';n++;}}"
                    + "return n;}"
                    + "var n=fix();"
                    + "if(!window.__ocBrandObs&&window.MutationObserver){"
                    + "window.__ocBrandObs=new MutationObserver(function(){"
                    + "if(window.__ocBrandT)return;"
                    + "window.__ocBrandT=setTimeout(function(){"
                    + "window.__ocBrandT=null;fix();},350);});"
                    + "window.__ocBrandObs.observe(document.body,{childList:true,subtree:true});}"
                    + "return 'brand:'+n;}catch(err){return 'brand-ERR:'+err;}})()",
                    v -> Log.d("OpenCode", "brand-fix: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "brand-fix fallo: " + e.getMessage());
        }
    }

    /** El switch "Auto-accept permissions" de opencode nace deshabilitado fuera
     *  de una sesion (su logica exige ?dir= en la ruta). Lo sustituimos por uno
     *  propio que manda al interruptor global del launcher. */
    private void shimAutoAccept() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "if(window.__ocAutoAcceptShim)return 'ya';"
                    + "window.__ocAutoAcceptShim=true;"
                    + "function on(){try{return String(ocConfig.autoAccept())==='1';}catch(e){return false;}}"
                    + "function paint(sw,v){if(!sw)return;"
                    + "if(v)sw.setAttribute('data-checked','');else sw.removeAttribute('data-checked');}"
                    + "function apply(){"
                    + "var row=document.querySelector('[data-action=settings-auto-accept-permissions]');"
                    + "if(!row)return 0;"
                    + "var old=row.querySelector('.oc-auto-shim');if(old)old.remove();"
                    + "var sw=row.querySelector('[data-component=switch]');"
                    + "if(!sw)return 0;"
                    + "sw.style.display='';"
                    + "sw.removeAttribute('data-disabled');"
                    + "sw.removeAttribute('data-readonly');"
                    + "sw.removeAttribute('aria-disabled');"
                    + "sw.style.cursor='pointer';"
                    + "paint(sw,on());"
                    + "return 1;}"
                    + "document.addEventListener('pointerdown',function(ev){"
                    + "var row=document.querySelector('[data-action=settings-auto-accept-permissions]');"
                    + "if(!row)return;"
                    + "var sw=row.querySelector('[data-component=switch]');"
                    + "var r=row.getBoundingClientRect();var x=ev.clientX,y=ev.clientY;"
                    + "var sr=sw?sw.getBoundingClientRect():null;"
                    + "var inSw=!!(sw&&(sw.contains(ev.target)||(sr&&x>=sr.left-12&&x<=sr.right+12&&y>=sr.top-12&&y<=sr.bottom+12)));"
                    + "if(!inSw&&(x<r.left-6||x>r.right+6||y<r.top-6||y>r.bottom+6))return;"
                    + "try{ocConfig.debug('toggle tap '+x.toFixed(0)+','+y.toFixed(0));}catch(e){}"
                    + "ev.preventDefault();ev.stopPropagation();"
                    + "var was=on();paint(sw,!was);"
                    + "try{ocConfig.toggleAutoAccept();}catch(e){paint(sw,was);}"
                    + "},true);"
                    + "var t=null;"
                    + "if(window.MutationObserver)new MutationObserver(function(){"
                    + "if(t)return;t=setTimeout(function(){t=null;apply();},250);})"
                    + ".observe(document.body,{childList:true,subtree:true});"
                    + "apply();setTimeout(apply,400);return 'auto-shim';}catch(e){return 'ERR:'+e;}})()",
                    v -> Log.d("OpenCode", "auto-shim: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "auto-shim fallo: " + e.getMessage());
        }
    }

    private void hideShortcutsSettings() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "if(window.__ocHideShortcuts)return 'ya';"
                    + "window.__ocHideShortcuts=true;"
                    // Solo actua si hay ajustes/dialogo abierto: en el chat no
                    // cuesta nada y ya no queda un interval de 600 ms eterno.
                    + "function hide(){"
                    + "var root=document.querySelector('[data-variant=settings]')"
                    + "||document.querySelector('[role=dialog]');"
                    + "if(!root)return 0;"
                    + "var n=0;"
                    + "var ws=root.querySelectorAll('[data-slot=tabs-v2-trigger-wrapper][data-value=shortcuts]');"
                    + "for(var i=0;i<ws.length;i++){"
                    + "ws[i].style.display='none';ws[i].setAttribute('aria-hidden','true');n++;}"
                    + "var cs=root.querySelectorAll('[data-slot=tabs-v2-content]');"
                    + "for(var j=0;j<cs.length;j++){var h=cs[j].querySelector('h2');"
                    + "var x=(h?h.textContent:cs[j].textContent)||'';"
                    + "if(/keyboard shortcuts|atajos de teclado/i.test(x)){"
                    + "cs[j].style.display='none';n++;}}"
                    + "if(n===0){var tn=root.getElementsByTagName('*');"
                    + "for(var m=0;m<tn.length;m++){var el=tn[m];"
                    + "if(el.children.length===0){var tx=(el.textContent||'').trim();"
                    + "if(tx==='Shortcuts'||tx==='Atajos'||tx==='Atajos de teclado'){"
                    + "var w=el.closest('[data-slot=tabs-v2-trigger-wrapper]')||el.closest('[data-value]');"
                    + "if(w){w.style.display='none';w.setAttribute('aria-hidden','true');n++;}}}}}"
                    + "return n;}"
                    + "function schedule(){if(window.__ocHideT)return;"
                    + "window.__ocHideT=setTimeout(function(){"
                    + "window.__ocHideT=null;hide();},300);}"
                    + "if(window.MutationObserver)new MutationObserver(schedule)"
                    + ".observe(document.body,{childList:true,subtree:true});"
                    + "var r=hide();setTimeout(hide,700);setTimeout(hide,2200);"
                    + "return 'shortcuts:'+r;}catch(e){return 'ERR:'+e;}})()",
                    v -> Log.d("OpenCode", "shortcuts-hide: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "shortcuts-hide fallo: " + e.getMessage());
        }
    }

    /** En movil el tooltip de "context usage" no abre al tocar (Kobalte ignora
     *  touch y el click abre el panel Context, que no existe por debajo de 768px).
     *  Emulamos el hover de PC: pointerenter sintetico y bloqueo del click. */
    private void shimUsageTap() {
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                    + "if(window.__ocUsageTap)return 'ya';"
                    + "window.__ocUsageTap=true;"
                    + "function find(el){"
                    + "var tr=el&&el.closest?el.closest('[data-component=tooltip-v2-trigger]'):null;"
                    + "if(!tr)return null;"
                    + "if(!tr.querySelector('[data-component=progress-circle-v2],[data-component=progress-circle]'))return null;"
                    + "return tr;}"
                    + "function leave(tr){try{tr.dispatchEvent(new PointerEvent('pointerleave',{bubbles:false,cancelable:true,pointerType:'mouse',isPrimary:true}));}catch(e){}}"
                    + "document.addEventListener('pointerdown',function(ev){"
                    + "var tr=find(ev.target);"
                    + "if(tr){ev.preventDefault();ev.stopPropagation();"
                    + "if(window.__ocUsageOpen===tr)return;"
                    + "window.__ocUsageOpen=tr;"
                    + "setTimeout(function(){try{tr.dispatchEvent(new PointerEvent('pointerenter',{bubbles:false,cancelable:true,pointerType:'mouse',isPrimary:true}));}catch(e){}},30);"
                    + "return;}"
                    + "if(window.__ocUsageOpen){leave(window.__ocUsageOpen);window.__ocUsageOpen=null;}"
                    + "},true);"
                    + "document.addEventListener('click',function(ev){"
                    + "var tr=find(ev.target);"
                    + "if(tr){ev.preventDefault();ev.stopPropagation();}"
                    + "},true);"
                    + "return 'usage-ok';}catch(e){return 'ERR:'+e;}})()",
                    v -> Log.d("OpenCode", "usage-tap: " + v));
        } catch (Exception e) {
            Log.d("OpenCode", "usage-tap fallo: " + e.getMessage());
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
                    + "window.__ocHelpObs=new MutationObserver(function(){"
                    + "if(window.__ocHelpT)return;"
                    + "window.__ocHelpT=setTimeout(function(){"
                    + "window.__ocHelpT=null;fix();},250);});"
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

    /** Acceso amplio a /sdcard: "todos los archivos" en Android 11+, o el
     *  permiso legacy en Android 8-10. */
    private boolean hasShareStorage() {
        if (Build.VERSION.SDK_INT >= 30) return hasAllFilesAccess();
        return ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Descargas publicas si se pueden escribir; si no, la carpeta externa
     *  privada de la app (el agente corre con el mismo UID y la lee igual). */
    private File shareDir() {
        if (hasShareStorage()) {
            File pub = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "opencode-shares");
            // En Android 10 el permiso puede estar concedido sin que haya
            // almacenamiento legacy: se comprueba creando la carpeta.
            if (pub.isDirectory() || pub.mkdirs()) return pub;
        }
        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getFilesDir();
        return new File(base, "opencode-shares");
    }

    private String shareLocationLabel() {
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (shareDir().getAbsolutePath().startsWith(downloads.getAbsolutePath())) {
            return T("Los archivos se copian a Descargas/opencode-shares",
                    "Files are copied to Downloads/opencode-shares");
        }
        return T("Los archivos se copian a la carpeta privada de la app (Android/data)",
                "Files are copied to the app's private folder (Android/data)");
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
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putLong("upd_ts", 0).apply();
        restartServer();
    }

    @Override
    protected void onDestroy() {
        stopProgressPoll();
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

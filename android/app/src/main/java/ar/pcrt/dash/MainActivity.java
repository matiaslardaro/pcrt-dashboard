package ar.pcrt.dash;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Pantalla completa con el dashboard (el mismo dashboard.html de siempre, servido por el puente). */
public class MainActivity extends Activity {
    static final String ACCION_SALIR = "ar.pcrt.dash.SALIR";
    private static final String URL = "http://127.0.0.1:8080/dashboard.html";

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver salir = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finishAndRemoveTask(); }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(salir, new IntentFilter(ACCION_SALIR), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(salir, new IntentFilter(ACCION_SALIR));
        }

        startForegroundService(new Intent(this, PuenteService.class));

        web = new WebView(this);
        web.setBackgroundColor(0xff000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                // el puente todavia esta arrancando: reintentamos en un segundo
                if (req.isForMainFrame()) handler.postDelayed(() -> cargar(), 1000);
            }
        });
        setContentView(web);
        pantallaCompleta();
        handler.postDelayed(this::cargar, 300);
        pedirPermisos();
    }

    private void cargar() {
        Puente p = PuenteService.puente;
        String v = p != null ? p.version() : "inicio";
        web.loadUrl(URL + "?v=" + v);
    }

    private void pedirPermisos() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        // pedir una sola vez que Android no le aplique ahorro de bateria (igual el servicio en primer plano alcanza)
        SharedPreferences prefs = getSharedPreferences("pcrt", MODE_PRIVATE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("pidioBateria", false)) {
            prefs.edit().putBoolean("pidioBateria", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void pantallaCompleta() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) pantallaCompleta();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // atras no cierra nada: manda la app al fondo y el servicio sigue recibiendo
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(salir); } catch (Exception ignored) {}
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

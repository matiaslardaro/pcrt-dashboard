package ar.pcrt.dash;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

/**
 * Servicio en primer plano (con notificacion fija): Android no lo mata en segundo plano como
 * hacia con Termux. Mantiene el wifi y la CPU despiertos mientras corre.
 */
public class PuenteService extends Service {
    static final String ACCION_CERRAR = "ar.pcrt.dash.CERRAR";
    private static final String CANAL = "pcrt";
    private static final int ID_NOTIF = 1;

    static volatile Puente puente;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Telemetria PCRT", NotificationManager.IMPORTANCE_LOW));
        Notification n = notificacion();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(ID_NOTIF, n);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pcrt:puente");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        // sin esto el wifi entra en ahorro de energia y se pierden paquetes de la PS5
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pcrt:wifi");
        wifiLock.acquire();
        multicastLock = wm.createMulticastLock("pcrt:descubrir");
        multicastLock.acquire();

        iniciarPuente();
    }

    private void iniciarPuente() {
        if (puente != null) return;
        SharedPreferences prefs = getSharedPreferences("pcrt", MODE_PRIVATE);
        Puente p = new Puente(new File(getFilesDir(), "web"), nombre -> {
            try { return getAssets().open(nombre); } catch (Exception e) { return null; }
        }, new Puente.Avisos() {
            @Override public void log(String msg) { Log.i("PCRT", msg); }
            @Override public void ps5Encontrada(String ip) { prefs.edit().putString("ps5Ip", ip).apply(); }
        }, prefs.getString("ps5Ip", null));
        try {
            p.iniciar();
            puente = p;
        } catch (Exception e) {
            Log.e("PCRT", "No se pudo iniciar el puente", e);
            p.detener();
        }
    }

    private Notification notificacion() {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cerrar = PendingIntent.getService(this, 1, new Intent(this, PuenteService.class).setAction(ACCION_CERRAR),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("PCRT recibiendo telemetria")
                .setContentText("Toca para abrir el dashboard")
                .setContentIntent(abrir)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Cerrar", cerrar).build())
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_CERRAR.equals(intent.getAction())) {
            sendBroadcast(new Intent(MainActivity.ACCION_SALIR).setPackage(getPackageName()));
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        iniciarPuente();
        return START_STICKY; // si Android lo llega a matar, lo vuelve a levantar
    }

    @Override
    public void onDestroy() {
        Puente p = puente;
        puente = null;
        if (p != null) p.detener();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

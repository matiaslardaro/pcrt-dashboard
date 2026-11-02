package ar.pcrt.dash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lo que antes hacia pcrt_server.py en Termux, sin Android adentro (se puede probar en una PC):
 *   - busca la PS5 en la red y le manda el heartbeat para que GT7 mande telemetria
 *   - recibe, descifra y parsea los paquetes (20 por segundo de los 60 que manda)
 *   - WebSocket en 8765 que manda el ultimo paquete 15 veces por segundo (igual que antes)
 *   - HTTP en 8080 que sirve dashboard.html y /version
 *   - baja dashboard.html de GitHub cada 3 minutos (las actualizaciones siguen llegando solas)
 */
public final class Puente {
    public interface Recursos {
        /** Archivo empaquetado con la app (assets), o null. */
        InputStream abrir(String nombre) throws IOException;
    }

    public interface Avisos {
        void log(String msg);
        void ps5Encontrada(String ip);
    }

    static final String URL_DASH = "https://raw.githubusercontent.com/matiaslardaro/pcrt-dashboard/main/dashboard.html";
    static final int PUERTO_PS5_HEARTBEAT = 33739;
    static final int PUERTO_RECIBIR = 33740;
    static final int PUERTO_DESCUBRIR = 9302;
    static final long INTERVALO_PROCESO_MS = 50;      // 20 paquetes/s
    static final long ENVIO_WS_MS = 1000 / 15;
    static final long VIEJO_MS = 4000;
    static final long ACTUALIZAR_MS = 3 * 60 * 1000;

    private final File dirDescargas;
    private final Recursos recursos;
    private final Avisos avisos;
    private final int puertoHttp, puertoWs;
    private final String urlDash;

    private volatile boolean andando = false;
    private volatile String ps5Ip;
    private volatile String ultimoJson = null;
    private volatile long ultimoPaqueteMs = 0;
    private volatile long ultimoRecibidoMs = 0;
    private volatile byte[] dashHtml = null;
    private volatile String version = "unknown";
    private volatile long paquetesProcesados = 0;

    private DatagramSocket udp;
    private ServerSocket srvHttp, srvWs;
    private DetectorCircuito detector;

    public Puente(File dirDescargas, Recursos recursos, Avisos avisos, String ps5IpGuardada, int puertoHttp, int puertoWs, String urlDash) {
        this.dirDescargas = dirDescargas;
        this.recursos = recursos;
        this.avisos = avisos;
        this.ps5Ip = ps5IpGuardada;
        this.puertoHttp = puertoHttp;
        this.puertoWs = puertoWs;
        this.urlDash = urlDash;
    }

    public Puente(File dirDescargas, Recursos recursos, Avisos avisos, String ps5IpGuardada) {
        this(dirDescargas, recursos, avisos, ps5IpGuardada, 8080, 8765, URL_DASH);
    }

    public String version() { return version; }
    public String ps5Ip() { return ps5Ip; }
    public long paquetesProcesados() { return paquetesProcesados; }
    public boolean recibiendo() { return System.currentTimeMillis() - ultimoRecibidoMs < VIEJO_MS; }

    private void log(String m) { if (avisos != null) avisos.log(m); }

    public synchronized void iniciar() throws IOException {
        if (andando) return;
        andando = true;
        try (InputStream csv = recursos.abrir("gt7trackdetect.csv")) {
            detector = new DetectorCircuito(csv);
        } catch (Exception e) {
            detector = new DetectorCircuito(null);
        }
        cargarDash();
        srvHttp = new ServerSocket();
        srvHttp.setReuseAddress(true);
        srvHttp.bind(new InetSocketAddress(puertoHttp));
        srvWs = new ServerSocket();
        srvWs.setReuseAddress(true);
        srvWs.bind(new InetSocketAddress(puertoWs));
        udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        udp.bind(new InetSocketAddress(PUERTO_RECIBIR));
        hilo("pcrt-http", () -> aceptar(srvHttp, true));
        hilo("pcrt-ws", () -> aceptar(srvWs, false));
        hilo("pcrt-udp", this::recibir);
        hilo("pcrt-heartbeat", this::heartbeat);
        hilo("pcrt-actualizar", this::actualizarDash);
        log("Puente andando (dashboard " + version + ")");
    }

    public synchronized void detener() {
        andando = false;
        cerrar(udp); cerrar(srvHttp); cerrar(srvWs);
    }

    private static void cerrar(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    private static void hilo(String nombre, Runnable r) {
        Thread t = new Thread(r, nombre);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ PS5
    private void recibir() {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        long ultimoProceso = 0;
        boolean primero = true;
        while (andando) {
            try {
                p.setLength(buf.length);
                udp.receive(p);
                long ahora = System.currentTimeMillis();
                ultimoRecibidoMs = ahora;
                // la PS5 manda 60/s y al dash le alcanzan 15: los sobrantes ni se descifran
                if (ahora - ultimoProceso < INTERVALO_PROCESO_MS) continue;
                ultimoProceso = ahora;
                byte[] dec = Telemetria.descifrar(buf, p.getLength());
                if (dec == null) continue;
                Telemetria t = Telemetria.parsear(dec);
                Integer track = detector.detectar(t);
                ultimoJson = t.json(track, ahora);
                ultimoPaqueteMs = ahora;
                paquetesProcesados++;
                if (primero) {
                    primero = false;
                    log("Primer paquete de telemetria recibido de la PS5");
                }
            } catch (Exception e) {
                if (andando) log("Error recibiendo telemetria: " + e);
            }
        }
    }

    private void heartbeat() {
        long ultimoDescubrir = 0;
        DatagramSocket s = null;
        int fallos = 0;
        while (andando) {
            long ahora = System.currentTimeMillis();
            // sin PS5 conocida, o hace rato que no llega nada (cambio de IP, PS5 reiniciada): la buscamos
            boolean sinDatos = ahora - ultimoRecibidoMs > 15000;
            if ((ps5Ip == null || sinDatos) && ahora - ultimoDescubrir > 10000) {
                ultimoDescubrir = ahora;
                String ip = descubrirPs5();
                if (ip != null && !ip.equals(ps5Ip)) {
                    ps5Ip = ip;
                    log("PS5 encontrada en " + ip);
                    if (avisos != null) avisos.ps5Encontrada(ip);
                }
            }
            String ip = ps5Ip;
            if (ip != null) {
                try {
                    if (s == null) s = new DatagramSocket();
                    byte[] a = {'A'};
                    s.send(new DatagramPacket(a, 1, InetAddress.getByName(ip), PUERTO_PS5_HEARTBEAT));
                    if (fallos > 0) log("Heartbeat recuperado despues de " + fallos + " fallos");
                    fallos = 0;
                } catch (Exception e) {
                    // microcorte de wifi: reintenta en el proximo ciclo en vez de morirse
                    if (fallos++ == 0) log("Heartbeat fallo (" + e + "), reintentando...");
                    cerrar(s);
                    s = null;
                }
            }
            dormir(1000);
        }
        cerrar(s);
    }

    /** Protocolo de descubrimiento de PlayStation (el mismo que usa gt-telem). */
    static String descubrirPs5() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(1000);
            byte[] q = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010".getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), PUERTO_DESCUBRIR));
            byte[] buf = new byte[1024];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            s.receive(r);
            return r.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ------------------------------------------------------------------ dashboard.html
    private void cargarDash() {
        byte[] html = null;
        File f = new File(dirDescargas, "dashboard.html");
        if (f.isFile()) html = leer(f);
        if (!esHtml(html)) {
            try (InputStream in = recursos.abrir("web/dashboard.html")) {
                html = in == null ? null : leerTodo(in);
            } catch (Exception ignored) {}
        }
        if (esHtml(html)) usarDash(html);
    }

    private void usarDash(byte[] html) {
        dashHtml = html;
        version = hashCorto(html);
    }

    private void actualizarDash() {
        while (andando) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(urlDash + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setUseCaches(false);
                if (c.getResponseCode() == 200) {
                    byte[] html;
                    try (InputStream in = c.getInputStream()) { html = leerTodo(in); }
                    if (esHtml(html) && !hashCorto(html).equals(version)) {
                        dirDescargas.mkdirs();
                        File tmp = new File(dirDescargas, "dashboard.html.tmp");
                        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(html); }
                        if (!tmp.renameTo(new File(dirDescargas, "dashboard.html"))) log("No se pudo guardar el dashboard nuevo");
                        String antes = version;
                        usarDash(html);
                        log("Dashboard actualizado: " + antes + " -> " + version);
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                log("No se pudo buscar actualizaciones del dashboard (" + e.getMessage() + ")");
            }
            dormir(ACTUALIZAR_MS);
        }
    }

    private static boolean esHtml(byte[] b) {
        if (b == null || b.length < 1000) return false;
        String fin = new String(b, Math.max(0, b.length - 200), Math.min(200, b.length), StandardCharsets.UTF_8);
        return fin.contains("</html>");
    }

    static String hashCorto(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.substring(0, 7);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static byte[] leer(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) { return leerTodo(in); } catch (Exception e) { return null; }
    }

    private static byte[] leerTodo(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ servidores
    private void aceptar(ServerSocket srv, boolean http) {
        while (andando) {
            try {
                Socket s = srv.accept();
                hilo(http ? "pcrt-http-cli" : "pcrt-ws-cli", () -> {
                    try {
                        if (http) atenderHttp(s); else atenderWs(s);
                    } catch (Exception ignored) {
                    } finally {
                        cerrar(s);
                    }
                });
            } catch (Exception e) {
                if (andando) { log("Error aceptando conexion: " + e); dormir(500); }
            }
        }
    }

    /** Lee la linea de pedido y los encabezados HTTP (hasta la linea vacia). */
    private static Map<String, String> leerPedido(InputStream in) throws IOException {
        ByteArrayOutputStream linea = new ByteArrayOutputStream();
        Map<String, String> h = new HashMap<>();
        String primera = null;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                String l = linea.toString("ISO-8859-1").trim();
                linea.reset();
                if (primera == null) { primera = l; h.put(":pedido", l); }
                else if (l.isEmpty()) return h;
                else {
                    int dp = l.indexOf(':');
                    if (dp > 0) h.put(l.substring(0, dp).trim().toLowerCase(Locale.ROOT), l.substring(dp + 1).trim());
                }
                if (h.size() > 100) throw new IOException("encabezados de mas");
            } else if (c != '\r') {
                linea.write(c);
                if (linea.size() > 8192) throw new IOException("linea muy larga");
            }
        }
        throw new IOException("conexion cerrada");
    }

    private void atenderHttp(Socket s) throws IOException {
        s.setSoTimeout(10000);
        Map<String, String> h = leerPedido(s.getInputStream());
        String[] partes = h.get(":pedido").split(" ");
        String ruta = partes.length > 1 ? partes[1] : "/";
        int q = ruta.indexOf('?');
        if (q >= 0) ruta = ruta.substring(0, q);
        OutputStream out = s.getOutputStream();
        if (!"GET".equals(partes[0]) && !"HEAD".equals(partes[0])) { responder(out, 405, "text/plain", "metodo no permitido".getBytes()); return; }
        if (ruta.equals("/version")) {
            responder(out, 200, "application/json", ("{\"hash\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
        } else if (ruta.equals("/") || ruta.equals("/dashboard.html")) {
            byte[] html = dashHtml;
            if (html == null) responder(out, 503, "text/plain", "dashboard no disponible todavia".getBytes());
            else responder(out, 200, "text/html; charset=utf-8", html);
        } else if (ruta.equals("/estado")) {
            String j = "{\"version\":\"" + version + "\",\"ps5\":" + (ps5Ip == null ? "null" : "\"" + ps5Ip + "\"") +
                    ",\"recibiendo\":" + recibiendo() + ",\"paquetes\":" + paquetesProcesados + "}";
            responder(out, 200, "application/json", j.getBytes(StandardCharsets.UTF_8));
        } else {
            responder(out, 404, "text/plain", "no encontrado".getBytes());
        }
    }

    private static void responder(OutputStream out, int codigo, String tipo, byte[] cuerpo) throws IOException {
        String texto = codigo == 200 ? "OK" : codigo == 404 ? "Not Found" : codigo == 405 ? "Method Not Allowed" : "Service Unavailable";
        String cab = "HTTP/1.1 " + codigo + " " + texto + "\r\n" +
                "Content-Type: " + tipo + "\r\n" +
                "Content-Length: " + cuerpo.length + "\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n" +
                "Pragma: no-cache\r\nExpires: 0\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(cab.getBytes(StandardCharsets.ISO_8859_1));
        out.write(cuerpo);
        out.flush();
    }

    private void atenderWs(Socket s) throws Exception {
        s.setSoTimeout(10000);
        s.setTcpNoDelay(true);
        InputStream in = s.getInputStream();
        Map<String, String> h = leerPedido(in);
        String key = h.get("sec-websocket-key");
        OutputStream out = s.getOutputStream();
        if (key == null) { responder(out, 404, "text/plain", "solo websocket".getBytes()); return; }
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        log("Tablet conectada por WebSocket");
        final boolean[] cerrado = {false};
        s.setSoTimeout(0);
        // el dash no manda nada; solo leemos para enterarnos si cierra
        hilo("pcrt-ws-leer", () -> {
            try {
                while (true) {
                    int b0 = in.read();
                    if (b0 == -1) break;
                    int b1 = in.read();
                    if (b1 == -1) break;
                    long len = b1 & 0x7f;
                    if (len == 126) len = (in.read() << 8) | in.read();
                    else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                    if ((b1 & 0x80) != 0) for (int i = 0; i < 4; i++) in.read();
                    for (long i = 0; i < len; i++) if (in.read() == -1) break;
                    if ((b0 & 0x0f) == 0x8) break; // close
                }
            } catch (Exception ignored) {}
            cerrado[0] = true;
            cerrar(s);
        });
        try {
            while (andando && !cerrado[0]) {
                enviarTexto(out, armarMensaje());
                Thread.sleep(ENVIO_WS_MS);
            }
        } catch (Exception ignored) {
        } finally {
            log("Tablet desconectada");
        }
    }

    private String armarMensaje() {
        String j = ultimoJson;
        if (j == null) return "{\"connected\":false}";
        // si hace mas de 4s que no llega un paquete nuevo, avisamos al dash en vez de mandarle
        // para siempre el ultimo dato bueno como si nada
        if (System.currentTimeMillis() - ultimoPaqueteMs > VIEJO_MS) return "{\"connected\":false" + j.substring("{\"connected\":true".length());
        return j;
    }

    private static void enviarTexto(OutputStream out, String texto) throws IOException {
        byte[] d = texto.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream f = new ByteArrayOutputStream(d.length + 10);
        f.write(0x81);
        if (d.length < 126) f.write(d.length);
        else if (d.length < 65536) { f.write(126); f.write(d.length >>> 8); f.write(d.length & 0xff); }
        else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int) ((long) d.length >>> (8 * i)) & 0xff); }
        f.write(d);
        out.write(f.toByteArray());
        out.flush();
    }
}

package ar.pcrt.dash;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Deteccion de circuito por la caja que recorre el auto y la linea de meta. Portado de gt-telem
 * (trackdetector), que a su vez lo adapta de gt7telemetry de Bornhall. Datos: gt7trackdetect.csv.
 */
final class DetectorCircuito {
    private static final double UMBRAL_PROB = 95;
    private static final double DESVIO_IOU = 0.02;

    private static final class Pista {
        int track; double p1x, p1y, p2x, p2y, minx, miny, maxx, maxy; String dir;
    }

    private final List<Pista> pistas = new ArrayList<>();
    private Integer trackId = null;
    private double[] oldXy = null;
    private double maxX = -999999.9, maxY = -999999.9, minX = 999999.9, minY = 999999.9;

    DetectorCircuito(InputStream csv) {
        if (csv == null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String linea = r.readLine(); // encabezado
            while ((linea = r.readLine()) != null) {
                String[] c = linea.replace("\"", "").split(",");
                if (c.length < 10) continue;
                Pista p = new Pista();
                p.track = Integer.parseInt(c[0].trim());
                p.p1x = Double.parseDouble(c[1]); p.p1y = Double.parseDouble(c[2]);
                p.p2x = Double.parseDouble(c[3]); p.p2y = Double.parseDouble(c[4]);
                p.dir = c[5].trim();
                p.minx = Double.parseDouble(c[6]); p.miny = Double.parseDouble(c[7]);
                p.maxx = Double.parseDouble(c[8]); p.maxy = Double.parseDouble(c[9]);
                pistas.add(p);
            }
        } catch (Exception e) {
            System.out.println("[pcrt] no se pudo leer la base de circuitos: " + e);
        }
    }

    Integer detectar(Telemetria t) {
        if (t.isLoading() || t.isPaused()) return trackId;
        if (trackId != null && t.carsOnTrack()) return trackId;
        trackId = detectarInterno(t);
        return trackId;
    }

    private Integer detectarInterno(Telemetria t) {
        if (!t.carsOnTrack()) {
            oldXy = null;
            maxX = maxY = -999999.9;
            minX = minY = 999999.9;
            return null;
        }
        double[] nuevo = {t.posX, t.posZ};
        maxX = Math.max(maxX, t.posX); minX = Math.min(minX, t.posX);
        maxY = Math.max(maxY, t.posZ); minY = Math.min(minY, t.posZ);
        Integer encontrado = null;
        if (t.currentLap > 1 && oldXy != null) {
            double[] mejor = buscar(oldXy[0], oldXy[1], nuevo[0], nuevo[1]);
            if (mejor != null && Math.round(mejor[0] * 1000) / 10.0 >= UMBRAL_PROB) encontrado = (int) mejor[1];
        }
        oldXy = nuevo;
        return encontrado;
    }

    /** {iou, track} de la mejor coincidencia, o null. */
    private double[] buscar(double l1x, double l1y, double l2x, double l2y) {
        double[] externo = caja(minX, minY, maxX, maxY);
        double mejorIou = -1; int mejorTrack = -1;
        for (Pista p : pistas) {
            String dir = cruce(p.p1x, p.p1y, p.p2x, p.p2y, l1x, l1y, l2x, l2y);
            if (dir == null || !dir.equals(p.dir)) continue;
            double iou = iou(externo, caja(p.minx, p.miny, p.maxx, p.maxy));
            if (iou > mejorIou) { mejorIou = iou; mejorTrack = p.track; }
        }
        return mejorTrack < 0 ? null : new double[]{mejorIou, mejorTrack};
    }

    private static double[] caja(double x1, double y1, double x2, double y2) {
        return new double[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
    }

    private static double area(double[] b) { return (b[2] - b[0]) * (b[3] - b[1]); }

    private static double iou(double[] a, double[] b) {
        double l = Math.max(a[0], b[0]), r = Math.min(a[2], b[2]), t = Math.max(a[1], b[1]), bo = Math.min(a[3], b[3]);
        double inter = (l > r || t > bo) ? 0 : area(new double[]{l, t, r, bo});
        double union = area(a) + area(b) - inter;
        return union == 0 ? 0 : inter / union;
    }

    /** Direccion del cruce si el tramo recorrido corta la linea de meta, o null si no la corta. */
    private static String cruce(double p0x, double p0y, double p1x, double p1y, double p2x, double p2y, double p3x, double p3y) {
        double s1x = p1x - p0x, s1y = p1y - p0y, s2x = p3x - p2x, s2y = p3y - p2y;
        double den = -s2x * s1y + s1x * s2y;
        if (den == 0) return null;
        double s = (-s1y * (p0x - p2x) + s1x * (p0y - p2y)) / den;
        double t = (s2x * (p0y - p2y) - s2y * (p0x - p2x)) / den;
        if (s < 0 || s > 1 || t < 0 || t > 1) return null;
        if (s2x > 0) return "PX";
        if (s2x < 0) return "NX";
        if (s2y > 0) return "PY";
        if (s2y < 0) return "NY";
        return "--";
    }
}

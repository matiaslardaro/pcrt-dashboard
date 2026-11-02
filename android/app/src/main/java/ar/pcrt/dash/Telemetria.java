package ar.pcrt.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Descifra y parsea un paquete de telemetria de GT7 (heartbeat "A", 296 bytes) y lo pasa al
 * mismo JSON que armaba gt-telem (as_dict), que es lo que lee dashboard.html.
 */
final class Telemetria {
    private static final byte[] KEY = "Simulator Interface Packet GT7 ver 0.0".substring(0, 32).getBytes(StandardCharsets.US_ASCII);
    private static final int IV_MASK = 0xDEADBEAF;
    static final int LARGO_MIN = 296;

    // campos (mismos nombres que gt-telem)
    float posX, posY, posZ, velX, velY, velZ, rotX, rotY, rotZ, orientation, angX, angY, angZ;
    float bodyHeight, engineRpm, iv, fuelLevel, fuelCapacity, speedMps, boostPressure, oilPressure, waterTemp, oilTemp;
    float[] tireTemp = new float[4], wheelRps = new float[4], tireRadius = new float[4], susHeight = new float[4];
    float[] roadPlane = new float[3];
    float roadPlaneDist, clutchPedal, clutchEngagement, transRpm, transTopSpeed;
    float[] gears = new float[8];
    int packetId, bestLapMs, lastLapMs, timeOfDayMs, carCode;
    int currentLap, totalLaps, raceStartPos, totalCars, minAlertRpm, maxAlertRpm, calcMaxSpeed, flags;
    int bits, throttle, brake;

    /** Devuelve el paquete descifrado o null si no es un paquete valido de GT7. */
    static byte[] descifrar(byte[] cifrado, int largo) {
        if (largo < LARGO_MIN) return null;
        byte[] in = new byte[largo];
        System.arraycopy(cifrado, 0, in, 0, largo);
        int seed = ByteBuffer.wrap(in, 0x40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int ivv = seed ^ IV_MASK;
        byte[] nonce = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(ivv).putInt(seed).array();
        byte[] dec = Salsa20.xor(KEY, nonce, in);
        // "0S7G"
        if (dec[0] != 0x30 || dec[1] != 0x53 || dec[2] != 0x37 || dec[3] != 0x47) return null;
        return dec;
    }

    static Telemetria parsear(byte[] dec) {
        ByteBuffer b = ByteBuffer.wrap(dec).order(ByteOrder.LITTLE_ENDIAN);
        b.position(4);
        Telemetria t = new Telemetria();
        t.posX = b.getFloat(); t.posY = b.getFloat(); t.posZ = b.getFloat();
        t.velX = b.getFloat(); t.velY = b.getFloat(); t.velZ = b.getFloat();
        t.rotX = b.getFloat(); t.rotY = b.getFloat(); t.rotZ = b.getFloat();
        t.orientation = b.getFloat();
        t.angX = b.getFloat(); t.angY = b.getFloat(); t.angZ = b.getFloat();
        t.bodyHeight = b.getFloat(); t.engineRpm = b.getFloat(); t.iv = b.getFloat();
        t.fuelLevel = b.getFloat(); t.fuelCapacity = b.getFloat(); t.speedMps = b.getFloat();
        t.boostPressure = b.getFloat(); t.oilPressure = b.getFloat(); t.waterTemp = b.getFloat(); t.oilTemp = b.getFloat();
        for (int i = 0; i < 4; i++) t.tireTemp[i] = b.getFloat();
        t.packetId = b.getInt();
        t.currentLap = b.getShort(); t.totalLaps = b.getShort();
        t.bestLapMs = b.getInt(); t.lastLapMs = b.getInt(); t.timeOfDayMs = b.getInt();
        t.raceStartPos = b.getShort(); t.totalCars = b.getShort();
        t.minAlertRpm = b.getShort(); t.maxAlertRpm = b.getShort(); t.calcMaxSpeed = b.getShort();
        t.flags = b.getShort();
        t.bits = b.get() & 0xff; t.throttle = b.get() & 0xff; t.brake = b.get() & 0xff; b.get();
        for (int i = 0; i < 3; i++) t.roadPlane[i] = b.getFloat();
        t.roadPlaneDist = b.getFloat();
        for (int i = 0; i < 4; i++) t.wheelRps[i] = b.getFloat();
        for (int i = 0; i < 4; i++) t.tireRadius[i] = b.getFloat();
        for (int i = 0; i < 4; i++) t.susHeight[i] = b.getFloat();
        b.position(b.position() + 8 * 4); // unused1..8
        t.clutchPedal = b.getFloat(); t.clutchEngagement = b.getFloat(); t.transRpm = b.getFloat(); t.transTopSpeed = b.getFloat();
        for (int i = 0; i < 8; i++) t.gears[i] = b.getFloat();
        t.carCode = b.getInt();
        return t;
    }

    boolean flag(int bit) { return (flags & (1 << bit)) != 0; }
    boolean carsOnTrack() { return flag(0); }
    boolean isPaused() { return flag(1); }
    boolean isLoading() { return flag(2); }

    // ---- JSON ----
    private static void num(StringBuilder sb, String k, double v) {
        sb.append(",\"").append(k).append("\":");
        if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null"); else sb.append((float) v);
    }

    private static void ent(StringBuilder sb, String k, long v) {
        sb.append(",\"").append(k).append("\":").append(v);
    }

    private static void bool(StringBuilder sb, String k, boolean v) {
        sb.append(",\"").append(k).append("\":").append(v);
    }

    private static void arr(StringBuilder sb, String k, float... v) {
        sb.append(",\"").append(k).append("\":[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            if (Float.isNaN(v[i]) || Float.isInfinite(v[i])) sb.append("null"); else sb.append(v[i]);
        }
        sb.append(']');
    }

    /** JSON con "connected" al principio (asi el server puede marcarlo como viejo sin re-armarlo). */
    String json(Integer trackId, long tsMs) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"connected\":true");
        num(sb, "_ts", tsMs / 1000.0);
        sb.append(",\"_track_id\":").append(trackId == null ? "null" : trackId.toString());
        sb.append(",\"track_id\":").append(trackId == null ? "null" : trackId.toString());
        arr(sb, "position", posX, posY, posZ);
        arr(sb, "velocity", velX, velY, velZ);
        arr(sb, "rotation", rotX, rotY, rotZ);
        arr(sb, "angular_velocity", angX, angY, angZ);
        arr(sb, "road_plane", roadPlane[0], roadPlane[1], roadPlane[2]);
        num(sb, "orientation", orientation);
        num(sb, "body_height", bodyHeight);
        num(sb, "engine_rpm", engineRpm);
        num(sb, "iv", iv);
        num(sb, "fuel_level", fuelLevel);
        num(sb, "fuel_capacity", fuelCapacity);
        num(sb, "speed_mps", speedMps);
        num(sb, "speed_kph", speedMps * 3.6);
        num(sb, "speed_mph", speedMps * 2.23694);
        num(sb, "boost_pressure", boostPressure);
        num(sb, "oil_pressure", oilPressure);
        num(sb, "water_temp", waterTemp);
        num(sb, "oil_temp", oilTemp);
        arr(sb, "tire_temp", tireTemp);
        arr(sb, "wheel_rps", wheelRps);
        arr(sb, "tire_radius", tireRadius);
        arr(sb, "suspension_height", susHeight);
        ent(sb, "packet_id", packetId);
        ent(sb, "current_lap", currentLap);
        ent(sb, "total_laps", totalLaps);
        ent(sb, "best_lap_time_ms", bestLapMs);
        ent(sb, "last_lap_time_ms", lastLapMs);
        ent(sb, "time_of_day_ms", timeOfDayMs);
        ent(sb, "race_start_pos", raceStartPos);
        ent(sb, "total_cars", totalCars);
        ent(sb, "min_alert_rpm", minAlertRpm);
        ent(sb, "max_alert_rpm", maxAlertRpm);
        ent(sb, "calc_max_speed", calcMaxSpeed);
        ent(sb, "throttle", throttle);
        ent(sb, "brake", brake);
        num(sb, "road_plane_dist", roadPlaneDist);
        num(sb, "clutch_pedal", clutchPedal);
        num(sb, "clutch_engagement", clutchEngagement);
        num(sb, "trans_rpm", transRpm);
        num(sb, "trans_top_speed", transTopSpeed);
        for (int i = 0; i < 8; i++) num(sb, "gear" + (i + 1), gears[i]);
        ent(sb, "car_code", carCode);
        ent(sb, "current_gear", bits & 0b1111);
        ent(sb, "suggested_gear", bits >> 4);
        bool(sb, "cars_on_track", flag(0));
        bool(sb, "is_paused", flag(1));
        bool(sb, "is_loading", flag(2));
        bool(sb, "in_gear", flag(3));
        bool(sb, "has_turbo", flag(4));
        bool(sb, "rev_limit", flag(5));
        bool(sb, "hand_brake_active", flag(6));
        bool(sb, "lights_active", flag(7));
        bool(sb, "high_beams", flag(8));
        bool(sb, "low_beams", flag(9));
        bool(sb, "asm_active", flag(10));
        bool(sb, "tcs_active", flag(11));
        sb.append('}');
        return sb.toString();
    }
}

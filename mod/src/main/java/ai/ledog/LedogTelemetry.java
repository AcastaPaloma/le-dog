package ai.ledog;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Per-tick telemetry of every Le Dog wolf, as JSON lines in <game dir>/logs/ledog/ledog-<date>.jsonl (one file per
 * session; written by a background thread so the server tick never waits on disk).
 *
 *   {"type":"tick", "t":gameTime, "id", "kind", "x","y","z", "yaw","body","head", "speed" (blocks/s, horizontal),
 *    "ground","water","hurt","nav" (navigation active), "phase" (brain state), ...brain fields}
 *   {"type":"event", "t", "id", "kind", "msg" (human readable), ...details}   brain decisions (plans, rests, vetoes)
 * Toggle: /ledog log on|off (default on). Summarise / plot: python -m ledog.dog_log <file>.
 */
public final class LedogTelemetry {
    // per-tick logs only in research mode (./ledog_port.txt) or with LEDOG_LOG=1; public players: off (disk use)
    private static volatile boolean enabled = "1".equals(System.getenv("LEDOG_LOG"))
            || (!"0".equals(System.getenv("LEDOG_LOG")) && java.nio.file.Files.exists(java.nio.file.Path.of("ledog_port.txt")));
    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(200_000);
    private static Path file;
    private static Thread writer;

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static synchronized Path file() {
        if (file == null) {
            Path dir;
            try {
                dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs/ledog");
            } catch (Throwable t) {
                dir = Path.of(System.getProperty("user.home"), ".ledog", "logs");
            }
            file = dir.resolve("ledog-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl");
            writer = new Thread(LedogTelemetry::run, "ledog-telemetry");
            writer.setDaemon(true);
            writer.start();
        }
        return file;
    }

    private static void run() {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                while (true) {
                    String line = queue.take();
                    w.write(line);
                    w.newLine();
                    if (queue.isEmpty()) w.flush();
                }
            }
        } catch (Exception e) {
            LedogMod.LOG.warn("Le Dog telemetry stopped: {}", e.toString());
        }
    }

    private static void put(JsonObject o) {
        if (!enabled) return;
        file();
        queue.offer(o.toString());  // drops lines rather than blocking the server if the disk cannot keep up
    }

    /** One line per server tick per Le Dog wolf. */
    public static void tick(long t, Wolf w, WolfBrain b) {
        if (!enabled) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "tick");
        o.addProperty("t", t);
        o.addProperty("id", w.getId());
        o.addProperty("kind", b.kind());
        o.addProperty("x", r3(w.getX()));
        o.addProperty("y", r3(w.getY()));
        o.addProperty("z", r3(w.getZ()));
        o.addProperty("yaw", r3(w.getYRot()));
        o.addProperty("body", r3(w.yBodyRot));
        o.addProperty("head", r3(w.getYHeadRot()));
        var v = w.getDeltaMovement();
        o.addProperty("speed", r3(Math.hypot(v.x, v.z) * 20.0));
        o.addProperty("ground", w.onGround());
        o.addProperty("water", w.isInWater());
        o.addProperty("hurt", w.hurtTime > 0);
        o.addProperty("nav", !w.getNavigation().isDone());
        JsonObject s = b.status();
        for (var e : s.entrySet()) o.add(e.getKey(), e.getValue());
        put(o);
    }

    /** A brain decision, human readable plus details. */
    public static void event(long t, Wolf w, String kind, String msg, JsonObject details) {
        if (!enabled) return;
        JsonObject o = details == null ? new JsonObject() : details.deepCopy();
        o.addProperty("type", "event");
        o.addProperty("t", t);
        o.addProperty("id", w.getId());
        o.addProperty("kind", kind);
        o.addProperty("msg", msg);
        put(o);
    }

    static double r3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private LedogTelemetry() {
    }
}

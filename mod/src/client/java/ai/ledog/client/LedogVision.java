package ai.ledog.client;

import ai.ledog.LedogModels;
import ai.ledog.PastMotion;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client half of the Le Dog VISION brain: the wolf's own rendered camera -> DINOv3-S (+ CLS/4x4 pooling, ONNX) at
 * 10 Hz -> every 1 s the vision policy (21i: 16 frames at 30 fps mapped to the nearest 10 Hz features + 0.5 s of past
 * motion) -> a 4-s path SHAPE, sent over the localhost control port to the server's ShapeBrain, whose deterministic
 * chunker executes it. Preprocessing = training/M4: drop the bottom 40/360 of the frame (HUD band), resize the shorter
 * side to 256 (bicubic, a = -0.75 as OpenCV INTER_CUBIC), centre-crop 224, RGB in [0, 1].
 */
public class LedogVision {
    private static final int HIST = 16, POSES = 51;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ledog-vision");
        t.setDaemon(true);
        return t;
    });
    private final ArrayDeque<float[]> feats = new ArrayDeque<>();  // newest last, 10 Hz
    private final List<double[]> poses = new ArrayList<>();
    private volatile boolean busy;
    private int wolfId = -1;
    private long frames;
    private Socket sock;
    private PrintWriter out;
    private BufferedReader in;
    private final Path debugDir;
    public volatile String status = "idle";

    public LedogVision() {
        String d = System.getenv("LEDOG_VISION_DEBUG");
        debugDir = d == null ? null : Path.of(d);
    }

    public void reset(int id) {
        wolfId = id;
        synchronized (feats) {
            feats.clear();
        }
        poses.clear();
    }

    /** Called every 2 client ticks with the wolf (pose) and the just-rendered frame's ARGB pixels. */
    public void onFrame(Wolf wolf, int[] argb, int w, int h, long seq) {
        if (wolf.getId() != wolfId) reset(wolf.getId());
        poses.add(new double[]{wolf.getX(), wolf.getY(), wolf.getZ(), wolf.yBodyRot});
        while (poses.size() > POSES) poses.remove(0);
        frames++;
        if (busy) return;  // drop frames if DINO is slower than 10 Hz (the policy then reuses the latest features)
        busy = true;
        final boolean plan = frames % 10 == 0;
        final List<double[]> ph = new ArrayList<>(poses);
        final int id = wolfId;
        worker.submit(() -> {
            try {
                float[] f = LedogModels.dino(preprocess(argb, w, h));
                if (debugDir != null) {
                    ByteBuffer bb = ByteBuffer.allocate(4 * f.length).order(ByteOrder.LITTLE_ENDIAN);
                    for (float v : f) bb.putFloat(v);
                    Files.createDirectories(debugDir);
                    Files.write(debugDir.resolve("f" + seq + ".feat"), bb.array());
                }
                synchronized (feats) {
                    feats.addLast(f);
                    while (feats.size() > 8) feats.removeFirst();
                }
                if (plan && ph.size() >= 6) sendShape(id, ph);
                status = "ok";
            } catch (Exception e) {
                status = e.toString();
            } finally {
                busy = false;
            }
        });
    }

    private void sendShape(int id, List<double[]> ph) throws Exception {
        float[][] buf;
        synchronized (feats) {
            buf = feats.toArray(new float[0][]);
        }
        float[] x = new float[HIST * 17 * 384];
        for (int i = 0; i < HIST; i++) {  // 30-fps frame i of the last 16 (oldest first) -> nearest 10 Hz feature
            int back = Math.round((HIST - 1 - i) / 3.0f);
            float[] f = buf[Math.max(0, buf.length - 1 - back)];
            System.arraycopy(f, 0, x, i * 17 * 384, 17 * 384);
        }
        float[] m = PastMotion.compute(ph, 15);
        float[][] shape = LedogModels.visionShape(x, m);
        JsonObject msg = new JsonObject();
        msg.addProperty("op", "vision_shape");
        msg.addProperty("id", id);
        JsonArray a = new JsonArray();
        for (float[] p : shape) {
            a.add(p[0]);
            a.add(p[1]);
        }
        msg.add("shape", a);
        if (sock == null || sock.isClosed()) {
            sock = new Socket("127.0.0.1", 25570);
            out = new PrintWriter(sock.getOutputStream(), true, StandardCharsets.UTF_8);
            in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        }
        out.println(new Gson().toJson(msg));
        in.readLine();
    }

    // ---------------- preprocessing ----------------
    static float[] preprocess(int[] argb, int w, int h) {
        int hc = h - Math.round(h * 40f / 360f);  // drop the bottom HUD band (40 px of 360 in training frames)
        double s = 256.0 / Math.min(w, hc);
        int rw = (int) Math.round(w * s), rh = (int) Math.round(hc * s);
        int x0 = (rw - 224) / 2, y0 = (rh - 224) / 2;
        float[] out = new float[3 * 224 * 224];
        for (int oy = 0; oy < 224; oy++) {
            double sy = (oy + y0 + 0.5) / s - 0.5;
            for (int ox = 0; ox < 224; ox++) {
                double sx = (ox + x0 + 0.5) / s - 0.5;
                double r = 0, g = 0, b = 0;
                int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy);
                double fx = sx - ix, fy = sy - iy;
                for (int dy = -1; dy <= 2; dy++) {
                    double wy = cubic(dy - fy);
                    int yy = Math.max(0, Math.min(hc - 1, iy + dy));
                    for (int dx = -1; dx <= 2; dx++) {
                        double wgt = wy * cubic(dx - fx);
                        int xx = Math.max(0, Math.min(w - 1, ix + dx));
                        int p = argb[yy * w + xx];
                        r += wgt * ((p >> 16) & 255);
                        g += wgt * ((p >> 8) & 255);
                        b += wgt * (p & 255);
                    }
                }
                int o = oy * 224 + ox;
                out[o] = (float) (clamp255(r) / 255.0);
                out[224 * 224 + o] = (float) (clamp255(g) / 255.0);
                out[2 * 224 * 224 + o] = (float) (clamp255(b) / 255.0);
            }
        }
        return out;
    }

    static double cubic(double x) {  // Keys kernel, a = -0.75 (OpenCV INTER_CUBIC)
        double a = -0.75, t = Math.abs(x);
        if (t <= 1) return ((a + 2) * t - (a + 3)) * t * t + 1;
        if (t < 2) return ((a * t - 5 * a) * t + 8 * a) * t - 4 * a;
        return 0;
    }

    static double clamp255(double v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}

package ai.ledog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Random;

/**
 * In-mod Le Dog behaviour model (Gate D deployability): the EgoPet-trained generative motion prior
 * (ledog/behavior_model.py) and its controller (ledog/wolfmod_auto.py BMController) ported to Java, no dependencies.
 * Weights come from a local file ($LEDOG_BM or ~/.ledog/bm.bin, written by `behavior_model export`); they are never
 * bundled in the jar (trained on CC BY-NC data).
 *
 * Every 2 server ticks (10 Hz) one control step; every 5 steps (0.5 s) a new plan: a mode is sampled by its predicted
 * probability among modes whose first 2 s are walkable. Execution: planned speed through the inverse actuator curve,
 * heading along the plan's 1-s tangent, limited to 7.57 deg per step, vanilla navigation as the locomotion layer.
 */
public class BmBrain implements WolfBrain {
    // ---- constants: identical to the Python controller ----
    static final int NH = 15, NF = 40, K = 6, IN = NH * 2 + (NH - 1), OUT = NF * 2;
    static final double U_MC = 2.86, TURN_MAX = 7.57, STOP_BELOW = 0.02;
    static final int REPLAN = 5, HORIZON_VETO = 20, LOOK = 10;
    static final double[] ACT_MOD = {0.1, 0.2, 0.3, 0.45, 0.6, 0.8, 1.0, 1.4};
    static final double[] ACT_V = {0.018, 0.072, 0.357, 0.77, 1.30, 2.43, 2.91, 5.90};

    // ---- MLP ----
    private final float[][] W, B;
    private final int[] outDim, inDim;

    public static BmBrain load() throws IOException {
        String p = System.getenv().getOrDefault("LEDOG_BM", System.getProperty("user.home") + "/.ledog/bm.bin");
        return new BmBrain(Files.readAllBytes(Path.of(p)));
    }

    BmBrain(byte[] raw) {
        this.rng = new Random(0);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = bb.getInt();
        W = new float[n][];
        B = new float[n][];
        outDim = new int[n];
        inDim = new int[n];
        for (int l = 0; l < n; l++) {
            outDim[l] = bb.getInt();
            inDim[l] = bb.getInt();
            W[l] = new float[outDim[l] * inDim[l]];
            B[l] = new float[outDim[l]];
            bb.asFloatBuffer().get(W[l]);
            bb.position(bb.position() + 4 * W[l].length);
            bb.asFloatBuffer().get(B[l]);
            bb.position(bb.position() + 4 * B[l].length);
        }
        if (inDim[0] != IN || outDim[n - 1] != K * OUT + K) throw new IllegalStateException("bm.bin shape mismatch");
    }

    /** Exact (erf) GELU, as torch.nn.GELU(). erf: Abramowitz-Stegun 7.1.26 (|err| < 1.5e-7). */
    static double gelu(double x) {
        return 0.5 * x * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    /** x (44) -> raw outputs (K*80 + K): K modes x 40 x (x_right, z_fwd), then K logits. */
    public double[] forward(double[] x) {
        double[] h = x;
        for (int l = 0; l < W.length; l++) {
            double[] o = new double[outDim[l]];
            for (int i = 0; i < outDim[l]; i++) {
                double s = B[l][i];
                int row = i * inDim[l];
                for (int j = 0; j < inDim[l]; j++) s += W[l][row + j] * h[j];
                o[i] = (l < W.length - 1) ? gelu(s) : s;
            }
            h = o;
        }
        return h;
    }

    // ---- controller state ----
    private final ArrayDeque<double[]> hist = new ArrayDeque<>();  // (x, z, bodyYaw), 10 Hz, oldest first
    private final Random rng;
    private double[][] plan;  // (40, 2) world xz
    private double[] origin;
    private int k = REPLAN;
    private Double heading;
    public int vetofail;
    public boolean blocked;
    // diagnostics (Java vs Python controller comparison, journal 22:20)
    public int nSteps, nReplans, nStopSteps, nBlocked, nWater;
    public double sumCmdSpd, lastCmdSpd;
    public int lastMode = -1;
    // telemetry state
    private String phase = "init", modeLabel = "", turnLabel = "";
    private double lastProb, lastDist4;
    private int lastFeasible;

    public BmBrain(BmBrain weights, long seed) {
        this.W = weights.W;
        this.B = weights.B;
        this.outDim = weights.outDim;
        this.inDim = weights.inDim;
        this.rng = new Random(seed);
    }

    static double[] fwd(double yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new double[]{-Math.sin(y), Math.cos(y)};
    }

    static double[] right(double yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new double[]{-Math.cos(y), -Math.sin(y)};
    }

    static double wrap(double d) {
        return ((d + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    static double modifierFor(double v) {
        v = Math.min(v, ACT_V[ACT_V.length - 1]);
        if (v <= ACT_V[0]) return ACT_MOD[0];
        for (int i = 1; i < ACT_V.length; i++)
            if (v <= ACT_V[i]) {
                double t = (v - ACT_V[i - 1]) / (ACT_V[i] - ACT_V[i - 1]);
                return ACT_MOD[i - 1] + t * (ACT_MOD[i] - ACT_MOD[i - 1]);
            }
        return ACT_MOD[ACT_MOD.length - 1];
    }

    /** Model input from a 15-sample history, as behavior_model.ego_history_mc (u = U_MC). */
    static double[] egoInput(double[][] h) {
        double y = Math.toRadians(h[NH - 1][2]);
        double rx = -Math.cos(y), rz = -Math.sin(y), fx = -Math.sin(y), fz = Math.cos(y);
        double[] x = new double[IN];
        for (int i = 0; i < NH; i++) {
            double dx = h[i][0] - h[NH - 1][0], dz = h[i][1] - h[NH - 1][1];
            x[2 * i] = clip((dx * rx + dz * rz) / U_MC);
            x[2 * i + 1] = clip((dx * fx + dz * fz) / U_MC);
        }
        for (int i = 0; i < NH - 1; i++) x[2 * NH + i] = clip(Math.toRadians(wrap(h[i + 1][2] - h[i][2])));
        return x;
    }

    static double clip(double v) {
        return Math.max(-20, Math.min(20, v));
    }

    @Override
    public String kind() {
        return "bm";
    }

    /** One 10 Hz control step for `wolf`. */
    public void step(ServerLevel level, Wolf wolf) {
        double[] now = {wolf.getX(), wolf.getZ(), wolf.yBodyRot};
        if (hist.isEmpty()) for (int i = 0; i < NH - 1; i++) hist.add(now.clone());
        hist.add(now);
        while (hist.size() > NH) hist.removeFirst();
        if (heading == null) heading = (double) wolf.yBodyRot;
        nSteps++;
        if (wolf.isInWater()) {
            if (!phase.equals("water")) LedogTelemetry.event(level.getGameTime(), wolf, kind(), "in water: swimming to the nearest dry ground", null);
            phase = "water";
            nWater++;  // water-exit reflex (same as the mod's reflex for the Python controllers)
            BlockPos dry = LedogMod.dryGround(level, wolf.blockPosition());
            wolf.getNavigation().moveTo(dry.getX() + 0.5, dry.getY(), dry.getZ() + 0.5, 1.0);
            return;
        }
        if (blocked) k = REPLAN;
        blocked = false;
        if (plan == null || k >= REPLAN) replan(level, wolf);
        int kk = k++;
        double[] w0 = plan[kk], w1 = plan[Math.min(kk + 1, NF - 1)];
        double spd = kk < NF - 1 ? Math.hypot(w1[0] - w0[0], w1[1] - w0[1]) * 10.0 : 0.0;
        sumCmdSpd += spd;
        lastCmdSpd = spd;
        if (spd < STOP_BELOW * U_MC) {
            nStopSteps++;
            phase = "still";
            wolf.getNavigation().stop();
            look(wolf);
            return;
        }
        phase = spd < 0.5 * U_MC ? "creep" : spd < 1.5 * U_MC ? "walk" : "run";
        double[] a = kk == 0 ? origin : plan[kk - 1];  // pts = [origin] + plan; pts[kk]
        double[] b = plan[Math.min(kk + LOOK, NF) - 1];  // pts[min(kk+LOOK, 40)]
        double dx = b[0] - a[0], dz = b[1] - a[1];
        if (Math.hypot(dx, dz) >= 0.05) {
            double want = Math.toDegrees(Math.atan2(-dx, dz));
            heading = heading + Math.max(-TURN_MAX, Math.min(TURN_MAX, wrap(want - heading)));
        }
        double[] f = fwd(heading);
        double tx = wolf.getX() + 2.0 * f[0], tz = wolf.getZ() + 2.0 * f[1];
        int fy = (int) Math.floor(wolf.getY());
        if (LedogMod.dryStep(level, (wolf.getX() + tx) / 2, (wolf.getZ() + tz) / 2, fy)
                && LedogMod.dryStep(level, tx, tz, fy)) {
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(tx), (int) Math.floor(tz));
            wolf.getNavigation().moveTo(tx, gy, tz, modifierFor(spd));
        } else {
            wolf.getNavigation().stop();
            blocked = true;
            nBlocked++;
            phase = "blocked";
            LedogTelemetry.event(level.getGameTime(), wolf, kind(), "blocked: next 2 blocks are water or a >1-block step, replanning", null);
        }
        look(wolf);
    }

    @Override
    public com.google.gson.JsonObject status() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("phase", phase);
        o.addProperty("plan_step", Math.min(k, REPLAN));
        o.addProperty("plan_speed", LedogTelemetry.r3(lastCmdSpd));
        o.addProperty("heading", heading == null ? 0 : LedogTelemetry.r3(heading));
        o.addProperty("mode", lastMode);
        o.addProperty("mode_label", modeLabel);
        o.addProperty("mode_prob", LedogTelemetry.r3(lastProb));
        o.addProperty("plan_dist_4s", LedogTelemetry.r3(lastDist4));
        o.addProperty("feasible_modes", lastFeasible);
        return o;
    }

    private void look(Wolf wolf) {
        double[] f = fwd(heading);
        wolf.getLookControl().setLookAt(wolf.getX() + 4 * f[0], wolf.getEyeY(), wolf.getZ() + 4 * f[1]);
    }

    private void replan(ServerLevel level, Wolf wolf) {
        double[][] h = hist.toArray(new double[0][]);
        double[] o = forward(egoInput(h));
        double yaw = wolf.yBodyRot;
        double[] r = right(yaw), f = fwd(yaw);
        double x0 = wolf.getX(), z0 = wolf.getZ();
        double[][][] modes = new double[K][NF][2];
        int[] ok = new int[K];
        double[] p = new double[K];
        double mx = Double.NEGATIVE_INFINITY;
        for (int m = 0; m < K; m++) mx = Math.max(mx, o[K * OUT + m]);
        double sum = 0;
        for (int m = 0; m < K; m++) {
            p[m] = Math.exp(o[K * OUT + m] - mx);
            sum += p[m];
            for (int t = 0; t < NF; t++) {
                double ex = o[m * OUT + 2 * t] * U_MC, ez = o[m * OUT + 2 * t + 1] * U_MC;
                modes[m][t][0] = x0 + ex * r[0] + ez * f[0];
                modes[m][t][1] = z0 + ex * r[1] + ez * f[1];
            }
            ok[m] = LedogMod.walkablePrefix(level, wolf, modes[m], HORIZON_VETO);
        }
        double q = 0;
        for (int m = 0; m < K; m++) {
            p[m] /= sum;
            if (ok[m] >= HORIZON_VETO) q += p[m];
        }
        int pick = 0;
        if (q > 0) {
            double u = rng.nextDouble() * q, c = 0;
            for (int m = 0; m < K; m++) {
                if (ok[m] < HORIZON_VETO) continue;
                c += p[m];
                pick = m;
                if (u <= c) break;
            }
        } else {
            vetofail++;
            double best = -1;
            for (int m = 0; m < K; m++)
                if (ok[m] + 1e-3 * p[m] > best) {
                    best = ok[m] + 1e-3 * p[m];
                    pick = m;
                }
        }
        plan = modes[pick];
        lastMode = pick;
        nReplans++;
        origin = new double[]{x0, z0};
        k = 0;
        // telemetry: describe the sampled plan
        int nf = 0;
        for (int m = 0; m < K; m++) if (ok[m] >= HORIZON_VETO) nf++;
        double ex = plan[NF - 1][0] - x0, ez = plan[NF - 1][1] - z0;
        lastDist4 = Math.hypot(ex, ez);
        lastProb = p[pick];
        lastFeasible = nf;
        double u = lastDist4 / U_MC;
        modeLabel = u < 0.4 ? "stay" : u < 1.6 ? "creep" : u < 5 ? "walk" : "sprint";
        double rel = wrap(Math.toDegrees(Math.atan2(-ex, ez)) - yaw);
        turnLabel = lastDist4 < 0.5 ? "" : Math.abs(rel) < 30 ? "ahead" : Math.abs(rel) > 135 ? "back" : rel > 0 ? "right" : "left";
        com.google.gson.JsonObject d = new com.google.gson.JsonObject();
        d.addProperty("mode", pick);
        d.addProperty("prob", LedogTelemetry.r3(p[pick]));
        com.google.gson.JsonArray pa = new com.google.gson.JsonArray();
        for (double v : p) pa.add(LedogTelemetry.r3(v));
        d.add("probs", pa);
        com.google.gson.JsonArray oa = new com.google.gson.JsonArray();
        for (int v : ok) oa.add(v >= HORIZON_VETO);
        d.add("walkable", oa);
        d.addProperty("dist_4s", LedogTelemetry.r3(lastDist4));
        d.addProperty("turn_deg", LedogTelemetry.r3(rel));
        LedogTelemetry.event(level.getGameTime(), wolf, kind(), String.format("plan: %s%s (mode %d, p=%.2f, %d/6 walkable), %.1f blocks in 4 s%s",
                modeLabel, turnLabel.isEmpty() ? "" : " " + turnLabel, pick, p[pick], nf, lastDist4,
                q > 0 ? "" : " [no walkable mode: least-bad]"), d);
    }
}

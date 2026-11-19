package ai.ledog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Le Dog shape brains: a DINO-transformer policy trained on EgoPet predicts the 4-s path SHAPE (ego, divided by its path
 * length: no speed), and a DETERMINISTIC chunker turns shapes into movement. No sampling anywhere.
 *
 * Kinds:  SHAPE  image-free policy on the wolf's own 1.5-s motion history (21k, shape_pm.onnx)
 *         FOLLOW goal-conditioned image-free policy (21m, shape_goal.onnx), goal = direction to the nearest player
 *                (pause when within 4 blocks)
 *         VISION shapes computed on the CLIENT from the wolf's own camera (DINOv3-S + 21i, sent over the control port);
 *                falls back to SHAPE when no shape newer than 2 s has arrived
 *
 * Chunker (every CHUNK = 1 s):
 *   shape normalised to unit path length; straightness r = net / path. r < 0.15 (the animal dithers in place) -> rest
 *   this chunk, at most REST_MAX = 2 chunks in a row, then walk it anyway (a still history -> "stay" -> would never end).
 *   Path length L = 4 s x walking speed x clamp(r, 0.3, 1)   (curvier shapes walk slower).
 *   World plan = origin + L x shape in the wolf's frame. Terrain veto on the first 2 s: try the shape, its mirror, then
 *   rotations +-45, +-90 deg (fixed order); none feasible -> pause and turn 30 deg (alternating sides).
 *   Each 0.1 s step: speed = local plan spacing (through the inverse actuator curve), heading along the 1-s plan tangent,
 *   max 7.57 deg per step; vanilla navigation only as the locomotion layer (steps/jumps), same safety as the BM brain.
 */
public class ShapeBrain implements WolfBrain {
    public enum Kind {SHAPE, FOLLOW, VISION}

    static final int CHUNK = 10, NF = 40, HIST = 51, HORIZON_VETO = 20, LOOK = 10, REST_MAX = 2;
    static final double V_WALK = 2.86, TURN_MAX = 7.57, GOAL_NET = 0.47, PAUSE_NET = 0.15, FOLLOW_NEAR = 4.0;

    public final Kind kind;
    private final List<double[]> hist = new ArrayList<>();  // [x, y, z, bodyYaw] at 10 Hz
    private double[][] plan;
    private double[] origin;
    private int k = CHUNK;
    private boolean pausing, turnLeft;
    private int restRun;
    private Double heading;
    public boolean blocked;
    // external (client vision) shape
    private float[][] extShape;
    private long extTick = -1000;
    // diagnostics
    public int nSteps, nChunks, nPauses, nVetoTurns, nExtUsed, nErrors;
    public String lastError = "";
    // telemetry state
    private String phase = "init", via = "", chunkWhy = "";
    private double lastSpd, lastStraight, lastL;

    public ShapeBrain(Kind kind) {
        this.kind = kind;
    }

    @Override
    public String kind() {
        return kind.name().toLowerCase();
    }

    public void setExternalShape(float[][] shape, long tick) {
        extShape = shape;
        extTick = tick;
    }

    @Override
    public void step(ServerLevel level, Wolf wolf) {
        hist.add(new double[]{wolf.getX(), wolf.getY(), wolf.getZ(), wolf.yBodyRot});
        while (hist.size() > HIST) hist.remove(0);
        if (heading == null) heading = (double) wolf.yBodyRot;
        nSteps++;
        if (wolf.isInWater()) {  // water-exit reflex
            if (!phase.equals("water")) LedogTelemetry.event(level.getGameTime(), wolf, kind(), "in water: swimming to the nearest dry ground", null);
            phase = "water";
            BlockPos dry = LedogMod.dryGround(level, wolf.blockPosition());
            wolf.getNavigation().moveTo(dry.getX() + 0.5, dry.getY(), dry.getZ() + 0.5, 1.0);
            return;
        }
        if (blocked) k = CHUNK;
        blocked = false;
        if (k >= CHUNK) newChunk(level, wolf);
        int kk = k++;
        if (pausing) {
            wolf.getNavigation().stop();
            lastSpd = 0;
            look(wolf);
            return;
        }
        phase = "walk";
        double[] a = kk == 0 ? origin : plan[kk - 1];
        double[] b = plan[Math.min(kk, NF - 1)];
        double spd = Math.hypot(b[0] - a[0], b[1] - a[1]) * 10.0;
        lastSpd = spd;
        double[] t = plan[Math.min(kk + LOOK, NF) - 1];
        double dx = t[0] - a[0], dz = t[1] - a[1];
        if (Math.hypot(dx, dz) >= 0.05) {
            double want = Math.toDegrees(Math.atan2(-dx, dz));
            heading = heading + Math.max(-TURN_MAX, Math.min(TURN_MAX, BmBrain.wrap(want - heading)));
        }
        double[] f = BmBrain.fwd(heading);
        double tx = wolf.getX() + 2.0 * f[0], tz = wolf.getZ() + 2.0 * f[1];
        int fy = (int) Math.floor(wolf.getY());
        if (spd < 0.06) {
            wolf.getNavigation().stop();
        } else if (LedogMod.dryStep(level, (wolf.getX() + tx) / 2, (wolf.getZ() + tz) / 2, fy) && LedogMod.dryStep(level, tx, tz, fy)) {
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(tx), (int) Math.floor(tz));
            wolf.getNavigation().moveTo(tx, gy, tz, BmBrain.modifierFor(spd));
        } else {
            wolf.getNavigation().stop();
            blocked = true;
            phase = "blocked";
            LedogTelemetry.event(level.getGameTime(), wolf, kind(), "blocked: next 2 blocks are water or a >1-block step, replanning", null);
        }
        look(wolf);
    }

    @Override
    public com.google.gson.JsonObject status() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("phase", phase);
        o.addProperty("chunk_step", Math.min(k, CHUNK));
        o.addProperty("plan_speed", LedogTelemetry.r3(lastSpd));
        o.addProperty("heading", heading == null ? 0 : LedogTelemetry.r3(heading));
        o.addProperty("straightness", LedogTelemetry.r3(lastStraight));
        o.addProperty("path_len", LedogTelemetry.r3(lastL));
        o.addProperty("via", via);
        o.addProperty("why", chunkWhy);
        if (kind == Kind.VISION) o.addProperty("vision_shapes_used", nExtUsed);
        return o;
    }

    private void look(Wolf wolf) {
        double[] f = BmBrain.fwd(heading);
        wolf.getLookControl().setLookAt(wolf.getX() + 4 * f[0], wolf.getEyeY(), wolf.getZ() + 4 * f[1]);
    }

    /** The policy's shape for this chunk, or null to pause. */
    private float[][] shape(ServerLevel level, Wolf wolf) throws Exception {
        if (kind == Kind.VISION && extShape != null && level.getGameTime() - extTick <= 40) {
            nExtUsed++;
            return extShape;
        }
        float[] pm = PastMotion.compute(hist, 45);
        if (kind == Kind.FOLLOW) {
            ServerPlayer p = (ServerPlayer) level.getNearestPlayer(wolf, 64.0);
            if (p == null) return LedogModels.shape("shape_pm", pm);
            double dx = p.getX() - wolf.getX(), dz = p.getZ() - wolf.getZ(), d = Math.hypot(dx, dz);
            if (d < FOLLOW_NEAR) return null;
            double y = Math.toRadians(wolf.yBodyRot);
            double ex = (dx * -Math.cos(y) + dz * -Math.sin(y)) / d, ez = (dx * -Math.sin(y) + dz * Math.cos(y)) / d;
            float[] m = new float[pm.length + 2];
            System.arraycopy(pm, 0, m, 0, pm.length);
            m[pm.length] = (float) (ex * GOAL_NET);
            m[pm.length + 1] = (float) (ez * GOAL_NET);
            return LedogModels.shape("shape_goal", m);
        }
        return LedogModels.shape("shape_pm", pm);
    }

    private void newChunk(ServerLevel level, Wolf wolf) {
        k = 0;
        nChunks++;
        float[][] s;
        try {
            s = shape(level, wolf);
        } catch (Exception e) {
            if (nErrors++ < 3) LedogMod.LOG.warn("Le Dog {} brain error: {}", kind(), e.toString());
            lastError = e.toString();
            s = null;
        }
        pausing = true;
        long gt = level.getGameTime();
        boolean fromVision = kind == Kind.VISION && extShape != null && s == extShape;
        if (s == null) {  // follow: within 4 blocks of the player (deliberate wait) or a brain error
            nPauses++;
            phase = nErrors > 0 && !lastError.isEmpty() ? "error" : "wait";
            chunkWhy = phase.equals("error") ? lastError : "player within 4 blocks";
            LedogTelemetry.event(gt, wolf, kind(), phase.equals("error") ? "brain error: " + lastError : "waiting: player within 4 blocks", null);
            return;
        }
        // normalise to unit path length (the regression's outputs are not exactly unit: a still history gives a tiny,
        // curled shape, path ~0.16), straightness r = net / path
        double P = 0;
        for (int i = 0; i < NF; i++) {
            double ax = i == 0 ? 0 : s[i - 1][0], az = i == 0 ? 0 : s[i - 1][1];
            P += Math.hypot(s[i][0] - ax, s[i][1] - az);
        }
        double straight = P > 1e-6 ? Math.hypot(s[NF - 1][0], s[NF - 1][1]) / P : 0;
        // rest rule with a wake-up: a still wolf has an all-zero motion history, for which the policy predicts "stay"
        // (net 0.046), so a pure pause rule never ends (0.1.0 bug). Rest at most REST_MAX chunks in a row, then walk the
        // predicted shape at the slowest pace; once the history is non-zero the policy carries on by itself.
        lastStraight = straight;
        if ((straight < PAUSE_NET || P <= 1e-6) && restRun < REST_MAX) {
            restRun++;
            nPauses++;
            phase = "rest";
            chunkWhy = String.format("policy predicts dithering in place (straightness %.2f < %.2f)", straight, PAUSE_NET);
            LedogTelemetry.event(gt, wolf, kind(), "rest 1 s: " + chunkWhy, null);
            return;
        }
        boolean wake = restRun >= REST_MAX && (straight < PAUSE_NET || P <= 1e-6);
        restRun = 0;
        if (P <= 1e-6) {  // degenerate: straight ahead
            for (int i = 0; i < NF; i++) { s[i][0] = 0; s[i][1] = (i + 1) / (float) NF; }
            P = 1;
            straight = 1;
        }
        for (int i = 0; i < NF; i++) { s[i][0] /= P; s[i][1] /= P; }
        double net = straight;
        double L = 4.0 * V_WALK * Math.max(0.3, Math.min(1.0, net));
        double yaw = wolf.yBodyRot, x0 = wolf.getX(), z0 = wolf.getZ();
        // candidate transforms of the shape: identity, mirror, then rotations (fixed order: deterministic)
        double[][] cand = {{0, 1}, {0, -1}, {45, 1}, {-45, 1}, {90, 1}, {-90, 1}};
        for (double[] c : cand) {
            double[] r = BmBrain.right(yaw + c[0]), f = BmBrain.fwd(yaw + c[0]);
            double[][] p = new double[NF][2];
            for (int i = 0; i < NF; i++) {
                double ex = s[i][0] * c[1] * L, ez = s[i][1] * L;
                p[i][0] = x0 + ex * r[0] + ez * f[0];
                p[i][1] = z0 + ex * r[1] + ez * f[1];
            }
            if (LedogMod.walkablePrefix(level, wolf, p, HORIZON_VETO) >= HORIZON_VETO) {
                plan = p;
                origin = new double[]{x0, z0};
                pausing = false;
                lastL = L;
                via = c[1] < 0 ? "mirror" : (c[0] == 0 ? "as predicted" : String.format("rotated %+.0f deg", c[0]));
                chunkWhy = (wake ? "wake-up after resting; " : "") + (fromVision ? "shape from vision" : "shape from motion history");
                com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                d.addProperty("straightness", LedogTelemetry.r3(net));
                d.addProperty("path_len_4s", LedogTelemetry.r3(L));
                d.addProperty("via", via);
                d.addProperty("end_dx", LedogTelemetry.r3(p[NF - 1][0] - x0));
                d.addProperty("end_dz", LedogTelemetry.r3(p[NF - 1][1] - z0));
                LedogTelemetry.event(gt, wolf, kind(), String.format("%swalk: 4-s path %.1f blocks, straightness %.2f, %s%s",
                        wake ? "wake-up, " : "", L, net, via, fromVision ? " (from vision)" : ""), d);
                return;
            }
        }
        nVetoTurns++;
        heading = heading + (turnLeft ? -30.0 : 30.0);
        phase = "veto-turn";
        chunkWhy = "every candidate path hits water or a >1-block step";
        LedogTelemetry.event(gt, wolf, kind(), "no walkable path (as predicted / mirror / +-45 / +-90): turning 30 deg "
                + (turnLeft ? "left" : "right"), null);
        turnLeft = !turnLeft;
    }
}

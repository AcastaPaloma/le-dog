package ai.ledog;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Contract v1 executor: runs a plan's primitives (PlanCode) one after another at 10 Hz on the Le Dog body model
 * (LimitedMoveControl turn limit, inverse actuator curve, vanilla navigation only as the locomotion layer, dry-step
 * safety, water-exit reflex). When the plan ends (or after MAX_PLAN_STEPS) the next plan comes from the queue
 * (external: arena_plan over the control port) or else from the PlanSource (fallback wander; the student later).
 * Every primitive start/end is a telemetry event with commanded vs achieved displacement.
 */
public class PlanBrain implements WolfBrain {
    /** Where plans come from when nothing is queued (fallback wander now; the distilled student later). */
    public interface PlanSource {
        String next(PlanBrain brain, Percepts.Percept p);
    }

    public static final PlanSource WANDER = PlanBrain::wander;
    static final int MAX_PLAN_STEPS = 60;     // 6 s: request the next plan at least this often
    /** Receding-horizon replanning for plans from the source (student): 0 = run whole plans (contract default),
     *  1 = replan after every primitive, 2 = replan every REPLAN_EVERY steps (10 Hz) with a fresh observation. */
    static volatile int REPLAN_MODE = 0, REPLAN_EVERY = 10;
    /** Obstacle sliding: max deviation (deg) from a blocked move direction (0 = off, as Gate E seed 10001). */
    static volatile int SLIDE_MAX = 90;
    static volatile double TURN_MAX = 7.57;   // deg per 0.1 s (body model; live-tunable via head_params "turn")

    public final Percepts.ArenaState st = new Percepts.ArenaState();
    public final int index;
    private final Random rng;
    public PlanSource source;
    public Percepts.GazeModel gaze = Percepts.HEURISTIC_GAZE;
    record Queued(String code, List<Percepts.ObjRef> refs) {
    }

    final ArrayDeque<Queued> queued = new ArrayDeque<>();
    // object references for queued external plans (from the last arena_obs) and for the running plan
    List<Percepts.ObjRef> extRefs = List.of();
    private List<Percepts.ObjRef> refs = List.of();
    private Set<String> prevKeys = Set.of();
    // running plan / primitive
    private List<PlanCode.Prim> plan;
    private String planCode = "", planSrc = "";
    private int pi, planSteps, primSteps, primTimeout;
    private Vec3 primStart, goal, moveDir;
    private double heading0;
    private Double heading;
    private boolean blocked;
    public boolean needsPlan = true;
    public int nPlans, nPrims, nTimeouts, nBlocked, nInvalid, nWater, nSlides;
    private String phase = "init";

    public PlanBrain(int index, long seed) {
        this.index = index;
        this.rng = new Random(seed);
        for (int i = 0; i < 5; i++) st.temper[i] = Math.round(rng.nextDouble() * 10) / 10.0;
        this.source = WANDER;
    }

    @Override
    public String kind() {
        return "plan";
    }

    /** Current observation (also refreshes the external reference list used by queued external plans). */
    public Percepts.Percept observe(ServerLevel level, Wolf wolf) {
        Percepts.Percept p = Percepts.build(level, wolf, wolf.yBodyRot, st, prevKeys, gaze);
        prevKeys = Percepts.keys(p.refs());
        return p;
    }

    public String enqueue(String code, int nObj) {
        PlanCode.parse(code, nObj);  // throws on invalid
        queued.addLast(new Queued(code, extRefs));  // indices refer to the arena_obs snapshot the plan was made from
        return "ok";
    }

    /** Built-in fallback: idle-ish wandering so arena wolves never freeze. */
    static String wander(PlanBrain b, Percepts.Percept p) {
        int r = b.rng.nextInt(10);
        if (r < 3) return "W/2";
        if (r < 4) return "T/2";
        if (r < 6 && !p.refs().isEmpty()) return "L#0;W/1";
        int brg = 30 * (b.rng.nextInt(5) - 2);
        return "M" + (brg >= 0 ? "+" : "") + brg + "/2/w";
    }

    @Override
    public void step(ServerLevel level, Wolf wolf) {
        if (heading == null) heading = (double) wolf.yBodyRot;
        updateState(wolf);
        if (wolf.isInWater()) {  // water-exit reflex (as the other brains)
            if (!phase.equals("water")) {
                nWater++;
                LedogTelemetry.event(level.getGameTime(), wolf, kind(), "in water: swimming to the nearest dry ground", null);
            }
            phase = "water";
            BlockPos dry = LedogMod.dryGround(level, wolf.blockPosition());
            wolf.getNavigation().moveTo(dry.getX() + 0.5, dry.getY(), dry.getZ() + 0.5, 1.0);
            return;
        }
        boolean replan = plan != null && pi < plan.size() && planSrc.equals("source")
                && ((REPLAN_MODE == 1 && pi >= 1 && primSteps == 0) || (REPLAN_MODE == 2 && planSteps >= REPLAN_EVERY));
        if (replan) {
            if (primSteps > 0) endPrim(level, wolf, "replan");
            pi = plan.size();  // fall through: a fresh plan from the current observation
        }
        if (plan == null || pi >= plan.size() || planSteps >= MAX_PLAN_STEPS) {
            if (plan != null && pi < plan.size()) endPrim(level, wolf, "plan_cut");
            startPlan(level, wolf);
        }
        planSteps++;
        if (primSteps == 0) startPrim(level, wolf);
        primSteps++;
        String end = runPrim(level, wolf, plan.get(pi));
        headTick(wolf);
        if (end == null && primSteps >= primTimeout) end = "timeout";
        if (end == null && blocked) end = "blocked";
        if (end != null) {
            endPrim(level, wolf, end);
            pi++;
            primSteps = 0;
        }
    }

    private void updateState(Wolf wolf) {
        double v = Math.hypot(wolf.getDeltaMovement().x, wolf.getDeltaMovement().z) * 20;
        boolean resting = wolf.isInSittingPose() || v < 0.05;
        st.energy = clamp(st.energy - 0.0015 * v + (resting ? 0.002 : 0));
        st.hunger = clamp(st.hunger + 0.0003);
        st.arousal = clamp(st.arousal + (st.hostileNear < 8 ? 0.03 : 0) + (v > 4 ? 0.01 : 0) - 0.01);
        st.restTicks = resting ? 0 : st.restTicks + 1;
    }

    static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private void startPlan(ServerLevel level, Wolf wolf) {
        Percepts.Percept p = null;
        Queued q = queued.pollFirst();
        String code = q == null ? null : q.code();
        planSrc = "external";
        List<Percepts.ObjRef> r = q == null ? extRefs : q.refs();
        if (code == null) {
            p = observe(level, wolf);
            code = source.next(this, p);
            r = p.refs();
            planSrc = source == WANDER ? "wander" : "source";
            needsPlan = true;
        } else {
            needsPlan = queued.isEmpty();
        }
        try {
            plan = PlanCode.parse(code, r.size());
        } catch (IllegalArgumentException e) {
            nInvalid++;
            LedogTelemetry.event(level.getGameTime(), wolf, kind(), "invalid plan " + code + ": " + e.getMessage(), null);
            plan = PlanCode.parse("W/1", 0);
            code = "W/1";
        }
        refs = r;
        if (p != null)
            gazedType = p.gazed() >= 0 && p.gazed() < p.refs().size() ? p.refs().get(p.gazed()).type() : "nothing";
        planCode = code;
        pi = 0;
        planSteps = 0;
        primSteps = 0;
        nPlans++;
        JsonObject d = new JsonObject();
        d.addProperty("plan", code);
        d.addProperty("src", planSrc);
        LedogTelemetry.event(level.getGameTime(), wolf, kind(), "plan (" + planSrc + "): " + code, d);
    }

    private void startPrim(ServerLevel level, Wolf wolf) {
        PlanCode.Prim pr = plan.get(pi);
        primStart = wolf.position();
        heading0 = heading;
        blocked = false;
        goal = null;
        sniffSteps = 0;
        primTimeout = (int) Math.ceil(10 * (2 * pr.nominalSecs() + 1.0));
        if (pr.op() == 'M' || pr.op() == 'R') {
            Vec3 dir;
            if (pr.obj() != null && pr.op() == 'R') {
                Vec3 o = obj(pr);
                dir = o == null ? fwd(heading0 + 180) : wolf.position().subtract(o).multiply(1, 0, 1).normalize();
            } else {
                double b = heading0 + pr.bearing() + (pr.op() == 'R' ? 180 : 0);
                dir = fwd(b);
            }
            goal = wolf.position().add(dir.scale(pr.dist()));
            moveDir = dir;
        }
        if (pr.op() == 'T' || pr.op() == 'Y') wolf.setInSittingPose(true);
        if (pr.op() == 'P') wolf.getJumpControl().jump();
        st.last = String.valueOf(pr.op());
        nPrims++;
        JsonObject d = new JsonObject();
        d.addProperty("prim", pr.code());
        d.addProperty("i", pi);
        d.addProperty("commanded_dist", pr.dist() == null ? 0 : pr.dist());
        LedogTelemetry.event(level.getGameTime(), wolf, kind(), "prim start " + pr.code(), d);
    }

    private void endPrim(ServerLevel level, Wolf wolf, String how) {
        PlanCode.Prim pr = plan.get(pi);
        if (pr.op() == 'T' || pr.op() == 'Y') wolf.setInSittingPose(false);
        wolf.getNavigation().stop();
        if (how.equals("timeout")) nTimeouts++;
        if (how.equals("blocked")) nBlocked++;
        double ach = primStart == null ? 0 : Math.hypot(wolf.getX() - primStart.x, wolf.getZ() - primStart.z);
        JsonObject d = new JsonObject();
        d.addProperty("prim", pr.code());
        d.addProperty("end", how);
        d.addProperty("steps", primSteps);
        d.addProperty("commanded_dist", pr.dist() == null ? 0 : pr.dist());
        d.addProperty("achieved_dist", LedogTelemetry.r3(ach));
        if (goal != null) d.addProperty("goal_err", LedogTelemetry.r3(Math.hypot(wolf.getX() - goal.x, wolf.getZ() - goal.z)));
        LedogTelemetry.event(level.getGameTime(), wolf, kind(), "prim end " + pr.code() + " (" + how + ")", d);
    }

    /** Execute one 10 Hz step of a primitive; return a non-null end reason when it finished. */
    private String runPrim(ServerLevel level, Wolf wolf, PlanCode.Prim pr) {
        int secsSteps = pr.secs() == null ? 0 : (int) Math.round(pr.secs() * 10);
        switch (pr.op()) {
            case 'M', 'R' -> {
                phase = pr.op() == 'M' ? "move" : "flee";
                if (goal == null || Math.hypot(wolf.getX() - goal.x, wolf.getZ() - goal.z) < 1.0) return "done";
                double prog = (wolf.getX() - primStart.x) * moveDir.x + (wolf.getZ() - primStart.z) * moveDir.z;
                if (prog >= pr.dist() - 0.5) return "done";  // turn-limited heading would otherwise orbit the goal
                moveToward(level, wolf, goal, pr.bps());
                return null;
            }
            case 'L' -> {
                phase = "look";
                wolf.getNavigation().stop();
                lookAt(wolf, target(wolf, pr));
                return primSteps >= 10 ? "done" : null;
            }
            case 'S' -> {
                phase = "sniff";
                Vec3 t = pr.obj() != null ? obj(pr) : wolf.position().add(fwd(heading0 + pr.bearing()));
                if (t == null) return "lost";
                if (goal == null && Math.hypot(wolf.getX() - t.x, wolf.getZ() - t.z) > 1.5 && primSteps < primTimeout / 2) {
                    moveToward(level, wolf, t, 2.9);
                    return null;
                }
                if (goal == null) goal = wolf.position();  // arrived: sniffing starts now
                wolf.getNavigation().stop();
                lookAt(wolf, new Vec3(t.x, wolf.getY(), t.z));
                return ++sniffSteps >= secsSteps ? resetSniff("done") : null;
            }
            case 'T', 'Y', 'W' -> {
                phase = pr.op() == 'W' ? "wait" : "sit";
                wolf.getNavigation().stop();
                return primSteps >= secsSteps ? "done" : null;
            }
            case 'B' -> {
                phase = "bark";
                wolf.getNavigation().stop();
                if (primSteps % 6 == 1) wolf.playAmbientSound();
                st.arousal = clamp(st.arousal + 0.02);
                return primSteps >= 6 * pr.n() ? "done" : null;
            }
            case 'F' -> {
                phase = "follow";
                Vec3 t = obj(pr);
                if (t == null) return "lost";
                if (Math.hypot(wolf.getX() - t.x, wolf.getZ() - t.z) > 2.0) moveToward(level, wolf, t, 4.0);
                else {
                    wolf.getNavigation().stop();
                    lookAt(wolf, t);
                }
                return primSteps >= secsSteps ? "done" : null;
            }
            case 'P' -> {
                phase = "play";
                wolf.getNavigation().stop();
                Vec3 t = refs.isEmpty() ? null : refs.get(0).pos();
                if (t != null) lookAt(wolf, t);
                return primSteps >= secsSteps ? "done" : null;
            }
            default -> {
                return "done";
            }
        }
    }

    // ---- live decision label (/ledog debug) ----
    public String presetName;
    private String gazedType = "-";
    private String lastLabel = "";

    private static String dir(PlanCode.Prim p, List<Percepts.ObjRef> refs) {
        if (p.obj() != null) return p.obj() < refs.size() ? refs.get(p.obj()).type() : "#" + p.obj();
        if (p.bearing() == null || p.bearing() == 0) return "ahead";
        int b = p.bearing();
        if (Math.abs(b) == 180) return "behind";
        return Math.abs(b) + "\u00b0 " + (b > 0 ? "right" : "left");
    }

    private static String secs(PlanCode.Prim p) {
        if (p.secs() == null) return "";
        double v = p.secs();
        return " " + (v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v)) + "s";
    }

    /** One primitive in plain words, e.g. "trot 4 to 30\u00b0 right", "sniff bone 2s", "bark x3". */
    static String words(PlanCode.Prim p, List<Percepts.ObjRef> refs) {
        String gait = p.speed() == null ? "walk" : switch (p.speed()) {
            case 's' -> "creep";
            case 't' -> "trot";
            case 'r' -> "run";
            default -> "walk";
        };
        return switch (p.op()) {
            case 'M' -> gait + " " + p.dist() + " to " + dir(p, refs);
            case 'R' -> "flee " + dir(p, refs) + " (" + gait + " " + p.dist() + ")";
            case 'L' -> "look at " + dir(p, refs);
            case 'S' -> "sniff " + dir(p, refs) + secs(p);
            case 'F' -> "follow " + dir(p, refs) + secs(p);
            case 'T' -> "sit" + secs(p);
            case 'Y' -> "lie down" + secs(p);
            case 'W' -> "wait" + secs(p);
            case 'P' -> "play-bow" + secs(p);
            case 'B' -> "bark x" + (p.n() == null ? 1 : p.n());
            default -> p.code();
        };
    }

    /** Name-tag label: [preset] done > CURRENT > next ... | sees <gazed>. null when unchanged since the last call. */
    public Component debugLabel() {
        StringBuilder key = new StringBuilder(planCode).append('|').append(pi).append('|').append(gazedType);
        if (key.toString().equals(lastLabel)) return null;
        lastLabel = key.toString();
        MutableComponent c = Component.literal("[" + (presetName == null ? (source == WANDER ? "wander" : "student") : presetName) + "] ")
                .withStyle(ChatFormatting.AQUA);
        if (plan != null)
            for (int i = 0; i < plan.size(); i++) {
                if (i > 0) c.append(Component.literal(" \u203a ").withStyle(ChatFormatting.DARK_GRAY));
                String w = words(plan.get(i), refs);
                c.append(i == pi ? Component.literal(w).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                        : Component.literal(w).withStyle(i < pi ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
            }
        c.append(Component.literal("  | sees " + gazedType).withStyle(ChatFormatting.GREEN));
        return c;
    }

    public void clearDebugLabel() {
        lastLabel = "";
    }

    private int sniffSteps;

    private String resetSniff(String r) {
        sniffSteps = 0;
        return r;
    }

    private Vec3 obj(PlanCode.Prim pr) {
        if (pr.obj() == null || pr.obj() >= refs.size()) return null;
        Percepts.ObjRef r = refs.get(pr.obj());
        return r.alive() ? r.pos() : null;
    }

    private Vec3 target(Wolf wolf, PlanCode.Prim pr) {
        if (pr.obj() != null) {
            Vec3 o = obj(pr);
            if (o != null) return o;
        }
        int b = pr.bearing() == null ? 0 : pr.bearing();
        return wolf.position().add(fwd(heading0 + b).scale(4));
    }

    static Vec3 fwd(double yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(y), 0, Math.cos(y));
    }

    /** Head kinematics (Gate E iteration (a), PROMOTION.md). v1 (vanilla LookControl) snapped the head; v2a (rate limit
     *  only) turned it continuously because plans retarget every ~1 s. Now: the brain owns the head yaw. It HOLDS while
     *  the look target is within HEAD_DEADBAND deg, then turns proportionally (HEAD_GAIN x error per 0.1-s step, at most
     *  HEAD_RATE deg per step) until within 2 deg; a small Ornstein-Uhlenbeck offset (HEAD_SIGMA deg, HEAD_TAU s) adds
     *  idle head motion. Parameters are live-tunable (control op head_params) for choosing them against the EgoPet VAL
     *  reference on training seeds. */
    static volatile double HEAD_RATE = 2.5, HEAD_GAIN = 0.15, HEAD_DEADBAND = 25.0, HEAD_SIGMA = 0.7, HEAD_TAU = 1.0;  // sweep H2 (VAL, training seeds 205+206)
    private double headNoise;
    private Double headYaw, wantYaw;
    private boolean turning;

    private void lookAt(Wolf wolf, Vec3 t) {
        if (t == null) return;
        double dx = t.x - wolf.getX(), dz = t.z - wolf.getZ();
        if (Math.hypot(dx, dz) < 1e-3) return;
        wantYaw = Math.toDegrees(Math.atan2(-dx, dz));
    }

    private void headTick(Wolf wolf) {
        double dt = 0.1;  // brain steps at 10 Hz
        if (headYaw == null) headYaw = (double) wolf.getYHeadRot();
        double want = wantYaw != null ? wantYaw : wolf.yBodyRot;
        double err = BmBrain.wrap(want - headYaw);
        if (!turning && Math.abs(err) > HEAD_DEADBAND) turning = true;
        if (turning) {
            headYaw += Math.max(-HEAD_RATE, Math.min(HEAD_RATE, HEAD_GAIN * err));
            if (Math.abs(BmBrain.wrap(want - headYaw)) < 2.0) turning = false;
        }
        double off = BmBrain.wrap(headYaw - wolf.yBodyRot);  // anatomical limit: head within 75 deg of the body
        if (Math.abs(off) > 75) {
            headYaw = wolf.yBodyRot + Math.signum(off) * 75;
            // target beyond the neck: a standing dog turns its BODY toward it (in place, body turn limit) instead of the
            // head chasing and being clamped back every step (seed-206 check: head 3.5 deg/0.1 s while sniffing/looking)
            double v = Math.hypot(wolf.getDeltaMovement().x, wolf.getDeltaMovement().z) * 20;
            if (v < 0.3 && heading != null) {
                heading = heading + Math.max(-TURN_MAX, Math.min(TURN_MAX, BmBrain.wrap(want - heading)));
                wolf.setYRot(heading.floatValue());
                wolf.setYBodyRot(heading.floatValue());
            }
            if (Math.abs(BmBrain.wrap(want - wolf.yBodyRot)) <= 75) turning = Math.abs(BmBrain.wrap(want - headYaw)) >= 2.0;
        }
        headNoise += -headNoise / HEAD_TAU * dt + HEAD_SIGMA * Math.sqrt(2 * dt / HEAD_TAU) * rng.nextGaussian();
        Vec3 f = fwd(headYaw + headNoise).scale(4);
        wolf.getLookControl().setLookAt(wolf.getX() + f.x, wolf.getEyeY(), wolf.getZ() + f.z, 40f, 30f);
        wantYaw = null;
    }

    /** Heading-limited step toward a world point at a given speed (blocks/s), navigation as the locomotion layer.
     *  "Blocked" means the DESIRED direction is not walkable (water, >1-block step, fence/wall, platform edge); if only
     *  the current, not-yet-turned heading is blocked, the wolf turns in place toward the desired direction instead. */
    private void moveToward(ServerLevel level, Wolf wolf, Vec3 t, double bps) {
        double dx = t.x - wolf.getX(), dz = t.z - wolf.getZ();
        if (Math.hypot(dx, dz) < 0.05) return;
        double want = Math.toDegrees(Math.atan2(-dx, dz));
        int fy = (int) Math.floor(wolf.getY());
        if (!clear(level, wolf, want, fy)) {
            // obstacle sliding (v3 checks: 0-30 deg moves made while facing a fence were blocked on the spot): take the
            // nearest clear direction within SLIDE_MAX deg of the wanted one, preferring the side of the current heading
            Double alt = null;
            if (SLIDE_MAX > 0) {
                double side = Math.signum(BmBrain.wrap(heading - want));
                if (side == 0) side = 1;
                for (int d = 30; d <= SLIDE_MAX && alt == null; d += 30)
                    for (double sg : new double[]{side, -side})
                        if (alt == null && clear(level, wolf, want + sg * d, fy)) alt = want + sg * d;
            }
            if (alt == null) {
                wolf.getNavigation().stop();
                blocked = true;
                return;
            }
            nSlides++;
            want = alt;
        }
        heading = heading + Math.max(-TURN_MAX, Math.min(TURN_MAX, BmBrain.wrap(want - heading)));
        Vec3 f = fwd(heading);
        double tx = wolf.getX() + 2.0 * f.x, tz = wolf.getZ() + 2.0 * f.z;
        if (clear(level, wolf, heading, fy)) {
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(tx), (int) Math.floor(tz));
            wolf.getNavigation().moveTo(tx, gy, tz, BmBrain.modifierFor(bps));
        } else {
            wolf.getNavigation().stop();  // turning in place toward a clear desired direction
        }
        lookAt(wolf, new Vec3(tx, wolf.getEyeY(), tz));
    }

    /** Midpoint and end of a 2-block step along `yawDeg` are dry, within a 1-block step, and not a fence/wall. */
    private static boolean clear(ServerLevel level, Wolf wolf, double yawDeg, int fy) {
        Vec3 f = fwd(yawDeg);
        double mx = wolf.getX() + f.x, mz = wolf.getZ() + f.z, tx = wolf.getX() + 2 * f.x, tz = wolf.getZ() + 2 * f.z;
        return LedogMod.dryStep(level, mx, mz, fy) && LedogMod.dryStep(level, tx, tz, fy)
                && !barrier(level, mx, mz, fy) && !barrier(level, tx, tz, fy);
    }

    /** Fences and walls pass the 1-block step check (collision 1.5 high) but cannot be crossed: treat as blocked. */
    static boolean barrier(ServerLevel level, double x, double z, int y) {
        BlockPos p = BlockPos.containing(x, y, z);
        var s = level.getBlockState(p);
        return s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS);
    }

    @Override
    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("phase", phase);
        o.addProperty("plan", planCode);
        o.addProperty("plan_src", planSrc);
        o.addProperty("prim", plan == null || pi >= plan.size() ? "" : plan.get(pi).code());
        o.addProperty("prim_step", primSteps);
        o.addProperty("energy", LedogTelemetry.r3(st.energy));
        o.addProperty("hunger", LedogTelemetry.r3(st.hunger));
        o.addProperty("arousal", LedogTelemetry.r3(st.arousal));
        o.addProperty("heading", heading == null ? 0 : LedogTelemetry.r3(heading));
        o.addProperty("gazed", gazedType);
        return o;
    }
}

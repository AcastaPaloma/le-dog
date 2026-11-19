package ai.ledog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluation bench (user request 2026-11-18): a grid of IDENTICAL fenced plots on a flat world, one dog per plot, so
 * policy versions and personalities can be compared in the same little environment. Every plot holds the same layout:
 * a small open house, an oak tree, a pond, a bush, a flower bed, a short wall, stairs, a bone, a ball (snowball item), a
 * steak, and one villager (wandering, invulnerable). No natural mobs, fixed noon, no weather. Wolves that leave their
 * plot (should not happen: fences) are put back and counted. Telemetry: the normal 20 Hz tick log (LedogTelemetry) for
 * every bench wolf; the Python side (ledog/bench.py) records which wolf id is which plot/policy/personality.
 *
 * Control-port ops:
 *   bench_build {cols, rows, size, gap, seed, dogs: [{policy: student|wander|bm, model: "student.onnx", preset, temper}]}
 *   bench_status   per dog: plot, id, policy, preset, pos, plan, prim, phase, gazed, counters, escapes
 *   bench_labels {on}   decision name tags above every bench dog
 *   bench_clear
 */
public class Bench {
    static final int Y_DEFAULT = -60;  // superflat surface (grass at y = -61)

    static final class Plot {
        final int k, x0, z0, y0, size;
        final JsonObject cfg;
        Wolf wolf;
        WolfBrain brain;
        Entity trainer;
        final List<Entity> props = new ArrayList<>();
        int escapes;

        Plot(int k, int x0, int z0, int y0, int size, JsonObject cfg) {
            this.k = k;
            this.x0 = x0;
            this.z0 = z0;
            this.y0 = y0;
            this.size = size;
            this.cfg = cfg;
        }

        BlockPos at(int dx, int dy, int dz) {
            return new BlockPos(x0 + dx, y0 + dy, z0 + dz);
        }

        boolean inside(Entity e) {
            return e.getX() >= x0 - 0.5 && e.getX() <= x0 + size + 0.5 && e.getZ() >= z0 - 0.5 && e.getZ() <= z0 + size + 0.5;
        }
    }

    private final LedogMod mod;
    private final List<Plot> plots = new ArrayList<>();
    private long spawnAtTick = -1;
    private long seed;
    private boolean labels = true;
    private int size = 32, gap = 32;

    Bench(LedogMod mod) {
        this.mod = mod;
    }

    public void tick(MinecraftServer server, long tick) {
        if (plots.isEmpty()) return;
        ServerLevel level = server.overworld();
        if (spawnAtTick >= 0 && tick >= spawnAtTick) {
            spawnAtTick = -1;
            for (Plot p : plots) {
                try {
                    spawnProps(level, p);
                    spawnDog(level, p);
                } catch (Exception e) {
                    LedogMod.LOG.error("bench spawn failed (plot " + p.k + ")", e);
                }
            }
        }
        for (Plot p : plots) {
            if (p.wolf == null) continue;
            if (!p.wolf.isAlive()) {
                try {
                    spawnDog(level, p);
                } catch (Exception e) {
                    LedogMod.LOG.error("bench respawn failed", e);
                }
                continue;
            }
            if (!p.inside(p.wolf)) {  // confinement (fences should already hold)
                p.escapes++;
                p.wolf.teleportTo(p.x0 + p.size / 2.0, p.y0 + 1, p.z0 + p.size / 2.0);
            }
            boolean rl = p.brain instanceof RlBrain;
            if (tick % 2 == 0) {
                p.brain.step(level, p.wolf);
                if (labels && p.brain instanceof PlanBrain b) {
                    Component lab = b.debugLabel();
                    if (lab != null) p.wolf.setCustomName(lab);
                }
            }
            if (!rl || p.cfg.has("telemetry")) LedogTelemetry.tick(level.getGameTime(), p.wolf, p.brain);  // rl: opt-in (realism scoring)
        }
        if (tick % 1200 == 0) noon(server);  // keep it noon (ADVANCE_TIME off; belt and braces)
    }

    public JsonObject handle(MinecraftServer server, String op, JsonObject m, long tick) throws Exception {
        ServerLevel level = server.overworld();
        JsonObject r = new JsonObject();
        switch (op) {
            case "bench_build" -> {
                clear(level);
                int cols = m.has("cols") ? m.get("cols").getAsInt() : 8;
                int rows = m.has("rows") ? m.get("rows").getAsInt() : 4;
                size = m.has("size") ? m.get("size").getAsInt() : 32;
                gap = m.has("gap") ? m.get("gap").getAsInt() : 32;
                seed = m.has("seed") ? m.get("seed").getAsLong() : 0L;
                JsonArray dogs = m.has("dogs") ? m.getAsJsonArray("dogs") : new JsonArray();
                server.setDifficulty(Difficulty.PEACEFUL, true);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                server.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
                server.getGameRules().set(GameRules.SPAWN_PATROLS, false, server);
                server.getGameRules().set(GameRules.SPAWN_PHANTOMS, false, server);
                server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                noon(server);
                int ox = m.has("x0") ? m.get("x0").getAsInt() : 0, oz = m.has("z0") ? m.get("z0").getAsInt() : 0;
                int y0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox, oz) - 1;  // floor block level
                if (y0 < -63) y0 = Y_DEFAULT - 1;
                int pitch = size + gap;
                JsonArray a = new JsonArray();
                for (int k = 0; k < cols * rows; k++) {
                    JsonObject cfg = k < dogs.size() ? dogs.get(k).getAsJsonObject() : new JsonObject();
                    Plot p = new Plot(k, ox + (k % cols) * pitch, oz + (k / cols) * pitch, y0, size, cfg);
                    for (int cx = (p.x0 - 2) >> 4; cx <= (p.x0 + size + 2) >> 4; cx++)
                        for (int cz = (p.z0 - 2) >> 4; cz <= (p.z0 + size + 2) >> 4; cz++) {
                            level.setChunkForced(cx, cz, true);
                            level.getChunk(cx, cz);
                        }
                    build(level, p);
                    plots.add(p);
                    JsonObject j = new JsonObject();
                    j.addProperty("k", k);
                    j.addProperty("x0", p.x0);
                    j.addProperty("z0", p.z0);
                    j.addProperty("y0", p.y0);
                    j.add("cfg", cfg);
                    a.add(j);
                }
                spawnAtTick = tick + 40;
                r.add("plots", a);
                r.addProperty("size", size);
                r.addProperty("pending", true);
            }
            case "bench_status" -> {
                JsonArray a = new JsonArray();
                for (Plot p : plots) {
                    JsonObject j = new JsonObject();
                    j.addProperty("k", p.k);
                    j.add("cfg", p.cfg);
                    j.addProperty("x0", p.x0);
                    j.addProperty("z0", p.z0);
                    j.addProperty("escapes", p.escapes);
                    if (p.wolf != null && p.wolf.isAlive()) {
                        j.addProperty("id", p.wolf.getId());
                        j.addProperty("x", LedogTelemetry.r3(p.wolf.getX()));
                        j.addProperty("z", LedogTelemetry.r3(p.wolf.getZ()));
                    }
                    if (p.brain instanceof PlanBrain b) {
                        j.add("status", b.status());
                        j.add("temper", b.st.temperJson());
                        j.addProperty("plans", b.nPlans);
                        j.addProperty("blocked", b.nBlocked);
                        j.addProperty("invalid", b.nInvalid);
                        j.addProperty("water", b.nWater);
                    } else if (p.brain != null) j.addProperty("kind", p.brain.kind());
                    a.add(j);
                }
                r.add("plots", a);
                r.addProperty("ready", !plots.isEmpty() && spawnAtTick < 0);
            }
            case "bench_labels" -> {
                labels = !m.has("on") || m.get("on").getAsBoolean();
                for (Plot p : plots) {
                    if (p.wolf == null) continue;
                    p.wolf.setCustomNameVisible(labels);
                    if (!labels) p.wolf.setCustomName(Component.literal(name(p)));
                    else if (p.brain instanceof PlanBrain b) b.clearDebugLabel();
                }
                r.addProperty("labels", labels);
            }
            case "bench_rl" -> {  // RL step: apply resets, actions, labels, effects; return observations (ledog/rl_bench.py)
                if (m.has("resets"))
                    for (JsonElement e : m.getAsJsonArray("resets")) {
                        JsonObject o = e.getAsJsonObject();
                        Plot p = plots.get(o.get("k").getAsInt());
                        if (p.wolf == null || !(p.brain instanceof RlBrain b)) continue;
                        double yaw = o.get("yaw").getAsDouble();
                        p.wolf.teleportTo(p.x0 + o.get("x").getAsDouble(), p.y0 + 1, p.z0 + o.get("z").getAsDouble());
                        p.wolf.setDeltaMovement(0, 0, 0);
                        b.heading = yaw;
                        b.init = true;
                        b.action = 0;
                        if (p.trainer != null && o.has("tx"))
                            p.trainer.teleportTo(p.x0 + o.get("tx").getAsDouble(), p.y0 + 1, p.z0 + o.get("tz").getAsDouble());
                    }
                if (m.has("acts"))
                    for (JsonElement e : m.getAsJsonArray("acts")) {
                        JsonArray a = e.getAsJsonArray();
                        Plot p = plots.get(a.get(0).getAsInt());
                        if (p.brain instanceof RlBrain b) b.action = a.get(1).getAsInt();
                    }
                if (m.has("labels"))
                    for (JsonElement e : m.getAsJsonArray("labels")) {
                        JsonArray a = e.getAsJsonArray();
                        Plot p = plots.get(a.get(0).getAsInt());
                        if (p.wolf != null) {
                            p.wolf.setCustomName(Component.literal(a.get(1).getAsString()));
                            p.wolf.setCustomNameVisible(true);
                        }
                        if (a.size() > 2 && p.trainer != null) p.trainer.setCustomName(Component.literal(a.get(2).getAsString()));
                    }
                if (m.has("fx"))
                    for (JsonElement e : m.getAsJsonArray("fx")) {
                        JsonArray a = e.getAsJsonArray();
                        Plot p = plots.get(a.get(0).getAsInt());
                        if (p.wolf != null)  // 7 = hearts (treat), 6 = smoke (no treat)
                            level.broadcastEntityEvent(p.wolf, a.get(1).getAsString().equals("good") ? (byte) 7 : (byte) 6);
                    }
                JsonArray obs = new JsonArray();
                for (Plot p : plots) {
                    JsonObject o = new JsonObject();
                    o.addProperty("k", p.k);
                    if (p.wolf != null && p.wolf.isAlive() && p.brain instanceof RlBrain b) {
                        o.addProperty("x", LedogTelemetry.r3(p.wolf.getX() - p.x0));
                        o.addProperty("z", LedogTelemetry.r3(p.wolf.getZ() - p.z0));
                        o.addProperty("y", LedogTelemetry.r3(p.wolf.getY() - p.y0));
                        o.addProperty("heading", LedogTelemetry.r3(b.heading));
                        var v = p.wolf.getDeltaMovement();
                        o.addProperty("speed", LedogTelemetry.r3(Math.hypot(v.x, v.z) * 20.0));
                        o.addProperty("sitting", b.sitting);
                        o.addProperty("ground", p.wolf.onGround());
                        o.addProperty("jumped", b.jumped);
                        o.addProperty("water", p.wolf.isInWater());
                        b.jumped = false;
                        JsonArray rays = new JsonArray();
                        for (int i = 0; i < 8; i++) rays.add(LedogTelemetry.r3(RlBrain.ray(level, p.wolf, b.heading + 45 * i, 6)));
                        o.add("rays", rays);
                    }
                    obs.add(o);
                }
                r.add("obs", obs);
                r.addProperty("ready", spawnAtTick < 0);
            }
            case "bench_clear" -> {
                clear(level);
                r.addProperty("result", "ok");
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        }
        return r;
    }

    static void noon(MinecraftServer server) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
    }

    static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsString();
    }

    static String name(Plot p) {
        return "#" + p.k + " " + str(p.cfg, "policy", "student") + " " + str(p.cfg, "preset", "");
    }

    private void clear(ServerLevel level) {
        for (Plot p : plots) {
            for (Entity e : p.props) e.discard();
            if (p.wolf != null) p.wolf.discard();
            for (int cx = (p.x0 - 2) >> 4; cx <= (p.x0 + p.size + 2) >> 4; cx++)
                for (int cz = (p.z0 - 2) >> 4; cz <= (p.z0 + p.size + 2) >> 4; cz++) level.setChunkForced(cx, cz, false);
        }
        plots.clear();
        spawnAtTick = -1;
    }

    // ------------------------------------------------------------ the plot template (identical in every plot)
    private static void set(ServerLevel l, BlockPos p, BlockState st) {
        l.setBlock(p, st, 2);
    }

    private static BlockState b(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }

    private void build(ServerLevel l, Plot p) {
        int S = p.size;
        BlockState air = b(Blocks.AIR), plank = b(Blocks.OAK_PLANKS);
        for (int dx = -1; dx <= S; dx++)
            for (int dz = -1; dz <= S; dz++) {
                set(l, p.at(dx, -1, dz), b(Blocks.DIRT));
                set(l, p.at(dx, 0, dz), b(Blocks.GRASS_BLOCK));
                for (int dy = 1; dy <= 8; dy++) set(l, p.at(dx, dy, dz), air);
                if (dx == -1 || dz == -1 || dx == S || dz == S) set(l, p.at(dx, 1, dz), b(Blocks.OAK_FENCE));
            }
        // house: 6x6 planks, 3 high, 2-wide doorway facing +z, flat plank roof, window holes
        int hx = 3, hz = 3;
        for (int dx = 0; dx < 6; dx++)
            for (int dz = 0; dz < 6; dz++) {
                boolean wall = dx == 0 || dz == 0 || dx == 5 || dz == 5;
                for (int dy = 1; dy <= 3; dy++) {
                    boolean door = dz == 5 && (dx == 2 || dx == 3) && dy <= 2;
                    boolean window = dy == 2 && ((dx == 0 || dx == 5) && dz == 2 || dz == 0 && dx == 3);
                    if (wall && !door && !window) set(l, p.at(hx + dx, dy, hz + dz), plank);
                }
                set(l, p.at(hx + dx, 4, hz + dz), b(Blocks.OAK_SLAB));
            }
        // tree: 4-log trunk + leaf crown
        int tx = S - 8, tz = 6;
        BlockState leaf = b(Blocks.OAK_LEAVES).setValue(LeavesBlock.PERSISTENT, true);
        for (int dy = 1; dy <= 4; dy++) set(l, p.at(tx, dy, tz), b(Blocks.OAK_LOG));
        for (int ax = -2; ax <= 2; ax++)
            for (int az = -2; az <= 2; az++)
                for (int dy = 4; dy <= 6; dy++) {
                    if (ax == 0 && az == 0 && dy == 4) continue;
                    if (Math.abs(ax) + Math.abs(az) > (dy == 6 ? 1 : 3)) continue;
                    set(l, p.at(tx + ax, dy, tz + az), leaf);
                }
        // pond 4x3 (water in the floor)
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 3; dz++) set(l, p.at(S - 10 + dx, 0, S - 9 + dz), b(Blocks.WATER));
        // bush, flower bed, tall grass, short wall, stairs
        set(l, p.at(14, 1, S - 6), leaf);
        set(l, p.at(15, 1, S - 6), leaf);
        BlockState[] fl = {b(Blocks.POPPY), b(Blocks.DANDELION), b(Blocks.CORNFLOWER)};
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 2; dz++) set(l, p.at(4 + dx, 1, S - 8 + dz), fl[(dx + dz) % 3]);
        int[][] grass = {{12, 14}, {20, 18}, {9, 22}, {24, 24}, {17, 10}, {26, 15}};
        for (int[] g : grass) if (g[0] < S && g[1] < S) set(l, p.at(g[0], 1, g[1]), b(Blocks.SHORT_GRASS));
        for (int dx = 0; dx < 4; dx++) set(l, p.at(12 + dx, 1, 12), b(Blocks.COBBLESTONE_WALL));
        set(l, p.at(S - 4, 1, S / 2), b(Blocks.OAK_STAIRS));
    }

    private void spawnProps(ServerLevel l, Plot p) throws Exception {
        int S = p.size;
        Object[][] items = {{Items.BONE, 18, 8}, {Items.SNOWBALL, 9, 17}, {Items.COOKED_BEEF, S - 6, 14}};
        for (Object[] it : items) {
            BlockPos q = p.at((Integer) it[1], 1, (Integer) it[2]);
            ItemEntity ie = new ItemEntity(l, q.getX() + 0.5, q.getY() + 0.1, q.getZ() + 0.5,
                    new ItemStack((net.minecraft.world.item.Item) it[0]));
            ie.setNeverPickUp();
            ie.setUnlimitedLifetime();
            ie.setDeltaMovement(0, 0, 0);
            l.addFreshEntity(ie);
            p.props.add(ie);
        }
        if (str(p.cfg, "policy", "").equals("rl")) {  // RL plots: a trainer marker instead of the villager
            Entity t = EntityTypes.ARMOR_STAND.spawn(l, p.at(S / 2, 1, 4), EntitySpawnReason.COMMAND);
            if (t != null) {
                Arenas.invulnerable(t);
                t.setCustomName(Component.literal("trainer"));
                t.setCustomNameVisible(true);
                p.trainer = t;
                p.props.add(t);
            }
            return;
        }
        Entity v = EntityTypes.VILLAGER.spawn(l, p.at(10, 1, S - 4), EntitySpawnReason.COMMAND);
        if (v != null) {
            Arenas.invulnerable(v);
            if (v instanceof Mob mob) mob.setPersistenceRequired();
            p.props.add(v);
        }
    }

    private void spawnDog(ServerLevel l, Plot p) throws Exception {
        if (p.wolf != null) p.wolf.discard();
        Wolf w = EntityTypes.WOLF.spawn(l, p.at(p.size / 2, 1, p.size / 2 + 2), EntitySpawnReason.COMMAND);
        if (w == null) throw new IllegalStateException("bench wolf spawn failed");
        LedogMod.clearGoals(w);
        Field mc = Mob.class.getDeclaredField("moveControl");
        mc.setAccessible(true);
        mc.set(w, new LimitedMoveControl(w, 3.785f));
        w.setPersistenceRequired();
        Arenas.invulnerable(w);
        w.setCustomName(Component.literal(name(p)));
        w.setCustomNameVisible(labels);
        p.wolf = w;
        if (p.brain == null) p.brain = makeBrain(p);
    }

    private WolfBrain makeBrain(Plot p) throws Exception {
        long s = seed * 7919L + p.k;
        String policy = str(p.cfg, "policy", "student");
        if (policy.equals("bm")) return mod.makeBrain("bm", s);
        if (policy.equals("rl")) return new RlBrain();
        PlanBrain b = new PlanBrain(p.k, s);
        if (LedogModels.available("gaze")) b.gaze = new OnnxGaze();
        if (policy.equals("student")) {
            String model = str(p.cfg, "model", "student.onnx");
            if (!java.nio.file.Files.exists(LedogModels.dir().resolve(model)))
                throw new IllegalStateException("missing model " + model);
            b.source = new StudentSource(new java.util.Random(s), 1.0, model);
        }  // "wander": PlanBrain's built-in WANDER source (baseline)
        String preset = str(p.cfg, "preset", null);
        if (preset != null && LedogMod.PRESETS.containsKey(preset)) {
            System.arraycopy(LedogMod.PRESETS.get(preset), 0, b.st.temper, 0, 5);
            b.presetName = preset;
        } else if (p.cfg.has("temper")) {
            JsonObject t = p.cfg.getAsJsonObject("temper");
            String[] k = {"curious", "bold", "energetic", "social", "playful"};
            for (int i = 0; i < 5; i++) if (t.has(k[i])) b.st.temper[i] = t.get(k[i]).getAsDouble();
            b.presetName = str(p.cfg, "label", "custom");
        } else b.presetName = "random";
        if (!policy.equals("student")) b.presetName = policy + (b.presetName == null ? "" : ":" + b.presetName);
        return b;
    }
}

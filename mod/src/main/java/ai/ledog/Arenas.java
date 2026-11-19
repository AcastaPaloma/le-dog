package ai.ledog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Multi-wolf arenas for teacher/student data collection (contract v1): N separate 48x48 grass platforms high in the sky
 * (y = Y0), fenced, 96 blocks apart along x, chunks force-loaded, each with one Le Dog wolf (PlanBrain, body model) and
 * random props: passive animals (wandering), NoAI hostile props (zombie/skeleton with helmets, creeper; silent,
 * invulnerable, cannot explode without AI), items (bone, food, ball), trees, bushes, grass/flowers, sometimes a water
 * pool, stairs and a wall segment, and an armor stand standing in for a player. Hostile props need difficulty > peaceful,
 * so arenas set EASY and turn natural mob/monster spawning off.
 *
 * Control-port ops (LedogMod.execute delegates every op starting with "arena_"):
 *   arena_spawn {n, seed}        build n arenas and (2 s later, once chunks tick) spawn wolves + props
 *   arena_obs                    per wolf: id, k, obs (contract v1), temper, plan, prim, needs_plan, queued, pos
 *   arena_plan {k, code}         queue a plan for wolf k (indices refer to its last arena_obs); ok / error
 *   arena_reset {k}              rebuild arena k's props
 *   arena_clear                  remove all arenas
 *   arena_stats                  per-wolf executor counters
 */
public class Arenas {
    static boolean studentSource;  // arena_spawn {source: "student"}: wolves planned by the in-mod student (Gate E)

    static final int Y0 = 150, SIZE = 48, SPACING = 96, X0 = 4000, Z0 = 4000;

    static final class Slot {
        final int k;
        final int x0, z0;
        final Random rng;
        Wolf wolf;
        PlanBrain brain;
        final List<Entity> props = new ArrayList<>();

        Slot(int k, long seed) {
            this.k = k;
            this.x0 = X0 + k * SPACING;
            this.z0 = Z0;
            this.rng = new Random(seed * 1000003L + k);
        }

        BlockPos at(int dx, int dy, int dz) {
            return new BlockPos(x0 + dx, Y0 + dy, z0 + dz);
        }
    }

    private final List<Slot> slots = new ArrayList<>();
    private long spawnAtTick = -1;
    private long seed;

    public void tick(MinecraftServer server, long tick) {
        ServerLevel level = server.overworld();
        if (spawnAtTick >= 0 && tick >= spawnAtTick) {
            spawnAtTick = -1;
            for (Slot s : slots) {
                try {
                    spawnContents(level, s);
                } catch (Exception e) {
                    LedogMod.LOG.error("arena spawn failed", e);
                }
            }
        }
        for (Slot s : slots) {
            if (s.wolf == null) continue;
            if (!s.wolf.isAlive()) {
                try {
                    spawnWolf(level, s);
                } catch (Exception e) {
                    LedogMod.LOG.error("arena wolf respawn failed", e);
                }
                continue;
            }
            if (tick % 2 == 0) s.brain.step(level, s.wolf);
            LedogTelemetry.tick(level.getGameTime(), s.wolf, s.brain);
        }
    }

    public JsonObject handle(MinecraftServer server, String op, JsonObject m, long tick) throws Exception {
        ServerLevel level = server.overworld();
        JsonObject r = new JsonObject();
        switch (op) {
            case "arena_spawn" -> {
                clear(level);
                int n = m.has("n") ? m.get("n").getAsInt() : 8;
                seed = m.has("seed") ? m.get("seed").getAsLong() : 0L;
                studentSource = m.has("source") && "student".equals(m.get("source").getAsString());
                server.setDifficulty(Difficulty.EASY, true);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                server.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
                JsonArray a = new JsonArray();
                for (int k = 0; k < n; k++) {
                    Slot s = new Slot(k, seed);
                    for (int cx = (s.x0 - 4) >> 4; cx <= (s.x0 + SIZE + 4) >> 4; cx++)
                        for (int cz = (s.z0 - 4) >> 4; cz <= (s.z0 + SIZE + 4) >> 4; cz++) {
                            level.setChunkForced(cx, cz, true);
                            level.getChunk(cx, cz);
                        }
                    buildPlatform(level, s);
                    slots.add(s);
                    JsonObject j = new JsonObject();
                    j.addProperty("k", k);
                    j.addProperty("x0", s.x0);
                    j.addProperty("z0", s.z0);
                    a.add(j);
                }
                spawnAtTick = tick + 40;  // chunks must be entity-ticking before entities are added (cf. LedogMod)
                r.addProperty("pending", true);
                r.add("arenas", a);
            }
            case "arena_obs" -> {
                JsonArray a = new JsonArray();
                for (Slot s : slots) {
                    if (s.wolf == null || !s.wolf.isAlive()) continue;
                    Percepts.Percept p = s.brain.observe(level, s.wolf);
                    s.brain.extRefs = p.refs();
                    JsonObject j = new JsonObject();
                    j.addProperty("k", s.k);
                    j.addProperty("id", s.wolf.getId());
                    j.add("obs", p.obs());
                    j.add("temper", s.brain.st.temperJson());
                    JsonObject st = s.brain.status();
                    j.addProperty("plan", st.get("plan").getAsString());
                    j.addProperty("prim", st.get("prim").getAsString());
                    j.addProperty("plan_src", st.get("plan_src").getAsString());
                    j.addProperty("needs_plan", s.brain.needsPlan && s.brain.queued.isEmpty());
                    j.addProperty("queued", s.brain.queued.size());
                    JsonArray pos = new JsonArray();
                    pos.add(LedogTelemetry.r3(s.wolf.getX()));
                    pos.add(LedogTelemetry.r3(s.wolf.getY()));
                    pos.add(LedogTelemetry.r3(s.wolf.getZ()));
                    j.add("pos", pos);
                    a.add(j);
                }
                r.add("wolves", a);
                r.addProperty("ready", !slots.isEmpty() && spawnAtTick < 0);
            }
            case "arena_plan" -> {
                Slot s = slots.get(m.get("k").getAsInt());
                if (s.brain == null) throw new IllegalStateException("arena not ready");
                try {
                    r.addProperty("result", s.brain.enqueue(m.get("code").getAsString(), s.brain.extRefs.size()));
                } catch (IllegalArgumentException e) {
                    r.addProperty("result", "invalid: " + e.getMessage());
                }
            }
            case "arena_reset" -> {
                Slot s = slots.get(m.get("k").getAsInt());
                removeProps(s);
                buildPlatform(level, s);
                spawnProps(level, s);
                r.addProperty("result", "ok");
            }
            case "arena_clear" -> {
                clear(level);
                r.addProperty("result", "ok");
            }
            case "arena_stats" -> {
                JsonArray a = new JsonArray();
                for (Slot s : slots) {
                    if (s.brain == null) continue;
                    PlanBrain b = s.brain;
                    JsonObject j = new JsonObject();
                    j.addProperty("k", s.k);
                    j.addProperty("plans", b.nPlans);
                    j.addProperty("prims", b.nPrims);
                    j.addProperty("timeouts", b.nTimeouts);
                    j.addProperty("blocked", b.nBlocked);
                    j.addProperty("invalid", b.nInvalid);
                    j.addProperty("water", b.nWater);
                    j.addProperty("slides", b.nSlides);
                    a.add(j);
                }
                r.add("stats", a);
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        }
        return r;
    }

    private void clear(ServerLevel level) {
        for (Slot s : slots) {
            removeProps(s);
            if (s.wolf != null) s.wolf.discard();
            for (int cx = (s.x0 - 4) >> 4; cx <= (s.x0 + SIZE + 4) >> 4; cx++)
                for (int cz = (s.z0 - 4) >> 4; cz <= (s.z0 + SIZE + 4) >> 4; cz++) level.setChunkForced(cx, cz, false);
        }
        slots.clear();
        spawnAtTick = -1;
    }

    private void removeProps(Slot s) {
        for (Entity e : s.props) e.discard();
        s.props.clear();
    }

    // ------------------------------------------------------------ building
    private void set(ServerLevel l, BlockPos p, BlockState st) {
        l.setBlock(p, st, 2);  // send to clients, no neighbour updates (faster; arenas are built whole)
    }

    private void buildPlatform(ServerLevel l, Slot s) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -1; dx <= SIZE; dx++)
            for (int dz = -1; dz <= SIZE; dz++) {
                set(l, s.at(dx, -1, dz), Blocks.STONE.defaultBlockState());
                set(l, s.at(dx, 0, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int dy = 1; dy <= 6; dy++) set(l, s.at(dx, dy, dz), air);
                boolean edge = dx == -1 || dz == -1 || dx == SIZE || dz == SIZE;
                if (edge) set(l, s.at(dx, 1, dz), Blocks.OAK_FENCE.defaultBlockState());
            }
        Random rng = s.rng;
        // trees: log column + leaves crown
        int trees = 1 + rng.nextInt(3);
        for (int i = 0; i < trees; i++) {
            int x = 3 + rng.nextInt(SIZE - 6), z = 3 + rng.nextInt(SIZE - 6);
            for (int y = 1; y <= 3; y++) set(l, s.at(x, y, z), Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            for (int ax = -1; ax <= 1; ax++)
                for (int az = -1; az <= 1; az++)
                    for (int y = 3; y <= 4; y++)
                        if (!(ax == 0 && az == 0 && y == 3)) set(l, s.at(x + ax, y, z + az), leaf);
        }
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int bushes = 1 + rng.nextInt(3);
        for (int i = 0; i < bushes; i++) set(l, s.at(2 + rng.nextInt(SIZE - 4), 1, 2 + rng.nextInt(SIZE - 4)), leaf);
        int plants = 6 + rng.nextInt(7);
        BlockState[] pl = {Blocks.SHORT_GRASS.defaultBlockState(), Blocks.POPPY.defaultBlockState(),
                Blocks.DANDELION.defaultBlockState()};
        for (int i = 0; i < plants; i++) {
            BlockPos p = s.at(1 + rng.nextInt(SIZE - 2), 1, 1 + rng.nextInt(SIZE - 2));
            if (l.getBlockState(p).isAir()) set(l, p, pl[rng.nextInt(pl.length)]);
        }
        if (rng.nextDouble() < 0.4) {  // water pool (floor replaced; stone below holds it)
            int x = 4 + rng.nextInt(SIZE - 10), z = 4 + rng.nextInt(SIZE - 10);
            for (int ax = 0; ax < 3; ax++)
                for (int az = 0; az < 3; az++) {
                    set(l, s.at(x + ax, 1, z + az), Blocks.AIR.defaultBlockState());
                    set(l, s.at(x + ax, 0, z + az), Blocks.WATER.defaultBlockState());
                }
        }
        if (rng.nextDouble() < 0.3) set(l, s.at(2 + rng.nextInt(SIZE - 4), 1, 2 + rng.nextInt(SIZE - 4)),
                Blocks.OAK_STAIRS.defaultBlockState());
        if (rng.nextDouble() < 0.3) {
            int x = 3 + rng.nextInt(SIZE - 8), z = 3 + rng.nextInt(SIZE - 6);
            for (int i = 0; i < 3; i++) set(l, s.at(x + i, 1, z), Blocks.COBBLESTONE_WALL.defaultBlockState());
        }
    }

    private void spawnContents(ServerLevel l, Slot s) throws Exception {
        spawnProps(l, s);
        spawnWolf(l, s);
    }

    private BlockPos freeSpot(ServerLevel l, Slot s) {
        for (int t = 0; t < 50; t++) {
            BlockPos p = s.at(2 + s.rng.nextInt(SIZE - 4), 1, 2 + s.rng.nextInt(SIZE - 4));
            if (l.getBlockState(p).isAir() && l.getBlockState(p.above()).isAir()
                    && l.getBlockState(p.below()).getFluidState().isEmpty()) return p;
        }
        return s.at(SIZE / 2, 1, SIZE / 2);
    }

    static void invulnerable(Entity e) throws Exception {
        Field inv = Entity.class.getDeclaredField("permanentlyInvulnerable");
        inv.setAccessible(true);
        inv.setBoolean(e, true);
    }

    private <T extends Entity> T spawn(ServerLevel l, EntityType<T> type, BlockPos p) throws Exception {
        T e = type.spawn(l, p, EntitySpawnReason.COMMAND);
        if (e == null) return null;
        invulnerable(e);
        if (e instanceof Mob mob) mob.setPersistenceRequired();
        return e;
    }

    private void spawnProps(ServerLevel l, Slot s) throws Exception {
        Random rng = s.rng;
        EntityType<?>[] passive = {EntityTypes.SHEEP, EntityTypes.COW, EntityTypes.PIG, EntityTypes.CHICKEN, EntityTypes.RABBIT};
        int np = 2 + rng.nextInt(5);
        for (int i = 0; i < np; i++) {
            Entity e = spawn(l, passive[rng.nextInt(passive.length)], freeSpot(l, s));
            if (e != null) s.props.add(e);
        }
        EntityType<?>[] hostile = {EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER};
        int nh = rng.nextInt(3);
        for (int i = 0; i < nh; i++) {
            Entity e = spawn(l, hostile[rng.nextInt(hostile.length)], freeSpot(l, s));
            if (e instanceof Mob mob) {
                mob.setNoAi(true);
                mob.setSilent(true);
                mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));  // no burning in daylight
                s.props.add(e);
            }
        }
        if (rng.nextBoolean()) {
            Entity e = spawn(l, EntityTypes.ARMOR_STAND, freeSpot(l, s));  // player stand-in
            if (e != null) {
                e.setCustomName(Component.literal("player"));
                s.props.add(e);
            }
        }
        net.minecraft.world.item.Item[] items = {Items.BONE, Items.COOKED_BEEF, Items.SNOWBALL};
        for (net.minecraft.world.item.Item it : items) {
            if (rng.nextDouble() < 0.5) continue;
            BlockPos p = freeSpot(l, s);
            ItemEntity ie = new ItemEntity(l, p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5, new ItemStack(it));
            ie.setNeverPickUp();
            ie.setUnlimitedLifetime();
            l.addFreshEntity(ie);
            s.props.add(ie);
        }
    }

    private void spawnWolf(ServerLevel l, Slot s) throws Exception {
        if (s.wolf != null) s.wolf.discard();
        Wolf w = EntityTypes.WOLF.spawn(l, freeSpot(l, s), EntitySpawnReason.COMMAND);
        if (w == null) throw new IllegalStateException("arena wolf spawn failed");
        LedogMod.clearGoals(w);
        Field mc = Mob.class.getDeclaredField("moveControl");
        mc.setAccessible(true);
        mc.set(w, new LimitedMoveControl(w, 3.785f));
        w.setCustomName(Component.literal("ledog-arena-" + s.k));
        w.setPersistenceRequired();
        invulnerable(w);
        s.wolf = w;
        if (s.brain == null) {
            s.brain = new PlanBrain(s.k, seed * 7919L + s.k);
            if (LedogModels.available("gaze")) s.brain.gaze = new OnnxGaze();  // real-dog gaze model when exported
            if (studentSource && LedogModels.available("student")) s.brain.source = new StudentSource(new java.util.Random(seed * 7919L + s.k), 1.0);  // in-mod student (sampled)
        }
    }
}

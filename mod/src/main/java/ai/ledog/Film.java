package ai.ledog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Filming ops for the media scripts (media/film.py), on a dedicated instance (scripts/film_up.sh): spawn Le Dog wolves
 * (student / trick, i.e. the real policies) or scripted ones (FilmBrain) at exact positions, script props (any mob with
 * its goals cleared, e.g. a creeper), and list positions for overlays. Nothing here is used by training or evaluation.
 */
public class Film {
    final LedogMod mod;
    final Map<Mob, FilmBrain> props = new HashMap<>();

    Film(LedogMod mod) {
        this.mod = mod;
    }

    void tick(MinecraftServer server, long tick) {
        if (tick % 2 != 0) return;
        props.entrySet().removeIf(e -> !e.getKey().isAlive());
        for (var e : props.entrySet()) e.getValue().drive((ServerLevel) e.getKey().level(), e.getKey());
    }

    static double num(JsonObject m, String k, double d) {
        return m.has(k) && !m.get(k).isJsonNull() ? m.get(k).getAsDouble() : d;
    }

    static String str(JsonObject m, String k, String d) {
        return m.has(k) && !m.get(k).isJsonNull() ? m.get(k).getAsString() : d;
    }

    FilmBrain brainOf(ServerLevel level, int id) {
        Entity e = level.getEntity(id);
        if (e instanceof Wolf w && mod.playBrains.get(w) instanceof FilmBrain b) return b;
        if (e instanceof Mob mb && props.get(mb) != null) return props.get(mb);
        throw new IllegalArgumentException("no scripted entity " + id);
    }

    JsonObject handle(MinecraftServer server, String op, JsonObject m) throws Exception {
        ServerLevel level = server.overworld();
        JsonObject r = new JsonObject();
        switch (op) {
            case "film_spawn" -> {  // {x, z, [y], kind: film|student|trick|<mob id>, preset, version, yaw}
                String kind = str(m, "kind", "film");
                double x = num(m, "x", 0), z = num(m, "z", 0);
                BlockPos at = m.has("y") ? BlockPos.containing(x, num(m, "y", 0), z)
                        : LedogMod.dryGround(level, BlockPos.containing(x, 64, z));
                Mob mob;
                if (kind.equals("film") || kind.equals("student") || kind.equals("trick")) {
                    Wolf w = EntityTypes.WOLF.spawn(level, at, EntitySpawnReason.COMMAND);
                    if (w == null) throw new IllegalStateException("wolf spawn failed");
                    LedogMod.clearGoals(w);
                    Field mc = Mob.class.getDeclaredField("moveControl");
                    mc.setAccessible(true);
                    mc.set(w, new LimitedMoveControl(w, 3.785f));
                    WolfBrain b = kind.equals("film") ? new FilmBrain() : mod.makeBrain(kind, w.getId() * 7919L);
                    PlanBrain pb = b instanceof PlanBrain p ? p : b instanceof TrickBrain tb ? tb.base : null;
                    if (pb != null && m.has("preset")) {
                        System.arraycopy(LedogMod.PRESETS.get(str(m, "preset", "bold")), 0, pb.st.temper, 0, 5);
                        pb.presetName = str(m, "preset", null);
                    }
                    if (pb != null && m.has("version")) {
                        String model = "student_" + str(m, "version", "v3") + ".onnx";
                        pb.source = new StudentSource(new java.util.Random(w.getId() * 7919L), 1.0, model);
                    }
                    if (b instanceof TrickBrain tb) tb.uuid = w.getUUID();
                    w.addTag("ledog_film");
                    w.setPersistenceRequired();
                    mod.playBrains.put(w, b);
                    mob = w;
                } else {  // a scripted prop: any mob, goals cleared, driven like a FilmBrain wolf
                    EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(kind));
                    Entity e = t.spawn(level, at, EntitySpawnReason.COMMAND);
                    if (!(e instanceof Mob mb)) throw new IllegalStateException("not a mob: " + kind);
                    LedogMod.clearGoals(mb);
                    mb.addTag("ledog_film");
                    mb.setPersistenceRequired();
                    props.put(mb, new FilmBrain());
                    mob = mb;
                }
                float yaw = (float) num(m, "yaw", mob.getYRot());
                mob.setYRot(yaw);
                mob.setYBodyRot(yaw);
                mob.setYHeadRot(yaw);
                r.addProperty("id", mob.getId());
                r.addProperty("uuid", mob.getUUID().toString());
            }
            case "film_set" -> {  // {id, path [[x,z],..], loop, speed, jump, sit, look_id, look_at [x,y,z], look_off}
                FilmBrain b = brainOf(level, m.get("id").getAsInt());
                if (m.has("speed")) b.speed = m.get("speed").getAsDouble();
                if (m.has("arrive")) b.arrive = m.get("arrive").getAsDouble();
                if (m.has("jump")) b.jumpEvery = m.get("jump").getAsInt();
                if (m.has("sit")) b.sit = m.get("sit").getAsBoolean();
                if (m.has("look_id")) {
                    b.lookId = m.get("look_id").getAsInt();
                    b.lookAt = null;
                }
                if (m.has("look_at")) {
                    JsonArray a = m.getAsJsonArray("look_at");
                    b.lookAt = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
                    b.lookId = null;
                }
                if (m.has("look_off")) {
                    b.lookId = null;
                    b.lookAt = null;
                }
                if (m.has("path")) {
                    JsonArray a = m.getAsJsonArray("path");
                    double[][] p = new double[a.size()][];
                    for (int i = 0; i < a.size(); i++)
                        p[i] = new double[]{a.get(i).getAsJsonArray().get(0).getAsDouble(), a.get(i).getAsJsonArray().get(1).getAsDouble()};
                    b.setPath(p.length == 0 ? null : p, m.has("loop") && m.get("loop").getAsBoolean());
                }
                r.addProperty("ok", true);
            }
            case "film_trick" -> {  // {id, word}: a trick dog hears a word (sit / jump / stay / come / new word)
                Entity e = level.getEntity(m.get("id").getAsInt());
                ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
                if (e instanceof Wolf w && mod.playBrains.get(w) instanceof TrickBrain tb) tb.command(str(m, "word", "sit"), p, w);
                else throw new IllegalArgumentException("not a trick dog");
                r.addProperty("ok", true);
            }
            case "film_entities" -> {  // positions of every Le Dog / film entity (overlays, framing)
                JsonArray a = new JsonArray();
                for (var e : mod.playBrains.entrySet()) a.add(ent(e.getKey(), e.getValue().kind()));
                for (var e : props.entrySet()) a.add(ent(e.getKey(), "prop"));
                r.add("entities", a);
                r.addProperty("time", level.getGameTime());
            }
            case "film_heights" -> {  // {x0, z0, w, h}: ground height, canopy height, surface block per column (scouting)
                int x0 = (int) num(m, "x0", 0), z0 = (int) num(m, "z0", 0), w = (int) num(m, "w", 32), h = (int) num(m, "h", 32);
                JsonArray g = new JsonArray(), cnp = new JsonArray(), blk = new JsonArray();
                for (int dz = 0; dz < h; dz++)
                    for (int dx = 0; dx < w; dx++) {
                        int x = x0 + dx, z = z0 + dz;
                        level.getChunk(x >> 4, z >> 4);
                        int gy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        g.add(gy);
                        cnp.add(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
                        blk.add(BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, gy - 1, z)).getBlock()).getPath());
                    }
                r.add("ground", g);
                r.add("canopy", cnp);
                r.add("block", blk);
            }
            case "film_clear" -> {  // remove every film entity (wolves and props)
                int n = 0;
                for (var it = mod.playBrains.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    if (e.getKey().entityTags().contains("ledog_film")) {
                        e.getKey().discard();
                        it.remove();
                        n++;
                    }
                }
                for (Mob mb : props.keySet()) {
                    mb.discard();
                    n++;
                }
                props.clear();
                r.addProperty("removed", n);
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        }
        return r;
    }

    static JsonObject ent(Mob e, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("kind", kind);
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        o.addProperty("yaw", e.getYRot());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
        return o;
    }
}

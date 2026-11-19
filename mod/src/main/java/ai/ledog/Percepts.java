package ai.ledog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side symbolic percepts in the contract v1 observation schema (ledog/contract.py), no rendering:
 * entities within 16 blocks mapped to OBJ_TYPES, block features sampled on a 2-block grid (tree, bush, tall_grass,
 * flower, water, fence, wall, stairs; one object per type cluster), egocentric bearing quantised to 30-degree bins from
 * the body yaw (+ = right), dist, dy, moving speed, same (seen at the previous percept), gazed (GazeModel hook), scent,
 * sound, state. Returns the JSON observation and the object references (index i in the JSON = refs.get(i)).
 */
public final class Percepts {
    public static final int MAX_OBJ = 12;
    static final double R_OBJ = 16, R_SCENT = 24;

    /** What an object index in a plan refers to: an entity or a fixed block position. */
    public record ObjRef(String type, Entity entity, Vec3 fixed) {
        public Vec3 pos() {
            return entity != null ? entity.position() : fixed;
        }

        public boolean alive() {
            return entity == null || entity.isAlive();
        }

        String key() {
            return entity != null ? "e" + entity.getId() : "b" + (int) fixed.x + "," + (int) fixed.y + "," + (int) fixed.z;
        }
    }

    public record Percept(JsonObject obs, List<ObjRef> refs, int gazed) {
    }

    /** Gaze model hook: given the candidate objects (already sorted by distance), return the gazed index or -1. */
    public interface GazeModel {
        int pick(Entity wolf, double bodyYaw, List<ObjRef> objs, List<double[]> egos);  // egos: [bearing_deg, dist, dy, moving]
    }

    /** Placeholder until the real-dog gaze model is trained: nearest object within +-60 degrees of the body heading. */
    public static final GazeModel HEURISTIC_GAZE = (wolf, bodyYaw, objs, egos) -> {
        for (int i = 0; i < egos.size(); i++) if (Math.abs(egos.get(i)[0]) <= 60) return i;
        return -1;
    };

    static final Map<String, String> ENTITY_TYPES = Map.ofEntries(
            Map.entry("player", "player"), Map.entry("armor_stand", "player"), Map.entry("wolf", "wolf"),
            Map.entry("cat", "cat"), Map.entry("ocelot", "cat"), Map.entry("sheep", "sheep"), Map.entry("cow", "cow"),
            Map.entry("mooshroom", "cow"), Map.entry("pig", "pig"), Map.entry("chicken", "chicken"),
            Map.entry("rabbit", "rabbit"), Map.entry("horse", "horse"), Map.entry("donkey", "horse"),
            Map.entry("mule", "horse"), Map.entry("villager", "villager"), Map.entry("wandering_trader", "villager"),
            Map.entry("fox", "fox"), Map.entry("zombie", "zombie"), Map.entry("husk", "zombie"),
            Map.entry("zombie_villager", "zombie"), Map.entry("skeleton", "skeleton"), Map.entry("stray", "skeleton"),
            Map.entry("creeper", "creeper"), Map.entry("spider", "spider"), Map.entry("cave_spider", "spider"),
            Map.entry("snowball", "ball"), Map.entry("minecart", "cart"));
    static final Set<String> FOOD = Set.of("beef", "cooked_beef", "porkchop", "cooked_porkchop", "chicken",
            "cooked_chicken", "mutton", "cooked_mutton", "rabbit", "cooked_rabbit", "bread", "rotten_flesh", "cod",
            "cooked_cod", "salmon", "apple", "cookie");
    static final Set<String> HOSTILE = Set.of("zombie", "skeleton", "creeper", "spider");

    static String entityType(Entity e) {
        if (e instanceof ItemEntity it) {
            String id = BuiltInRegistries.ITEM.getKey(it.getItem().getItem()).getPath();
            if (id.equals("bone")) return "bone";
            if (id.equals("snowball") || id.equals("slime_ball")) return "ball";
            return FOOD.contains(id) ? "food" : "item";
        }
        return ENTITY_TYPES.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
    }

    static String blockType(BlockState s) {
        if (s.is(BlockTags.LOGS)) return "tree";
        if (s.is(BlockTags.LEAVES)) return "bush";
        if (s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)) return "tall_grass";
        if (s.is(BlockTags.SMALL_FLOWERS) || s.is(BlockTags.FLOWERS)) return "flower";
        if (!s.getFluidState().isEmpty()) return "water";
        if (s.is(BlockTags.FENCES)) return "fence";
        if (s.is(BlockTags.WALLS)) return "wall";
        if (s.is(BlockTags.STAIRS)) return "stairs";
        return null;
    }

    /** Egocentric bearing (degrees, + = right, quantised to 30) of world point p from the wolf's body yaw. */
    public static int bearing(Entity wolf, double bodyYaw, Vec3 p) {
        double dx = p.x - wolf.getX(), dz = p.z - wolf.getZ();
        double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        return (int) (30 * Math.round(BmBrain.wrap(yawTo - bodyYaw) / 30.0));
    }

    public static Percept build(ServerLevel level, Entity wolf, double bodyYaw, ArenaState st, Set<String> prevKeys,
                                GazeModel gaze) {
        List<ObjRef> cand = new ArrayList<>();
        AABB box = wolf.getBoundingBox().inflate(R_SCENT);
        List<Entity> ents = level.getEntities(wolf, box, e -> e.isAlive() && entityType(e) != null);
        for (Entity e : ents)
            if (e.distanceTo(wolf) <= R_OBJ) cand.add(new ObjRef(entityType(e), e, null));
        // block features on a 2-block grid, heights -1..+3 around the wolf; keep one object per 6-block type cluster
        BlockPos c = wolf.blockPosition();
        List<ObjRef> blocks = new ArrayList<>();
        for (int dx = -16; dx <= 16; dx += 2)
            for (int dz = -16; dz <= 16; dz += 2) {
                if (dx * dx + dz * dz > R_OBJ * R_OBJ) continue;
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos p = c.offset(dx, dy, dz);
                    String t = blockType(level.getBlockState(p));
                    if (t == null) continue;
                    Vec3 v = Vec3.atBottomCenterOf(p);
                    boolean near = false;
                    for (ObjRef b : blocks)
                        if (b.type.equals(t) && b.fixed.distanceTo(v) < 6) {
                            near = true;
                            break;
                        }
                    if (!near) blocks.add(new ObjRef(t, null, v));
                    break;
                }
            }
        cand.addAll(blocks);
        cand.sort((a, b) -> Double.compare(a.pos().distanceTo(wolf.position()), b.pos().distanceTo(wolf.position())));
        List<ObjRef> refs = cand.subList(0, Math.min(MAX_OBJ, cand.size()));
        List<double[]> egos = new ArrayList<>();
        for (ObjRef r : refs) {
            Vec3 p = r.pos();
            double mv = r.entity != null ? Math.hypot(r.entity.getDeltaMovement().x, r.entity.getDeltaMovement().z) * 20 : 0;
            egos.add(new double[]{bearing(wolf, bodyYaw, p), p.distanceTo(wolf.position()), p.y - wolf.getY(), mv});
        }
        int g = gaze == null ? -1 : gaze.pick(wolf, bodyYaw, refs, egos);
        JsonObject o = new JsonObject();
        o.addProperty("v", PlanCode.VERSION);
        JsonArray objs = new JsonArray();
        for (int i = 0; i < refs.size(); i++) {
            double[] e = egos.get(i);
            JsonObject j = new JsonObject();
            j.addProperty("type", refs.get(i).type);
            j.addProperty("bearing", (int) e[0]);
            j.addProperty("dist", Math.round(e[1] * 10) / 10.0);
            j.addProperty("dy", Math.round(e[2]));
            j.addProperty("moving", Math.round(e[3] * 10) / 10.0);
            j.addProperty("gazed", i == g);
            j.addProperty("same", prevKeys != null && prevKeys.contains(refs.get(i).key()));
            objs.add(j);
        }
        o.add("objects", objs);
        // scent: up to 3 nearest animals / food / players within 24 blocks; strength 1 - d/24
        JsonArray scent = new JsonArray();
        List<Entity> sc = new ArrayList<>(ents);
        sc.sort((a, b) -> Double.compare(a.distanceTo(wolf), b.distanceTo(wolf)));
        for (Entity e : sc) {
            if (scent.size() >= 3) break;
            String t = entityType(e);
            String s = switch (t) {
                case "rabbit", "sheep", "cat", "food", "player" -> t;
                case "wolf" -> "dog";
                case "zombie" -> "zombie";
                case "bone" -> "food";
                default -> null;
            };
            if (s == null) continue;
            double d = e.distanceTo(wolf);
            if (d > R_SCENT) continue;
            JsonObject j = new JsonObject();
            j.addProperty("type", s);
            j.addProperty("bearing", bearing(wolf, bodyYaw, e.position()));
            j.addProperty("strength", Math.round((1 - d / R_SCENT) * 10) / 10.0);
            scent.add(j);
        }
        o.add("scent", scent);
        // sound: hostile within 16 -> groan (hiss for creeper); moving player within 16 -> footsteps
        JsonArray sound = new JsonArray();
        double hostileNear = 99;
        for (Entity e : sc) {
            if (sound.size() >= 3) break;
            String t = entityType(e);
            double d = e.distanceTo(wolf);
            if (d > R_OBJ) continue;
            String s = null;
            if (HOSTILE.contains(t)) {
                s = t.equals("creeper") ? "hiss" : "groan";
                hostileNear = Math.min(hostileNear, d);
            } else if (e instanceof Player && Math.hypot(e.getDeltaMovement().x, e.getDeltaMovement().z) > 0.05) {
                s = "footsteps";
            }
            if (s == null) continue;
            JsonObject j = new JsonObject();
            j.addProperty("type", s);
            j.addProperty("bearing", bearing(wolf, bodyYaw, e.position()));
            j.addProperty("loudness", Math.round(Math.max(0.1, 1 - d / R_OBJ) * 10) / 10.0);
            sound.add(j);
        }
        o.add("sound", sound);
        if (st != null) st.hostileNear = hostileNear;
        JsonObject state = new JsonObject();
        if (st != null) {
            state.addProperty("energy", Math.round(st.energy * 10) / 10.0);
            state.addProperty("hunger", Math.round(st.hunger * 10) / 10.0);
            state.addProperty("arousal", Math.round(st.arousal * 10) / 10.0);
            state.addProperty("last", st.last);
            state.addProperty("rest_s", Math.round(st.restTicks / 10.0));
        }
        o.add("state", state);
        return new Percept(o, new ArrayList<>(refs), g);
    }

    public static Set<String> keys(List<ObjRef> refs) {
        Set<String> s = new HashSet<>();
        for (ObjRef r : refs) s.add(r.key());
        return s;
    }

    /** Internal state + temperament of one dog. */
    public static final class ArenaState {
        public double energy = 0.8, hunger = 0.3, arousal = 0.2, hostileNear = 99;
        public String last = "-";
        public long restTicks;  // 10 Hz steps since the last rest (sit/lie/wait)
        public final double[] temper = new double[5];  // curious, bold, energetic, social, playful

        public JsonObject temperJson() {
            String[] k = {"curious", "bold", "energetic", "social", "playful"};
            JsonObject o = new JsonObject();
            for (int i = 0; i < 5; i++) o.addProperty(k[i], Math.round(temper[i] * 10) / 10.0);
            return o;
        }
    }

    private Percepts() {
    }
}

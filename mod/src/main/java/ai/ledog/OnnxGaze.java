package ai.ledog;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-dog gaze-selection model in the mod (ledog/gaze_select.py, --prev-mode none, + dwell rule), one instance per wolf.
 * Features per candidate are computed EXACTLY as gaze_select.frame_features does from EgoPet boxes, here from game state:
 *   bearing (deg, + right, continuous, from the body yaw), elevation (deg, eye -> object centre), angular width/height
 *   (deg, entity bounding box or 1 block), motion (deg/s of bearing between decisions, only if seen before), same, prev=0
 *   -> [bear/90, elev/45, log1p(aw)/4, log1p(ah)/4, min(mot,60)/60, same, 0, sin(bear), cos(bear)].
 * Candidates = objects inside the camera's horizontal field of view (HFOV 100 deg, as in the training video), at most 24.
 * Output: softmax over candidates + NONE; dwell: keep the current target while visible unless another one is >= 0.15
 * more probable. Model file: gaze.onnx in LedogModels.dir() (exported by gaze_select train).
 */
public class OnnxGaze implements Percepts.GazeModel {
    static final double HFOV = 100.0, DWELL = 0.15;
    static final int MAXC = 24, NF = 9;
    static final String[] OBJ_TYPES = {"player", "wolf", "dog", "cat", "sheep", "cow", "pig", "chicken", "rabbit", "horse",
            "villager", "fox", "zombie", "skeleton", "creeper", "spider", "item", "bone", "ball", "food", "tree", "bush",
            "tall_grass", "flower", "water", "rock", "fence", "wall", "door", "stairs", "house", "cart"};
    private final Map<String, Double> prevBearing = new HashMap<>();
    private long prevNanos;
    private String curKey;
    public int nCalls, nNone, nSwitches, nErrors;

    /** Gaze-model input only: Minecraft types that never occur in the real-dog training data (EgoPet + OWLv2 queries,
     *  ledog/gaze_boxes.py MC map) are fed as the nearest TRAINED real-world type, instead of an untrained embedding.
     *  The student / teacher still see the true type. wolf = how OWL saw real dogs; humanoid mobs = "person";
     *  spider = a low quadruped; wall = fence. Mirrors contract.GAZE_ALIAS (test_gaze_alias checks they agree). */
    static final Map<String, String> GAZE_ALIAS = Map.of("wolf", "dog", "villager", "player", "zombie", "player",
            "skeleton", "player", "creeper", "player", "spider", "dog", "wall", "fence");

    static int typeIndex(String t) {
        t = GAZE_ALIAS.getOrDefault(t, t);
        for (int i = 0; i < OBJ_TYPES.length; i++) if (OBJ_TYPES[i].equals(t)) return i + 1;
        return 0;
    }

    static double exactBearing(Entity wolf, double bodyYaw, Vec3 p) {
        double dx = p.x - wolf.getX(), dz = p.z - wolf.getZ();
        return BmBrain.wrap(Math.toDegrees(Math.atan2(-dx, dz)) - bodyYaw);
    }

    @Override
    public int pick(Entity wolf, double bodyYaw, List<Percepts.ObjRef> objs, List<double[]> egos) {
        nCalls++;
        long now = System.nanoTime();
        double dt = prevNanos == 0 ? 0.5 : Math.max(0.05, (now - prevNanos) / 1e9);
        prevNanos = now;
        Vec3 eye = wolf.getEyePosition();
        List<Integer> idx = new ArrayList<>();
        List<double[]> feats = new ArrayList<>();
        Map<String, Double> seen = new HashMap<>();
        for (int i = 0; i < objs.size() && idx.size() < MAXC; i++) {
            Percepts.ObjRef r = objs.get(i);
            Vec3 c;
            double w, h;
            if (r.entity() != null) {
                Entity e = r.entity();
                w = e.getBbWidth();
                h = e.getBbHeight();
                c = e.position().add(0, h / 2, 0);
            } else {
                w = 1;
                h = 1;
                c = r.fixed().add(0, 0.5, 0);
            }
            double bear = exactBearing(wolf, bodyYaw, c);
            String key = r.entity() != null ? "e" + r.entity().getId() : "b" + c.x + "," + c.y + "," + c.z;
            seen.put(key, bear);
            if (Math.abs(bear) > HFOV / 2) continue;
            double horiz = Math.max(0.3, Math.hypot(c.x - eye.x, c.z - eye.z));
            double d = Math.max(0.3, c.distanceTo(eye));
            double elev = Math.toDegrees(Math.atan2(c.y - eye.y, horiz));
            double aw = Math.toDegrees(2 * Math.atan(w / 2 / d)), ah = Math.toDegrees(2 * Math.atan(h / 2 / d));
            Double pb = prevBearing.get(key);
            boolean same = pb != null;
            double mot = same ? Math.abs(BmBrain.wrap(bear - pb)) / dt : 0;
            double br = Math.toRadians(bear);
            feats.add(new double[]{bear / 90, elev / 45, Math.log1p(aw) / 4, Math.log1p(ah) / 4,
                    same ? Math.min(mot, 60) / 60 : 0, same ? 1 : 0, 0, Math.sin(br), Math.cos(br), typeIndex(r.type())});
            idx.add(i);
        }
        prevBearing.clear();
        prevBearing.putAll(seen);
        if (idx.isEmpty()) {
            curKey = null;
            nNone++;
            return -1;
        }
        long[] types = new long[MAXC];
        float[] f = new float[MAXC * NF], m = new float[MAXC];
        for (int j = 0; j < idx.size(); j++) {
            double[] v = feats.get(j);
            for (int q = 0; q < NF; q++) f[j * NF + q] = (float) v[q];
            types[j] = (long) v[NF];
            m[j] = 1;
        }
        float[] lo;
        try {
            lo = LedogModels.gaze(types, f, m);
        } catch (Exception e) {
            nErrors++;
            return Percepts.HEURISTIC_GAZE.pick(wolf, bodyYaw, objs, egos);
        }
        // softmax over valid candidates + NONE (index MAXC)
        double mx = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < idx.size(); j++) mx = Math.max(mx, lo[j]);
        mx = Math.max(mx, lo[MAXC]);
        double[] p = new double[idx.size() + 1];
        double s = 0;
        for (int j = 0; j < idx.size(); j++) s += p[j] = Math.exp(lo[j] - mx);
        s += p[idx.size()] = Math.exp(lo[MAXC] - mx);
        // NONE is never chosen while a candidate is visible: in EgoPet "no target" mostly means the labeller's tracker lost
        // the object (detection gap), not a dog staring at nothing; in-game it made wolves "look at nothing" 85% of the time.
        int best = 0;
        for (int j = 0; j <= idx.size(); j++) p[j] /= s;
        for (int j = 0; j < idx.size(); j++) if (p[j] > p[best]) best = j;
        // dwell on the current target
        if (curKey != null) {
            for (int j = 0; j < idx.size(); j++) {
                Percepts.ObjRef r = objs.get(idx.get(j));
                String key = r.entity() != null ? "e" + r.entity().getId() : null;
                if (key != null && key.equals(curKey) && p[best] < p[j] + DWELL) {
                    best = j;
                    break;
                }
            }
        }
        if (best == idx.size()) {  // NONE
            curKey = null;
            nNone++;
            return -1;
        }
        Percepts.ObjRef r = objs.get(idx.get(best));
        String key = r.entity() != null ? "e" + r.entity().getId() : null;
        if (key == null || !key.equals(curKey)) nSwitches++;
        curKey = key;
        return idx.get(best);
    }
}

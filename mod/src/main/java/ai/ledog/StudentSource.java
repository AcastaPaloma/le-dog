package ai.ledog;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The distilled student as the plan source of an arena / play wolf (ledog/student.py, exported by `student export` to
 * student.onnx in LedogModels.dir()). featurise() and decode() are line-by-line ports of the Python functions, working
 * on the SAME contract-v1 observation JSON the student was trained on. Invalid decodes fall back to "W/1" (as Python).
 */
public class StudentSource implements PlanBrain.PlanSource {
    static final String OPS = "MLSTYBFRWP";
    static final String[] OPS_END = {"M", "L", "S", "T", "Y", "B", "F", "R", "W", "P", "END"};
    static final String[] SCENTS = {"rabbit", "food", "player", "dog", "cat", "sheep", "zombie", "smoke", "flower"};
    static final String[] SOUNDS = {"voice", "whistle", "bark", "groan", "hiss", "footsteps", "splash", "bell", "explosion"};
    static final String[] TEMPER = {"curious", "bold", "energetic", "social", "playful"};
    static final int[] BEAR = {-150, -120, -90, -60, -30, 0, 30, 60, 90, 120, 150, 180};
    static final int[] DIST = {1, 2, 4, 8, 16};
    static final double[] SECS = {0.5, 1, 2, 4, 8};
    static final int NOBJ = 12, NSC = 3, NSO = 3, NSLOT = 5;
    public long nCalls, nanosTotal, nInvalid;
    public double lastMs;
    /** null = argmax (parity/evaluation); otherwise every choice is SAMPLED from the student's softmax (how wolves act:
     *  argmax repeated one plan hundreds of times when the observation did not change; journal 19:30). */
    private final java.util.Random rng;
    private final double temp;
    public final String model;  // ONNX file in LedogModels.dir() (student.onnx; the bench compares versions)

    public StudentSource() {
        this(null, 1.0);
    }

    public StudentSource(java.util.Random rng, double temp) {
        this(rng, temp, "student.onnx");
    }

    public StudentSource(java.util.Random rng, double temp, String model) {
        this.rng = rng;
        this.temp = temp;
        this.model = model;
    }

    private static OrtEnvironment env;
    private static final Map<String, OrtSession> SESS = new HashMap<>();

    private static synchronized OrtSession session(String model) throws Exception {
        if (env == null) env = OrtEnvironment.getEnvironment();
        OrtSession s = SESS.get(model);
        if (s == null) {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1);
            s = env.createSession(LedogModels.dir().resolve(model).toString(), o);
            SESS.put(model, s);
        }
        return s;
    }

    static double num(JsonObject o, String k, double d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsDouble();
    }

    static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    /** Port of student.featurise(scene, temper). */
    static Map<String, OnnxTensor> featurise(JsonObject obs, JsonObject temper) throws Exception {
        float[] ob = new float[NOBJ * 8], om = new float[NOBJ], sc = new float[(NSC + NSO) * 4], sm = new float[NSC + NSO];
        long[] ot = new long[NOBJ], st = new long[NSC + NSO];
        JsonArray objs = obs.getAsJsonArray("objects");
        for (int i = 0; i < Math.min(NOBJ, objs.size()); i++) {
            JsonObject o = objs.get(i).getAsJsonObject();
            double r = Math.toRadians(num(o, "bearing", 0));
            float[] v = {(float) Math.sin(r), (float) Math.cos(r), (float) (Math.log1p(num(o, "dist", 0)) / 3),
                    (float) (num(o, "dy", 0) / 3), (float) (num(o, "moving", 0) / 3),
                    o.has("gazed") && o.get("gazed").getAsBoolean() ? 1f : 0f,
                    o.has("same") && o.get("same").getAsBoolean() ? 1f : 0f, 1f};
            System.arraycopy(v, 0, ob, i * 8, 8);
            int t = indexOf(OnnxGaze.OBJ_TYPES, o.get("type").getAsString());
            ot[i] = t >= 0 ? t + 1 : 0;
            om[i] = 1;
        }
        JsonArray scent = obs.has("scent") ? obs.getAsJsonArray("scent") : new JsonArray();
        for (int j = 0; j < Math.min(NSC, scent.size()); j++) {
            JsonObject s = scent.get(j).getAsJsonObject();
            double r = Math.toRadians(num(s, "bearing", 0));
            sc[j * 4] = (float) Math.sin(r);
            sc[j * 4 + 1] = (float) Math.cos(r);
            sc[j * 4 + 2] = (float) num(s, "strength", 0);
            st[j] = indexOf(SCENTS, s.get("type").getAsString()) + 1;
            sm[j] = 1;
        }
        JsonArray sound = obs.has("sound") ? obs.getAsJsonArray("sound") : new JsonArray();
        for (int j = 0; j < Math.min(NSO, sound.size()); j++) {
            JsonObject s = sound.get(j).getAsJsonObject();
            double r = Math.toRadians(num(s, "bearing", 0));
            int q = NSC + j;
            sc[q * 4] = (float) Math.sin(r);
            sc[q * 4 + 1] = (float) Math.cos(r);
            sc[q * 4 + 2] = (float) num(s, "loudness", 0);
            sc[q * 4 + 3] = 1f;
            st[q] = SCENTS.length + indexOf(SOUNDS, s.get("type").getAsString()) + 1;
            sm[q] = 1;
        }
        JsonObject s = obs.getAsJsonObject("state");
        float[] g = new float[4 + OPS.length() + TEMPER.length];
        g[0] = (float) num(s, "energy", 0);
        g[1] = (float) num(s, "hunger", 0);
        g[2] = (float) num(s, "arousal", 0);
        g[3] = (float) (Math.min(num(s, "rest_s", 0), 600) / 600);
        String last = s.has("last") && !s.get("last").isJsonNull() ? s.get("last").getAsString() : "-";
        for (int i = 0; i < OPS.length(); i++) g[4 + i] = last.equals(String.valueOf(OPS.charAt(i))) ? 1f : 0f;
        for (int i = 0; i < TEMPER.length; i++) g[4 + OPS.length() + i] = (float) num(temper, TEMPER[i], 0);
        Map<String, OnnxTensor> in = new HashMap<>();
        in.put("ob", OnnxTensor.createTensor(env, FloatBuffer.wrap(ob), new long[]{1, NOBJ, 8}));
        in.put("ot", OnnxTensor.createTensor(env, LongBuffer.wrap(ot), new long[]{1, NOBJ}));
        in.put("om", OnnxTensor.createTensor(env, FloatBuffer.wrap(om), new long[]{1, NOBJ}));
        in.put("sc", OnnxTensor.createTensor(env, FloatBuffer.wrap(sc), new long[]{1, NSC + NSO, 4}));
        in.put("st", OnnxTensor.createTensor(env, LongBuffer.wrap(st), new long[]{1, NSC + NSO}));
        in.put("sm", OnnxTensor.createTensor(env, FloatBuffer.wrap(sm), new long[]{1, NSC + NSO}));
        in.put("g", OnnxTensor.createTensor(env, FloatBuffer.wrap(g), new long[]{1, g.length}));
        return in;
    }

    static int argmax(float[] a, int n) {
        int b = 0;
        for (int i = 1; i < n; i++) if (a[i] > a[b]) b = i;
        return b;
    }

    /** Port of student._pick: argmax when rng == null, else a sample from softmax(a[0..n) / temp). */
    static int pick(float[] a, int n, java.util.Random rng, double temp) {
        if (rng == null) return argmax(a, n);
        double mx = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) mx = Math.max(mx, a[i]);
        double[] p = new double[n];
        double s = 0;
        for (int i = 0; i < n; i++) s += p[i] = Math.exp((a[i] - mx) / temp);
        double u = rng.nextDouble() * s, c = 0;
        for (int i = 0; i < n; i++) {
            c += p[i];
            if (u <= c) return i;
        }
        return n - 1;
    }

    static String secStr(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    /** Port of student.decode(out, i, n_obj). out: name -> [slot][classes]. */
    static String decode(Map<String, float[][]> o, int nObj, java.util.Random rng, double temp) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < NSLOT; k++) {
            String op = OPS_END[pick(o.get("op")[k], OPS_END.length, rng, temp)];
            if (op.equals("END")) break;
            int tgt = pick(o.get("tgt")[k], 3, rng, temp);
            String head;
            if (op.equals("F") || ("LSR".contains(op) && tgt == 1 && nObj > 0)) {
                head = nObj == 0 ? String.format("%+d", BEAR[pick(o.get("bear")[k], BEAR.length, rng, temp)])
                        : "#" + pick(o.get("obj")[k], Math.min(nObj, NOBJ), rng, temp);
            } else if ("MLSR".contains(op)) {
                head = String.format("%+d", BEAR[pick(o.get("bear")[k], BEAR.length, rng, temp)]);
            } else head = "";
            String sec = secStr(SECS[pick(o.get("secs")[k], SECS.length, rng, temp)]);
            String p;
            if ("MR".contains(op))
                p = op + head + "/" + DIST[pick(o.get("dist")[k], DIST.length, rng, temp)] + "/" + "swtr".charAt(pick(o.get("speed")[k], 4, rng, temp));
            else if (op.equals("L")) p = "L" + head;
            else if ("SF".contains(op)) p = op + head + "/" + sec;
            else if (op.equals("B")) p = "B/" + (pick(o.get("n")[k], 3, rng, temp) + 1);
            else p = op + "/" + sec;
            if (sb.length() > 0) sb.append(';');
            sb.append(p);
        }
        return sb.length() == 0 ? "W/1" : sb.toString();
    }

    /** Observation + temperament -> plan code (validated). */
    public String infer(JsonObject obs, JsonObject temper) throws Exception {
        long t0 = System.nanoTime();
        OrtSession s = session(model);
        Map<String, OnnxTensor> in = featurise(obs, temper);
        Map<String, float[][]> out = new HashMap<>();
        try (OrtSession.Result r = s.run(in)) {
            String[] names = {"op", "tgt", "bear", "obj", "dist", "speed", "secs", "n"};
            for (int i = 0; i < names.length; i++) out.put(names[i], ((float[][][]) r.get(i).getValue())[0]);
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
        int nObj = obs.getAsJsonArray("objects").size();
        String code = decode(out, nObj, rng, temp);
        try {
            PlanCode.parse(code, nObj);
        } catch (Exception e) {
            nInvalid++;
            code = "W/1";
        }
        long dt = System.nanoTime() - t0;
        nCalls++;
        nanosTotal += dt;
        lastMs = dt / 1e6;
        return code;
    }

    @Override
    public String next(PlanBrain brain, Percepts.Percept p) {
        try {
            return infer(p.obs(), brain.st.temperJson());
        } catch (Exception e) {
            return PlanBrain.WANDER.next(brain, p);
        }
    }
}

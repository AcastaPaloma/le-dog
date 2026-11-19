package ai.ledog;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.nio.FloatBuffer;
import java.util.Map;

/**
 * A dog that knows tricks (user request 2026-11-19): it lives as a normal student dog (PlanBrain + real-dog gaze) until
 * a player says a command in chat (sit / come / jump / stay); then the PPO trick policy learned on the eval bench
 * (ledog/rl_bench.py, exported by ledog/rl_export.py to rl_tricks.onnx) drives it through RlBrain's 9 actions every
 * 0.5 s. Success -> a treat (hearts, "good dog!", hunger down); time out / breaking a stay -> smoke. Then back to the
 * student. Observation features are a line-by-line port of rl_bench.Dog.features (the trainer = the commanding player).
 */
public class TrickBrain implements WolfBrain {
    static final String[] CMDS = {"come", "sit", "jump", "stay"};
    static final int[] LIMIT = {40, 8, 6, 8};  // decisions of 0.5 s, as rl_bench.LIMIT
    static final int OBS = 28, NA = 9;

    final PlanBrain base;
    final RlBrain rl = new RlBrain();
    int cmd = -1, t, sub, prevA, sitRun;
    ServerPlayer trainer;
    double x0, z0;
    public int treats, misses;
    String lastResult = "";

    // ---- human-feedback adapter (COACH-style policy gradient with your rewards; per dog, saved to ~/.ledog/dogs) ----
    final double[][] W = new double[NA][OBS];                       // linear adapter on the trick policy's logits
    final Map<String, double[]> wordBias = new java.util.HashMap<>();  // learned bias per word (known or new)
    record Decision(long ms, float[] obs, int a, double[] p, String word) {
    }
    final java.util.ArrayDeque<Decision> hist = new java.util.ArrayDeque<>();
    Decision clicked;
    long clickMs;
    String word;          // active new-word episode (cmd == -2)
    int wordTries;
    public java.util.UUID uuid;
    static final double LR_BIAS = 2.0, LR_W = 0.1, TEMP = 0.5;  // 3-5 rewards teach a word

    TrickBrain(PlanBrain base) {
        this.base = base;
    }

    @Override
    public String kind() {
        return "trick";
    }

    private static OrtEnvironment env;
    private static OrtSession sess;

    static synchronized OrtSession session() throws Exception {
        if (sess == null) {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1);
            sess = env.createSession(LedogModels.dir().resolve("rl_tricks.onnx").toString(), o);
        }
        return sess;
    }

    /** A player said a command near this dog. */
    void command(String c, ServerPlayer p, Wolf wolf) {
        int i = java.util.Arrays.asList(CMDS).indexOf(c);
        if (i < 0) {  // a new word: try actions (1 s each, 4 tries) from what this word has earned so far
            cmd = -2;
            word = c;
            wordTries = 0;
            sub = 0;
            trainer = p;
            rl.heading = wolf.getYRot();
            rl.init = true;
            wolf.getNavigation().stop();
            return;
        }
        word = null;
        cmd = i;
        t = 0;
        sub = 0;
        sitRun = 0;
        prevA = 0;
        trainer = p;
        x0 = wolf.getX();
        z0 = wolf.getZ();
        rl.heading = wolf.getYRot();
        rl.init = true;
        rl.jumped = false;
        wolf.getNavigation().stop();
    }

    @Override
    public void step(ServerLevel level, Wolf wolf) {
        if (cmd == -2) {
            if (sub++ % 10 == 0) {
                if (wordTries >= 4) {
                    endWord(wolf);
                    base.step(level, wolf);
                    return;
                }
                double[] z = wordBias.computeIfAbsent(word, k -> new double[NA]).clone();
                double[] pr = softmax(z, 1.0);
                int a = sample(pr, level.getRandom().nextDouble());
                remember(new Decision(System.currentTimeMillis(), null, a, pr, word));
                rl.action = a;
                wordTries++;
            }
            rl.step(level, wolf);
            return;
        }
        if (cmd < 0) {
            base.step(level, wolf);
            return;
        }
        if (sub++ % 5 == 0) {  // a decision every 0.5 s (brain steps at 10 Hz)
            if (judge(level, wolf)) return;
            rl.action = decide(level, wolf);
            prevA = rl.action;
            rl.jumped = false;
        }
        rl.step(level, wolf);
    }

    /** Success / failure of the current command after the decision that just ran; true = episode over. */
    private boolean judge(ServerLevel level, Wolf wolf) {
        if (sub == 1) return false;  // first decision of the episode
        t++;
        String c = CMDS[cmd];
        Boolean ok = null;
        if (c.equals("come") && trainer != null && wolf.distanceTo(trainer) < 1.6) ok = true;
        if (c.equals("sit")) {
            sitRun = rl.sitting ? sitRun + 1 : 0;
            if (sitRun >= 2) ok = true;
        }
        if (c.equals("jump") && rl.jumped) ok = true;
        if (c.equals("stay")) {
            if (Math.hypot(wolf.getX() - x0, wolf.getZ() - z0) > 0.7 || rl.jumped) ok = false;
            else if (t >= LIMIT[cmd]) ok = true;
        }
        if (ok == null && t >= LIMIT[cmd]) ok = false;
        if (ok == null) return false;
        level.broadcastEntityEvent(wolf, ok ? (byte) 7 : (byte) 6);  // hearts = treat, smoke = no treat
        if (ok) {
            treats++;
            base.st.hunger = Math.max(0, base.st.hunger - 0.15);
        } else {
            misses++;
            base.st.hunger = Math.min(1, base.st.hunger + 0.05);
        }
        lastResult = (ok ? "good dog! " : "no treat: ") + c;
        if (trainer != null) {
            String msg = "[" + wolf.getName().getString().split("\\|")[0].trim() + "] " + lastResult + "  (treats " + treats + ", misses " + misses + ")";
            trainer.sendSystemMessage(Component.literal(msg));
        }
        if (rl.sitting && !c.equals("sit")) {
            wolf.setInSittingPose(false);
            rl.sitting = false;
        }
        cmd = -1;
        rl.action = 0;
        return true;
    }

    private int decide(ServerLevel level, Wolf wolf) {
        float[] f = new float[OBS];
        f[cmd] = 1;
        f[4] = (float) t / LIMIT[cmd];
        if (CMDS[cmd].equals("come") && trainer != null) {
            double dx = trainer.getX() - wolf.getX(), dz = trainer.getZ() - wolf.getZ();
            double want = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = Math.toRadians(((want - rl.heading + 180) % 360 + 360) % 360 - 180);
            f[5] = (float) Math.sin(rel);
            f[6] = (float) Math.cos(rel);
            f[7] = (float) (Math.min(Math.hypot(dx, dz), 16) / 16);
        }
        var v = wolf.getDeltaMovement();
        f[8] = (float) (Math.min(Math.hypot(v.x, v.z) * 20, 6) / 6);
        f[9] = rl.sitting ? 1 : 0;
        f[10] = rl.jumped ? 1 : 0;
        for (int i = 0; i < 8; i++) f[11 + i] = (float) (RlBrain.ray(level, wolf, rl.heading + 45 * i, 6) / 6);
        f[19 + prevA] = 1;
        try {
            OrtSession s = session();
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), new long[]{1, OBS});
                 OrtSession.Result r = s.run(Map.of("obs", in))) {
                float[] lg = ((float[][]) r.get(0).getValue())[0];
                double[] z = new double[NA];
                double[] wb = wordBias.get(CMDS[cmd]);
                for (int a = 0; a < NA; a++) {
                    z[a] = lg[a] + (wb == null ? 0 : wb[a]);
                    for (int j = 0; j < OBS; j++) z[a] += W[a][j] * f[j];
                }
                double[] pr = softmax(z, TEMP);
                int a = sample(pr, level.getRandom().nextDouble());
                remember(new Decision(System.currentTimeMillis(), f, a, pr, CMDS[cmd]));
                return a;
            }
        } catch (Exception e) {
            LedogMod.LOG.error("trick policy failed", e);
            cmd = -1;
            return 0;
        }
    }

    static double[] softmax(double[] z, double temp) {
        double mx = Double.NEGATIVE_INFINITY, sum = 0;
        for (double v : z) mx = Math.max(mx, v);
        double[] p = new double[z.length];
        for (int i = 0; i < z.length; i++) sum += p[i] = Math.exp((z[i] - mx) / temp);
        for (int i = 0; i < z.length; i++) p[i] /= sum;
        return p;
    }

    static int sample(double[] p, double u) {
        double c = 0;
        for (int i = 0; i < p.length; i++) if (u <= (c += p[i])) return i;
        return p.length - 1;
    }

    void remember(Decision d) {
        hist.addLast(d);
        while (hist.size() > 16) hist.removeFirst();
    }

    void endWord(Wolf wolf) {
        cmd = -1;
        word = null;
        rl.action = 0;
        if (rl.sitting) {
            wolf.setInSittingPose(false);
            rl.sitting = false;
        }
    }

    /** Clicker: marks the exact decision that a reward in the next 5 s is for. */
    void click() {
        clicked = hist.peekLast();
        clickMs = System.currentTimeMillis();
    }

    /** Your reward r for recent behaviour: credit the clicked decision, or decisions 0.2-4 s ago (peak at 1 s). */
    String feedback(double r, String signal, Wolf wolf, ServerLevel level) {
        long now = System.currentTimeMillis();
        java.util.List<Decision> ds = new java.util.ArrayList<>();
        java.util.List<Double> ws = new java.util.ArrayList<>();
        if (clicked != null && now - clickMs < 5000) {
            ds.add(clicked);
            ws.add(1.0);
        } else
            for (Decision d : hist) {
                double age = (now - d.ms()) / 1000.0;
                if (age < 0.2 || age > 4.0) continue;
                ds.add(d);
                ws.add(Math.exp(-(age - 1.0) * (age - 1.0) / (2 * 0.8 * 0.8)));
            }
        clicked = null;
        double tot = 0;
        for (double w : ws) tot += w;
        String learned = "";
        for (int i = 0; i < ds.size(); i++) {
            Decision d = ds.get(i);
            double w = ws.get(i) / tot;
            double[] wb = wordBias.computeIfAbsent(d.word(), k -> new double[NA]);
            for (int a = 0; a < NA; a++) {
                double g = r * w * ((a == d.a() ? 1 : 0) - d.p()[a]);
                wb[a] = Math.max(-6, Math.min(6, wb[a] + LR_BIAS * g));
                if (d.obs() != null) for (int j = 0; j < OBS; j++) W[a][j] += LR_W * g * d.obs()[j];
            }
            if (ws.get(i) / tot > 0.4) learned = RlBrainNames.NAMES[d.a()] + " on '" + d.word() + "'";
        }
        level.broadcastEntityEvent(wolf, r > 0 ? (byte) 7 : (byte) 6);
        if (r > 0 && cmd == -2) endWord(wolf);  // got it: the word episode ends on a reward
        log(signal, r, ds, ws, wolf);
        save(wolf);
        return ds.isEmpty() ? "(nothing recent to credit)" : (r > 0 ? "more " : "less ") + learned;
    }

    void log(String signal, double r, java.util.List<Decision> ds, java.util.List<Double> ws, Wolf wolf) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("t", System.currentTimeMillis());
            o.addProperty("dog", wolf.getStringUUID());
            o.addProperty("signal", signal);
            o.addProperty("r", r);
            o.addProperty("hunger", base.st.hunger);
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (int i = 0; i < ds.size(); i++) {
                com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                d.addProperty("a", ds.get(i).a());
                d.addProperty("word", ds.get(i).word());
                d.addProperty("w", ws.get(i));
                if (ds.get(i).obs() != null) d.add("obs", LedogMod.GSON.toJsonTree(ds.get(i).obs()));
                a.add(d);
            }
            o.add("credited", a);
            java.nio.file.Path f = LedogModels.dir().resolve("feedback.jsonl");
            java.nio.file.Files.writeString(f, o + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            LedogMod.LOG.warn("feedback log failed", e);
        }
    }

    void save(Wolf wolf) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.add("W", LedogMod.GSON.toJsonTree(W));
            o.add("words", LedogMod.GSON.toJsonTree(wordBias));
            o.add("temper", LedogMod.GSON.toJsonTree(base.st.temper));
            o.addProperty("preset", base.presetName);
            o.addProperty("treats", treats);
            o.addProperty("misses", misses);
            java.nio.file.Path d = LedogModels.dir().resolve("dogs");
            java.nio.file.Files.createDirectories(d);
            java.nio.file.Files.writeString(d.resolve(wolf.getStringUUID() + ".json"), LedogMod.GSON.toJson(o));
        } catch (Exception e) {
            LedogMod.LOG.warn("dog save failed", e);
        }
    }

    void load(Wolf wolf) {
        try {
            java.nio.file.Path f = LedogModels.dir().resolve("dogs").resolve(wolf.getStringUUID() + ".json");
            if (!java.nio.file.Files.exists(f)) return;
            com.google.gson.JsonObject o = LedogMod.GSON.fromJson(java.nio.file.Files.readString(f), com.google.gson.JsonObject.class);
            double[][] w = LedogMod.GSON.fromJson(o.get("W"), double[][].class);
            for (int a = 0; a < NA; a++) System.arraycopy(w[a], 0, W[a], 0, OBS);
            for (var e : o.getAsJsonObject("words").entrySet())
                wordBias.put(e.getKey(), LedogMod.GSON.fromJson(e.getValue(), double[].class));
            double[] t = LedogMod.GSON.fromJson(o.get("temper"), double[].class);
            System.arraycopy(t, 0, base.st.temper, 0, 5);
            if (o.has("preset") && !o.get("preset").isJsonNull()) base.presetName = o.get("preset").getAsString();
            treats = o.get("treats").getAsInt();
            misses = o.get("misses").getAsInt();
        } catch (Exception e) {
            LedogMod.LOG.warn("dog load failed", e);
        }
    }

    @Override
    public com.google.gson.JsonObject status() {
        com.google.gson.JsonObject o = cmd < 0 ? base.status() : new com.google.gson.JsonObject();
        o.addProperty("trick", cmd >= 0 ? CMDS[cmd] : cmd == -2 ? word : "");
        return o;
    }
}

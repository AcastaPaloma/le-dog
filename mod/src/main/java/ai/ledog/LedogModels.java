package ai.ledog;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * ONNX Runtime sessions for the Le Dog transformer policies and the DINOv3-S encoder. Model files live in $LEDOG_DIR or
 * ~/.ledog (exported by `python -m ledog.export_onnx`); they are never bundled (CC BY-NC / DINOv3 licence).
 *   shape_pm.onnx   motion(1,90)                        -> (1,40,2) unit-path-length ego shape (image-free)
 *   shape_goal.onnx motion(1,92) = past motion + goal   -> (1,40,2)
 *   vision_pm.onnx  feats(1,16,17,384), motion(1,30)    -> (1,40,2)
 *   dino_pool.onnx  image(1,3,224,224) in [0,1]         -> (1,17,384)
 */
public final class LedogModels {
    private static OrtEnvironment env;
    private static final Map<String, OrtSession> sessions = new HashMap<>();

    public static Path dir() {
        return Path.of(System.getenv().getOrDefault("LEDOG_DIR", System.getProperty("user.home") + "/.ledog"));
    }

    public static synchronized boolean available(String name) {
        return Files.exists(dir().resolve(name + ".onnx"));
    }

    private static synchronized OrtSession session(String name) throws Exception {
        if (env == null) env = OrtEnvironment.getEnvironment();
        OrtSession s = sessions.get(name);
        if (s == null) {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(2);
            s = env.createSession(dir().resolve(name + ".onnx").toString(), o);
            sessions.put(name, s);
        }
        return s;
    }

    /** Image-free shape policy: motion vector -> (40, 2) ego shape. */
    public static float[][] shape(String model, float[] motion) throws Exception {
        OrtSession s = session(model);
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(motion), new long[]{1, motion.length});
             OrtSession.Result r = s.run(Map.of("motion", x))) {
            return ((float[][][]) r.get(0).getValue())[0];
        }
    }

    /** Vision policy: (16, 17, 384) features + motion (30) -> (40, 2) ego shape. */
    public static float[][] visionShape(float[] feats, float[] motion) throws Exception {
        OrtSession s = session("vision_pm");
        try (OnnxTensor f = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[]{1, 16, 17, 384});
             OnnxTensor m = OnnxTensor.createTensor(env, FloatBuffer.wrap(motion), new long[]{1, motion.length});
             OrtSession.Result r = s.run(Map.of("feats", f, "motion", m))) {
            return ((float[][][]) r.get(0).getValue())[0];
        }
    }

    /** Real-dog gaze selection: types [1,24] int64, feats [1,24,9], mask [1,24] -> logits [25] (index 24 = NONE). */
    public static float[] gaze(long[] types, float[] feats, float[] mask) throws Exception {
        OrtSession s = session("gaze");
        try (OnnxTensor t = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(types), new long[]{1, types.length});
             OnnxTensor f = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[]{1, types.length, feats.length / types.length});
             OnnxTensor m = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, mask.length});
             OrtSession.Result r = s.run(Map.of("types", t, "feats", f, "mask", m))) {
            return ((float[][]) r.get(0).getValue())[0];
        }
    }

    /** DINOv3-S + pooling: CHW image in [0,1] (3*224*224) -> (17*384) tokens, flattened. */
    public static float[] dino(float[] chw) throws Exception {
        OrtSession s = session("dino_pool");
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, 224, 224});
             OrtSession.Result r = s.run(Map.of("image", x))) {
            float[][][] o = (float[][][]) r.get(0).getValue();
            float[] flat = new float[17 * 384];
            for (int t = 0; t < 17; t++) System.arraycopy(o[0][t], 0, flat, t * 384, 384);
            return flat;
        }
    }

    private LedogModels() {
    }
}

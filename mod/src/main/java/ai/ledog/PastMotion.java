package ai.ledog;

import java.util.Arrays;
import java.util.List;

/**
 * Past-motion input of the DINO-transformer policies (ledog.data.past_motion) from a 10 Hz Minecraft history
 * [x, y, z, yawDeg] (oldest first): linear resampling to 30 fps (as ledog.m4_eval.poses_30fps_from_10hz, yaw unwrapped,
 * pitch 0), camera frame at the last sample (x right, z forward), scale = median 30-fps step over the last 5 s x 15,
 * clip +-5; a still history (median step < 0.01 block/frame) gives zeros (as the M3 driver). Python reference:
 * ledog.shape_ref.motion_input.
 */
public final class PastMotion {
    public static float[] compute(List<double[]> hist10, int n) {
        int T = hist10.size();
        int n30 = (T - 1) * 3 + 1;
        double[][] pos = new double[n30][3];
        double[] yaw = new double[n30];
        double[] yu = new double[T];
        for (int i = 0; i < T; i++) {  // np.unwrap of the yaw in radians
            double y = Math.toRadians(hist10.get(i)[3]);
            if (i == 0) {
                yu[i] = y;
            } else {
                double dd = y - Math.toRadians(hist10.get(i - 1)[3]);
                double m = dd - 2 * Math.PI * Math.floor((dd + Math.PI) / (2 * Math.PI));
                if (m == -Math.PI && dd > 0) m = Math.PI;
                yu[i] = yu[i - 1] + m;
            }
        }
        for (int j = 0; j < n30; j++) {
            double t = j / 3.0;
            int i0 = Math.min((int) Math.floor(t), T - 1), i1 = Math.min(i0 + 1, T - 1);
            double w = t - i0;
            for (int c = 0; c < 3; c++) pos[j][c] = hist10.get(i0)[c] * (1 - w) + hist10.get(i1)[c] * w;
            yaw[j] = yu[i0] * (1 - w) + yu[i1] * w;
        }
        int cut = n30 - 1, lo = Math.max(0, cut - 150);
        double[] steps = new double[cut - lo];
        for (int j = lo; j < cut; j++)
            steps[j - lo] = Math.sqrt(sq(pos[j + 1][0] - pos[j][0]) + sq(pos[j + 1][1] - pos[j][1]) + sq(pos[j + 1][2] - pos[j][2]));
        float[] out = new float[2 * n];
        if (steps.length == 0) return out;
        double med = median(steps);
        if (med < 0.01) return out;
        double scale = med * 15 + 1e-8;
        double y = yaw[cut];
        double rx = -Math.cos(y), rz = -Math.sin(y), fx = -Math.sin(y), fz = Math.cos(y);
        for (int k = 0; k < n; k++) {
            int idx = Math.max(0, cut - (n - 1 - k));
            double vx = pos[idx][0] - pos[cut][0], vz = pos[idx][2] - pos[cut][2];
            out[2 * k] = (float) clip((vx * rx + vz * rz) / scale);
            out[2 * k + 1] = (float) clip((vx * fx + vz * fz) / scale);
        }
        return out;
    }

    static double median(double[] a) {
        double[] b = a.clone();
        Arrays.sort(b);
        int m = b.length / 2;
        return b.length % 2 == 1 ? b[m] : 0.5 * (b[m - 1] + b[m]);
    }

    static double sq(double v) {
        return v * v;
    }

    static double clip(double v) {
        return Math.max(-5, Math.min(5, v));
    }

    private PastMotion() {
    }
}

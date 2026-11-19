package ai.ledog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Contract v1 action codes (ledog/contract.py is the reference; keep the two in sync and bump VERSION together).
 * A plan is 1..5 primitives separated by ';', e.g. "M+30/4/t;S#1/2". Same regexes, bins and checks as
 * contract.parse_plan: bearings must be 30-degree bins in [-180, 180], object indices must be < nObj.
 */
public final class PlanCode {
    public static final int VERSION = 1;
    static final String B = "[+-]\\d{1,3}", IDX = "#\\d{1,2}", SEC = "(?:0\\.5|1|2|4|8)", DST = "(?:1|2|4|8|16)", SPD = "[swtr]";
    static final Pattern PRIM = Pattern.compile("^(?:"
            + "M" + B + "/" + DST + "/" + SPD
            + "|L(?:" + IDX + "|" + B + ")"
            + "|S(?:" + IDX + "|" + B + ")/" + SEC
            + "|T/" + SEC + "|Y/" + SEC
            + "|B/[123]"
            + "|F" + IDX + "/" + SEC
            + "|R(?:" + IDX + "|" + B + ")/" + DST + "/" + SPD
            + "|W/" + SEC + "|P/" + SEC + ")$");

    /** One primitive. Unused fields are null. */
    public record Prim(char op, Integer obj, Integer bearing, Integer dist, Character speed, Double secs, Integer n,
                       String code) {
        /** Nominal speed in blocks/s for M/R (s 1.2, w 2.9, t 4.0, r 6.0), as contract.plan_seconds. */
        public double bps() {
            return switch (speed == null ? 'w' : speed) {
                case 's' -> 1.2;
                case 't' -> 4.0;
                case 'r' -> 6.0;
                default -> 2.9;
            };
        }

        /** Nominal duration in seconds (contract.plan_seconds). */
        public double nominalSecs() {
            if (op == 'M' || op == 'R') return dist / bps();
            if (secs != null) return secs;
            if (op == 'B') return 0.6 * n;
            return 0.5;
        }
    }

    public static List<Prim> parse(String code, int nObj) {
        String s = code.strip();
        while (s.startsWith(";")) s = s.substring(1);
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        List<Prim> out = new ArrayList<>();
        for (String raw : s.split(";")) {
            String p = raw.strip();
            if (!PRIM.matcher(p).matches()) throw new IllegalArgumentException("bad primitive '" + p + "'");
            char op = p.charAt(0);
            String[] parts = p.substring(1).split("/", -1);
            String head = parts[0];
            Integer obj = null, bearing = null, dist = null, n = null;
            Character speed = null;
            Double secs = null;
            if (head.startsWith("#")) {
                obj = Integer.parseInt(head.substring(1));
                if (obj >= nObj) throw new IllegalArgumentException("object index " + obj + " >= " + nObj);
            } else if (!head.isEmpty()) {
                bearing = Integer.parseInt(head);
                if (bearing % 30 != 0 || bearing < -180 || bearing > 180)
                    throw new IllegalArgumentException("bearing " + bearing + " not in 30-deg bins");
            }
            if (op == 'M' || op == 'R') {
                dist = Integer.parseInt(parts[1]);
                speed = parts[2].charAt(0);
            } else if ("STYWPF".indexOf(op) >= 0) {
                secs = Double.parseDouble(parts[1]);
            } else if (op == 'B') {
                n = Integer.parseInt(parts[1]);
            }
            out.add(new Prim(op, obj, bearing, dist, speed, secs, n, p));
        }
        if (out.isEmpty() || out.size() > 5) throw new IllegalArgumentException("plan must have 1..5 primitives");
        return out;
    }

    private PlanCode() {
    }
}

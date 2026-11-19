package ai.ledog;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;

/**
 * Scripted wolf for filming (media/film.py): NOT a learned policy. The film driver sets a goal (one point or a looping
 * path), a speed, periodic jumps, sitting, and a look target over the control port (op film_set); this brain executes
 * them at 10 Hz on the same body (LimitedMoveControl turn limit) as the Le Dog brains, so scripted shots move like them.
 */
public class FilmBrain implements WolfBrain {
    public volatile double[][] path;   // waypoints [x, z]; one point = go there and stop; loop = repeat
    public volatile boolean loop;
    public volatile double speed = 2.9;   // blocks/s
    public volatile int jumpEvery;     // brain steps (0.1 s) between jumps; 0 = never
    public volatile boolean sit;
    public volatile Integer lookId;     // entity id to look at
    public volatile double[] lookAt;    // or a point [x, y, z]
    public volatile double arrive = 0.6;
    int wp, n;
    boolean sitting;

    @Override
    public String kind() {
        return "film";
    }

    public synchronized void setPath(double[][] p, boolean lp) {
        path = p;
        loop = lp;
        wp = 0;
    }

    @Override
    public void step(ServerLevel level, Wolf wolf) {
        drive(level, wolf);
    }

    /** Wolves and scripted props (any Mob with its goals cleared) share this driver. */
    public void drive(ServerLevel level, net.minecraft.world.entity.Mob wolf) {
        n++;
        if (sit != sitting && wolf instanceof Wolf w) {
            w.setInSittingPose(sit);
            sitting = sit;
        }
        double[][] p = path;
        boolean moving = false;
        if (!sit && p != null && p.length > 0) {
            synchronized (this) {
                if (wp >= p.length) wp = loop ? 0 : p.length - 1;
                double[] g = p[wp];
                double d = Math.hypot(g[0] - wolf.getX(), g[1] - wolf.getZ());
                if (d < arrive) {
                    if (wp < p.length - 1 || loop) wp = (wp + 1) % p.length;
                    else path = null;
                }
                if (path != null) {
                    double[] h = p[Math.min(wp, p.length - 1)];
                    wolf.getMoveControl().setWantedPosition(h[0], wolf.getY(), h[1], BmBrain.modifierFor(speed));
                    moving = true;
                }
            }
        }
        if (!moving) {
            wolf.getNavigation().stop();
            wolf.getMoveControl().setWantedPosition(wolf.getX(), wolf.getY(), wolf.getZ(), 0.0);
        }
        if (jumpEvery > 0 && !sit && n % jumpEvery == 0 && wolf.onGround()) wolf.getJumpControl().jump();
        Integer id = lookId;
        if (id != null) {
            Entity e = level.getEntity(id);
            if (e != null) wolf.getLookControl().setLookAt(e, 30f, 30f);
        } else if (lookAt != null) {
            double[] q = lookAt;
            wolf.getLookControl().setLookAt(q[0], q[1], q[2]);
        }
    }

    @Override
    public com.google.gson.JsonObject status() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("phase", sit ? "sit" : path != null ? "move" : "still");
        return o;
    }
}

package ai.ledog.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Cinematic camera for filming (media/film.py). A shot file ($LEDOG_CINE; unset = disabled) describes the
 * camera; the client renders from a detached, client-only camera entity whose pose is set once per tick (previous pose
 * kept in xo/yRotO), so the renderer interpolates between ticks and the motion is smooth at any frame rate.
 * Every tick it appends the camera pose and nearby entity positions to the shot's log (for overlays).
 *
 * Shot JSON: {"id", "mode": "path"|"follow"|"orbit"|"off", "fov",
 *   path:   "keys": [[t, x, y, z, yaw, pitch], ...] (Catmull-Rom through positions, seconds of game time)
 *   follow: "target": entity id, "offset": [dx, dy, dz] (world frame), "lag": 0..1 (spring, default 0.12)
 *   orbit:  "center": [x, y, z] or "target", "radius", "height", "deg_per_s", "start_deg"
 *   look:   "look_target": entity id | "look_point": [x, y, z] (overrides the keyed yaw/pitch), "look_lag"}
 */
public class CineCam {
    private static final Gson GSON = new Gson();
    private final Path file = System.getenv("LEDOG_CINE") == null ? null : Path.of(System.getenv("LEDOG_CINE"));
    private long lastMtime = -1, tick, t0;
    private JsonObject shot;
    private ArmorStand cam;
    private double cx, cy, cz, yaw, pitch, lx, ly, lz;   // camera and smoothed look point
    private boolean init, hudWasHidden;
    private BufferedWriter log;

    /** Called every client tick; true while a shot is active (the caller then skips its own camera logic). */
    public boolean tick(Minecraft mc) {
        tick++;
        if (tick % 5 == 0) poll();
        if (mc.level == null || shot == null || "off".equals(s("mode", "off"))) {
            if (cam != null) stop(mc);
            return false;
        }
        if (cam == null || cam.level() != mc.level) {
            cam = (ArmorStand) EntityTypes.ARMOR_STAND.create(mc.level, EntitySpawnReason.COMMAND);
            cam.setNoGravity(true);
            cam.setInvisible(true);
            init = false;
            hudWasHidden = mc.gui.hud.isHidden();
        }
        double t = (tick - t0) / 20.0;
        double[] pose = pose(mc, t);
        if (pose == null) return true;
        double eye = cam.getEyeHeight();
        if (!init) {
            cam.setPos(pose[0], pose[1] - eye, pose[2]);
            cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ();
            cam.setYRot((float) pose[3]); cam.yRotO = (float) pose[3];
            cam.yHeadRot = (float) pose[3]; cam.yHeadRotO = (float) pose[3];  // living entities view along the head
            cam.setXRot((float) pose[4]); cam.xRotO = (float) pose[4];
            init = true;
        } else {
            cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ();
            cam.yRotO = cam.getYRot(); cam.xRotO = cam.getXRot();
            cam.setPos(pose[0], pose[1] - eye, pose[2]);
            // unwrapped yaw: the renderer lerps yRotO -> yRot linearly
            cam.yHeadRotO = cam.yHeadRot;
            cam.setYRot((float) (cam.getYRot() + Mth.wrapDegrees(pose[3] - cam.getYRot())));
            cam.yHeadRot = cam.getYRot();
            cam.setXRot((float) pose[4]);
        }
        if (mc.getCameraEntity() != cam) mc.setCameraEntity(cam);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        writeLog(mc, t);
        return true;
    }

    private void stop(Minecraft mc) {
        if (mc.player != null) mc.setCameraEntity(mc.player);
        if (mc.gui.hud.isHidden() != hudWasHidden) mc.gui.hud.toggle();
        cam = null;
        closeLog();
    }

    private void poll() {
        try {
            if (file == null || !Files.exists(file)) return;
            long mt = Files.getLastModifiedTime(file).toMillis();
            if (mt == lastMtime) return;
            lastMtime = mt;
            shot = GSON.fromJson(Files.readString(file), JsonObject.class);
            t0 = tick;
            init = false;
            closeLog();
            if (shot.has("log")) {
                Path p = Path.of(shot.get("log").getAsString());
                Files.createDirectories(p.getParent());
                log = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (shot.has("fov")) Minecraft.getInstance().options.fov().set(shot.get("fov").getAsInt());
        } catch (Exception e) {
            System.err.println("[ledog cine] bad shot file: " + e);
        }
    }

    private String s(String k, String d) {
        return shot.has(k) ? shot.get(k).getAsString() : d;
    }

    private double n(String k, double d) {
        return shot.has(k) ? shot.get(k).getAsDouble() : d;
    }

    private Entity ent(Minecraft mc, String k) {
        return shot.has(k) ? mc.level.getEntity(shot.get(k).getAsInt()) : null;
    }

    /** -> [x, y, z, yaw, pitch] of the camera eye at shot time t (seconds of game time). */
    private double[] pose(Minecraft mc, double t) {
        String mode = s("mode", "path");
        double x, y, z, yw = 0, pt = 0;
        boolean keyedLook = false;
        switch (mode) {
            case "follow" -> {
                Entity e = ent(mc, "target");
                if (e == null) return null;
                JsonArray o = shot.getAsJsonArray("offset");
                double tx = e.getX() + o.get(0).getAsDouble(), ty = e.getY() + o.get(1).getAsDouble(), tz = e.getZ() + o.get(2).getAsDouble();
                double k = init ? n("lag", 0.12) : 1.0;
                cx += (tx - cx) * k; cy += (ty - cy) * k; cz += (tz - cz) * k;
                if (!init) { cx = tx; cy = ty; cz = tz; }
                x = cx; y = cy; z = cz;
            }
            case "orbit" -> {
                double ox, oy, oz;
                Entity e = ent(mc, "target");
                if (e != null) { ox = e.getX(); oy = e.getY(); oz = e.getZ(); }
                else {
                    JsonArray c = shot.getAsJsonArray("center");
                    ox = c.get(0).getAsDouble(); oy = c.get(1).getAsDouble(); oz = c.get(2).getAsDouble();
                }
                double a = Math.toRadians(n("start_deg", 0) + n("deg_per_s", 10) * t), rr = n("radius", 6);
                double tx = ox + rr * Math.sin(a), ty = oy + n("height", 2), tz = oz + rr * Math.cos(a);
                double k = init ? n("lag", 0.2) : 1.0;
                if (!init) { cx = tx; cy = ty; cz = tz; }
                cx += (tx - cx) * k; cy += (ty - cy) * k; cz += (tz - cz) * k;
                x = cx; y = cy; z = cz;
                if (!shot.has("look_target") && !shot.has("look_point")) {
                    shot.add("look_point", GSON.toJsonTree(new double[]{ox, oy + 0.6, oz}));
                }
            }
            default -> {  // path
                JsonArray keys = shot.getAsJsonArray("keys");
                int nk = keys.size();
                double[][] K = new double[nk][];
                for (int i = 0; i < nk; i++) {
                    JsonArray a = keys.get(i).getAsJsonArray();
                    K[i] = new double[a.size()];
                    for (int j = 0; j < a.size(); j++) K[i][j] = a.get(j).getAsDouble();
                }
                for (int i = 1; i < nk; i++) {  // unwrap yaw keys
                    K[i][4] = K[i - 1][4] + Mth.wrapDegrees(K[i][4] - K[i - 1][4]);
                }
                double T = K[nk - 1][0];
                if (shot.has("ease") && shot.get("ease").getAsBoolean() && T > 0) {  // ease in/out over the whole move
                    double v = Mth.clamp(t / T, 0, 1);
                    t = T * v * v * (3 - 2 * v);
                }
                int i = 0;
                while (i < nk - 2 && t > K[i + 1][0]) i++;
                double[] p0 = K[Math.max(i - 1, 0)], p1 = K[i], p2 = K[Math.min(i + 1, nk - 1)], p3 = K[Math.min(i + 2, nk - 1)];
                double u = p2[0] > p1[0] ? Mth.clamp((t - p1[0]) / (p2[0] - p1[0]), 0, 1) : 1;
                double[] r = new double[5];
                for (int j = 1; j <= 5; j++) r[j - 1] = cr(p0[j], p1[j], p2[j], p3[j], u);
                x = r[0]; y = r[1]; z = r[2]; yw = r[3]; pt = r[4];
                keyedLook = true;
            }
        }
        double[] look = null;
        Entity lt = ent(mc, "look_target");
        if (lt != null) look = new double[]{lt.getX(), lt.getY() + n("look_dy", 0.5), lt.getZ()};
        else if (shot.has("look_point")) {
            JsonArray a = shot.getAsJsonArray("look_point");
            look = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        }
        if (look != null) {
            double k = init ? n("look_lag", 0.15) : 1.0;
            if (!init) { lx = look[0]; ly = look[1]; lz = look[2]; }
            lx += (look[0] - lx) * k; ly += (look[1] - ly) * k; lz += (look[2] - lz) * k;
            double dx = lx - x, dy = ly - y, dz = lz - z;
            yw = Math.toDegrees(Math.atan2(-dx, dz));
            pt = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        } else if (!keyedLook) {
            yw = cam.getYRot();
            pt = cam.getXRot();
        }
        return new double[]{x, y, z, yw, pt};
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private void writeLog(Minecraft mc, double t) {
        if (log == null) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("t", t);
            o.addProperty("wall", System.currentTimeMillis());
            o.addProperty("gt", mc.level.getGameTime());
            o.add("cam", GSON.toJsonTree(new double[]{cam.getX(), cam.getY() + cam.getEyeHeight(), cam.getZ(), cam.getYRot(), cam.getXRot()}));
            o.addProperty("fov", mc.options.fov().get());
            JsonArray es = new JsonArray();
            for (Entity e : mc.level.entitiesForRendering()) {
                String ty = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                if (!(ty.equals("wolf") || ty.equals("creeper") || ty.equals("armor_stand") || ty.equals("item")
                        || ty.equals("villager"))) continue;
                if (e.distanceToSqr(cam) > 96 * 96) continue;
                es.add(GSON.toJsonTree(new Object[]{e.getId(), ty, round(e.getX()), round(e.getY()), round(e.getZ()), round(e.getYRot())}));
            }
            o.add("ents", es);
            log.write(o.toString());
            log.newLine();
            if (tick % 20 == 0) log.flush();
        } catch (Exception e) {
            closeLog();
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private void closeLog() {
        try {
            if (log != null) log.close();
        } catch (Exception ignored) {
        }
        log = null;
    }
}

package ai.ledog.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client half of the Le Dog wolf body: render from the wolf's eyes (camera entity = the wolf named "ledog") and dump a
 * frame + the CLIENT-side camera pose every 2 ticks (10 Hz) to $LEDOG_FRAMES (default ~/wolfmod/frames), so frames and
 * labels come from the same client tick. Files: f<seq>.png and f<seq>.json, written atomically (tmp + move).
 */
public class LedogClient implements ClientModInitializer {
    private static final Path OUT = Path.of(System.getenv().getOrDefault("LEDOG_FRAMES",
            System.getProperty("user.home") + "/wolfmod/frames"));
    private long ticks, seq;
    private final LedogVision vision = new LedogVision();
    private final boolean researchVision = "1".equals(System.getenv("LEDOG_VISION"));
    private Wolf camWolf;          // /ledogcam (normal play): ride along in a Le Dog wolf's eyes
    private boolean camVision, hudWasHidden;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((d, ctx) ->
                d.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("ledogcam").executes(c -> {
                    toggleCam(Minecraft.getInstance());
                    return 1;
                })));
    }

    private void toggleCam(Minecraft mc) {
        if (camWolf != null) {
            camWolf = null;
            mc.setCameraEntity(mc.player);
            if (mc.gui.hud.isHidden() != hudWasHidden) mc.gui.hud.toggle();
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Le Dog cam off"));
            return;
        }
        Wolf best = null;
        double bd = 64 * 64;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Wolf w && w.getCustomName() != null && w.getCustomName().getString().startsWith("Le Dog")) {
                double d = w.distanceToSqr(mc.player);
                if (d < bd) { bd = d; best = w; }
            }
        }
        if (best == null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("no Le Dog wolf within 64 blocks (/ledog <bm|shape|follow|vision>)"));
            return;
        }
        camWolf = best;
        camVision = best.getCustomName().getString().contains("(vision)");
        hudWasHidden = mc.gui.hud.isHidden();
        mc.setCameraEntity(best);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        vision.reset(best.getId());
        mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Le Dog cam on" + (camVision ? " (vision brain: DINOv3 on this view drives the wolf)" : "") + " - /ledogcam to exit"));
    }

    private void onTick(Minecraft mc) {
        ticks++;
        if (mc.level == null) return;
        if (camWolf != null) {  // normal play ride-along
            if (!camWolf.isAlive()) { toggleCam(mc); return; }
            if (mc.getCameraEntity() != camWolf) mc.setCameraEntity(camWolf);
            if (camVision && ticks % 2 == 0) {
                final Wolf w0 = camWolf;
                final long n = seq++;
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
                    try (img) {
                        vision.onFrame(w0, img.getPixels(), img.getWidth(), img.getHeight(), n);
                    }
                });
            }
            return;
        }
        Wolf wolf = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Wolf w && w.getCustomName() != null && "ledog".equals(w.getCustomName().getString())) {
                wolf = w;
                break;
            }
        }
        if (wolf == null) return;
        if (mc.getCameraEntity() != wolf) mc.setCameraEntity(wolf);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();  // F1: no hotbar/crosshair in the frames
        if (ticks % 2 != 0) return;
        final long n = seq++;
        final JsonObject pose = new JsonObject();
        pose.addProperty("seq", n);
        pose.addProperty("clientTick", ticks);
        pose.addProperty("x", wolf.getX());
        pose.addProperty("y", wolf.getY());
        pose.addProperty("z", wolf.getZ());
        pose.addProperty("eyeY", wolf.getEyeY());
        pose.addProperty("headYaw", wolf.getYHeadRot());
        pose.addProperty("bodyYaw", wolf.yBodyRot);
        pose.addProperty("pitch", wolf.getXRot());
        pose.addProperty("camYaw", wolf.getViewYRot(1.0f));   // what setCameraEntity actually renders with
        pose.addProperty("camPitch", wolf.getViewXRot(1.0f));
        try {
            Files.createDirectories(OUT);
        } catch (Exception ignored) {
        }
        final Wolf rw = wolf;
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
            try (img) {
                if (researchVision) vision.onFrame(rw, img.getPixels(), img.getWidth(), img.getHeight(), n);
                Path png = OUT.resolve("f" + n + ".png"), tmp = OUT.resolve("f" + n + ".png.tmp");
                img.writeToFile(tmp);
                Files.move(tmp, png, StandardCopyOption.ATOMIC_MOVE);
                Path js = OUT.resolve("f" + n + ".json"), jtmp = OUT.resolve("f" + n + ".json.tmp");
                Files.writeString(jtmp, pose.toString(), StandardCharsets.UTF_8);
                Files.move(jtmp, js, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                System.err.println("[ledog] frame write failed: " + e);
            }
        });
    }
}

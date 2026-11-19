package ai.ledog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Le Dog wolf body (plan 8.3). A wolf whose AI GOALS are cleared but whose AI/physics stay on, so vanilla MoveControl,
 * gravity, collisions and jumping move it toward a target that an external controller (Python) sets every tick.
 *
 * Control: localhost TCP 25570, one JSON object per line, one JSON reply per line.
 *   {"op":"spawn","biome":"plains"|"savanna"|"meadow"|null, "x":..,"z":..}  spawn (or respawn) the wolf; with biome:
 *       nearest such biome to (x,z) or the player; always placed on the GROUND (heightmap ignoring leaves)
 *   {"op":"goto","x":..,"y":..,"z":..,"speed":1.0}  move target (kept and re-issued every tick until replaced)
 *   {"op":"stop"}                                   clear the move target
 *   {"op":"look","yaw":..,"pitch":..}               head direction (degrees, MC convention: yaw 0 = +z, + = right)
 *   {"op":"state"}                                  pose: x y z bodyYaw headYaw pitch onGround tick
 * The (spectator) player is teleported along with the wolf so chunks stay loaded and the client sees it.
 */
public class LedogMod implements ModInitializer {
    public static final String MOD_ID = "ledog";
    public static final String WOLF_NAME = "ledog";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    static final Gson GSON = new Gson();

    private record Cmd(JsonObject msg, CompletableFuture<JsonObject> reply) {}
    private final ConcurrentLinkedQueue<Cmd> queue = new ConcurrentLinkedQueue<>();

    private Wolf wolf;
    private Vec3 target;
    private double speed = 1.0;
    private Float lookYaw, lookPitch;
    private long tick;
    private BlockPos pendingSpawn;  // two-phase spawn: teleport player first, spawn once chunks are entity-ticking
    private long spawnAtTick;
    private int spawnedId = -1;
    private boolean pendingVanilla, spawnedVanilla;
    private String pendingBrain;
    private long pendingSeed;  // Gate D baseline: keep the vanilla wolf AI goals
    private static final double PLAYER_ABOVE = 24.0;  // outside vanilla LookAtPlayerGoal range (8 blocks)

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        LedogShare.startUploader();  // uploads nothing unless the player ran /ledog share on
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            Thread t = new Thread(() -> {
                try {
                    LedogShare.checkForUpdate();
                } catch (Exception e) {
                    LOG.debug("Le Dog update check: " + e);
                }
            }, "ledog-update-check");
            t.setDaemon(true);
            t.start();
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            if (LedogShare.latest != null)
                handler.getPlayer().sendSystemMessage(Component.literal("Le Dog: new dog brains are available ("
                        + LedogShare.latest.get("policy").getAsString() + "). Run /ledog update").withStyle(net.minecraft.ChatFormatting.AQUA));
        });
        // trick dogs hear chat: "sit", "come", "jump", "stay" (also "sit!" etc.) reach trick dogs within 32 blocks
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((msg, sender, params) ->
                hear(sender, msg.signedContent()));
        // rewards by hand: steak (+1.0), bone (+0.7), golden carrot (+2.0), sneak + empty hand = pet (+0.3);
        // food counts more when the dog is hungry; vanilla taming / feeding is skipped for trick dogs
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() || hand != net.minecraft.world.InteractionHand.MAIN_HAND) return net.minecraft.world.InteractionResult.PASS;
            if (!(entity instanceof Wolf w) || !(playBrains.get(w) instanceof TrickBrain tb) || !(player instanceof ServerPlayer sp))
                return net.minecraft.world.InteractionResult.PASS;
            var item = player.getItemInHand(hand);
            double r;
            String sig;
            boolean food = true;
            if (item.is(net.minecraft.world.item.Items.COOKED_BEEF) || item.is(net.minecraft.world.item.Items.BEEF)) {
                r = 1.0; sig = "steak";
            } else if (item.is(net.minecraft.world.item.Items.BONE)) {
                r = 0.7; sig = "bone";
            } else if (item.is(net.minecraft.world.item.Items.GOLDEN_CARROT)) {
                r = 2.0; sig = "golden carrot";
            } else if (item.isEmpty() && player.isShiftKeyDown()) {
                r = 0.3; sig = "pet"; food = false;
            } else return net.minecraft.world.InteractionResult.PASS;
            if (food) {
                r *= 0.5 + tb.base.st.hunger;  // a hungry dog values food more
                tb.base.st.hunger = Math.max(0, tb.base.st.hunger - 0.2);
                if (!player.isCreative()) item.shrink(1);
            }
            reward(sp, w, tb, r, sig);
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
        // clicker: right-click with a stick marks the exact moment for the next reward (nearest trick dog, 16 blocks)
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide() || !player.getItemInHand(hand).is(net.minecraft.world.item.Items.STICK))
                return net.minecraft.world.InteractionResult.PASS;
            var t = nearestTrick(player, 16);
            if (t == null) return net.minecraft.world.InteractionResult.PASS;
            t.getValue().click();
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.8f);
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof Wolf w) reattach(w);
        });
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, ctx, env) -> {
            var root = net.minecraft.commands.Commands.literal("ledog").executes(c -> {
                c.getSource().sendSuccess(() -> Component.literal(
                        "Le Dog: /ledog update (download dog brains), /ledog student [bold|shy|lazy|playful|curious|social],"
                        + " /ledog trick [preset] (then say sit / come / jump / stay), /ledog debug, /ledog share on|off"), false);
                return 1;
            });
            for (String kind : new String[]{"bm", "shape", "follow", "vision"})
                root = root.then(net.minecraft.commands.Commands.literal(kind).executes(c -> playCommand(c.getSource(), kind)));
            var stu = net.minecraft.commands.Commands.literal("student").executes(c -> playCommand(c.getSource(), "student"));
            for (String preset : PRESETS.keySet())
            {
                var pr = net.minecraft.commands.Commands.literal(preset).executes(c -> playStudent(c.getSource(), preset, null));
                for (String ver : VERSIONS)  // /ledog student <preset> <version>: compare policy versions side by side
                    pr = pr.then(net.minecraft.commands.Commands.literal(ver).executes(c -> playStudent(c.getSource(), preset, ver)));
                stu = stu.then(pr);
            }
            root = root.then(stu);
            var tr = net.minecraft.commands.Commands.literal("trick").executes(c -> playTrick(c.getSource(), null));
            for (String preset : PRESETS.keySet())
                tr = tr.then(net.minecraft.commands.Commands.literal(preset).executes(c -> playTrick(c.getSource(), preset)));
            root = root.then(tr);
            root = root.then(net.minecraft.commands.Commands.literal("share")
                    .then(net.minecraft.commands.Commands.literal("on").executes(c -> {
                        LedogShare.setSharing(true);
                        c.getSource().sendSuccess(() -> Component.literal("Le Dog: sharing ON. Your dog-training feedback (anonymous: no names, no chat) helps train better dogs for everyone. /ledog share off to stop."), false);
                        return 1;
                    }))
                    .then(net.minecraft.commands.Commands.literal("off").executes(c -> {
                        LedogShare.setSharing(false);
                        c.getSource().sendSuccess(() -> Component.literal("Le Dog: sharing OFF. Feedback stays on this computer."), false);
                        return 1;
                    }))
                    .executes(c -> {
                        boolean on = LedogShare.sharing();
                        c.getSource().sendSuccess(() -> Component.literal("Le Dog sharing is " + (on ? "ON" : "OFF")
                                + " (brain " + LedogShare.policyVersion() + "). /ledog share on|off"), false);
                        return 1;
                    }));
            root = root.then(net.minecraft.commands.Commands.literal("update").executes(c -> {
                var src = c.getSource();
                src.sendSuccess(() -> Component.literal("Le Dog: checking for new dog brains..."), false);
                Thread t = new Thread(() -> {
                    String msg;
                    try {
                        msg = LedogShare.update();
                    } catch (Exception e) {
                        msg = "update failed: " + e.getMessage();
                    }
                    String m = msg;
                    src.getServer().execute(() -> src.sendSuccess(() -> Component.literal("Le Dog: " + m), false));
                }, "ledog-update");
                t.setDaemon(true);
                t.start();
                return 1;
            }));
            // /ledog say <word>: same as typing the word in chat (commands, new words, praise / no)
            root = root.then(net.minecraft.commands.Commands.literal("say").then(
                    net.minecraft.commands.Commands.argument("word", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
                        hear(c.getSource().getPlayerOrException(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "word"));
                        return 1;
                    })));
            // /ledog replan plan|step|fast|faster: how often student dogs re-decide (whole plan / every primitive /
            // every 1 s / every 0.5 s), receding-horizon style with a fresh observation
            var rp = net.minecraft.commands.Commands.literal("replan");
            for (String[] mode : new String[][]{{"plan", "0", "10"}, {"step", "1", "10"}, {"fast", "2", "10"}, {"faster", "2", "5"}})
                rp = rp.then(net.minecraft.commands.Commands.literal(mode[0]).executes(c -> {
                    PlanBrain.REPLAN_MODE = Integer.parseInt(mode[1]);
                    PlanBrain.REPLAN_EVERY = Integer.parseInt(mode[2]);
                    c.getSource().sendSuccess(() -> Component.literal("Le Dog replanning: " + mode[0]), false);
                    return 1;
                }));
            root = root.then(rp);
            root = root.then(net.minecraft.commands.Commands.literal("debug").executes(c -> {
                debugLabels = !debugLabels;
                if (!debugLabels)
                    for (var e : playBrains.entrySet())
                        if (e.getValue() instanceof PlanBrain b) {
                            e.getKey().setCustomName(null);
                            e.getKey().setCustomNameVisible(false);
                            b.clearDebugLabel();
                        }
                boolean on = debugLabels;
                c.getSource().sendSuccess(() -> Component.literal("Le Dog decision labels " + (on ? "ON" : "OFF")), false);
                return 1;
            }));
            root = root.then(net.minecraft.commands.Commands.literal("log")
                    .then(net.minecraft.commands.Commands.literal("on").executes(c -> logCommand(c.getSource(), true)))
                    .then(net.minecraft.commands.Commands.literal("off").executes(c -> logCommand(c.getSource(), false)))
                    .executes(c -> logCommand(c.getSource(), null)));
            d.register(root);
        });
        // The control port (and per-tick telemetry) are research tools: only on when ./ledog_port.txt exists (our
        // training / bench stacks write it). Public players never get a local command port.
        if (researchMode()) {
            Thread t = new Thread(this::serve, "ledog-control");
            t.setDaemon(true);
            t.start();
            LOG.info("Le Dog control port starting on 127.0.0.1:" + controlPort());
        }
    }

    /** Control port: 25570, or the number in ./ledog_port.txt (the run dir), so a second instance (the eval bench) can
     *  run beside the training stack. */
    static boolean researchMode() {
        return java.nio.file.Files.exists(java.nio.file.Path.of("ledog_port.txt"));
    }

    static int controlPort() {
        try {
            java.nio.file.Path f = java.nio.file.Path.of("ledog_port.txt");
            if (java.nio.file.Files.exists(f)) return Integer.parseInt(java.nio.file.Files.readString(f).trim());
        } catch (Exception e) {
            LOG.warn("bad ledog_port.txt", e);
        }
        return 25570;
    }

    // ---------------- control socket (background thread; work is handed to the server thread) ----------------
    private void serve() {
        try (ServerSocket ss = new ServerSocket(controlPort(), 4, InetAddress.getLoopbackAddress())) {
            while (true) {
                Socket s = ss.accept();
                Thread h = new Thread(() -> handle(s), "ledog-conn");
                h.setDaemon(true);
                h.start();
            }
        } catch (Exception e) {
            LOG.error("control socket died", e);
        }
    }

    private void handle(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject msg = GSON.fromJson(line, JsonObject.class);
                CompletableFuture<JsonObject> fut = new CompletableFuture<>();
                queue.add(new Cmd(msg, fut));
                out.println(GSON.toJson(fut.get()));
            }
        } catch (Exception e) {
            LOG.warn("control connection closed: {}", e.toString());
        }
    }

    // ---------------- server thread ----------------
    private void onTick(MinecraftServer server) {
        tick++;
        Cmd c;
        while ((c = queue.poll()) != null) {
            JsonObject r;
            try {
                r = execute(server, c.msg());
            } catch (Exception e) {
                r = new JsonObject();
                r.addProperty("error", e.toString());
            }
            c.reply().complete(r);
        }
        if (pendingSpawn != null && tick >= spawnAtTick) {
            try {
                doSpawn(server.overworld(), pendingSpawn, pendingVanilla);
            } catch (Exception e) {
                LOG.error("spawn failed", e);
            }
            pendingSpawn = null;
        }
        if (tick % 2 == 0) {  // normal-play brains (10 Hz)
            playBrains.entrySet().removeIf(e -> !e.getKey().isAlive());
            for (var e : playBrains.entrySet()) e.getValue().step((ServerLevel) e.getKey().level(), e.getKey());
            if (debugLabels)  // live student decisions above each wolf (/ledog debug)
                for (var e : playBrains.entrySet())
                    if (e.getValue() instanceof TrickBrain tb && tb.cmd != -1) {
                        String what = tb.cmd >= 0 ? TrickBrain.CMDS[tb.cmd] : "'" + tb.word + "'? trying " + RlBrainNames.NAMES[tb.rl.action];
                        e.getKey().setCustomName(Component.literal("[trick] " + what + "... (treats "
                                + tb.treats + ")").withStyle(net.minecraft.ChatFormatting.GOLD));
                        e.getKey().setCustomNameVisible(true);
                        tb.base.clearDebugLabel();
                    } else if ((e.getValue() instanceof PlanBrain || e.getValue() instanceof TrickBrain)) {
                        PlanBrain b = e.getValue() instanceof TrickBrain tb2 ? tb2.base : (PlanBrain) e.getValue();
                        Component lab = b.debugLabel();
                        if (lab != null) {
                            e.getKey().setCustomName(lab);
                            e.getKey().setCustomNameVisible(true);
                        }
                    }
        }
        for (var e : playBrains.entrySet())  // per-tick telemetry (20 Hz)
            LedogTelemetry.tick(((ServerLevel) e.getKey().level()).getGameTime(), e.getKey(), e.getValue());
        arenas.tick(server, tick);
        bench.tick(server, tick);  // multi-wolf arenas (contract v1 executor)
        film.tick(server, tick);   // scripted film props
        if (wolf == null || !wolf.isAlive()) return;
        if (brain != null && tick % 2 == 0) brain.step(server.overworld(), wolf);
        if (brain != null) LedogTelemetry.tick(server.overworld().getGameTime(), wolf, brain);
        if (target != null) wolf.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
        if (!spawnedVanilla && wolf.isInWater() && tick % 10 == 0) {  // water-exit reflex: nearest dry ground
            BlockPos dry = dryGround(server.overworld(), wolf.blockPosition());
            wolf.getNavigation().moveTo(dry.getX() + 0.5, dry.getY(), dry.getZ() + 0.5, 1.0);
        }
        if (lookYaw != null) {
            double y = Math.toRadians(lookYaw), p = Math.toRadians(lookPitch == null ? 0 : lookPitch);
            Vec3 dir = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
            wolf.getLookControl().setLookAt(wolf.getEyePosition().add(dir.scale(4.0)));
        }
        if (research) for (ServerPlayer pl : server.getPlayerList().getPlayers()) {  // keep chunks + tracking around the wolf
            double dx = pl.getX() - wolf.getX(), dz = pl.getZ() - wolf.getZ();
            if (dx * dx + dz * dz > 4.0 || Math.abs(pl.getY() - (wolf.getY() + PLAYER_ABOVE)) > 4.0)
                pl.teleportTo(server.overworld(), wolf.getX(), wolf.getY() + PLAYER_ABOVE, wolf.getZ(), java.util.Set.of(),
                        pl.getYRot(), pl.getXRot(), true);
        }
    }

    final Arenas arenas = new Arenas();

    private JsonObject execute(MinecraftServer server, JsonObject m) throws Exception {
        String op = m.get("op").getAsString();
        if (op.startsWith("arena_")) return arenas.handle(server, op, m, tick);
        if (op.startsWith("bench_")) return bench.handle(server, op, m, tick);
        if (op.startsWith("film_")) return film.handle(server, op, m);
        JsonObject r = new JsonObject();
        switch (op) {
            case "server_cmd" -> {  // run a command as the server console (permission 4): camera tp, gamemode, time
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), m.get("cmd").getAsString());
                r.addProperty("ok", true);
            }
            case "head_params" -> {  // live head-kinematics parameters (PlanBrain), for tuning on training seeds
                if (m.has("rate")) PlanBrain.HEAD_RATE = m.get("rate").getAsDouble();
                if (m.has("gain")) PlanBrain.HEAD_GAIN = m.get("gain").getAsDouble();
                if (m.has("deadband")) PlanBrain.HEAD_DEADBAND = m.get("deadband").getAsDouble();
                if (m.has("sigma")) PlanBrain.HEAD_SIGMA = m.get("sigma").getAsDouble();
                if (m.has("tau")) PlanBrain.HEAD_TAU = m.get("tau").getAsDouble();
                if (m.has("turn")) PlanBrain.TURN_MAX = m.get("turn").getAsDouble();
                if (m.has("slide")) PlanBrain.SLIDE_MAX = m.get("slide").getAsInt();
                r.addProperty("slide", PlanBrain.SLIDE_MAX);
                if (m.has("replan_mode")) PlanBrain.REPLAN_MODE = m.get("replan_mode").getAsInt();
                if (m.has("replan_every")) PlanBrain.REPLAN_EVERY = m.get("replan_every").getAsInt();
                r.addProperty("replan_mode", PlanBrain.REPLAN_MODE);
                r.addProperty("replan_every", PlanBrain.REPLAN_EVERY);
                r.addProperty("turn", PlanBrain.TURN_MAX);
                r.addProperty("rate", PlanBrain.HEAD_RATE);
                r.addProperty("gain", PlanBrain.HEAD_GAIN);
                r.addProperty("deadband", PlanBrain.HEAD_DEADBAND);
                r.addProperty("sigma", PlanBrain.HEAD_SIGMA);
                r.addProperty("tau", PlanBrain.HEAD_TAU);
            }
            case "players" -> {
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (ServerPlayer pl : server.getPlayerList().getPlayers()) a.add(pl.getGameProfile().name());
                r.add("players", a);
            }
            case "player_cmd" -> {  // test hook: run a chat command as the first player (e.g. "ledog student bold")
                ServerPlayer pl = server.getPlayerList().getPlayers().get(0);
                server.getCommands().performPrefixedCommand(pl.createCommandSourceStack(), m.get("cmd").getAsString());
                r.addProperty("ok", true);
            }
            case "play_wolves" -> {  // test hook: normal-play wolves with their name tags (decision labels)
                com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                for (var e : playBrains.entrySet()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", e.getKey().getId());
                    o.addProperty("kind", e.getValue().kind());
                    o.addProperty("name", e.getKey().getCustomName() == null ? "" : e.getKey().getCustomName().getString());
                    a.add(o);
                }
                r.add("wolves", a);
            }
            case "spawn" -> {
                ServerLevel level = server.overworld();
                ServerPlayer pl = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
                BlockPos at = pl != null ? pl.blockPosition() : level.getRespawnData().pos();
                if (m.has("x") && m.has("z")) at = new BlockPos(m.get("x").getAsInt(), at.getY(), m.get("z").getAsInt());
                if (m.has("biome") && !m.get("biome").isJsonNull()) {
                    var key = switch (m.get("biome").getAsString()) {
                        case "savanna" -> Biomes.SAVANNA;
                        case "meadow" -> Biomes.MEADOW;
                        default -> Biomes.PLAINS;
                    };
                    var found = level.findClosestBiome3d(h -> h.is(key), at, 6400, 32, 64);
                    if (found == null) throw new IllegalStateException("biome not found: " + key);
                    at = found.getFirst();
                }
                level.getChunk(at.getX() >> 4, at.getZ() >> 4);  // load before reading the heightmap
                at = dryGround(level, at);  // never spawn on water/lava (the heightmap counts fluid as ground)
                int gy = at.getY();
                if (pl != null) {
                    // Spectators do not make chunks entity-ticking (the wolf froze); use an invisible flying creative player.
                    pl.setGameMode(GameType.CREATIVE);
                    pl.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            net.minecraft.world.effect.MobEffects.INVISIBILITY, 100000000, 0, false, false));
                    pl.getAbilities().flying = true;
                    pl.onUpdateAbilities();
                    pl.teleportTo(level, at.getX() + 0.5, gy + PLAYER_ABOVE, at.getZ() + 0.5, java.util.Set.of(), 0f, 0f, true);
                }
                if (wolf != null) wolf.discard();
                wolf = null;
                target = null;
                lookYaw = null;
                lookPitch = null;
                pendingSpawn = at;
                pendingVanilla = m.has("vanilla") && m.get("vanilla").getAsBoolean();
                pendingBrain = m.has("brain") && !m.get("brain").isJsonNull() ? m.get("brain").getAsString() : null;
                pendingSeed = m.has("seed") ? m.get("seed").getAsLong() : 0L;
                research = true;
                brain = null;
                spawnAtTick = tick + 40;  // ~2 s for chunks around the player to become entity-ticking
                r.addProperty("pending", true);
                r.addProperty("spawnAtTick", spawnAtTick);
            }
            case "goto" -> {
                target = new Vec3(m.get("x").getAsDouble(), m.get("y").getAsDouble(), m.get("z").getAsDouble());
                speed = m.has("speed") ? m.get("speed").getAsDouble() : 1.0;
            }
            case "stop" -> target = null;
            case "drive" -> {  // one call per control step: optional move target (null = stop) + optional look
                boolean nav = m.has("nav") && m.get("nav").getAsBoolean();
                if (m.has("x") && !m.get("x").isJsonNull()) {
                    double tx = m.get("x").getAsDouble(), tz = m.get("z").getAsDouble();
                    speed = m.has("speed") ? m.get("speed").getAsDouble() : 1.0;
                    if (nav && wolf != null) {
                        // locomotion layer: vanilla navigation to a short target (handles steps/jumps); we choose where.
                        // Safety: the target column (and the midpoint) must be dry and within a 1-block step, else refuse
                        // (reply blocked=true so the controller replans). In water the water-exit reflex drives.
                        ServerLevel lv = server.overworld();
                        int gy = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(tx), (int) Math.floor(tz));
                        double mx = (wolf.getX() + tx) / 2, mz = (wolf.getZ() + tz) / 2;
                        boolean safe = dryStep(lv, mx, mz, (int) Math.floor(wolf.getY())) && dryStep(lv, tx, tz, (int) Math.floor(wolf.getY()));
                        target = null;
                        if (wolf.isInWater()) {
                            r.addProperty("reflex", "water");
                        } else if (safe) {
                            wolf.getNavigation().moveTo(tx, gy, tz, speed);
                        } else {
                            wolf.getNavigation().stop();
                            r.addProperty("blocked", true);
                        }
                    } else {
                        target = new Vec3(tx, m.get("y").getAsDouble(), tz);
                    }
                } else {
                    target = null;
                    if (wolf != null) wolf.getNavigation().stop();
                }
                if (m.has("yaw")) {
                    lookYaw = m.get("yaw").getAsFloat();
                    lookPitch = m.has("pitch") ? m.get("pitch").getAsFloat() : 0f;
                }
            }
            case "walkable" -> {  // "paths": [[[x,z],...], ...] -> "ok": [prefix length per path]
                com.google.gson.JsonArray res = new com.google.gson.JsonArray();
                for (var p : m.getAsJsonArray("paths")) res.add(walkable(server.overworld(), p.getAsJsonArray()));
                r.add("ok", res);
            }
            case "look" -> {
                lookYaw = m.get("yaw").getAsFloat();
                lookPitch = m.has("pitch") ? m.get("pitch").getAsFloat() : 0f;
            }
            case "state" -> { }
            case "shape_infer" -> {  // parity: Java past-motion input + ONNX shape for a given 10 Hz history
                var ha = m.getAsJsonArray("hist");
                java.util.List<double[]> h = new java.util.ArrayList<>();
                for (var e : ha) {
                    var q = e.getAsJsonArray();
                    h.add(new double[]{q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble(), q.get(3).getAsDouble()});
                }
                String model = m.has("model") ? m.get("model").getAsString() : "shape_pm";
                float[] pm = PastMotion.compute(h, model.equals("vision_pm") ? 15 : 45);
                com.google.gson.JsonArray pa = new com.google.gson.JsonArray();
                for (float v : pm) pa.add(v);
                r.add("motion", pa);
                if (!model.equals("vision_pm")) {
                    float[] in = pm;
                    if (m.has("goal")) {
                        in = java.util.Arrays.copyOf(pm, pm.length + 2);
                        in[pm.length] = m.getAsJsonArray("goal").get(0).getAsFloat();
                        in[pm.length + 1] = m.getAsJsonArray("goal").get(1).getAsFloat();
                    }
                    float[][] sh = LedogModels.shape(model, in);
                    com.google.gson.JsonArray sa = new com.google.gson.JsonArray();
                    for (float[] w : sh) { sa.add(w[0]); sa.add(w[1]); }
                    r.add("shape", sa);
                }
            }
            case "vision_shape" -> {  // a client-side vision shape (40x2 ego, unit path length) for wolf `id`
                int id = m.get("id").getAsInt();
                var sa = m.getAsJsonArray("shape");
                float[][] sh = new float[40][2];
                for (int i = 0; i < 40; i++) { sh[i][0] = sa.get(2 * i).getAsFloat(); sh[i][1] = sa.get(2 * i + 1).getAsFloat(); }
                boolean ok = false;
                if (wolf != null && wolf.getId() == id && brain instanceof ShapeBrain b) { b.setExternalShape(sh, server.overworld().getGameTime()); ok = true; }
                for (var e : playBrains.entrySet())
                    if (e.getKey().getId() == id && e.getValue() instanceof ShapeBrain b) {
                        b.setExternalShape(sh, ((ServerLevel) e.getKey().level()).getGameTime());
                        ok = true;
                    }
                r.addProperty("accepted", ok);
            }
            case "student_infer" -> {  // parity/latency: in-mod student on a given observation + temperament
                if (studentInfer == null) studentInfer = new StudentSource();
                String code = studentInfer.infer(m.getAsJsonObject("obs"), m.getAsJsonObject("temper"));
                r.addProperty("code", code);
                r.addProperty("ms", studentInfer.lastMs);
            }
            case "bm_infer" -> {  // parity test: raw network output for a given 44-d input
                var xa = m.getAsJsonArray("x");
                double[] x = new double[xa.size()];
                for (int i = 0; i < x.length; i++) x[i] = xa.get(i).getAsDouble();
                double[] y = weights().forward(x);
                com.google.gson.JsonArray ya = new com.google.gson.JsonArray();
                for (double v : y) ya.add(v);
                r.add("y", ya);
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        }
        if (wolf != null) {
            r.addProperty("x", wolf.getX());
            r.addProperty("y", wolf.getY());
            r.addProperty("z", wolf.getZ());
            r.addProperty("bodyYaw", wolf.yBodyRot);
            r.addProperty("headYaw", wolf.getYHeadRot());
            r.addProperty("pitch", wolf.getXRot());
            r.addProperty("onGround", wolf.onGround());
            r.addProperty("alive", wolf.isAlive());
            r.addProperty("hasWanted", wolf.getMoveControl().hasWanted());  // debug (M1 movement)
            r.addProperty("zza", wolf.zza);
            r.addProperty("speed", wolf.getSpeed());
            r.addProperty("noAi", wolf.isNoAi());
            r.addProperty("effectiveAi", wolf.isEffectiveAi());
            r.addProperty("hurt", wolf.hurtTime > 0);
            r.addProperty("inWater", wolf.isInWater());
            r.addProperty("hCollide", wolf.horizontalCollision);
            r.addProperty("navDone", wolf.getNavigation().isDone());
            r.addProperty("brain", brain != null);
            if (brain instanceof BmBrain b) {
                r.addProperty("vetofail", b.vetofail);
                r.addProperty("blocked", b.blocked);
                r.addProperty("cmd_spd", b.lastCmdSpd);
                r.addProperty("bm_steps", b.nSteps);
                r.addProperty("bm_replans", b.nReplans);
                r.addProperty("bm_stop_steps", b.nStopSteps);
                r.addProperty("bm_blocked", b.nBlocked);
                r.addProperty("bm_water", b.nWater);
                r.addProperty("bm_mean_cmd_spd", b.nSteps > 0 ? b.sumCmdSpd / b.nSteps : 0);
            } else if (brain instanceof ShapeBrain b) {
                r.addProperty("blocked", b.blocked);
                r.addProperty("sb_kind", b.kind());
                r.addProperty("sb_steps", b.nSteps);
                r.addProperty("sb_chunks", b.nChunks);
                r.addProperty("sb_pauses", b.nPauses);
                r.addProperty("sb_veto_turns", b.nVetoTurns);
                r.addProperty("sb_ext_used", b.nExtUsed);
                r.addProperty("sb_errors", b.nErrors);
                r.addProperty("sb_last_error", b.lastError);
            }
        }
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            ServerPlayer pl = server.getPlayerList().getPlayers().get(0);
            r.addProperty("playerX", pl.getX());
            r.addProperty("playerZ", pl.getZ());
        }
        r.addProperty("spawnedId", spawnedId);
        r.addProperty("ready", wolf != null && wolf.isAlive() && pendingSpawn == null);
        r.addProperty("tick", tick);
        return r;
    }

    /**
     * Terrain feasibility of a path [[x,z],...] starting at the wolf: for each point, the ground height (heightmap,
     * leaves ignored) may change by at most 1 block from the previous point (a wolf can step/jump 1), and the
     * ground block must not be water/lava. Returns the number of feasible points from the start (prefix length).
     */
    private int walkable(ServerLevel level, com.google.gson.JsonArray pts) {
        if (wolf == null) return 0;
        int prevY = (int) Math.floor(wolf.getY());
        for (int i = 0; i < pts.size(); i++) {
            var a = pts.get(i).getAsJsonArray();
            int bx = (int) Math.floor(a.get(0).getAsDouble()), bz = (int) Math.floor(a.get(1).getAsDouble());
            if (!level.hasChunk(bx >> 4, bz >> 4)) return i;
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            if (Math.abs(gy - prevY) > 1) return i;
            var below = level.getBlockState(new BlockPos(bx, gy - 1, bz));
            if (!below.getFluidState().isEmpty() && !wolf.isInWater()) return i;  // in water: any way out is allowed
            prevY = gy;
        }
        return pts.size();
    }

    public static boolean dryStep(ServerLevel lv, double x, double z, int fromY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int gy = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        return Math.abs(gy - fromY) <= 1 && lv.getBlockState(new BlockPos(bx, gy - 1, bz)).getFluidState().isEmpty();
    }

    /** Nearest column (square spiral, <= 48 blocks) whose top block is not a fluid; returns the position on its ground. */
    public static BlockPos dryGround(ServerLevel level, BlockPos at) {
        for (int r = 0; r <= 48; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = at.getX() + dx, z = at.getZ() + dz;
                    level.getChunk(x >> 4, z >> 4);
                    int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (level.getBlockState(new BlockPos(x, gy - 1, z)).getFluidState().isEmpty())
                        return new BlockPos(x, gy, z);
                }
            }
        }
        return at;
    }

    /** Same rule as the `walkable` op, for in-mod brains: feasible prefix length of the first n points of a path. */
    public static int walkablePrefix(ServerLevel level, Wolf w, double[][] pts, int n) {
        int prevY = (int) Math.floor(w.getY());
        for (int i = 0; i < Math.min(n, pts.length); i++) {
            int bx = (int) Math.floor(pts[i][0]), bz = (int) Math.floor(pts[i][1]);
            if (!level.hasChunk(bx >> 4, bz >> 4)) return i;
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            if (Math.abs(gy - prevY) > 1) return i;
            if (!level.getBlockState(new BlockPos(bx, gy - 1, bz)).getFluidState().isEmpty() && !w.isInWater()) return i;
            prevY = gy;
        }
        return Math.min(n, pts.length);
    }

    // ---------------- in-mod behaviour model (Gate D deployability) ----------------
    private StudentSource studentInfer;
    private BmBrain bmWeights;  // loaded lazily from $LEDOG_BM or ~/.ledog/bm.bin
    private WolfBrain brain;    // the research wolf's brain (spawn op with "brain": bm|shape|follow|vision)
    private boolean debugLabels = false;
    final Bench bench = new Bench(this);
    final Film film = new Film(this);  // filming ops (media/film.py)
    final java.util.Map<Wolf, WolfBrain> playBrains = new java.util.HashMap<>();  // /ledog wolves (normal play)
    private boolean research;   // true once the control port spawned a wolf: the player-follow teleport applies

    private BmBrain weights() throws java.io.IOException {
        if (bmWeights == null) bmWeights = BmBrain.load();
        return bmWeights;
    }

    /** Spawn a behaviour-model wolf next to a player (normal play; no teleports, no camera changes). */
    private int logCommand(net.minecraft.commands.CommandSourceStack src, Boolean on) {
        if (on != null) LedogTelemetry.setEnabled(on);
        src.sendSuccess(() -> Component.literal("Le Dog telemetry " + (LedogTelemetry.enabled() ? "ON -> " + LedogTelemetry.file() : "OFF")), false);
        return 1;
    }

    /** Temperament presets for /ledog student <preset>: curious, bold, energetic, social, playful (0..1). */
    static final java.util.Map<String, double[]> PRESETS = new java.util.LinkedHashMap<>();
    static {
        PRESETS.put("bold", new double[]{0.6, 0.9, 0.7, 0.5, 0.5});
        PRESETS.put("shy", new double[]{0.4, 0.1, 0.4, 0.3, 0.3});
        PRESETS.put("lazy", new double[]{0.3, 0.5, 0.1, 0.5, 0.2});
        PRESETS.put("playful", new double[]{0.6, 0.6, 0.8, 0.7, 0.9});
        PRESETS.put("curious", new double[]{0.9, 0.6, 0.6, 0.5, 0.5});
        PRESETS.put("social", new double[]{0.5, 0.6, 0.6, 0.9, 0.6});
    }

    private int playTrick(net.minecraft.commands.CommandSourceStack src, String preset) {
        try {
            ServerPlayer pl = src.getPlayerOrException();
            int id = spawnPlayWolf(pl, "trick");
            for (var e : playBrains.entrySet())
                if (e.getKey().getId() == id && e.getValue() instanceof TrickBrain tb) {
                    if (preset != null) System.arraycopy(PRESETS.get(preset), 0, tb.base.st.temper, 0, 5);
                    tb.base.presetName = "trick " + (preset == null ? "dog" : preset);
                }
            src.sendSuccess(() -> Component.literal("Le Dog (trick dog) spawned, id " + id
                    + ": say sit / come / jump / stay in chat"), false);
            if (!LedogShare.cfg().has("asked")) {
                var o = LedogShare.cfg();
                o.addProperty("asked", true);
                LedogShare.save(o);
                src.sendSuccess(() -> Component.literal("Le Dog: help train better dogs for everyone? /ledog share on shares your"
                        + " dog-training feedback anonymously (no names, no chat). It is off unless you turn it on.")
                        .withStyle(net.minecraft.ChatFormatting.AQUA), false);
            }
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Le Dog: " + e));
            return 0;
        }
    }

    /** Student versions selectable in-game; each is ~/.ledog/student_<version>.onnx. */
    static final String[] VERSIONS = {"v1", "v2a", "v2", "v3", "v4"};

    private int playStudent(net.minecraft.commands.CommandSourceStack src, String preset, String version) {
        try {
            ServerPlayer pl = src.getPlayerOrException();
            String model = version == null ? "student.onnx" : "student_" + version + ".onnx";
            if (!java.nio.file.Files.exists(LedogModels.dir().resolve(model)))
                throw new IllegalStateException("no " + LedogModels.dir().resolve(model));
            int id = spawnPlayWolf(pl, "student");
            for (var e : playBrains.entrySet())
                if (e.getKey().getId() == id && e.getValue() instanceof PlanBrain b)
                {
                    System.arraycopy(PRESETS.get(preset), 0, b.st.temper, 0, 5);
                    b.presetName = version == null ? preset : preset + " " + version;
                    if (version != null) b.source = new StudentSource(new java.util.Random(id * 7919L), 1.0, model);
                }
            String tag = version == null ? "" : " " + version;
            src.sendSuccess(() -> Component.literal("Le Dog (student" + tag + ", " + preset + ") spawned, id " + id), false);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Le Dog: " + e));
            return 0;
        }
    }

    private int playCommand(net.minecraft.commands.CommandSourceStack src, String kind) {
        try {
            ServerPlayer pl = src.getPlayerOrException();
            int id = spawnPlayWolf(pl, kind);
            src.sendSuccess(() -> Component.literal("Le Dog (" + kind + ") wolf spawned, id " + id
                    + (kind.equals("vision") ? " - run /ledogcam to see through its eyes (vision runs on your client)" : "")
                    + (LedogTelemetry.enabled() ? "\nper-tick log: " + LedogTelemetry.file() : "")), false);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Le Dog: " + e));
            return 0;
        }
    }

    /** bm = behaviour model (pure Java); shape / follow / vision = DINO-transformer shape policies + deterministic chunker. */
    /** A player said something: trick dogs nearby get the command if it is one they know. */
    static final java.util.Set<String> PRAISE = java.util.Set.of("good", "gooddog", "goodboy", "goodgirl", "yes", "yay", "nice", "goodjob");
    static final java.util.Set<String> SCOLD = java.util.Set.of("no", "bad", "nope", "baddog", "badboy", "badgirl");

    void hear(ServerPlayer p, String text) {
        String raw = text.trim().toLowerCase(java.util.Locale.ROOT);
        String c = raw.replaceAll("[^a-z]", "");
        if (c.isEmpty()) return;
        p.level().getServer().execute(() -> {
            if (PRAISE.contains(c) || SCOLD.contains(c)) {  // praise / no: the nearest trick dog within 16 blocks
                var t = nearestTrick(p, 16);
                if (t != null) reward(p, t.getKey(), t.getValue(), PRAISE.contains(c) ? 0.4 : -0.5, c);
                return;
            }
            boolean known = java.util.Arrays.asList(TrickBrain.CMDS).contains(c);
            if (!known && (raw.contains(" ") || c.length() > 12)) return;  // sentences are not commands
            for (var e : playBrains.entrySet())
                if (e.getValue() instanceof TrickBrain tb && e.getKey().level() == p.level() && e.getKey().distanceTo(p) < 32)
                    tb.command(c, p, e.getKey());
        });
    }

    WolfBrain makeBrain(String kind, long seed) throws Exception {
        switch (kind) {
            case "trick": {  // student dog + PPO trick policy on chat commands (rl_tricks.onnx)
                if (!LedogModels.available("rl_tricks"))
                    throw new IllegalStateException("no trick brain yet: run /ledog update");
                PlanBrain b = (PlanBrain) makeBrain("student", seed);
                return new TrickBrain(b);
            }
            case "bm":
                return new BmBrain(weights(), seed);
            case "student": {  // the teacher-distilled student + real-dog gaze, sampled plans (Gate E brain)
                if (!LedogModels.available("student"))
                    throw new IllegalStateException("no dog brain yet: run /ledog update");
                PlanBrain b = new PlanBrain(0, seed);
                b.source = new StudentSource(new java.util.Random(seed), 1.0);
                if (LedogModels.available("gaze")) b.gaze = new OnnxGaze();
                return b;
            }
            case "shape":
            case "follow":
            case "vision": {
                String need = kind.equals("follow") ? "shape_goal" : "shape_pm";
                if (!LedogModels.available(need))
                    throw new IllegalStateException("missing " + LedogModels.dir().resolve(need + ".onnx")
                            + " (python -m ledog.export_onnx)");
                return new ShapeBrain(ShapeBrain.Kind.valueOf(kind.toUpperCase()));
            }
            default:
                throw new IllegalArgumentException("unknown brain " + kind);
        }
    }

    public int spawnPlayWolf(ServerPlayer pl, String kind) throws Exception {
        ServerLevel level = (ServerLevel) pl.level();
        BlockPos at = dryGround(level, pl.blockPosition().offset(2, 0, 2));
        Wolf w = EntityTypes.WOLF.spawn(level, at, EntitySpawnReason.COMMAND);
        if (w == null) throw new IllegalStateException("wolf spawn failed");
        clearGoals(w);
        Field mc = Mob.class.getDeclaredField("moveControl");
        mc.setAccessible(true);
        mc.set(w, new LimitedMoveControl(w, 3.785f));
        WolfBrain b = makeBrain(kind, w.getId() * 7919L + tick);
        w.setCustomName(Component.literal("Le Dog (" + kind + ")"));
        w.setPersistenceRequired();
        if (b instanceof TrickBrain tb) {
            w.addTag("ledog_trick");  // re-attached with its saved training when the world loads again
            tb.uuid = w.getUUID();
        }
        playBrains.put(w, b);
        return w.getId();
    }

    /** A saved trick dog came back with its chunk / the world: give it its brain and its training again. */
    void reattach(Wolf w) {
        if (playBrains.containsKey(w) || !w.entityTags().contains("ledog_trick")) return;
        try {
            clearGoals(w);
            Field mc = Mob.class.getDeclaredField("moveControl");
            mc.setAccessible(true);
            mc.set(w, new LimitedMoveControl(w, 3.785f));
            TrickBrain tb = (TrickBrain) makeBrain("trick", w.getId() * 7919L + tick);
            tb.uuid = w.getUUID();
            tb.load(w);
            playBrains.put(w, tb);
        } catch (Exception e) {
            LOG.error("trick dog re-attach failed", e);
        }
    }

    /** Nearest trick dog within r blocks of a player. */
    java.util.Map.Entry<Wolf, TrickBrain> nearestTrick(net.minecraft.world.entity.player.Player p, double r) {
        java.util.Map.Entry<Wolf, TrickBrain> best = null;
        double bd = r;
        for (var e : playBrains.entrySet())
            if (e.getValue() instanceof TrickBrain tb && e.getKey().level() == p.level() && e.getKey().distanceTo(p) < bd) {
                bd = e.getKey().distanceTo(p);
                best = java.util.Map.entry(e.getKey(), tb);
            }
        return best;
    }

    void reward(ServerPlayer p, Wolf w, TrickBrain tb, double r, String signal) {
        String what = tb.feedback(r, signal, w, (ServerLevel) w.level());
        p.sendSystemMessage(Component.literal(String.format("[dog] %s %+.2f -> %s", signal, r, what))
                .withStyle(r > 0 ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED));
    }

    private void doSpawn(ServerLevel level, BlockPos at, boolean vanilla) throws Exception {
        // Remove stale "ledog" wolves now that the chunks are loaded (the client would attach to them).
        for (Wolf w : level.getEntities(EntityTypes.WOLF, w -> w.getCustomName() != null
                && WOLF_NAME.equals(w.getCustomName().getString()))) {
            w.discard();
        }
        Wolf w = EntityTypes.WOLF.spawn(level, at, EntitySpawnReason.COMMAND);
        if (w == null) throw new IllegalStateException("wolf spawn failed");
        if (!vanilla) {
            clearGoals(w);
            Field mc = Mob.class.getDeclaredField("moveControl");
            mc.setAccessible(true);
            mc.set(w, new LimitedMoveControl(w, 3.785f));  // body actuator model (our controllers only)
        }
        spawnedVanilla = vanilla;
        if (pendingBrain != null && !vanilla) brain = makeBrain(pendingBrain, pendingSeed);
        w.setCustomName(Component.literal(WOLF_NAME));
        w.setCustomNameVisible(false);
        w.setPersistenceRequired();
        Field inv = net.minecraft.world.entity.Entity.class.getDeclaredField("permanentlyInvulnerable");
        inv.setAccessible(true);  // 26.x removed setInvulnerable(); same state, set directly
        inv.setBoolean(w, true);
        wolf = w;
        spawnedId = w.getId();
        LOG.info("ledog wolf spawned id={} at {} vanilla={}", spawnedId, at, vanilla);
    }

    /** Clear goal + target selectors (protected fields on Mob; names are unobfuscated in MC 26.x). */
    static void clearGoals(Mob mob) throws Exception {
        for (String name : new String[]{"goalSelector", "targetSelector"}) {
            Field f = Mob.class.getDeclaredField(name);
            f.setAccessible(true);
            ((GoalSelector) f.get(mob)).removeAllGoals(g -> true);
        }
    }
}

package ai.ledog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Community training (opt-in): players' dog-training feedback (TrickBrain's feedback.jsonl: what the dog saw and did,
 * and the reward given) can be shared anonymously with the Le Dog collector, where a reward model of what players
 * reward is learned and new dog brains are trained; new brains are published as GitHub releases and fetched with
 * /ledog update (checksums verified). Nothing is uploaded unless the player runs /ledog share on.
 * Shared per event: install id (random), mod/policy version, a hashed dog id, the reward signal and value, the dog's
 * hunger, the trick word, and the 28 observation numbers + action of the credited decisions. Never player names or chat.
 */
public final class LedogShare {
    public static final String VERSION = "1.0.2";
    /** Ed25519 public key (DER, base64) of the Le Dog release signer. Release manifests must carry a valid
     *  manifest.json.sig made with the matching private key (kept offline by the maintainer), or updates are refused. */
    static final String RELEASE_PUBKEY = "MCowBQYDK2VwAyEAD7EgWwVhHouNglkRDqMUKzLtkKa/LPFPG6fk0pYmhto=";
    public static final String RELEASES = "https://github.com/AcastaPaloma/le-dog/releases/latest/download/manifest.json";
    public static final String DEFAULT_ENDPOINT = "";  // set once the collector is deployed
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    static volatile JsonObject latest;  // manifest of the newest release, when newer than what is installed

    private LedogShare() {
    }

    static Path cfgPath() {
        return LedogModels.dir().resolve("share.json");
    }

    static synchronized JsonObject cfg() {
        try {
            if (Files.exists(cfgPath())) return LedogMod.GSON.fromJson(Files.readString(cfgPath()), JsonObject.class);
        } catch (Exception e) {
            LedogMod.LOG.warn("share.json unreadable, recreating", e);
        }
        JsonObject o = new JsonObject();
        o.addProperty("install", UUID.randomUUID().toString());
        o.addProperty("share", false);
        o.addProperty("offset", 0L);
        o.addProperty("endpoint", DEFAULT_ENDPOINT);
        o.add("models", new JsonObject());
        save(o);
        return o;
    }

    static synchronized void save(JsonObject o) {
        try {
            Files.createDirectories(LedogModels.dir());
            Files.writeString(cfgPath(), LedogMod.GSON.toJson(o));
        } catch (Exception e) {
            LedogMod.LOG.warn("share.json save failed", e);
        }
    }

    public static boolean sharing() {
        return cfg().get("share").getAsBoolean();
    }

    public static void setSharing(boolean on) {
        JsonObject o = cfg();
        o.addProperty("share", on);
        save(o);
    }

    static String endpoint() {
        JsonObject o = cfg();
        String e = o.has("endpoint") && !o.get("endpoint").getAsString().isEmpty() ? o.get("endpoint").getAsString() : DEFAULT_ENDPOINT;
        return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
    }

    static String policyVersion() {
        JsonObject m = cfg().getAsJsonObject("models");
        return m != null && m.has("student.onnx") ? m.get("student.onnx").getAsString() : "local";
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("ledog:" + s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) {
            return "x";
        }
    }

    /** Background loop: upload new feedback events every 60 s while sharing is on. */
    public static void startUploader() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                    if (sharing() && !endpoint().isEmpty()) uploadOnce();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    LedogMod.LOG.debug("Le Dog upload skipped: " + e);
                }
            }
        }, "ledog-share");
        t.setDaemon(true);
        t.start();
    }

    /** Upload up to 200 not-yet-shared feedback events; returns how many were accepted. */
    static synchronized int uploadOnce() throws Exception {
        Path f = LedogModels.dir().resolve("feedback.jsonl");
        if (!Files.exists(f)) return 0;
        JsonObject c = cfg();
        long off = c.get("offset").getAsLong();
        byte[] all = Files.readAllBytes(f);
        if (off > all.length) off = 0;  // file was reset
        String rest = new String(all, (int) off, all.length - (int) off, StandardCharsets.UTF_8);
        int complete = rest.lastIndexOf('\n');  // only whole lines (the game may be appending one right now)
        if (complete < 0) return 0;
        String[] lines = rest.substring(0, complete).split("\n", -1);
        JsonArray ev = new JsonArray();
        long used = 0;
        for (String ln : lines) {
            if (ev.size() >= 200) break;
            used += ln.getBytes(StandardCharsets.UTF_8).length + 1;
            if (ln.isBlank()) continue;
            JsonObject o = LedogMod.GSON.fromJson(ln, JsonObject.class);
            if (o.has("dog")) o.addProperty("dog", hash(o.get("dog").getAsString()));
            ev.add(o);
        }
        if (ev.isEmpty()) return 0;
        JsonObject body = new JsonObject();
        body.addProperty("install", c.get("install").getAsString());
        body.addProperty("mod", VERSION);
        body.addProperty("policy", policyVersion());
        body.add("events", ev);
        HttpRequest rq = HttpRequest.newBuilder(URI.create(endpoint() + "/v1/events")).timeout(Duration.ofSeconds(20))
                .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("collector " + r.statusCode());
        c.addProperty("offset", off + used);
        save(c);
        return ev.size();
    }

    /** Fetch the newest release manifest; remembers it when it is newer than the installed models. */
    public static JsonObject checkForUpdate() throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(RELEASES)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IllegalStateException("release manifest " + r.statusCode());
        HttpRequest rs = HttpRequest.newBuilder(URI.create(RELEASES + ".sig")).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> sg = HTTP.send(rs, HttpResponse.BodyHandlers.ofByteArray());
        if (sg.statusCode() != 200 || !verify(r.body(), sg.body()))
            throw new IllegalStateException("release manifest signature missing or invalid; update refused");
        JsonObject m = LedogMod.GSON.fromJson(new String(r.body(), StandardCharsets.UTF_8), JsonObject.class);
        if (m.has("collector")) {  // the collector address is published with each release (no jar rebuild needed)
            JsonObject c = cfg();
            boolean auto = !c.has("endpoint") || c.get("endpoint").getAsString().isEmpty()
                    || (c.has("endpoint_auto") && c.get("endpoint_auto").getAsBoolean());
            if (auto) {
                c.addProperty("endpoint", m.get("collector").getAsString());
                c.addProperty("endpoint_auto", true);
                save(c);
            }
        }
        JsonObject have = cfg().getAsJsonObject("models");
        boolean newer = false;
        for (JsonElement e : m.getAsJsonArray("models")) {
            JsonObject mm = e.getAsJsonObject();
            String file = mm.get("file").getAsString(), ver = mm.get("version").getAsString();
            if (have == null || !have.has(file) || !have.get(file).getAsString().equals(ver)
                    || !Files.exists(LedogModels.dir().resolve(file))) newer = true;
        }
        latest = newer ? m : null;
        return m;
    }

    /** Ed25519 signature check of the release manifest against RELEASE_PUBKEY. */
    static boolean verify(byte[] data, byte[] sig) {
        try {
            var kf = java.security.KeyFactory.getInstance("Ed25519");
            var pk = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(RELEASE_PUBKEY)));
            var v = java.security.Signature.getInstance("Ed25519");
            v.initVerify(pk);
            v.update(data);
            return v.verify(sig);
        } catch (Exception e) {
            LedogMod.LOG.warn("manifest signature check failed", e);
            return false;
        }
    }

    /** Download every model of the newest release whose version differs; sha256-verified, atomic replace. */
    public static String update() throws Exception {
        JsonObject m = checkForUpdate();
        JsonObject c = cfg();
        JsonObject have = c.has("models") ? c.getAsJsonObject("models") : new JsonObject();
        StringBuilder done = new StringBuilder();
        for (JsonElement e : m.getAsJsonArray("models")) {
            JsonObject mm = e.getAsJsonObject();
            String file = mm.get("file").getAsString(), ver = mm.get("version").getAsString();
            if (!file.matches("[a-z0-9_]+\\.onnx")) continue;  // only plain model files into ~/.ledog
            Path dst = LedogModels.dir().resolve(file);
            if (have.has(file) && have.get(file).getAsString().equals(ver) && Files.exists(dst)) continue;
            HttpRequest rq = HttpRequest.newBuilder(URI.create(mm.get("url").getAsString())).timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<byte[]> r = HTTP.send(rq, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IllegalStateException(file + ": HTTP " + r.statusCode());
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.body()));
            if (!sha.equalsIgnoreCase(mm.get("sha256").getAsString())) throw new IllegalStateException(file + ": checksum mismatch");
            Files.createDirectories(LedogModels.dir());
            Path tmp = dst.resolveSibling(file + ".download");
            Files.write(tmp, r.body());
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            have.addProperty(file, ver);
            done.append(file).append(' ').append(ver).append("; ");
        }
        c.add("models", have);
        save(c);
        latest = null;
        return done.length() == 0 ? "already up to date (" + m.get("policy").getAsString() + ")"
                : "installed " + done + "(restart the world to load new brains)";
    }
}

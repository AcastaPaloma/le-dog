// Le Dog community-training collector (Cloudflare Worker + D1). Free tier: 100k requests/day.
//   POST /v1/events   {install, mod, policy, events: [...]}   (the mod's opt-in uploader)
//   GET  /v1/stats    public counts
//   GET  /v1/export?after=<id>&limit=<n>   Authorization: Bearer <EXPORT_TOKEN>   (training pipeline)
const SIGNALS = new Set(["steak", "bone", "golden carrot", "pet", "good", "gooddog", "goodboy", "goodgirl", "yes", "yay",
  "nice", "goodjob", "no", "bad", "nope", "baddog", "badboy", "badgirl"]);
const MAX_EVENTS = 200, MAX_BYTES = 512 * 1024, PER_HOUR = 3000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });
const num = (v, lo, hi) => (typeof v === "number" && isFinite(v) ? Math.min(hi, Math.max(lo, v)) : null);

function clean(e) {
  if (!e || typeof e !== "object" || !SIGNALS.has(e.signal)) return null;
  const credited = Array.isArray(e.credited) ? e.credited.slice(0, 16).map((d) => ({
    a: Number.isInteger(d.a) && d.a >= 0 && d.a < 9 ? d.a : null,
    word: typeof d.word === "string" && /^[a-z]{1,16}$/.test(d.word) ? d.word : null,
    w: num(d.w, 0, 1),
    obs: Array.isArray(d.obs) && d.obs.length === 28 ? d.obs.map((x) => num(x, -10, 10)) : null,
  })).filter((d) => d.a !== null) : [];
  return {
    t: Number.isInteger(e.t) ? e.t : null,
    dog: typeof e.dog === "string" && /^[0-9a-f]{1,32}$/.test(e.dog) ? e.dog : null,
    signal: e.signal, r: num(e.r, -3, 3), hunger: num(e.hunger, 0, 1), credited: JSON.stringify(credited),
  };
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "POST" && url.pathname === "/v1/events") {
      const len = Number(req.headers.get("content-length") || 0);
      if (len > MAX_BYTES) return json({ error: "too large" }, 413);
      let body;
      try { body = JSON.parse(await req.text()); } catch { return json({ error: "bad json" }, 400); }
      if (!body || !UUID.test(body.install || "") || !Array.isArray(body.events)) return json({ error: "bad request" }, 400);
      const evs = body.events.slice(0, MAX_EVENTS).map(clean).filter(Boolean);
      const now = Date.now();
      const { n } = await env.DB.prepare("SELECT COUNT(*) AS n FROM events WHERE install = ? AND received > ?")
        .bind(body.install, now - 3600_000).first();
      if (n + evs.length > PER_HOUR) return json({ error: "rate limited" }, 429);
      const mod = String(body.mod || "").slice(0, 16), policy = String(body.policy || "").slice(0, 16);
      const st = env.DB.prepare("INSERT INTO events (received, install, mod, policy, t, dog, signal, r, hunger, credited) VALUES (?,?,?,?,?,?,?,?,?,?)");
      if (evs.length) await env.DB.batch(evs.map((e) => st.bind(now, body.install, mod, policy, e.t, e.dog, e.signal, e.r, e.hunger, e.credited)));
      return json({ accepted: evs.length });
    }
    if (req.method === "GET" && url.pathname === "/v1/stats") {
      const tot = await env.DB.prepare("SELECT COUNT(*) AS events, COUNT(DISTINCT install) AS players FROM events").first();
      const sig = await env.DB.prepare("SELECT signal, COUNT(*) AS n FROM events GROUP BY signal ORDER BY n DESC").all();
      return json({ ...tot, signals: sig.results });
    }
    if (req.method === "GET" && url.pathname === "/v1/export") {
      if (!env.EXPORT_TOKEN || req.headers.get("authorization") !== `Bearer ${env.EXPORT_TOKEN}`) return json({ error: "unauthorized" }, 401);
      const after = Number(url.searchParams.get("after") || 0), limit = Math.min(5000, Number(url.searchParams.get("limit") || 5000));
      const rows = await env.DB.prepare("SELECT * FROM events WHERE id > ? ORDER BY id LIMIT ?").bind(after, limit).all();
      return json({ rows: rows.results });
    }
    return json({ service: "le-dog-collector", endpoints: ["POST /v1/events", "GET /v1/stats"] });
  },
};

-- Le Dog community training events (one row per player feedback event)
CREATE TABLE IF NOT EXISTS events (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  received  INTEGER NOT NULL,      -- server time (ms)
  install   TEXT NOT NULL,         -- random per-install UUID (anonymous)
  mod       TEXT, policy TEXT,     -- mod and dog-brain versions
  t         INTEGER,               -- client time (ms)
  dog       TEXT,                  -- hashed dog id
  signal    TEXT, r REAL, hunger REAL,
  credited  TEXT                   -- JSON: [{a, word, w, obs[28]}]
);
CREATE INDEX IF NOT EXISTS events_install_received ON events (install, received);

-- per-IP rate limit buckets (salted hash of the IP, hourly; rows older than a day are deleted)
CREATE TABLE IF NOT EXISTS ratelimit (k TEXT NOT NULL, hour INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (k, hour));

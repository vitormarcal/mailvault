CREATE TABLE IF NOT EXISTS assets (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    original_url TEXT NOT NULL,
    storage_path TEXT,
    content_type TEXT,
    size INTEGER,
    sha256 TEXT,
    status TEXT NOT NULL,
    downloaded_at TEXT,
    error TEXT,
    UNIQUE(message_id, original_url)
);

CREATE INDEX IF NOT EXISTS idx_assets_message_id ON assets(message_id);

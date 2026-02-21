CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    file_path TEXT NOT NULL UNIQUE,
    file_mtime_epoch INTEGER NOT NULL,
    file_size INTEGER NOT NULL,
    date_raw TEXT,
    subject TEXT,
    from_raw TEXT,
    message_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_messages_subject ON messages(subject);
CREATE INDEX IF NOT EXISTS idx_messages_from_raw ON messages(from_raw);
CREATE INDEX IF NOT EXISTS idx_messages_date_raw ON messages(date_raw);

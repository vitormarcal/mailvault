CREATE TABLE IF NOT EXISTS message_bodies (
    message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    text_plain TEXT
);

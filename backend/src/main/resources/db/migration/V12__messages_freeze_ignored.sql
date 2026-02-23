ALTER TABLE messages ADD COLUMN freeze_ignored INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_messages_freeze_ignored ON messages(freeze_ignored);

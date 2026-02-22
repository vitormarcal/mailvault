ALTER TABLE messages ADD COLUMN date_epoch INTEGER;

CREATE INDEX IF NOT EXISTS idx_messages_date_epoch ON messages(date_epoch);

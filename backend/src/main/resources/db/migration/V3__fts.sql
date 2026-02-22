CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    id UNINDEXED,
    subject,
    from_raw
);

INSERT INTO messages_fts(id, subject, from_raw)
SELECT id, COALESCE(subject, ''), COALESCE(from_raw, '')
FROM messages;

CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(id, subject, from_raw)
    VALUES (new.id, COALESCE(new.subject, ''), COALESCE(new.from_raw, ''));
END;

CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
    DELETE FROM messages_fts
    WHERE id = old.id;
END;

CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
    DELETE FROM messages_fts
    WHERE id = old.id;

    INSERT INTO messages_fts(id, subject, from_raw)
    VALUES (new.id, COALESCE(new.subject, ''), COALESCE(new.from_raw, ''));
END;

ALTER TABLE message_bodies ADD COLUMN html_text TEXT NULL;

UPDATE message_bodies
SET html_text = html_raw
WHERE html_text IS NULL
  AND html_raw IS NOT NULL
  AND TRIM(html_raw) <> '';

DROP TRIGGER IF EXISTS messages_ai;
DROP TRIGGER IF EXISTS messages_ad;
DROP TRIGGER IF EXISTS messages_au;
DROP TRIGGER IF EXISTS message_bodies_ai_fts;
DROP TRIGGER IF EXISTS message_bodies_au_fts;
DROP TRIGGER IF EXISTS message_bodies_ad_fts;

DROP TABLE IF EXISTS messages_fts;

CREATE VIRTUAL TABLE messages_fts USING fts5(
    id UNINDEXED,
    subject,
    from_raw,
    text_plain,
    html_text
);

INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
SELECT
    m.id,
    COALESCE(m.subject, ''),
    COALESCE(m.from_raw, ''),
    COALESCE(mb.text_plain, ''),
    COALESCE(mb.html_text, '')
FROM messages m
LEFT JOIN message_bodies mb ON mb.message_id = m.id;

CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
    VALUES (
        new.id,
        COALESCE(new.subject, ''),
        COALESCE(new.from_raw, ''),
        COALESCE((SELECT text_plain FROM message_bodies WHERE message_id = new.id), ''),
        COALESCE((SELECT html_text FROM message_bodies WHERE message_id = new.id), '')
    );
END;

CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
    DELETE FROM messages_fts
    WHERE id = old.id;
END;

CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN
    DELETE FROM messages_fts
    WHERE id = old.id;

    INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
    VALUES (
        new.id,
        COALESCE(new.subject, ''),
        COALESCE(new.from_raw, ''),
        COALESCE((SELECT text_plain FROM message_bodies WHERE message_id = new.id), ''),
        COALESCE((SELECT html_text FROM message_bodies WHERE message_id = new.id), '')
    );
END;

CREATE TRIGGER message_bodies_ai_fts AFTER INSERT ON message_bodies BEGIN
    DELETE FROM messages_fts
    WHERE id = new.message_id;

    INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
    SELECT
        m.id,
        COALESCE(m.subject, ''),
        COALESCE(m.from_raw, ''),
        COALESCE(new.text_plain, ''),
        COALESCE(new.html_text, '')
    FROM messages m
    WHERE m.id = new.message_id;
END;

CREATE TRIGGER message_bodies_au_fts AFTER UPDATE ON message_bodies BEGIN
    DELETE FROM messages_fts
    WHERE id = new.message_id;

    INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
    SELECT
        m.id,
        COALESCE(m.subject, ''),
        COALESCE(m.from_raw, ''),
        COALESCE(new.text_plain, ''),
        COALESCE(new.html_text, '')
    FROM messages m
    WHERE m.id = new.message_id;
END;

CREATE TRIGGER message_bodies_ad_fts AFTER DELETE ON message_bodies BEGIN
    DELETE FROM messages_fts
    WHERE id = old.message_id;

    INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
    SELECT
        m.id,
        COALESCE(m.subject, ''),
        COALESCE(m.from_raw, ''),
        '',
        ''
    FROM messages m
    WHERE m.id = old.message_id;
END;

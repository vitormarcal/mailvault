package dev.marcal.mailvault.repository

import dev.marcal.mailvault.domain.AttachmentUpsert
import dev.marcal.mailvault.domain.ExistingMessage
import dev.marcal.mailvault.domain.MessageBodyUpsert
import dev.marcal.mailvault.domain.MessageUpsert
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class IndexWriteRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun putMeta(key: String, value: String) {
        jdbcTemplate.update(
            """
            INSERT INTO app_meta (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value
            """.trimIndent(),
            key,
            value,
        )
    }

    fun findByFilePath(filePath: String): ExistingMessage? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT
                    m.file_mtime_epoch,
                    m.file_size,
                    CASE
                        WHEN m.date_epoch IS NOT NULL THEN 1
                        ELSE 0
                    END AS has_date_epoch,
                    CASE
                        WHEN COALESCE(TRIM(mb.text_plain), '') <> '' OR COALESCE(TRIM(mb.html_raw), '') <> '' THEN 1
                        ELSE 0
                    END AS has_body_content
                FROM messages m
                LEFT JOIN message_bodies mb ON mb.message_id = m.id
                WHERE file_path = ?
                """.trimIndent(),
                { rs, _ ->
                    ExistingMessage(
                        fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        fileSize = rs.getLong("file_size"),
                        hasBodyContent = rs.getInt("has_body_content") == 1,
                        hasDateEpoch = rs.getInt("has_date_epoch") == 1,
                    )
                },
                filePath,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun upsertMessage(message: MessageUpsert) {
        jdbcTemplate.update(
            """
            INSERT INTO messages (
                id, file_path, file_mtime_epoch, file_size, date_raw, date_epoch, subject, from_raw, message_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(file_path) DO UPDATE SET
                id = excluded.id,
                file_mtime_epoch = excluded.file_mtime_epoch,
                file_size = excluded.file_size,
                date_raw = excluded.date_raw,
                date_epoch = excluded.date_epoch,
                subject = excluded.subject,
                from_raw = excluded.from_raw,
                message_id = excluded.message_id
            """.trimIndent(),
            message.id,
            message.filePath,
            message.fileMtimeEpoch,
            message.fileSize,
            message.dateRaw,
            message.dateEpoch,
            message.subject,
            message.fromRaw,
            message.messageId,
        )
    }

    fun upsertMessageBody(body: MessageBodyUpsert) {
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies (message_id, text_plain, html_raw, html_text, html_sanitized)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(message_id) DO UPDATE SET
                text_plain = excluded.text_plain,
                html_raw = excluded.html_raw,
                html_text = excluded.html_text,
                html_sanitized = excluded.html_sanitized
            """.trimIndent(),
            body.messageId,
            body.textPlain,
            body.htmlRaw,
            body.htmlText,
            body.htmlSanitized,
        )
    }

    fun replaceAttachments(messageId: String, attachments: List<AttachmentUpsert>) {
        jdbcTemplate.update("DELETE FROM attachments WHERE message_id = ?", messageId)
        attachments.forEach { attachment ->
            jdbcTemplate.update(
                """
                INSERT INTO attachments (
                    id, message_id, filename, content_type, size, inline_cid, storage_path, sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                attachment.id,
                attachment.messageId,
                attachment.filename,
                attachment.contentType,
                attachment.size,
                attachment.inlineCid,
                attachment.storagePath,
                attachment.sha256,
            )
        }
    }
}

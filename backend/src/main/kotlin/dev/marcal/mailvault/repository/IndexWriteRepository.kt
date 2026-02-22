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
    fun findByFilePath(filePath: String): ExistingMessage? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT file_mtime_epoch, file_size
                FROM messages
                WHERE file_path = ?
                """.trimIndent(),
                { rs, _ ->
                    ExistingMessage(
                        fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        fileSize = rs.getLong("file_size"),
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
                id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(file_path) DO UPDATE SET
                id = excluded.id,
                file_mtime_epoch = excluded.file_mtime_epoch,
                file_size = excluded.file_size,
                date_raw = excluded.date_raw,
                subject = excluded.subject,
                from_raw = excluded.from_raw,
                message_id = excluded.message_id
            """.trimIndent(),
            message.id,
            message.filePath,
            message.fileMtimeEpoch,
            message.fileSize,
            message.dateRaw,
            message.subject,
            message.fromRaw,
            message.messageId,
        )
    }

    fun upsertMessageBody(body: MessageBodyUpsert) {
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies (message_id, text_plain, html_raw, html_sanitized)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(message_id) DO UPDATE SET
                text_plain = excluded.text_plain,
                html_raw = excluded.html_raw,
                html_sanitized = excluded.html_sanitized
            """.trimIndent(),
            body.messageId,
            body.textPlain,
            body.htmlRaw,
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

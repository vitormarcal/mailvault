package dev.marcal.mailvault.repository

import dev.marcal.mailvault.domain.AttachmentRecord
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AttachmentRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun listByMessageId(messageId: String): List<AttachmentRecord> =
        jdbcTemplate.query(
            """
            SELECT id, message_id, filename, content_type, size, inline_cid, storage_path
            FROM attachments
            WHERE message_id = ?
            ORDER BY COALESCE(filename, ''), id
            """.trimIndent(),
            { rs, _ ->
                AttachmentRecord(
                    id = rs.getString("id"),
                    messageId = rs.getString("message_id"),
                    filename = rs.getString("filename"),
                    contentType = rs.getString("content_type"),
                    size = rs.getLong("size"),
                    inlineCid = rs.getString("inline_cid"),
                    storagePath = rs.getString("storage_path"),
                )
            },
            messageId,
        )

    fun findById(id: String): AttachmentRecord? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT id, message_id, filename, content_type, size, inline_cid, storage_path
                FROM attachments
                WHERE id = ?
                """.trimIndent(),
                { rs, _ ->
                    AttachmentRecord(
                        id = rs.getString("id"),
                        messageId = rs.getString("message_id"),
                        filename = rs.getString("filename"),
                        contentType = rs.getString("content_type"),
                        size = rs.getLong("size"),
                        inlineCid = rs.getString("inline_cid"),
                        storagePath = rs.getString("storage_path"),
                    )
                },
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun findInlineByMessageAndCid(
        messageId: String,
        cid: String,
    ): AttachmentRecord? {
        val normalized = cid.trim().removePrefix("<").removeSuffix(">")
        return try {
            jdbcTemplate.queryForObject(
                """
                SELECT id, message_id, filename, content_type, size, inline_cid, storage_path
                FROM attachments
                WHERE message_id = ?
                  AND REPLACE(REPLACE(COALESCE(inline_cid, ''), '<', ''), '>', '') = ?
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    AttachmentRecord(
                        id = rs.getString("id"),
                        messageId = rs.getString("message_id"),
                        filename = rs.getString("filename"),
                        contentType = rs.getString("content_type"),
                        size = rs.getLong("size"),
                        inlineCid = rs.getString("inline_cid"),
                        storagePath = rs.getString("storage_path"),
                    )
                },
                messageId,
                normalized,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }
    }
}

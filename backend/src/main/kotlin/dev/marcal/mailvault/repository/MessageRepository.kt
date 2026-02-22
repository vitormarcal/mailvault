package dev.marcal.mailvault.repository

import dev.marcal.mailvault.domain.MessageDetail
import dev.marcal.mailvault.domain.MessageSummary
import dev.marcal.mailvault.domain.MessagesPage
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class MessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun list(query: String?, page: Int, size: Int): MessagesPage {
        val sanitizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val offset = page * size

        val total =
            if (sanitizedQuery == null) {
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Long::class.java) ?: 0L
            } else {
                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM messages
                    WHERE id IN (
                        SELECT id
                        FROM messages_fts
                        WHERE messages_fts MATCH ?
                    )
                    """.trimIndent(),
                    Long::class.java,
                    sanitizedQuery,
                ) ?: 0L
            }

        val items =
            if (sanitizedQuery == null) {
                jdbcTemplate.query(
                    """
                    SELECT id, date_raw, subject, from_raw, file_mtime_epoch
                    FROM messages
                    ORDER BY COALESCE(date_epoch, file_mtime_epoch) DESC, file_mtime_epoch DESC, id DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    { rs, _ ->
                        MessageSummary(
                            id = rs.getString("id"),
                            dateRaw = rs.getString("date_raw"),
                            subject = rs.getString("subject"),
                            fromRaw = rs.getString("from_raw"),
                            fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        )
                    },
                    size,
                    offset,
                )
            } else {
                jdbcTemplate.query(
                    """
                    SELECT id, date_raw, subject, from_raw, file_mtime_epoch
                    FROM messages
                    WHERE id IN (
                        SELECT id
                        FROM messages_fts
                        WHERE messages_fts MATCH ?
                    )
                    ORDER BY COALESCE(date_epoch, file_mtime_epoch) DESC, file_mtime_epoch DESC, id DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    { rs, _ ->
                        MessageSummary(
                            id = rs.getString("id"),
                            dateRaw = rs.getString("date_raw"),
                            subject = rs.getString("subject"),
                            fromRaw = rs.getString("from_raw"),
                            fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        )
                    },
                    sanitizedQuery,
                    size,
                    offset,
                )
            }

        return MessagesPage(total = total, items = items)
    }

    fun findById(id: String): MessageDetail? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT m.id, m.file_path, m.file_mtime_epoch, m.file_size, m.date_raw, m.subject, m.from_raw, m.message_id,
                       mb.text_plain
                FROM messages m
                LEFT JOIN message_bodies mb ON mb.message_id = m.id
                WHERE m.id = ?
                """.trimIndent(),
                { rs, _ ->
                    MessageDetail(
                        id = rs.getString("id"),
                        filePath = rs.getString("file_path"),
                        fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        fileSize = rs.getLong("file_size"),
                        dateRaw = rs.getString("date_raw"),
                        subject = rs.getString("subject"),
                        fromRaw = rs.getString("from_raw"),
                        messageId = rs.getString("message_id"),
                        textPlain = rs.getString("text_plain"),
                    )
                },
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }
}

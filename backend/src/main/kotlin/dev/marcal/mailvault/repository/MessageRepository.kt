package dev.marcal.mailvault.repository

import dev.marcal.mailvault.api.MessageDetailResponse
import dev.marcal.mailvault.api.MessageSummaryResponse
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

data class MessagesPage(
    val total: Long,
    val items: List<MessageSummaryResponse>,
)

@Repository
class MessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun list(query: String?, page: Int, size: Int): MessagesPage {
        val sanitizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val like = "%${sanitizedQuery ?: ""}%"
        val offset = page * size

        val whereClause =
            if (sanitizedQuery == null) {
                ""
            } else {
                "WHERE subject LIKE ? OR from_raw LIKE ?"
            }

        val total =
            if (sanitizedQuery == null) {
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Long::class.java) ?: 0L
            } else {
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages $whereClause",
                    Long::class.java,
                    like,
                    like,
                ) ?: 0L
            }

        val items =
            if (sanitizedQuery == null) {
                jdbcTemplate.query(
                    """
                    SELECT id, date_raw, subject, from_raw, file_mtime_epoch
                    FROM messages
                    ORDER BY
                        CASE WHEN date_raw IS NULL OR TRIM(date_raw) = '' THEN 1 ELSE 0 END,
                        date_raw DESC,
                        file_mtime_epoch DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    { rs, _ ->
                        MessageSummaryResponse(
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
                    $whereClause
                    ORDER BY
                        CASE WHEN date_raw IS NULL OR TRIM(date_raw) = '' THEN 1 ELSE 0 END,
                        date_raw DESC,
                        file_mtime_epoch DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    { rs, _ ->
                        MessageSummaryResponse(
                            id = rs.getString("id"),
                            dateRaw = rs.getString("date_raw"),
                            subject = rs.getString("subject"),
                            fromRaw = rs.getString("from_raw"),
                            fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        )
                    },
                    like,
                    like,
                    size,
                    offset,
                )
            }

        return MessagesPage(total = total, items = items)
    }

    fun findById(id: String): MessageDetailResponse? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id
                FROM messages
                WHERE id = ?
                """.trimIndent(),
                { rs, _ ->
                    MessageDetailResponse(
                        id = rs.getString("id"),
                        filePath = rs.getString("file_path"),
                        fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        fileSize = rs.getLong("file_size"),
                        dateRaw = rs.getString("date_raw"),
                        subject = rs.getString("subject"),
                        fromRaw = rs.getString("from_raw"),
                        messageId = rs.getString("message_id"),
                    )
                },
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }
}

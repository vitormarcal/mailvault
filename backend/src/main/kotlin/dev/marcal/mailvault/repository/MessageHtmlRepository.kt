package dev.marcal.mailvault.repository

import dev.marcal.mailvault.domain.MessageHtml
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class MessageHtmlRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findByMessageId(messageId: String): MessageHtml? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT m.id AS message_id, mb.html_raw, mb.html_sanitized
                FROM messages m
                LEFT JOIN message_bodies mb ON mb.message_id = m.id
                WHERE m.id = ?
                """.trimIndent(),
                { rs, _ ->
                    MessageHtml(
                        messageId = rs.getString("message_id"),
                        htmlRaw = rs.getString("html_raw"),
                        htmlSanitized = rs.getString("html_sanitized"),
                    )
                },
                messageId,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun updateHtmlSanitized(messageId: String, htmlSanitized: String?) {
        jdbcTemplate.update(
            """
            UPDATE message_bodies
            SET html_sanitized = ?
            WHERE message_id = ?
            """.trimIndent(),
            htmlSanitized,
            messageId,
        )
    }

    fun clearHtmlSanitized(messageId: String) {
        updateHtmlSanitized(messageId, null)
    }
}

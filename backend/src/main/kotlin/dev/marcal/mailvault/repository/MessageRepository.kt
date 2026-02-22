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
    fun list(
        query: String?,
        page: Int,
        size: Int,
        year: Int?,
        hasAttachments: Boolean?,
        hasHtml: Boolean?,
        hasFrozenImages: Boolean?,
    ): MessagesPage {
        val sanitizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val offset = page * size
        val whereParts = mutableListOf<String>()
        val whereParams = mutableListOf<Any>()

        if (sanitizedQuery != null) {
            whereParts.add("messages_fts MATCH ?")
            whereParams.add(sanitizedQuery)
        }
        if (year != null) {
            whereParts.add(
                """
                strftime(
                    '%Y',
                    datetime(
                        CASE
                            WHEN m.date_epoch > 32503680000 THEN m.date_epoch / 1000
                            ELSE m.date_epoch
                        END,
                        'unixepoch'
                    )
                ) = ?
                """.trimIndent(),
            )
            whereParams.add(year.toString())
        }
        if (hasAttachments != null) {
            whereParts.add(
                if (hasAttachments) {
                    "EXISTS (SELECT 1 FROM attachments a WHERE a.message_id = m.id)"
                } else {
                    "NOT EXISTS (SELECT 1 FROM attachments a WHERE a.message_id = m.id)"
                },
            )
        }
        if (hasHtml != null) {
            whereParts.add(
                if (hasHtml) {
                    "EXISTS (SELECT 1 FROM message_bodies mb WHERE mb.message_id = m.id AND mb.html_raw IS NOT NULL AND TRIM(mb.html_raw) <> '')"
                } else {
                    "NOT EXISTS (SELECT 1 FROM message_bodies mb WHERE mb.message_id = m.id AND mb.html_raw IS NOT NULL AND TRIM(mb.html_raw) <> '')"
                },
            )
        }
        if (hasFrozenImages != null) {
            whereParts.add(
                if (hasFrozenImages) {
                    "EXISTS (SELECT 1 FROM assets s WHERE s.message_id = m.id AND s.status = 'DOWNLOADED')"
                } else {
                    "NOT EXISTS (SELECT 1 FROM assets s WHERE s.message_id = m.id AND s.status = 'DOWNLOADED')"
                },
            )
        }

        val fromClause =
            if (sanitizedQuery == null) {
                "FROM messages m"
            } else {
                "FROM messages m JOIN messages_fts ON messages_fts.id = m.id"
            }
        val whereClause =
            if (whereParts.isEmpty()) {
                ""
            } else {
                " WHERE ${whereParts.joinToString(" AND ")}"
            }

        val total =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) $fromClause$whereClause",
                Long::class.java,
                *whereParams.toTypedArray(),
            ) ?: 0L

        val items =
            jdbcTemplate.query(
                """
                SELECT m.id, m.date_raw, m.subject, m.from_raw, m.file_mtime_epoch
                $fromClause
                $whereClause
                ORDER BY ${
                    if (sanitizedQuery == null) {
                        "COALESCE(m.date_epoch, m.file_mtime_epoch) DESC, m.file_mtime_epoch DESC, m.id DESC"
                    } else {
                        "bm25(messages_fts) ASC, COALESCE(m.date_epoch, m.file_mtime_epoch) DESC, m.file_mtime_epoch DESC, m.id DESC"
                    }
                }
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
                *(whereParams + listOf(size, offset)).toTypedArray(),
            )

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

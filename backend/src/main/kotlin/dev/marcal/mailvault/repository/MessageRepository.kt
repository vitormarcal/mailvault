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
        val sanitizedQuery =
            query
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { toSafeFtsExpression(it) }
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
                WITH paged AS (
                    SELECT
                        ROW_NUMBER() OVER (
                            ORDER BY ${
                    if (sanitizedQuery == null) {
                        "COALESCE(m.date_epoch, m.file_mtime_epoch) DESC, m.file_mtime_epoch DESC, m.id DESC"
                    } else {
                        "bm25(messages_fts) ASC, COALESCE(m.date_epoch, m.file_mtime_epoch) DESC, m.file_mtime_epoch DESC, m.id DESC"
                    }
                }
                        ) AS page_order,
                        m.id,
                        m.date_raw,
                        m.date_epoch,
                        m.subject,
                        m.subject_display,
                        COALESCE(
                            NULLIF(TRIM(mb.text_plain), ''),
                            NULLIF(TRIM(mb.html_text), ''),
                            NULLIF(TRIM(mb.html_raw), '')
                        ) AS snippet_source,
                        CASE
                            WHEN mb.html_raw IS NOT NULL AND TRIM(mb.html_raw) <> '' THEN 1
                            ELSE 0
                        END AS has_html,
                        m.freeze_ignored,
                        m.from_raw,
                        m.from_display,
                        m.file_mtime_epoch
                    $fromClause
                    LEFT JOIN message_bodies mb ON mb.message_id = m.id
                    $whereClause
                    ORDER BY ${
                    if (sanitizedQuery == null) {
                        "COALESCE(m.date_epoch, m.file_mtime_epoch) DESC, m.file_mtime_epoch DESC, m.id DESC"
                    } else {
                        "bm25(messages_fts) ASC, COALESCE(m.date_epoch, m.file_mtime_epoch) DESC, m.file_mtime_epoch DESC, m.id DESC"
                    }
                }
                    LIMIT ? OFFSET ?
                ),
                attachment_counts AS (
                    SELECT a.message_id, COUNT(*) AS attachments_count
                    FROM attachments a
                    GROUP BY a.message_id
                ),
                asset_counts AS (
                    SELECT
                        s.message_id,
                        SUM(CASE WHEN s.status = 'DOWNLOADED' THEN 1 ELSE 0 END) AS frozen_assets_count,
                        SUM(CASE WHEN s.status = 'FAILED' THEN 1 ELSE 0 END) AS assets_failed_count
                    FROM assets s
                    GROUP BY s.message_id
                )
                SELECT
                    p.id,
                    p.date_raw,
                    p.date_epoch,
                    p.subject,
                    p.subject_display,
                    p.snippet_source,
                    p.has_html,
                    COALESCE(ac.attachments_count, 0) AS attachments_count,
                    COALESCE(sc.frozen_assets_count, 0) AS frozen_assets_count,
                    COALESCE(sc.assets_failed_count, 0) AS assets_failed_count,
                    p.freeze_ignored,
                    p.from_raw,
                    p.from_display,
                    p.file_mtime_epoch
                FROM paged p
                LEFT JOIN attachment_counts ac ON ac.message_id = p.id
                LEFT JOIN asset_counts sc ON sc.message_id = p.id
                ORDER BY p.page_order ASC
                """.trimIndent(),
                { rs, _ ->
                    MessageSummary(
                        id = rs.getString("id"),
                        dateRaw = rs.getString("date_raw"),
                        dateEpoch = readNullableLong(rs, "date_epoch"),
                        subject = rs.getString("subject"),
                        subjectDisplay = rs.getString("subject_display"),
                        snippetSource = rs.getString("snippet_source"),
                        hasHtml = rs.getInt("has_html") == 1,
                        attachmentsCount = rs.getInt("attachments_count"),
                        frozenAssetsCount = rs.getInt("frozen_assets_count"),
                        assetsFailedCount = rs.getInt("assets_failed_count"),
                        freezeIgnored = rs.getInt("freeze_ignored") == 1,
                        fromRaw = rs.getString("from_raw"),
                        fromDisplay = rs.getString("from_display"),
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
                SELECT m.id, m.file_path, m.file_mtime_epoch, m.file_size, m.date_raw, m.date_epoch, m.subject, m.subject_display, m.from_raw, m.from_display, m.from_email, m.from_name, m.message_id,
                       mb.text_plain,
                       (SELECT COUNT(*) FROM attachments a WHERE a.message_id = m.id) AS attachments_count,
                       (SELECT COUNT(*) FROM assets s WHERE s.message_id = m.id AND s.status = 'DOWNLOADED') AS frozen_assets_count,
                       (SELECT COUNT(*) FROM assets s WHERE s.message_id = m.id AND s.status = 'FAILED') AS assets_failed_count,
                       m.freeze_ignored
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
                        dateEpoch = readNullableLong(rs, "date_epoch"),
                        subject = rs.getString("subject"),
                        subjectDisplay = rs.getString("subject_display"),
                        fromRaw = rs.getString("from_raw"),
                        fromDisplay = rs.getString("from_display"),
                        fromEmail = rs.getString("from_email"),
                        fromName = rs.getString("from_name"),
                        attachmentsCount = rs.getInt("attachments_count"),
                        frozenAssetsCount = rs.getInt("frozen_assets_count"),
                        assetsFailedCount = rs.getInt("assets_failed_count"),
                        freezeIgnored = rs.getInt("freeze_ignored") == 1,
                        messageId = rs.getString("message_id"),
                        textPlain = rs.getString("text_plain"),
                    )
                },
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun setFreezeIgnored(
        id: String,
        ignored: Boolean,
    ): Boolean =
        jdbcTemplate.update(
            "UPDATE messages SET freeze_ignored = ? WHERE id = ?",
            if (ignored) 1 else 0,
            id,
        ) > 0

    private fun readNullableLong(
        rs: java.sql.ResultSet,
        column: String,
    ): Long? {
        val value = rs.getLong(column)
        return if (rs.wasNull()) null else value
    }

    fun findPrevId(id: String): String? =
        try {
            jdbcTemplate.queryForObject(
                """
                WITH ordered AS (
                    SELECT
                        id,
                        ROW_NUMBER() OVER (
                            ORDER BY COALESCE(date_epoch, file_mtime_epoch) DESC, file_mtime_epoch DESC, id DESC
                        ) AS rn
                    FROM messages
                ),
                current_row AS (
                    SELECT rn
                    FROM ordered
                    WHERE id = ?
                )
                SELECT o.id
                FROM ordered o
                JOIN current_row c ON o.rn = c.rn - 1
                """.trimIndent(),
                String::class.java,
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun findNextId(id: String): String? =
        try {
            jdbcTemplate.queryForObject(
                """
                WITH ordered AS (
                    SELECT
                        id,
                        ROW_NUMBER() OVER (
                            ORDER BY COALESCE(date_epoch, file_mtime_epoch) DESC, file_mtime_epoch DESC, id DESC
                        ) AS rn
                    FROM messages
                ),
                current_row AS (
                    SELECT rn
                    FROM ordered
                    WHERE id = ?
                )
                SELECT o.id
                FROM ordered o
                JOIN current_row c ON o.rn = c.rn + 1
                """.trimIndent(),
                String::class.java,
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    private fun toSafeFtsExpression(query: String): String {
        val terms =
            SAFE_TERM_REGEX
                .findAll(query)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        if (terms.isEmpty()) {
            return quoteFtsTerm(query)
        }
        return terms.joinToString(" AND ") { quoteFtsTerm(it) }
    }

    private fun quoteFtsTerm(term: String): String = "\"${term.replace("\"", "\"\"")}\""

    private companion object {
        val SAFE_TERM_REGEX = Regex("""[\p{L}\p{N}@._%+\-]+""")
    }
}

package dev.marcal.mailvault.repository

import dev.marcal.mailvault.api.StatsResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class StatsRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun getStats(): StatsResponse {
        val totalMessages = queryLong("SELECT COUNT(*) FROM messages")
        val totalWithHtml =
            queryLong(
                """
                SELECT COUNT(*)
                FROM message_bodies
                WHERE html_raw IS NOT NULL AND TRIM(html_raw) <> ''
                """.trimIndent(),
            )
        val totalAttachments = queryLong("SELECT COUNT(*) FROM attachments")
        val totalAssetsDownloaded = queryLong("SELECT COUNT(*) FROM assets WHERE status = 'DOWNLOADED'")
        val totalAssetsFailed = queryLong("SELECT COUNT(*) FROM assets WHERE status = 'FAILED'")
        val storageBytesAttachments = queryLong("SELECT COALESCE(SUM(size), 0) FROM attachments")
        val storageBytesAssets = queryLong("SELECT COALESCE(SUM(size), 0) FROM assets WHERE status = 'DOWNLOADED'")
        val lastIndexAt = queryString("SELECT value FROM app_meta WHERE key = 'lastIndexAt'")

        return StatsResponse(
            totalMessages = totalMessages,
            totalWithHtml = totalWithHtml,
            totalAttachments = totalAttachments,
            totalAssetsDownloaded = totalAssetsDownloaded,
            totalAssetsFailed = totalAssetsFailed,
            storageBytesAttachments = storageBytesAttachments,
            storageBytesAssets = storageBytesAssets,
            lastIndexAt = lastIndexAt,
        )
    }

    private fun queryLong(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0L

    private fun queryString(sql: String): String? = jdbcTemplate.queryForObject(sql, String::class.java)
}

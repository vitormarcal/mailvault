package dev.marcal.mailvault.repository

import dev.marcal.mailvault.domain.AssetRecord
import dev.marcal.mailvault.domain.AssetStatus
import dev.marcal.mailvault.domain.AssetUpsert
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AssetRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun upsert(asset: AssetUpsert) {
        jdbcTemplate.update(
            """
            INSERT INTO assets (
                id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(message_id, original_url) DO UPDATE SET
                id = excluded.id,
                storage_path = excluded.storage_path,
                content_type = excluded.content_type,
                size = excluded.size,
                sha256 = excluded.sha256,
                status = excluded.status,
                downloaded_at = excluded.downloaded_at,
                error = excluded.error
            """.trimIndent(),
            asset.id,
            asset.messageId,
            asset.originalUrl,
            asset.storagePath,
            asset.contentType,
            asset.size,
            asset.sha256,
            asset.status.name,
            asset.downloadedAt,
            asset.error,
        )
    }

    fun findDownloadedByMessageAndOriginalUrl(
        messageId: String,
        originalUrl: String,
    ): AssetRecord? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error
                FROM assets
                WHERE message_id = ? AND original_url = ? AND status = 'DOWNLOADED'
                LIMIT 1
                """.trimIndent(),
                { rs, _ -> rsToAsset(rs) },
                messageId,
                originalUrl,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun findDownloadedByMessageAndFilename(
        messageId: String,
        filename: String,
    ): AssetRecord? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error
                FROM assets
                WHERE message_id = ?
                  AND status = 'DOWNLOADED'
                  AND (storage_path LIKE '%' || '/' || ? OR storage_path LIKE '%' || '\\' || ?)
                LIMIT 1
                """.trimIndent(),
                { rs, _ -> rsToAsset(rs) },
                messageId,
                filename,
                filename,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun findDownloadedByMessageAndSha(
        messageId: String,
        sha256: String,
    ): AssetRecord? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error
                FROM assets
                WHERE message_id = ? AND sha256 = ? AND status = 'DOWNLOADED'
                LIMIT 1
                """.trimIndent(),
                { rs, _ -> rsToAsset(rs) },
                messageId,
                sha256,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun hasDownloadedByMessageId(messageId: String): Boolean =
        (
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM assets
                WHERE message_id = ? AND status = 'DOWNLOADED'
                """.trimIndent(),
                Int::class.java,
                messageId,
            ) ?: 0
        ) > 0

    private fun rsToAsset(rs: java.sql.ResultSet): AssetRecord =
        AssetRecord(
            id = rs.getString("id"),
            messageId = rs.getString("message_id"),
            originalUrl = rs.getString("original_url"),
            storagePath = rs.getString("storage_path"),
            contentType = rs.getString("content_type"),
            size = rs.getLong("size").let { if (rs.wasNull()) null else it },
            sha256 = rs.getString("sha256"),
            status = AssetStatus.valueOf(rs.getString("status")),
            downloadedAt = rs.getString("downloaded_at"),
            error = rs.getString("error"),
        )
}

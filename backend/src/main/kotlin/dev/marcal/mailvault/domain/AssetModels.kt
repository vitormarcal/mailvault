package dev.marcal.mailvault.domain

enum class AssetStatus {
    DOWNLOADED,
    FAILED,
    SKIPPED,
}

data class AssetRecord(
    val id: String,
    val messageId: String,
    val originalUrl: String,
    val storagePath: String?,
    val contentType: String?,
    val size: Long?,
    val sha256: String?,
    val status: AssetStatus,
    val downloadedAt: String?,
    val error: String?,
    val securityBlocked: Boolean,
)

data class AssetUpsert(
    val id: String,
    val messageId: String,
    val originalUrl: String,
    val storagePath: String?,
    val contentType: String?,
    val size: Long?,
    val sha256: String?,
    val status: AssetStatus,
    val downloadedAt: String?,
    val error: String?,
    val securityBlocked: Boolean = false,
)

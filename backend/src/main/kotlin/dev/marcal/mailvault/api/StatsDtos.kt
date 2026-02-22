package dev.marcal.mailvault.api

data class StatsResponse(
    val totalMessages: Long,
    val totalWithHtml: Long,
    val totalAttachments: Long,
    val totalAssetsDownloaded: Long,
    val totalAssetsFailed: Long,
    val storageBytesAttachments: Long,
    val storageBytesAssets: Long,
    val lastIndexAt: String?,
)

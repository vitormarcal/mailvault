package dev.marcal.mailvault.api

data class AssetFreezeResponse(
    val totalFound: Int,
    val downloaded: Int,
    val failed: Int,
    val skipped: Int,
    val failures: List<AssetFreezeFailureSummary> = emptyList(),
)

data class AssetFreezeFailureSummary(
    val host: String,
    val reason: String,
    val count: Int,
)

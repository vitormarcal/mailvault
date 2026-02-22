package dev.marcal.mailvault.api

data class AssetFreezeResponse(
    val downloaded: Int,
    val failed: Int,
    val skipped: Int,
)

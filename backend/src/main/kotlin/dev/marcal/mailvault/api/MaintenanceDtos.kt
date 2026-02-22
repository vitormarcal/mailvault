package dev.marcal.mailvault.api

data class CleanupResponse(
    val removedAttachmentFiles: Int,
    val removedAssetFiles: Int,
    val removedAttachmentDirs: Int,
    val removedAssetDirs: Int,
    val removedMissingMessageRows: Int,
)

data class VacuumResponse(
    val ok: Boolean,
    val durationMs: Long,
)

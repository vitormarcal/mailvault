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

data class ResetIndexedDataResponse(
    val removedMessages: Int,
    val removedMessageBodies: Int,
    val removedAttachmentsRows: Int,
    val removedAssetsRows: Int,
    val removedAttachmentFiles: Int,
    val removedAssetFiles: Int,
    val removedAttachmentDirs: Int,
    val removedAssetDirs: Int,
    val vacuumDurationMs: Long,
    val totalDurationMs: Long,
)

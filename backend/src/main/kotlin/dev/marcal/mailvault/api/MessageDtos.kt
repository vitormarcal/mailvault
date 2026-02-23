package dev.marcal.mailvault.api

data class MessageSummaryResponse(
    val id: String,
    val dateRaw: String?,
    val dateEpoch: Long?,
    val subject: String?,
    val subjectDisplay: String?,
    val snippet: String?,
    val hasHtml: Boolean,
    val attachmentsCount: Int,
    val frozenAssetsCount: Int,
    val assetsFailedCount: Int,
    val freezeIgnored: Boolean,
    val fromRaw: String?,
    val fromDisplay: String?,
    val fileMtimeEpoch: Long,
)

data class MessagesListResponse(
    val page: Int,
    val size: Int,
    val total: Long,
    val items: List<MessageSummaryResponse>,
)

data class MessageDetailResponse(
    val id: String,
    val filePath: String,
    val fileMtimeEpoch: Long,
    val fileSize: Long,
    val messageSizeBytes: Long,
    val dateRaw: String?,
    val dateEpoch: Long?,
    val subject: String?,
    val subjectDisplay: String?,
    val fromRaw: String?,
    val fromDisplay: String?,
    val fromEmail: String?,
    val fromName: String?,
    val attachmentsCount: Int,
    val frozenAssetsCount: Int,
    val assetsFailedCount: Int,
    val freezeIgnored: Boolean,
    val messageId: String?,
    val textPlain: String?,
)

data class MessageFreezeIgnoredResponse(
    val id: String,
    val freezeIgnored: Boolean,
)

data class MessageNeighborResponse(
    val id: String?,
)

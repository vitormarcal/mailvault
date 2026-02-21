package dev.marcal.mailvault.api

data class MessagesListRequest(
    val query: String?,
    val page: Int = 0,
    val size: Int = 50,
)

data class MessageSummaryResponse(
    val id: String,
    val dateRaw: String?,
    val subject: String?,
    val fromRaw: String?,
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
    val dateRaw: String?,
    val subject: String?,
    val fromRaw: String?,
    val messageId: String?,
)

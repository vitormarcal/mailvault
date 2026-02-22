package dev.marcal.mailvault.domain

data class MessageSummary(
    val id: String,
    val dateRaw: String?,
    val subject: String?,
    val fromRaw: String?,
    val fileMtimeEpoch: Long,
)

data class MessageDetail(
    val id: String,
    val filePath: String,
    val fileMtimeEpoch: Long,
    val fileSize: Long,
    val dateRaw: String?,
    val subject: String?,
    val fromRaw: String?,
    val messageId: String?,
    val textPlain: String?,
)

data class MessagesPage(
    val total: Long,
    val items: List<MessageSummary>,
)

data class ExistingMessage(
    val fileMtimeEpoch: Long,
    val fileSize: Long,
)

data class MessageUpsert(
    val id: String,
    val filePath: String,
    val fileMtimeEpoch: Long,
    val fileSize: Long,
    val dateRaw: String?,
    val subject: String?,
    val fromRaw: String?,
    val messageId: String?,
)

data class MessageBodyUpsert(
    val messageId: String,
    val textPlain: String?,
    val htmlRaw: String?,
    val htmlSanitized: String?,
)

data class AttachmentUpsert(
    val id: String,
    val messageId: String,
    val filename: String?,
    val contentType: String?,
    val size: Long,
    val inlineCid: String?,
    val storagePath: String,
    val sha256: String,
)

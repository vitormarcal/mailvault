package dev.marcal.mailvault.domain

data class MessageSummary(
    val id: String,
    val dateRaw: String?,
    val dateEpoch: Long?,
    val subject: String?,
    val subjectDisplay: String?,
    val snippetSource: String?,
    val hasHtml: Boolean,
    val attachmentsCount: Int,
    val frozenAssetsCount: Int,
    val assetsFailedCount: Int,
    val freezeIgnored: Boolean,
    val fromRaw: String?,
    val fromDisplay: String?,
    val fileMtimeEpoch: Long,
)

data class MessageDetail(
    val id: String,
    val filePath: String,
    val fileMtimeEpoch: Long,
    val fileSize: Long,
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

data class MessagesPage(
    val total: Long,
    val items: List<MessageSummary>,
)

data class ExistingMessage(
    val id: String,
    val fileMtimeEpoch: Long,
    val fileSize: Long,
    val hasBodyContent: Boolean,
    val hasDateEpoch: Boolean,
    val freezeIgnored: Boolean,
)

data class MessageUpsert(
    val id: String,
    val filePath: String,
    val fileMtimeEpoch: Long,
    val fileSize: Long,
    val dateRaw: String?,
    val dateEpoch: Long?,
    val subject: String?,
    val subjectDisplay: String?,
    val fromRaw: String?,
    val fromDisplay: String?,
    val fromEmail: String?,
    val fromName: String?,
    val messageId: String?,
)

data class MessageBodyUpsert(
    val messageId: String,
    val textPlain: String?,
    val htmlRaw: String?,
    val htmlText: String?,
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

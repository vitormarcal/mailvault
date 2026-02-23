package dev.marcal.mailvault.domain

data class AttachmentRecord(
    val id: String,
    val messageId: String,
    val filename: String?,
    val contentType: String?,
    val size: Long,
    val inlineCid: String?,
    val storagePath: String,
)

package dev.marcal.mailvault.api

data class AttachmentResponse(
    val id: String,
    val filename: String?,
    val contentType: String?,
    val size: Long,
    val isInline: Boolean,
    val inlineCid: String?,
)

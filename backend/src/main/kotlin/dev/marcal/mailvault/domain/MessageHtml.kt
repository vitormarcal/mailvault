package dev.marcal.mailvault.domain

data class MessageHtml(
    val messageId: String,
    val htmlRaw: String?,
    val htmlSanitized: String?,
)

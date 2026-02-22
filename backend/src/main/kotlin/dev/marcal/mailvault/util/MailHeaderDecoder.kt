package dev.marcal.mailvault.util

import jakarta.mail.internet.MimeUtility

object MailHeaderDecoder {
    fun decodeHeader(raw: String?): String? {
        if (raw == null) {
            return null
        }
        if (!raw.contains("=?")) {
            return raw
        }
        return try {
            MimeUtility.decodeText(raw)
        } catch (_: Exception) {
            raw
        }
    }
}

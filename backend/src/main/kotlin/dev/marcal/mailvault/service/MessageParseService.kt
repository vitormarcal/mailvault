package dev.marcal.mailvault.service

import jakarta.mail.BodyPart
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private const val HEADER_FROM = "From"
private const val HEADER_DATE = "Date"
private const val HEADER_MESSAGE_ID = "Message-ID"
private const val HEADER_CONTENT_ID = "Content-ID"

data class ParsedAttachment(
    val filename: String?,
    val contentType: String?,
    val size: Long,
    val inlineCid: String?,
    val bytes: ByteArray,
)

data class ParsedMessage(
    val dateRaw: String?,
    val subject: String?,
    val fromRaw: String?,
    val messageId: String?,
    val textPlain: String?,
    val htmlRaw: String?,
    val attachments: List<ParsedAttachment>,
)

@Service
class MessageParseService {
    fun parse(filePath: Path): ParsedMessage {
        val session = Session.getInstance(Properties())
        val mimeMessage = Files.newInputStream(filePath).use { input -> MimeMessage(session, input) }

        val accumulator = ParseAccumulator()
        parsePart(mimeMessage, accumulator)

        return ParsedMessage(
            dateRaw = mimeMessage.getHeader(HEADER_DATE, null),
            subject = mimeMessage.subject,
            fromRaw = mimeMessage.getHeader(HEADER_FROM, null),
            messageId = mimeMessage.getHeader(HEADER_MESSAGE_ID, null),
            textPlain = accumulator.textPlain?.ifBlank { null },
            htmlRaw = accumulator.htmlRaw?.ifBlank { null },
            attachments = accumulator.attachments,
        )
    }

    private fun parsePart(part: Part, accumulator: ParseAccumulator) {
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as Multipart
            for (index in 0 until multipart.count) {
                val bodyPart = multipart.getBodyPart(index)
                parseBodyPart(bodyPart, accumulator)
            }
            return
        }

        if (part.isMimeType("message/rfc822")) {
            val nested = part.content
            if (nested is Part) {
                parsePart(nested, accumulator)
            }
            return
        }

        if (part.isMimeType("text/plain")) {
            val text = readPartAsText(part)
            accumulator.textPlain = appendText(accumulator.textPlain, text)
            return
        }

        if (part.isMimeType("text/html")) {
            val html = readPartAsText(part)
            accumulator.htmlRaw = appendText(accumulator.htmlRaw, html)
            return
        }
    }

    private fun parseBodyPart(part: BodyPart, accumulator: ParseAccumulator) {
        if (part.isMimeType("multipart/*") || part.isMimeType("message/rfc822")) {
            parsePart(part, accumulator)
            return
        }

        if (isAttachmentLike(part)) {
            val bytes = readPartAsBytes(part)
            val contentId = normalizeContentId(firstHeader(part, HEADER_CONTENT_ID))
            val inlineCid = if (part.isMimeType("image/*") || contentId != null) contentId else null
            accumulator.attachments +=
                ParsedAttachment(
                    filename = decodeFilename(part.fileName),
                    contentType = normalizeContentType(part.contentType),
                    size = bytes.size.toLong(),
                    inlineCid = inlineCid,
                    bytes = bytes,
                )
            return
        }

        parsePart(part, accumulator)
    }

    private fun isAttachmentLike(part: BodyPart): Boolean {
        val disposition = part.disposition?.lowercase()
        val hasAttachmentDisposition = disposition == Part.ATTACHMENT.lowercase()
        val hasInlineDisposition = disposition == Part.INLINE.lowercase()
        val hasFilename = !part.fileName.isNullOrBlank()
        val hasContentId = firstHeader(part, HEADER_CONTENT_ID) != null
        val isTextLike = part.isMimeType("text/plain") || part.isMimeType("text/html")
        return hasAttachmentDisposition || hasFilename || hasContentId || (hasInlineDisposition && !isTextLike)
    }

    private fun readPartAsText(part: Part): String {
        val content = part.content
        return when (content) {
            is String -> content
            else -> part.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        }
    }

    private fun readPartAsBytes(part: Part): ByteArray {
        val content = part.content
        return when (content) {
            is ByteArray -> content
            is InputStream -> content.use { it.readBytes() }
            else -> {
                val output = ByteArrayOutputStream()
                part.dataHandler.writeTo(output)
                output.toByteArray()
            }
        }
    }

    private fun decodeFilename(fileName: String?): String? {
        if (fileName.isNullOrBlank()) {
            return null
        }
        return try {
            MimeUtility.decodeText(fileName)
        } catch (_: Exception) {
            fileName
        }
    }

    private fun normalizeContentType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) {
            return null
        }
        return contentType.substringBefore(';').trim().lowercase()
    }

    private fun normalizeContentId(contentId: String?): String? {
        if (contentId.isNullOrBlank()) {
            return null
        }
        return contentId.trim().removePrefix("<").removeSuffix(">")
    }

    private fun firstHeader(part: Part, headerName: String): String? {
        val headers = part.getHeader(headerName) ?: return null
        return headers.firstOrNull()
    }

    private fun appendText(current: String?, next: String?): String? {
        if (next.isNullOrBlank()) {
            return current
        }
        if (current.isNullOrBlank()) {
            return next
        }
        return "$current\n\n$next"
    }

    private data class ParseAccumulator(
        var textPlain: String? = null,
        var htmlRaw: String? = null,
        val attachments: MutableList<ParsedAttachment> = mutableListOf(),
    )
}

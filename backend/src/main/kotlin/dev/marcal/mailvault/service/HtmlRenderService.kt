package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class HtmlRenderService(
    private val messageHtmlRepository: MessageHtmlRepository,
    private val htmlSanitizerService: HtmlSanitizerService,
) {
    fun render(messageId: String): String {
        val html = messageHtmlRepository.findByMessageId(messageId)
            ?: throw ResourceNotFoundException("message not found")

        if (!html.htmlSanitized.isNullOrBlank()) {
            return html.htmlSanitized
        }

        if (html.htmlRaw.isNullOrBlank()) {
            return ""
        }

        val rewritten = rewriteHtml(html.htmlRaw, messageId)
        val sanitized = htmlSanitizerService.sanitize(rewritten)
        val finalized = finalizeHtml(sanitized)
        messageHtmlRepository.updateHtmlSanitized(messageId, finalized)
        return finalized
    }

    private fun rewriteHtml(rawHtml: String, messageId: String): String {
        val withLinks = ANCHOR_HREF_REGEX.replace(rawHtml) { match ->
            val prefix = match.groups[1]?.value ?: ""
            val quote = match.groups[2]?.value ?: "\""
            val href = match.groups[3]?.value ?: ""
            val rewrittenHref = "/go?url=${urlEncode(href)}"
            "<a$prefix data-safe-href=$quote$rewrittenHref$quote"
        }

        return IMG_SRC_REGEX.replace(withLinks) { match ->
            val prefix = match.groups[1]?.value ?: ""
            val quote = match.groups[2]?.value ?: "\""
            val src = (match.groups[3]?.value ?: "").trim()
            val rewrittenSrc =
                when {
                    src.startsWith("cid:", ignoreCase = true) -> {
                        val cid = src.removePrefix("cid:").removePrefix("CID:").trim().removePrefix("<").removeSuffix(">")
                        "/api/messages/$messageId/cid/$cid"
                    }

                    src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true) -> {
                        REMOTE_IMAGE_PLACEHOLDER
                    }

                    else -> src
                }

            val originalAttr =
                if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
                    " data-original-src=$quote$src$quote"
                } else {
                    ""
                }

            "<img$prefix src=$quote$rewrittenSrc$quote$originalAttr"
                .replace(" src=", " data-safe-src=")
        }
    }

    private fun finalizeHtml(sanitized: String): String =
        sanitized
            .replace("data-safe-href=", "href=")
            .replace("data-safe-src=", "src=")

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val ANCHOR_HREF_REGEX = Regex("""(?i)<a\b([^>]*?)\s+href\s*=\s*(["'])(.*?)\2""")
        val IMG_SRC_REGEX = Regex("""(?i)<img\b([^>]*?)\s+src\s*=\s*(["'])(.*?)\2""")
        const val REMOTE_IMAGE_PLACEHOLDER = "/static/remote-image-blocked.svg"
    }
}

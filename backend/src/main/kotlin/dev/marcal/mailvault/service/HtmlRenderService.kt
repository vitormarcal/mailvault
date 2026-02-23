package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

@Service
class HtmlRenderService(
    private val messageHtmlRepository: MessageHtmlRepository,
    private val assetRepository: AssetRepository,
    private val htmlSanitizerService: HtmlSanitizerService,
) {
    fun render(messageId: String): String {
        val html =
            messageHtmlRepository.findByMessageId(messageId)
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

    private fun rewriteHtml(
        rawHtml: String,
        messageId: String,
    ): String {
        val withLinks =
            ANCHOR_HREF_REGEX.replace(rawHtml) { match ->
                val prefix = match.groups[1]?.value ?: ""
                val quote = match.groups[2]?.value ?: "\""
                val href = match.groups[3]?.value ?: ""
                val rewrittenHref = "/go?url=${urlEncode(href)}"
                val rel =
                    if (REL_ATTR_REGEX.containsMatchIn(prefix)) {
                        ""
                    } else {
                        " rel=${quote}noopener noreferrer$quote"
                    }
                "<a$prefix data-safe-href=$quote$rewrittenHref$quote$rel"
            }

        return IMG_SRC_REGEX.replace(withLinks) { match ->
            val prefix = match.groups[1]?.value ?: ""
            val quote = match.groups[2]?.value ?: "\""
            val src = (match.groups[3]?.value ?: "").trim()
            val rewrittenSrc =
                when {
                    src.startsWith("cid:", ignoreCase = true) -> {
                        val cid =
                            src
                                .removePrefix("cid:")
                                .removePrefix("CID:")
                                .trim()
                                .removePrefix("<")
                                .removeSuffix(">")
                        "/api/messages/$messageId/cid/$cid"
                    }

                    src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true) -> {
                        val normalized = normalizeRemoteUrl(src)
                        val downloaded = normalized?.let { assetRepository.findDownloadedByMessageAndOriginalUrl(messageId, it) }
                        if (downloaded?.storagePath != null) {
                            val fileName = Path.of(downloaded.storagePath).fileName.toString()
                            "/assets/$messageId/$fileName"
                        } else {
                            REMOTE_IMAGE_PLACEHOLDER
                        }
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

    private fun normalizeRemoteUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            null,
        ).toString()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val ANCHOR_HREF_REGEX = Regex("""(?i)<a\b([^>]*?)\s+href\s*=\s*(["'])(.*?)\2""")
        val REL_ATTR_REGEX = Regex("""(?i)\brel\s*=""")
        val IMG_SRC_REGEX = Regex("""(?i)<img\b([^>]*?)\s+src\s*=\s*(["'])(.*?)\2""")
        const val REMOTE_IMAGE_PLACEHOLDER = "/static/remote-image-blocked.svg"
    }
}

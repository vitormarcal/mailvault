package dev.marcal.mailvault.service

import org.owasp.html.HtmlPolicyBuilder
import org.owasp.html.PolicyFactory
import org.springframework.stereotype.Service
import java.util.regex.Pattern

@Service
class HtmlSanitizerService {
    private val policy: PolicyFactory =
        HtmlPolicyBuilder()
            .allowElements(
                "a", "p", "br", "div", "span", "table", "tr", "td", "th", "thead", "tbody", "img", "ul", "ol", "li",
                "b", "strong", "i", "em", "pre", "code", "blockquote", "hr",
            )
            .allowAttributes("data-safe-href").matching(SAFE_LINK_PATTERN).onElements("a")
            .allowAttributes("title").onElements("a")
            .allowAttributes("data-safe-src").matching(SAFE_IMAGE_PATTERN).onElements("img")
            .allowAttributes("data-original-src").matching(SAFE_REMOTE_PATTERN).onElements("img")
            .allowAttributes("alt", "title").onElements("img")
            .allowAttributes("title").onElements(
                "p", "div", "span", "table", "tr", "td", "th", "thead", "tbody", "ul", "ol", "li", "pre", "code", "blockquote",
            )
            .allowAttributes("colspan", "rowspan").onElements("td", "th")
            .toFactory()

    fun sanitize(html: String): String = policy.sanitize(html)

    private companion object {
        val SAFE_LINK_PATTERN: Pattern = Pattern.compile("(?i)^(https?://|/go\\?url=).+")
        val SAFE_IMAGE_PATTERN: Pattern = Pattern.compile("(?i)^(/api/messages/.+/cid/.+|/static/remote-image-blocked\\.svg)$")
        val SAFE_REMOTE_PATTERN: Pattern = Pattern.compile("(?i)^https?://.+")
    }
}

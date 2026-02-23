package dev.marcal.mailvault.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlSanitizerServiceTest {
    private val service = HtmlSanitizerService()

    @Test
    fun `removes dangerous tags and handlers`() {
        val input =
            """
            <div onclick=\"evil()\">safe</div>
            <script>alert('x')</script>
            <img src=\"https://example.com/a.png\" onerror=\"evil()\" />
            <a href=\"javascript:alert(1)\">bad</a>
            """.trimIndent()

        val sanitized = service.sanitize(input)

        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.contains("onerror", ignoreCase = true))
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `keeps allowed tags and attrs`() {
        val input = "<table><tr><td colspan=\"2\">c</td></tr></table><p title=\"t\">x</p>"
        val sanitized = service.sanitize(input)
        assertTrue(sanitized.contains("<p"))
        assertTrue(sanitized.contains("colspan=\"2\""))
        assertEquals(true, sanitized.contains("title=\"t\""))
    }
}

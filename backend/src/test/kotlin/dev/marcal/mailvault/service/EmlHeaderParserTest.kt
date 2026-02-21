package dev.marcal.mailvault.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmlHeaderParserTest {
    private val parser = EmlHeaderParser()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parses basic headers`() {
        val file = tempDir.resolve("basic.eml")
        Files.writeString(
            file,
            """
            From: Alice <alice@example.com>
            Date: Sat, 21 Feb 2026 20:00:00 -0300
            Subject: Hello
            Message-ID: <id-1@example.com>
            
            body line
            """.trimIndent(),
        )

        val headers = parser.parse(file)

        assertEquals("Alice <alice@example.com>", headers.from)
        assertEquals("Sat, 21 Feb 2026 20:00:00 -0300", headers.date)
        assertEquals("Hello", headers.subject)
        assertEquals("<id-1@example.com>", headers.messageId)
    }

    @Test
    fun `handles folded headers and ignores body-like headers`() {
        val file = tempDir.resolve("folded.eml")
        Files.writeString(
            file,
            """
            Subject: First line
             second line
            From: Bob <bob@example.com>
            
            Subject: should not be parsed from body
            """.trimIndent(),
        )

        val headers = parser.parse(file)

        assertEquals("First line second line", headers.subject)
        assertEquals("Bob <bob@example.com>", headers.from)
        assertNull(headers.date)
        assertNull(headers.messageId)
    }
}

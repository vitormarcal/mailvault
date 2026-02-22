package dev.marcal.mailvault.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageParseServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val parser = MessageParseService()

    @Test
    fun `parses headers and plain text for simple email`() {
        val file = tempDir.resolve("simple.eml")
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

        val parsed = parser.parse(file)

        assertEquals("Alice <alice@example.com>", parsed.fromRaw)
        assertEquals("Sat, 21 Feb 2026 20:00:00 -0300", parsed.dateRaw)
        assertEquals("Hello", parsed.subject)
        assertEquals("<id-1@example.com>", parsed.messageId)
        assertEquals("body line", parsed.textPlain?.trim())
        assertNull(parsed.htmlRaw)
        assertNull(parsed.htmlText)
        assertEquals(0, parsed.attachments.size)
    }

    @Test
    fun `parses multipart with html attachment and inline cid`() {
        val file = tempDir.resolve("multipart.eml")
        Files.writeString(
            file,
            """
            From: Bob <bob@example.com>
            Subject: Multi
            Message-ID: <id-2@example.com>
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mix"

            --mix
            Content-Type: multipart/alternative; boundary="alt"

            --alt
            Content-Type: text/plain; charset=UTF-8

            plain part
            --alt
            Content-Type: text/html; charset=UTF-8

            <html><body><p>html part</p><img src="cid:image-1"></body></html>
            --alt--
            --mix
            Content-Type: image/png; name="pixel.png"
            Content-Transfer-Encoding: base64
            Content-Disposition: inline; filename="pixel.png"
            Content-ID: <image-1>

            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO9fZQAAAABJRU5ErkJggg==
            --mix--
            """.trimIndent(),
        )

        val parsed = parser.parse(file)

        assertEquals("plain part", parsed.textPlain?.trim())
        assertEquals(true, parsed.htmlRaw?.contains("html part") == true)
        assertEquals(true, parsed.htmlText?.contains("html part") == true)
        assertEquals(1, parsed.attachments.size)
        val attachment = parsed.attachments.first()
        assertEquals("pixel.png", attachment.filename)
        assertEquals("image/png", attachment.contentType)
        assertEquals("image-1", attachment.inlineCid)
        assertNotNull(attachment.bytes)
        assertEquals(true, attachment.bytes.isNotEmpty())
    }

    @Test
    fun `parses text plain body even when disposition is inline`() {
        val file = tempDir.resolve("inline-text.eml")
        Files.writeString(
            file,
            """
            From: Inline <inline@example.com>
            Subject: Inline plain
            Message-ID: <id-3@example.com>
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mix"

            --mix
            Content-Disposition: inline
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            Ol=C3=A1 mundo
            --mix--
            """.trimIndent(),
        )

        val parsed = parser.parse(file)

        assertEquals("Olá mundo", parsed.textPlain?.trim())
        assertEquals(0, parsed.attachments.size)
    }
}

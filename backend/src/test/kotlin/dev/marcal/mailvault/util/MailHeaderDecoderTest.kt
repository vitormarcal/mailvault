package dev.marcal.mailvault.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MailHeaderDecoderTest {
    @Test
    fun `decodeHeader decodes encoded header`() {
        val decoded = MailHeaderDecoder.decodeHeader("=?UTF-8?Q?Ol=C3=A1_Mundo?=")
        assertEquals("Olá Mundo", decoded)
    }

    @Test
    fun `decodeHeader returns original value on invalid encoded header`() {
        val raw = "=?UTF-8?Q?broken"
        val decoded = MailHeaderDecoder.decodeHeader(raw)
        assertEquals(raw, decoded)
    }
}

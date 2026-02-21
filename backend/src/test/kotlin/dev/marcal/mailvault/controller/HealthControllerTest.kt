package dev.marcal.mailvault.controller

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HealthControllerTest {
    @Test
    fun `returns ok status`() {
        val controller = HealthController()
        assertEquals(mapOf("status" to "ok"), controller.health())
    }
}

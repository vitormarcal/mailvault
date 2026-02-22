package dev.marcal.mailvault.controller

import dev.marcal.mailvault.repository.MessageRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessagesControllerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var controller: MessagesController

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("messages-controller-test.db").toAbsolutePath().normalize()
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$dbPath"
        }
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                file_path TEXT NOT NULL UNIQUE,
                file_mtime_epoch INTEGER NOT NULL,
                file_size INTEGER NOT NULL,
                date_raw TEXT,
                subject TEXT,
                from_raw TEXT,
                message_id TEXT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS message_bodies (
                message_id TEXT PRIMARY KEY,
                text_plain TEXT
            )
            """.trimIndent(),
        )
        controller = MessagesController(MessageRepository(jdbcTemplate))
    }

    @Test
    fun `returns bad request when page is negative`() {
        val ex = assertFailsWith<ResponseStatusException> { controller.list(query = null, page = -1, size = 10) }
        assertEquals(400, ex.statusCode.value())
    }

    @Test
    fun `returns bad request when size is not positive`() {
        val ex = assertFailsWith<ResponseStatusException> { controller.list(query = null, page = 0, size = 0) }
        assertEquals(400, ex.statusCode.value())
    }

    @Test
    fun `returns not found when id does not exist`() {
        val ex = assertFailsWith<ResponseStatusException> { controller.detail("missing") }
        assertEquals(404, ex.statusCode.value())
    }
}

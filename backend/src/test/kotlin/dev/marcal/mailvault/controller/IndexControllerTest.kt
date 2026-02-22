package dev.marcal.mailvault.controller

import dev.marcal.mailvault.service.EmlHeaderParser
import dev.marcal.mailvault.service.IndexResult
import dev.marcal.mailvault.service.IndexerService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexControllerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var controller: IndexController

    @BeforeEach
    fun setUp() {
        val emailsDir = tempDir.resolve("emails")
        Files.createDirectories(emailsDir)

        val dbPath = tempDir.resolve("index-controller-test.db").toAbsolutePath().normalize()
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
        controller = IndexController(IndexerService(jdbcTemplate, EmlHeaderParser(), emailsDir.toString()))
    }

    @Test
    fun `returns counters for valid directory`() {
        val result = controller.index()
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 0), result)
    }

    @Test
    fun `maps invalid configured root dir to bad request`() {
        controller = IndexController(IndexerService(jdbcTemplate, EmlHeaderParser(), tempDir.resolve("missing").toString()))

        val ex =
            assertFailsWith<ResponseStatusException> {
                controller.index()
            }
        assertEquals(400, ex.statusCode.value())
    }
}

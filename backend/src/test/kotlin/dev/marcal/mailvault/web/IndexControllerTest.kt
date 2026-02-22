package dev.marcal.mailvault.web

import dev.marcal.mailvault.service.IndexResult
import dev.marcal.mailvault.service.IndexerService
import dev.marcal.mailvault.service.MessageParseService
import dev.marcal.mailvault.service.AttachmentStorageService
import dev.marcal.mailvault.repository.IndexWriteRepository
import dev.marcal.mailvault.config.MailVaultProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
                date_epoch INTEGER,
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
                text_plain TEXT,
                html_raw TEXT,
                html_sanitized TEXT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                filename TEXT,
                content_type TEXT,
                size INTEGER,
                inline_cid TEXT NULL,
                storage_path TEXT NOT NULL,
                sha256 TEXT NOT NULL
            )
            """.trimIndent(),
        )
        controller =
            IndexController(
                IndexerService(
                    IndexWriteRepository(jdbcTemplate),
                    MessageParseService(),
                    AttachmentStorageService(),
                    MailVaultProperties(
                        rootEmailsDir = emailsDir.toString(),
                        storageDir = tempDir.resolve("storage").toString(),
                    ),
                ),
            )
    }

    @Test
    fun `returns counters for valid directory`() {
        val result = controller.index()
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 0), result)
    }

    @Test
    fun `maps invalid configured root dir to bad request`() {
        controller =
            IndexController(
                IndexerService(
                    IndexWriteRepository(jdbcTemplate),
                    MessageParseService(),
                    AttachmentStorageService(),
                    MailVaultProperties(
                        rootEmailsDir = tempDir.resolve("missing").toString(),
                        storageDir = tempDir.resolve("storage").toString(),
                    ),
                ),
            )

        val ex = assertFailsWith<IllegalArgumentException> { controller.index() }
        assertEquals("Invalid rootDir: ${tempDir.resolve("missing")}", ex.message)
    }
}

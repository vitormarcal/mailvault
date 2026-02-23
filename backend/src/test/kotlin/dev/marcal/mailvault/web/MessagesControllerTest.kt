package dev.marcal.mailvault.web

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.AttachmentRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.repository.MessageRepository
import dev.marcal.mailvault.service.AssetFreezeService
import dev.marcal.mailvault.service.AttachmentService
import dev.marcal.mailvault.service.FreezePendingService
import dev.marcal.mailvault.service.HtmlRenderService
import dev.marcal.mailvault.service.HtmlSanitizerService
import dev.marcal.mailvault.service.MessageQueryService
import dev.marcal.mailvault.util.ResourceNotFoundException
import dev.marcal.mailvault.util.ValidationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
        val dataSource =
            DriverManagerDataSource().apply {
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
                subject_display TEXT,
                from_display TEXT,
                from_email TEXT,
                from_name TEXT,
                freeze_ignored INTEGER NOT NULL DEFAULT 0,
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
                html_text TEXT,
                html_sanitized TEXT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS assets (
                id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                original_url TEXT NOT NULL,
                storage_path TEXT,
                content_type TEXT,
                size INTEGER,
                sha256 TEXT,
                status TEXT NOT NULL,
                downloaded_at TEXT,
                error TEXT,
                UNIQUE(message_id, original_url)
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
        val messageHtmlRepository = MessageHtmlRepository(jdbcTemplate)
        val assetRepository = AssetRepository(jdbcTemplate)
        val messageRepository = MessageRepository(jdbcTemplate)
        val htmlRenderService = HtmlRenderService(messageHtmlRepository, assetRepository, HtmlSanitizerService())
        val assetFreezeService =
            AssetFreezeService(
                messageHtmlRepository,
                assetRepository,
                MailVaultProperties(),
                htmlRenderService,
            )
        controller =
            MessagesController(
                MessageQueryService(messageRepository),
                htmlRenderService,
                AttachmentService(AttachmentRepository(jdbcTemplate), MailVaultProperties(storageDir = tempDir.toString())),
                assetFreezeService,
                FreezePendingService(messageRepository, assetFreezeService),
            )
    }

    @Test
    fun `returns bad request when page is negative`() {
        val ex =
            assertFailsWith<ValidationException> {
                controller.list(
                    query = null,
                    year = null,
                    hasAttachments = null,
                    hasHtml = null,
                    hasFrozenImages = null,
                    page = -1,
                    size = 10,
                )
            }
        assertEquals("page must be >= 0", ex.message)
    }

    @Test
    fun `returns bad request when size is not positive`() {
        val ex =
            assertFailsWith<ValidationException> {
                controller.list(
                    query = null,
                    year = null,
                    hasAttachments = null,
                    hasHtml = null,
                    hasFrozenImages = null,
                    page = 0,
                    size = 0,
                )
            }
        assertEquals("size must be > 0", ex.message)
    }

    @Test
    fun `returns not found when id does not exist`() {
        val ex = assertFailsWith<ResourceNotFoundException> { controller.detail("missing") }
        assertEquals("message not found", ex.message)
    }

    @Test
    fun `toggles freeze ignored flag`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-1",
            "/tmp/m-1.eml",
            1L,
            1L,
        )

        val enabled = controller.setFreezeIgnored("m-1", true)
        val disabled = controller.setFreezeIgnored("m-1", false)

        assertEquals("m-1", enabled.id)
        assertEquals(true, enabled.freezeIgnored)
        assertEquals(false, disabled.freezeIgnored)
    }
}

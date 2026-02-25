package dev.marcal.mailvault.web

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AppMetaRepository
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.IndexWriteRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.repository.MessageRepository
import dev.marcal.mailvault.service.AssetFreezeService
import dev.marcal.mailvault.service.AttachmentStorageService
import dev.marcal.mailvault.service.HtmlRenderService
import dev.marcal.mailvault.service.HtmlSanitizerService
import dev.marcal.mailvault.service.IndexJobService
import dev.marcal.mailvault.service.IndexerService
import dev.marcal.mailvault.service.MessageParseService
import dev.marcal.mailvault.service.UiFreezeOnIndexService
import dev.marcal.mailvault.util.ResourceNotFoundException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
        val dataSource =
            DriverManagerDataSource().apply {
                setDriverClassName("org.sqlite.JDBC")
                url = "jdbc:sqlite:$dbPath"
            }
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS app_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
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
                freeze_last_reason TEXT,
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
                security_blocked INTEGER NOT NULL DEFAULT 0,
                UNIQUE(message_id, original_url)
            )
            """.trimIndent(),
        )
        val properties =
            MailVaultProperties(
                rootEmailsDir = emailsDir.toString(),
                storageDir = tempDir.resolve("storage").toString(),
            )
        val messageHtmlRepository = MessageHtmlRepository(jdbcTemplate)
        val assetRepository = AssetRepository(jdbcTemplate)
        val htmlRenderService = HtmlRenderService(messageHtmlRepository, assetRepository, HtmlSanitizerService())
        controller =
            IndexController(
                IndexJobService(
                    IndexerService(
                        IndexWriteRepository(jdbcTemplate),
                        MessageParseService(),
                        AttachmentStorageService(),
                        assetRepository,
                        AssetFreezeService(
                            messageHtmlRepository,
                            assetRepository,
                            MessageRepository(jdbcTemplate),
                            properties,
                            htmlRenderService,
                        ),
                        UiFreezeOnIndexService(AppMetaRepository(jdbcTemplate)),
                        properties,
                    ),
                ),
            )
    }

    @Test
    fun `starts async job and eventually returns counters`() {
        val started = controller.startIndex()
        assertNotNull(started.jobId)
        assertEquals("RUNNING", started.status)

        val result = waitForTerminal(started.jobId)
        assertEquals("SUCCEEDED", result.status)
        assertEquals(0, result.totalFiles)
        assertEquals(0, result.processedFiles)
        assertEquals(null, result.progressPercent)
        val counters = assertNotNull(result.result)
        assertEquals(0, counters.inserted)
        assertEquals(0, counters.updated)
        assertEquals(0, counters.skipped)
    }

    @Test
    fun `invalid configured root dir fails job status without blocking start`() {
        controller =
            IndexController(
                IndexJobService(
                    IndexerService(
                        IndexWriteRepository(jdbcTemplate),
                        MessageParseService(),
                        AttachmentStorageService(),
                        AssetRepository(jdbcTemplate),
                        AssetFreezeService(
                            MessageHtmlRepository(jdbcTemplate),
                            AssetRepository(jdbcTemplate),
                            MessageRepository(jdbcTemplate),
                            MailVaultProperties(
                                rootEmailsDir = tempDir.resolve("missing").toString(),
                                storageDir = tempDir.resolve("storage").toString(),
                            ),
                            HtmlRenderService(
                                MessageHtmlRepository(jdbcTemplate),
                                AssetRepository(jdbcTemplate),
                                HtmlSanitizerService(),
                            ),
                        ),
                        UiFreezeOnIndexService(AppMetaRepository(jdbcTemplate)),
                        MailVaultProperties(
                            rootEmailsDir = tempDir.resolve("missing").toString(),
                            storageDir = tempDir.resolve("storage").toString(),
                        ),
                    ),
                ),
            )

        val started = controller.startIndex()
        val result = waitForTerminal(started.jobId)

        assertEquals("FAILED", result.status)
        assertEquals(true, (result.error ?: "").contains("Invalid rootDir:"))
    }

    @Test
    fun `missing job id returns not found`() {
        assertFailsWith<ResourceNotFoundException> { controller.getIndexJob("missing-id") }
    }

    private fun waitForTerminal(jobId: String): dev.marcal.mailvault.api.IndexJobStatusResponse {
        repeat(60) {
            val status = controller.getIndexJob(jobId)
            if (status.status != "RUNNING") {
                return status
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for job status jobId=$jobId")
    }
}

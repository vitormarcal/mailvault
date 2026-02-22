package dev.marcal.mailvault.service

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class AssetFreezeServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: AssetFreezeService

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("asset-freeze-test.db").toAbsolutePath().normalize()
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$dbPath"
        }
        val jdbcTemplate = JdbcTemplate(dataSource)
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

        val messageHtmlRepository = MessageHtmlRepository(jdbcTemplate)
        val assetRepository = AssetRepository(jdbcTemplate)
        val htmlRenderService = HtmlRenderService(messageHtmlRepository, assetRepository, HtmlSanitizerService())
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)
        service =
            AssetFreezeService(
                messageHtmlRepository = messageHtmlRepository,
                assetRepository = assetRepository,
                mailVaultProperties = MailVaultProperties(storageDir = storageDir.toString()),
                htmlRenderService = htmlRenderService,
            )
    }

    @Test
    fun `validator rejects redirect target to loopback ipv4`() {
        assertFailsWith<IllegalArgumentException> {
            service.validateRemoteUri("http://127.0.0.1/image.png")
        }
    }

    @Test
    fun `validator rejects url with userinfo`() {
        assertFailsWith<IllegalArgumentException> {
            service.validateRemoteUri("https://user:pass@example.com/image.png")
        }
    }
}

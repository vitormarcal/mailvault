package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.repository.AssetRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlRenderServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var service: HtmlRenderService

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("html-render-test.db").toAbsolutePath().normalize()
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

        service = HtmlRenderService(MessageHtmlRepository(jdbcTemplate), AssetRepository(jdbcTemplate), HtmlSanitizerService())
    }

    @Test
    fun `rewrites links blocks remote images and rewrites cid then sanitizes and caches`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "m1", "/tmp/1.eml", 1L, 1L, null, "s", "f", "<m1@x>",
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, text_plain, html_raw, html_sanitized)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m1",
            null,
            """
            <div>
              <a href="https://example.com/page">open</a>
              <img src="cid:img-1" alt="cid" />
              <img src="https://example.com/remote.png" alt="remote" />
              <script>alert('x')</script>
            </div>
            """.trimIndent(),
            null,
        )

        val rendered = service.render("m1")

        assertTrue(rendered.contains("/go?url"))
        assertTrue(rendered.contains("/api/messages/m1/cid/img-1"))
        assertTrue(rendered.contains("/static/remote-image-blocked.svg"))
        assertTrue(rendered.contains("data-original-src=\"https://example.com/remote.png\""))
        assertEquals(false, rendered.contains("<script", ignoreCase = true))

        val cached = jdbcTemplate.queryForObject(
            "SELECT html_sanitized FROM message_bodies WHERE message_id = ?",
            String::class.java,
            "m1",
        )
        assertEquals(rendered, cached)
    }

    @Test
    fun `uses downloaded frozen asset when available`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "m2", "/tmp/2.eml", 1L, 1L, null, "s", "f", "<m2@x>",
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, text_plain, html_raw, html_sanitized)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m2",
            null,
            """<img src="https://example.com/logo.png#frag" alt="logo" />""",
            null,
        )
        jdbcTemplate.update(
            """
            INSERT INTO assets(id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "a1",
            "m2",
            "https://example.com/logo.png",
            "/tmp/assets/m2/abc.png",
            "image/png",
            10L,
            "abc",
            "DOWNLOADED",
            "2026-02-22T10:00:00Z",
            null,
        )

        val rendered = service.render("m2")

        assertTrue(rendered.contains("/assets/m2/abc.png"))
        assertEquals(false, rendered.contains("/static/remote-image-blocked.svg"))
    }
}

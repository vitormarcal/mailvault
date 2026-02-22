package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.MessageHtmlRepository
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

        service = HtmlRenderService(MessageHtmlRepository(jdbcTemplate), HtmlSanitizerService())
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
}

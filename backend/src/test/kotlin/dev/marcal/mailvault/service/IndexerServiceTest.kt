package dev.marcal.mailvault.service

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

class IndexerServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var service: IndexerService
    private lateinit var rootDir: Path
    private lateinit var storageDir: Path

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("indexer-unit-test.db").toAbsolutePath().normalize()
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

        rootDir = tempDir.resolve("emails")
        storageDir = tempDir.resolve("storage")
        Files.createDirectories(rootDir)
        Files.createDirectories(storageDir)

        service = IndexerService(jdbcTemplate, MessageParseService(), rootDir.toString(), storageDir.toString())
    }

    @Test
    fun `throws when configured root dir is invalid`() {
        service = IndexerService(jdbcTemplate, MessageParseService(), tempDir.resolve("missing").toString(), storageDir.toString())

        assertFailsWith<IllegalArgumentException> {
            service.index()
        }
    }

    @Test
    fun `ignores non eml files`() {
        Files.writeString(rootDir.resolve("notes.txt"), "plain file")

        val result = service.index()
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 0), result)
    }

    @Test
    fun `keeps stable id for same file path and message id`() {
        val eml = rootDir.resolve("a.eml")

        Files.writeString(
            eml,
            """
            From: A <a@x.com>
            Subject: First
            Message-ID: <stable@x>

            Body
            """.trimIndent(),
        )
        service.index()
        val firstId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE file_path = ?",
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )

        Thread.sleep(5)
        Files.writeString(
            eml,
            """
            From: A <a@x.com>
            Subject: Second
            Message-ID: <stable@x>

            Body changed
            """.trimIndent(),
        )
        service.index()
        val secondId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE file_path = ?",
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )

        assertEquals(firstId, secondId)
    }

    @Test
    fun `stores text plain for simple email`() {
        val eml = rootDir.resolve("simple.eml")
        Files.writeString(
            eml,
            """
            From: A <a@x.com>
            Subject: Plain
            Message-ID: <plain@x>

            Hello body line
            """.trimIndent(),
        )

        service.index()

        val textPlain = jdbcTemplate.queryForObject(
            """
            SELECT mb.text_plain
            FROM message_bodies mb
            JOIN messages m ON m.id = mb.message_id
            WHERE m.file_path = ?
            """.trimIndent(),
            String::class.java,
            eml.toAbsolutePath().normalize().toString(),
        )
        assertEquals("Hello body line", textPlain?.trim())
    }

    @Test
    fun `parses multipart plain html and attachments including inline cid`() {
        val eml = rootDir.resolve("multipart.eml")
        Files.writeString(
            eml,
            """
            From: A <a@x.com>
            Subject: Multipart
            Message-ID: <multi@x>
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mix"

            --mix
            Content-Type: multipart/alternative; boundary="alt"

            --alt
            Content-Type: text/plain; charset=UTF-8

            Plain part
            --alt
            Content-Type: text/html; charset=UTF-8

            <html><body><p>Hello HTML</p><img src="cid:img-1"></body></html>
            --alt--
            --mix
            Content-Type: text/plain; name="note.txt"
            Content-Disposition: attachment; filename="note.txt"

            attachment-content
            --mix
            Content-Type: image/png; name="pixel.png"
            Content-Transfer-Encoding: base64
            Content-Disposition: inline; filename="pixel.png"
            Content-ID: <img-1>

            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO9fZQAAAABJRU5ErkJggg==
            --mix--
            """.trimIndent(),
        )

        val result = service.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), result)

        val textPlain = jdbcTemplate.queryForObject(
            """
            SELECT mb.text_plain
            FROM message_bodies mb
            JOIN messages m ON m.id = mb.message_id
            WHERE m.file_path = ?
            """.trimIndent(),
            String::class.java,
            eml.toAbsolutePath().normalize().toString(),
        )
        assertEquals("Plain part", textPlain?.trim())

        val htmlRaw = jdbcTemplate.queryForObject(
            """
            SELECT mb.html_raw
            FROM message_bodies mb
            JOIN messages m ON m.id = mb.message_id
            WHERE m.file_path = ?
            """.trimIndent(),
            String::class.java,
            eml.toAbsolutePath().normalize().toString(),
        )
        assertEquals(true, htmlRaw?.contains("Hello HTML") == true)

        val attachmentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachments", Int::class.java)
        assertEquals(2, attachmentCount)

        val inlineCid = jdbcTemplate.queryForObject(
            "SELECT inline_cid FROM attachments WHERE filename = 'pixel.png'",
            String::class.java,
        )
        assertEquals("img-1", inlineCid)

        val storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM attachments WHERE filename = 'note.txt'",
            String::class.java,
        )
        assertNotNull(storagePath)
        assertEquals(true, Files.exists(Path.of(storagePath)))
    }
}

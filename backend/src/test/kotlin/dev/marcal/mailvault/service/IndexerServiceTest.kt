package dev.marcal.mailvault.service

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AppMetaRepository
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.IndexWriteRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.repository.MessageRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

        rootDir = tempDir.resolve("emails")
        storageDir = tempDir.resolve("storage")
        Files.createDirectories(rootDir)
        Files.createDirectories(storageDir)

        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                ),
            )
    }

    @Test
    fun `throws when configured root dir is invalid`() {
        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = tempDir.resolve("missing").toString(),
                    storageDir = storageDir.toString(),
                ),
            )

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

        val subjectDisplay =
            jdbcTemplate.queryForObject(
                "SELECT subject_display FROM messages WHERE file_path = ?",
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )
        val fromDisplay =
            jdbcTemplate.queryForObject(
                "SELECT from_display FROM messages WHERE file_path = ?",
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )
        val fromEmail =
            jdbcTemplate.queryForObject(
                "SELECT from_email FROM messages WHERE file_path = ?",
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )

        val textPlain =
            jdbcTemplate.queryForObject(
                """
                SELECT mb.text_plain
                FROM message_bodies mb
                JOIN messages m ON m.id = mb.message_id
                WHERE m.file_path = ?
                """.trimIndent(),
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )
        assertEquals("Plain", subjectDisplay)
        assertEquals("A <a@x.com>", fromDisplay)
        assertEquals("a@x.com", fromEmail)
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

        val textPlain =
            jdbcTemplate.queryForObject(
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

        val htmlRaw =
            jdbcTemplate.queryForObject(
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

        val htmlText =
            jdbcTemplate.queryForObject(
                """
                SELECT mb.html_text
                FROM message_bodies mb
                JOIN messages m ON m.id = mb.message_id
                WHERE m.file_path = ?
                """.trimIndent(),
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )
        assertEquals("Hello HTML", htmlText?.trim())

        val attachmentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachments", Int::class.java)
        assertEquals(2, attachmentCount)

        val inlineCid =
            jdbcTemplate.queryForObject(
                "SELECT inline_cid FROM attachments WHERE filename = 'pixel.png'",
                String::class.java,
            )
        assertEquals("img-1", inlineCid)

        val storagePath =
            jdbcTemplate.queryForObject(
                "SELECT storage_path FROM attachments WHERE filename = 'note.txt'",
                String::class.java,
            )
        assertNotNull(storagePath)
        assertEquals(true, Files.exists(Path.of(storagePath)))
    }

    @Test
    fun `reprocesses unchanged file when existing body is empty`() {
        val eml = rootDir.resolve("legacy-empty-body.eml")
        Files.writeString(
            eml,
            """
            From: Legacy <legacy@x.com>
            Subject: Legacy
            Message-ID: <legacy@x>

            Corpo legado
            """.trimIndent(),
        )

        val normalizedPath = eml.toAbsolutePath().normalize().toString()
        val mtime = Files.getLastModifiedTime(eml).toMillis()
        val size = Files.size(eml)
        val messageId = sha256Hex("<legacy@x>|$normalizedPath")

        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            messageId,
            normalizedPath,
            mtime,
            size,
            null,
            "Legacy",
            "Legacy <legacy@x.com>",
            "<legacy@x>",
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, text_plain, html_raw, html_sanitized)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            messageId,
            null,
            null,
            null,
        )

        val result = service.index()
        assertEquals(IndexResult(inserted = 0, updated = 1, skipped = 0), result)

        val textPlain =
            jdbcTemplate.queryForObject(
                "SELECT text_plain FROM message_bodies WHERE message_id = ?",
                String::class.java,
                messageId,
            )
        assertEquals("Corpo legado", textPlain?.trim())
    }

    @Test
    fun `freeze on index is best effort and does not fail indexing`() {
        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                    freezeOnIndexConcurrency = 2,
                ),
            )
        enableFreezeOnIndex()

        val eml = rootDir.resolve("remote-html.eml")
        Files.writeString(
            eml,
            """
            From: Freeze <freeze@x.com>
            Subject: Remote html
            Message-ID: <freeze-1@x>
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="http://localhost/private.png"></body></html>
            --b1--
            """.trimIndent(),
        )

        val result = service.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), result)

        val assetCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assets", Int::class.java)
        assertEquals(1, assetCount)
        val status = jdbcTemplate.queryForObject("SELECT status FROM assets LIMIT 1", String::class.java)
        assertEquals("SKIPPED", status)
    }

    @Test
    fun `freeze on index skips message that already has downloaded assets`() {
        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                    freezeOnIndexConcurrency = 2,
                ),
            )
        enableFreezeOnIndex()

        val eml = rootDir.resolve("remote-html-2.eml")
        Files.writeString(
            eml,
            """
            From: Freeze <freeze@x.com>
            Subject: Remote html
            Message-ID: <freeze-2@x>
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="https://cdn.example.com/img.png"></body></html>
            --b1--
            """.trimIndent(),
        )

        val normalizedPath = eml.toAbsolutePath().normalize().toString()
        val messageId = sha256Hex("<freeze-2@x>|$normalizedPath")
        jdbcTemplate.update(
            """
            INSERT INTO assets(id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "a-existing",
            messageId,
            "https://cdn.example.com/other.png",
            "/tmp/other.png",
            "image/png",
            10L,
            "sha",
            "DOWNLOADED",
            "2026-02-22T10:00:00Z",
            null,
        )

        val result = service.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), result)

        val assetCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assets", Int::class.java)
        assertEquals(1, assetCount)
    }

    @Test
    fun `freeze on index attempts all candidate messages when enabled`() {
        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                    freezeOnIndexConcurrency = 2,
                ),
            )
        enableFreezeOnIndex()

        Files.writeString(
            rootDir.resolve("freeze-all-1.eml"),
            """
            From: Freeze 1 <freeze1@x.com>
            Subject: Remote 1
            Message-ID: <freeze-all-1@x>
            MIME-Version: 1.0
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="http://localhost/private-1.png"></body></html>
            """.trimIndent(),
        )
        Files.writeString(
            rootDir.resolve("freeze-all-2.eml"),
            """
            From: Freeze 2 <freeze2@x.com>
            Subject: Remote 2
            Message-ID: <freeze-all-2@x>
            MIME-Version: 1.0
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="http://localhost/private-2.png"></body></html>
            """.trimIndent(),
        )

        val result = service.index()
        assertEquals(IndexResult(inserted = 2, updated = 0, skipped = 0), result)

        val skippedAssets =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE status = 'SKIPPED'",
                Int::class.java,
            )
        assertEquals(2, skippedAssets)
    }

    @Test
    fun `freeze on index does not run for unchanged already indexed message in incremental mode`() {
        val eml = rootDir.resolve("already-indexed.eml")
        Files.writeString(
            eml,
            """
            From: Freeze <freeze@x.com>
            Date: Sat, 21 Feb 2026 20:00:00 -0300
            Subject: Existing
            Message-ID: <freeze-existing@x>
            MIME-Version: 1.0
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="http://localhost/private-existing.png"></body></html>
            """.trimIndent(),
        )

        val first = service.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), first)
        val before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assets", Int::class.java)
        assertEquals(0, before)

        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                    freezeOnIndexConcurrency = 2,
                ),
            )
        enableFreezeOnIndex()

        val second = service.index()
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 1), second)

        val skippedAssets =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE status = 'SKIPPED'",
                Int::class.java,
            )
        assertEquals(0, skippedAssets)
    }

    @Test
    fun `freeze on reindex runs for unchanged already indexed message in full mode`() {
        val eml = rootDir.resolve("already-indexed-full.eml")
        Files.writeString(
            eml,
            """
            From: Freeze <freeze@x.com>
            Date: Sat, 21 Feb 2026 20:00:00 -0300
            Subject: Existing full
            Message-ID: <freeze-existing-full@x>
            MIME-Version: 1.0
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="http://localhost/private-existing-full.png"></body></html>
            """.trimIndent(),
        )

        val first = service.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), first)
        val before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM assets", Int::class.java)
        assertEquals(0, before)

        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                    freezeOnIndexConcurrency = 2,
                ),
            )
        enableFreezeOnIndex()

        val second = service.reindex(null)
        assertEquals(IndexResult(inserted = 0, updated = 1, skipped = 0), second)

        val skippedAssets =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE status = 'SKIPPED'",
                Int::class.java,
            )
        assertEquals(1, skippedAssets)
    }

    @Test
    fun `freeze on index skips unchanged message marked as freeze ignored`() {
        val eml = rootDir.resolve("already-indexed-ignored.eml")
        Files.writeString(
            eml,
            """
            From: Freeze <freeze@x.com>
            Date: Sat, 21 Feb 2026 20:00:00 -0300
            Subject: Existing ignored
            Message-ID: <freeze-existing-ignored@x>
            MIME-Version: 1.0
            Content-Type: text/html; charset=UTF-8

            <html><body><img src="http://localhost/private-existing-ignored.png"></body></html>
            """.trimIndent(),
        )

        val first = service.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), first)
        jdbcTemplate.update("UPDATE messages SET freeze_ignored = 1 WHERE message_id = ?", "<freeze-existing-ignored@x>")

        service =
            createIndexerService(
                MailVaultProperties(
                    rootEmailsDir = rootDir.toString(),
                    storageDir = storageDir.toString(),
                    freezeOnIndexConcurrency = 2,
                ),
            )
        enableFreezeOnIndex()

        val second = service.index()
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 1), second)

        val skippedAssets =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE status = 'SKIPPED'",
                Int::class.java,
            )
        assertEquals(0, skippedAssets)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun createIndexerService(properties: MailVaultProperties): IndexerService {
        val assetRepository = AssetRepository(jdbcTemplate)
        val messageHtmlRepository = MessageHtmlRepository(jdbcTemplate)
        val messageRepository = MessageRepository(jdbcTemplate)
        val htmlRenderService = HtmlRenderService(messageHtmlRepository, assetRepository, HtmlSanitizerService())
        val uiFreezeOnIndexService = UiFreezeOnIndexService(AppMetaRepository(jdbcTemplate))
        val assetFreezeService =
            AssetFreezeService(messageHtmlRepository, assetRepository, messageRepository, properties, htmlRenderService)
        return IndexerService(
            IndexWriteRepository(jdbcTemplate),
            MessageParseService(),
            AttachmentStorageService(),
            assetRepository,
            assetFreezeService,
            uiFreezeOnIndexService,
            properties,
        )
    }

    private fun enableFreezeOnIndex() {
        jdbcTemplate.update(
            """
            INSERT INTO app_meta(key, value)
            VALUES ('ui.freezeOnIndex', 'true')
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        )
    }
}

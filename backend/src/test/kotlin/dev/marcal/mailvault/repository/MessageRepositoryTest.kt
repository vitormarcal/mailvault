package dev.marcal.mailvault.repository

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var repository: MessageRepository

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("messages-test.db").toAbsolutePath().normalize()
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$dbPath"
        }

        jdbcTemplate = JdbcTemplate(dataSource)
        repository = MessageRepository(jdbcTemplate)

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
                inline_cid TEXT,
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
                error TEXT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                id UNINDEXED,
                subject,
                from_raw,
                text_plain,
                html_text
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
                VALUES (
                    new.id,
                    COALESCE(new.subject, ''),
                    COALESCE(new.from_raw, ''),
                    COALESCE((SELECT text_plain FROM message_bodies WHERE message_id = new.id), ''),
                    COALESCE((SELECT html_text FROM message_bodies WHERE message_id = new.id), '')
                );
            END
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                DELETE FROM messages_fts
                WHERE id = old.id;
            END
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                DELETE FROM messages_fts
                WHERE id = old.id;

                INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
                VALUES (
                    new.id,
                    COALESCE(new.subject, ''),
                    COALESCE(new.from_raw, ''),
                    COALESCE((SELECT text_plain FROM message_bodies WHERE message_id = new.id), ''),
                    COALESCE((SELECT html_text FROM message_bodies WHERE message_id = new.id), '')
                );
            END
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS message_bodies_ai_fts AFTER INSERT ON message_bodies BEGIN
                DELETE FROM messages_fts
                WHERE id = new.message_id;

                INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
                SELECT m.id, COALESCE(m.subject, ''), COALESCE(m.from_raw, ''), COALESCE(new.text_plain, ''), COALESCE(new.html_text, '')
                FROM messages m
                WHERE m.id = new.message_id;
            END
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS message_bodies_au_fts AFTER UPDATE ON message_bodies BEGIN
                DELETE FROM messages_fts
                WHERE id = new.message_id;

                INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
                SELECT m.id, COALESCE(m.subject, ''), COALESCE(m.from_raw, ''), COALESCE(new.text_plain, ''), COALESCE(new.html_text, '')
                FROM messages m
                WHERE m.id = new.message_id;
            END
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS message_bodies_ad_fts AFTER DELETE ON message_bodies BEGIN
                DELETE FROM messages_fts
                WHERE id = old.message_id;

                INSERT INTO messages_fts(id, subject, from_raw, text_plain, html_text)
                SELECT m.id, COALESCE(m.subject, ''), COALESCE(m.from_raw, ''), '', ''
                FROM messages m
                WHERE m.id = old.message_id;
            END
            """.trimIndent(),
        )
        jdbcTemplate.update("DELETE FROM messages")
        jdbcTemplate.update("DELETE FROM messages_fts")
        jdbcTemplate.update("DELETE FROM message_bodies")
        jdbcTemplate.update("DELETE FROM attachments")
        jdbcTemplate.update("DELETE FROM assets")

        insert("a", "/tmp/a.eml", 1000, 10, "2024-01-01T10:00:00Z", "Hello", "Alice <alice@x.com>", "<a@x>")
        insert("b", "/tmp/b.eml", 3000, 20, "2024-02-01T10:00:00Z", "Report", "Bob <bob@x.com>", "<b@x>")
        insert("c", "/tmp/c.eml", 4000, 30, null, "No date", "Charlie <charlie@x.com>", "<c@x>")
    }

    @Test
    fun `list paginates and orders by file_mtime_epoch desc`() {
        val page = list(query = null, page = 0, size = 2)

        assertEquals(3, page.total)
        assertEquals(2, page.items.size)
        assertEquals("c", page.items[0].id)
        assertEquals("b", page.items[1].id)
    }

    @Test
    fun `list filters by subject and from_raw`() {
        val bySubject = list(query = "report", page = 0, size = 50)
        val byFrom = list(query = "charlie", page = 0, size = 50)

        assertEquals(1, bySubject.total)
        assertEquals("b", bySubject.items.first().id)
        assertEquals(1, byFrom.total)
        assertEquals("c", byFrom.items.first().id)
    }

    @Test
    fun `list query reddit matches subject and from using fts`() {
        insert("r1", "/tmp/r1.eml", 5000, 10, "2024-03-01T10:00:00Z", "Daily reddit digest", "News Bot <bot@x.com>", "<r1@x>")
        insert("r2", "/tmp/r2.eml", 6000, 10, "2024-03-02T10:00:00Z", "Other topic", "Reddit Alerts <alerts@x.com>", "<r2@x>")

        val result = list(query = "reddit", page = 0, size = 50)
        val ids = result.items.map { it.id }.toSet()

        assertEquals(2, result.total)
        assertEquals(setOf("r1", "r2"), ids)
    }

    @Test
    fun `list supports offset pagination`() {
        val secondPage = list(query = null, page = 1, size = 1)
        assertEquals(3, secondPage.total)
        assertEquals(1, secondPage.items.size)
        assertEquals("b", secondPage.items.first().id)
    }

    @Test
    fun `blank query is treated as no filter`() {
        val blank = list(query = "   ", page = 0, size = 50)
        assertEquals(3, blank.total)
    }

    @Test
    fun `fallback ordering by mtime applies when query is empty and date is null`() {
        insert("d", "/tmp/d.eml", 5000, 40, null, "No date newer", "Dave <dave@x.com>", "<d@x>")

        val page = list(query = null, page = 0, size = 50)
        assertEquals(4, page.total)
        assertEquals("d", page.items[0].id)
        assertEquals("c", page.items[1].id)
    }

    @Test
    fun `list query finds content from text_plain`() {
        jdbcTemplate.update("INSERT INTO message_bodies(message_id, text_plain) VALUES (?, ?)", "b", "meeting follow up")

        val result = list(query = "\"meeting follow up\"", page = 0, size = 50)
        assertEquals(1, result.total)
        assertEquals("b", result.items.first().id)
    }

    @Test
    fun `list supports combined filters with query`() {
        jdbcTemplate.update("UPDATE messages SET date_epoch = ? WHERE id = ?", 1706781600L, "b")
        jdbcTemplate.update("INSERT INTO message_bodies(message_id, text_plain, html_raw, html_text) VALUES (?, ?, ?, ?)", "b", "budget plan", "<p>budget plan</p>", "budget plan")
        jdbcTemplate.update(
            """
            INSERT INTO attachments(id, message_id, filename, content_type, size, inline_cid, storage_path, sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "att-1",
            "b",
            "report.pdf",
            "application/pdf",
            10,
            null,
            "/tmp/report.pdf",
            "sha",
        )
        jdbcTemplate.update(
            """
            INSERT INTO assets(id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "asset-1",
            "b",
            "https://example.com/budget.png",
            "/tmp/budget.png",
            "image/png",
            20,
            "sha2",
            "DOWNLOADED",
            "2026-02-22T10:00:00Z",
            null,
        )

        val filtered =
            list(
                query = "budget",
                year = 2024,
                hasAttachments = true,
                hasHtml = true,
                hasFrozenImages = true,
                page = 0,
                size = 50,
            )

        assertEquals(1, filtered.total)
        assertEquals("b", filtered.items.first().id)
    }

    @Test
    fun `list year filter supports date_epoch in milliseconds`() {
        jdbcTemplate.update("UPDATE messages SET date_epoch = ? WHERE id = ?", 1706781600000L, "b")

        val result = list(query = null, year = 2024, page = 0, size = 50)
        val ids = result.items.map { it.id }.toSet()

        assertEquals(1, result.total)
        assertEquals(setOf("b"), ids)
    }

    @Test
    fun `list query finds content from html_text`() {
        jdbcTemplate.update("INSERT INTO message_bodies(message_id, html_raw, html_text) VALUES (?, ?, ?)", "c", "<p>Only html phrase</p>", "Only html phrase")

        val result = list(query = "\"Only html phrase\"", page = 0, size = 50)
        assertEquals(1, result.total)
        assertEquals("c", result.items.first().id)
    }

    @Test
    fun `list query supports email terms with at symbol`() {
        val result = list(query = "alice@x.com", page = 0, size = 50)
        assertEquals(1, result.total)
        assertEquals("a", result.items.first().id)
    }

    @Test
    fun `findById returns full detail and null for unknown id`() {
        jdbcTemplate.update(
            "UPDATE messages SET subject_display = ?, from_display = ?, from_email = ?, from_name = ? WHERE id = ?",
            "Hello decoded",
            "Alice <alice@x.com>",
            "alice@x.com",
            "Alice",
            "a",
        )
        jdbcTemplate.update(
            "INSERT INTO message_bodies(message_id, text_plain) VALUES (?, ?)",
            "a",
            "Body A",
        )

        val found = repository.findById("a")
        val missing = repository.findById("missing")

        assertNotNull(found)
        assertEquals("/tmp/a.eml", found.filePath)
        assertEquals("Hello", found.subject)
        assertEquals("Hello decoded", found.subjectDisplay)
        assertEquals("Alice <alice@x.com>", found.fromDisplay)
        assertEquals("alice@x.com", found.fromEmail)
        assertEquals("Alice", found.fromName)
        assertEquals(0, found.attachmentsCount)
        assertEquals(0, found.frozenAssetsCount)
        assertEquals(0, found.assetsFailedCount)
        assertEquals("Body A", found.textPlain)
        assertNull(missing)
    }

    @Test
    fun `findPrevId and findNextId follow descending date_epoch with mtime fallback`() {
        jdbcTemplate.update("UPDATE messages SET date_epoch = ? WHERE id = ?", 1704103200L, "a")
        jdbcTemplate.update("UPDATE messages SET date_epoch = ? WHERE id = ?", 1706781600L, "b")
        jdbcTemplate.update("UPDATE messages SET date_epoch = NULL WHERE id = ?", "c")

        assertEquals("b", repository.findPrevId("a"))
        assertEquals("c", repository.findNextId("a"))
        assertEquals(null, repository.findPrevId("b"))
        assertEquals(null, repository.findNextId("c"))
    }

    private fun insert(
        id: String,
        filePath: String,
        fileMtimeEpoch: Long,
        fileSize: Long,
        dateRaw: String?,
        subject: String?,
        fromRaw: String?,
        messageId: String?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            filePath,
            fileMtimeEpoch,
            fileSize,
            dateRaw,
            subject,
            fromRaw,
            messageId,
        )
    }

    private fun list(
        query: String?,
        page: Int,
        size: Int,
        year: Int? = null,
        hasAttachments: Boolean? = null,
        hasHtml: Boolean? = null,
        hasFrozenImages: Boolean? = null,
    ) = repository.list(query, page, size, year, hasAttachments, hasHtml, hasFrozenImages)
}

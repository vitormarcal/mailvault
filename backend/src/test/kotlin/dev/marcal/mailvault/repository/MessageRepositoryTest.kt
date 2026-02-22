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
                subject TEXT,
                from_raw TEXT,
                message_id TEXT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
                id UNINDEXED,
                subject,
                from_raw
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(id, subject, from_raw)
                VALUES (new.id, COALESCE(new.subject, ''), COALESCE(new.from_raw, ''));
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

                INSERT INTO messages_fts(id, subject, from_raw)
                VALUES (new.id, COALESCE(new.subject, ''), COALESCE(new.from_raw, ''));
            END
            """.trimIndent(),
        )
        jdbcTemplate.update("DELETE FROM messages")
        jdbcTemplate.update("DELETE FROM messages_fts")

        insert("a", "/tmp/a.eml", 1000, 10, "2024-01-01T10:00:00Z", "Hello", "Alice <alice@x.com>", "<a@x>")
        insert("b", "/tmp/b.eml", 3000, 20, "2024-02-01T10:00:00Z", "Report", "Bob <bob@x.com>", "<b@x>")
        insert("c", "/tmp/c.eml", 4000, 30, null, "No date", "Charlie <charlie@x.com>", "<c@x>")
    }

    @Test
    fun `list paginates and orders by date_raw desc then file_mtime_epoch desc`() {
        val page = repository.list(query = null, page = 0, size = 2)

        assertEquals(3, page.total)
        assertEquals(2, page.items.size)
        assertEquals("b", page.items[0].id)
        assertEquals("a", page.items[1].id)
    }

    @Test
    fun `list filters by subject and from_raw`() {
        val bySubject = repository.list(query = "report", page = 0, size = 50)
        val byFrom = repository.list(query = "charlie", page = 0, size = 50)

        assertEquals(1, bySubject.total)
        assertEquals("b", bySubject.items.first().id)
        assertEquals(1, byFrom.total)
        assertEquals("c", byFrom.items.first().id)
    }

    @Test
    fun `list query reddit matches subject and from using fts`() {
        insert("r1", "/tmp/r1.eml", 5000, 10, "2024-03-01T10:00:00Z", "Daily reddit digest", "News Bot <bot@x.com>", "<r1@x>")
        insert("r2", "/tmp/r2.eml", 6000, 10, "2024-03-02T10:00:00Z", "Other topic", "Reddit Alerts <alerts@x.com>", "<r2@x>")

        val result = repository.list(query = "reddit", page = 0, size = 50)
        val ids = result.items.map { it.id }.toSet()

        assertEquals(2, result.total)
        assertEquals(setOf("r1", "r2"), ids)
    }

    @Test
    fun `list supports offset pagination`() {
        val secondPage = repository.list(query = null, page = 1, size = 1)
        assertEquals(3, secondPage.total)
        assertEquals(1, secondPage.items.size)
        assertEquals("a", secondPage.items.first().id)
    }

    @Test
    fun `blank query is treated as no filter`() {
        val blank = repository.list(query = "   ", page = 0, size = 50)
        assertEquals(3, blank.total)
    }

    @Test
    fun `fallback ordering by mtime applies when date is null`() {
        insert("d", "/tmp/d.eml", 5000, 40, null, "No date newer", "Dave <dave@x.com>", "<d@x>")

        val page = repository.list(query = "No date", page = 0, size = 50)
        assertEquals(2, page.total)
        assertEquals("d", page.items[0].id)
        assertEquals("c", page.items[1].id)
    }

    @Test
    fun `findById returns full detail and null for unknown id`() {
        val found = repository.findById("a")
        val missing = repository.findById("missing")

        assertNotNull(found)
        assertEquals("/tmp/a.eml", found.filePath)
        assertEquals("Hello", found.subject)
        assertNull(missing)
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
}

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

class IndexerServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var service: IndexerService

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
        service = IndexerService(jdbcTemplate, EmlHeaderParser())
    }

    @Test
    fun `throws when root dir is invalid`() {
        assertFailsWith<IllegalArgumentException> {
            service.index(tempDir.resolve("missing").toString())
        }
    }

    @Test
    fun `ignores non eml files`() {
        val root = tempDir.resolve("emails")
        Files.createDirectories(root)
        Files.writeString(root.resolve("notes.txt"), "plain file")

        val result = service.index(root.toString())
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 0), result)
    }

    @Test
    fun `keeps stable id for same file path and message id`() {
        val root = tempDir.resolve("emails")
        Files.createDirectories(root)
        val eml = root.resolve("a.eml")

        Files.writeString(
            eml,
            """
            From: A <a@x.com>
            Subject: First
            Message-ID: <stable@x>
            
            Body
            """.trimIndent(),
        )
        service.index(root.toString())
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
        service.index(root.toString())
        val secondId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE file_path = ?",
                String::class.java,
                eml.toAbsolutePath().normalize().toString(),
            )

        assertEquals(firstId, secondId)
    }
}

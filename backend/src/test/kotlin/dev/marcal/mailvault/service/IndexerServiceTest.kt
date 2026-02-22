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
    private lateinit var rootDir: Path

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
        rootDir = tempDir.resolve("emails")
        Files.createDirectories(rootDir)
        service = IndexerService(jdbcTemplate, EmlHeaderParser(), rootDir.toString())
    }

    @Test
    fun `throws when configured root dir is invalid`() {
        service = IndexerService(jdbcTemplate, EmlHeaderParser(), tempDir.resolve("missing").toString())

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
}

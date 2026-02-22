package dev.marcal.mailvault.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
class IndexerServiceIntegrationTest {
    @Autowired
    lateinit var indexerService: IndexerService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanMessagesTable() {
        jdbcTemplate.update("DELETE FROM attachments")
        jdbcTemplate.update("DELETE FROM message_bodies")
        jdbcTemplate.update("DELETE FROM messages")
        if (Files.exists(indexRootDir)) {
            Files.walk(indexRootDir)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
        Files.createDirectories(indexRootDir)
        if (Files.exists(storageRootDir)) {
            Files.walk(storageRootDir)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
        Files.createDirectories(storageRootDir)
    }

    @Test
    fun `migrations are applied and messages table exists`() {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
            Int::class.java,
        )
        assertNotNull(count)
        assertEquals(true, count >= 6)
    }

    @Test
    fun `indexes eml files and skips unchanged on next run`() {
        Files.createDirectories(indexRootDir.resolve("sub"))

        Files.writeString(
            indexRootDir.resolve("a.eml"),
            """
            From: Alice <alice@example.com>
            Date: Sat, 21 Feb 2026 20:00:00 -0300
            Subject: Hello
            Message-ID: <m1@example.com>
            
            Body A
            """.trimIndent(),
        )
        Files.writeString(
            indexRootDir.resolve("sub").resolve("b.eml"),
            """
            From: Bob <bob@example.com>
            Date: Sat, 21 Feb 2026 20:05:00 -0300
            Subject: World
            Message-ID: <m2@example.com>
            
            Body B
            """.trimIndent(),
        )

        val first = indexerService.index()
        val second = indexerService.index()

        assertEquals(IndexResult(inserted = 2, updated = 0, skipped = 0), first)
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 2), second)
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Int::class.java))
    }

    @Test
    fun `parses folded headers and updates when file changes`() {
        val emlPath = indexRootDir.resolve("c.eml")

        Files.writeString(
            emlPath,
            """
            From: Carol <carol@example.com>
            Date: Sat, 21 Feb 2026 21:00:00 -0300
            Subject: First line
             second line
            Message-ID: <m3@example.com>
            
            Body C
            X-Body-Like: must-not-be-read
            """.trimIndent(),
        )

        val first = indexerService.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), first)

        val initialSubject = jdbcTemplate.queryForObject(
            "SELECT subject FROM messages WHERE file_path = ?",
            String::class.java,
            emlPath.toAbsolutePath().normalize().toString(),
        )
        assertEquals("First line second line", initialSubject)

        Thread.sleep(5)
        Files.writeString(
            emlPath,
            """
            From: Carol <carol@example.com>
            Date: Sat, 21 Feb 2026 21:10:00 -0300
            Subject: Updated subject
            Message-ID: <m3@example.com>
            
            Body C updated
            """.trimIndent(),
        )

        val second = indexerService.index()
        assertEquals(IndexResult(inserted = 0, updated = 1, skipped = 0), second)

        val updatedSubject = jdbcTemplate.queryForObject(
            "SELECT subject FROM messages WHERE file_path = ?",
            String::class.java,
            emlPath.toAbsolutePath().normalize().toString(),
        )
        assertEquals("Updated subject", updatedSubject)
    }

    @Test
    fun `indexation keeps fts in sync via triggers`() {
        val emlPath = indexRootDir.resolve("reddit.eml")

        Files.writeString(
            emlPath,
            """
            From: Community Watch <watch@example.com>
            Date: Sat, 21 Feb 2026 22:00:00 -0300
            Subject: reddittoken digest
            Message-ID: <reddit-1@example.com>

            Body
            """.trimIndent(),
        )

        indexerService.index()
        val firstCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?",
            Int::class.java,
            "reddittoken",
        )
        assertEquals(1, firstCount)

        Thread.sleep(5)
        Files.writeString(
            emlPath,
            """
            From: Community Watch <watch@example.com>
            Date: Sat, 21 Feb 2026 22:05:00 -0300
            Subject: forum digest
            Message-ID: <reddit-1@example.com>

            Body updated
            """.trimIndent(),
        )

        indexerService.index()
        val secondCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?",
            Int::class.java,
            "reddittoken",
        )
        assertEquals(0, secondCount)
    }

    @Test
    fun `stores text plain for simple email in message_bodies`() {
        val emlPath = indexRootDir.resolve("plain.eml")
        Files.writeString(
            emlPath,
            """
            From: Plain Sender <plain@example.com>
            Subject: Simple
            Message-ID: <plain-1@example.com>

            Line one
            Line two
            """.trimIndent(),
        )

        indexerService.index()

        val textPlain = jdbcTemplate.queryForObject(
            """
            SELECT mb.text_plain
            FROM message_bodies mb
            JOIN messages m ON m.id = mb.message_id
            WHERE m.file_path = ?
            """.trimIndent(),
            String::class.java,
            emlPath.toAbsolutePath().normalize().toString(),
        )
        assertEquals("Line one\nLine two", textPlain?.trim())
    }

    @Test
    fun `stores text plain for multipart email without failing`() {
        val emlPath = indexRootDir.resolve("multipart.eml")
        Files.writeString(
            emlPath,
            """
            From: Multi Sender <multi@example.com>
            Subject: Multi
            Message-ID: <multi-1@example.com>
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8

            Hello part
            --b1
            Content-Type: text/html; charset=UTF-8

            <p>Hello part</p>
            --b1--
            """.trimIndent(),
        )

        val result = indexerService.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), result)

        val textPlain = jdbcTemplate.queryForObject(
            """
            SELECT mb.text_plain
            FROM message_bodies mb
            JOIN messages m ON m.id = mb.message_id
            WHERE m.file_path = ?
            """.trimIndent(),
            String::class.java,
            emlPath.toAbsolutePath().normalize().toString(),
        )
        assertEquals("Hello part", textPlain?.trim())

        val htmlRaw = jdbcTemplate.queryForObject(
            """
            SELECT mb.html_raw
            FROM message_bodies mb
            JOIN messages m ON m.id = mb.message_id
            WHERE m.file_path = ?
            """.trimIndent(),
            String::class.java,
            emlPath.toAbsolutePath().normalize().toString(),
        )
        assertEquals(true, htmlRaw?.contains("Hello part") == true)
    }

    @Test
    fun `stores attachments and inline cid metadata with file on disk`() {
        val emlPath = indexRootDir.resolve("with-attachment.eml")
        Files.writeString(
            emlPath,
            """
            From: Multi Sender <multi@example.com>
            Subject: Attach
            Message-ID: <attach-1@example.com>
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="mix"

            --mix
            Content-Type: text/plain; charset=UTF-8

            Body with attachment
            --mix
            Content-Type: image/png; name="pixel.png"
            Content-Transfer-Encoding: base64
            Content-Disposition: inline; filename="pixel.png"
            Content-ID: <img-42>

            iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO9fZQAAAABJRU5ErkJggg==
            --mix--
            """.trimIndent(),
        )

        val result = indexerService.index()
        assertEquals(IndexResult(inserted = 1, updated = 0, skipped = 0), result)

        val attachmentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attachments", Int::class.java)
        assertEquals(1, attachmentCount)

        val inlineCid = jdbcTemplate.queryForObject(
            "SELECT inline_cid FROM attachments LIMIT 1",
            String::class.java,
        )
        assertEquals("img-42", inlineCid)

        val storagePath = jdbcTemplate.queryForObject(
            "SELECT storage_path FROM attachments LIMIT 1",
            String::class.java,
        )
        assertEquals(true, Files.exists(Path.of(storagePath)))
    }

    companion object {
        private val dbPath =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                "mailvault-test-${UUID.randomUUID()}.db",
            ).toAbsolutePath().normalize()
        private val indexRootDir =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                "mailvault-index-test-${UUID.randomUUID()}",
            ).toAbsolutePath().normalize()
        private val storageRootDir =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                "mailvault-storage-test-${UUID.randomUUID()}",
            ).toAbsolutePath().normalize()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:$dbPath" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.url") { "jdbc:sqlite:$dbPath" }
            registry.add("spring.flyway.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.flyway.locations") { "classpath:db/migration" }
            registry.add("mailvault.index.root-dir") { indexRootDir.toString() }
            registry.add("mailvault.storageDir") { storageRootDir.toString() }
        }
    }
}

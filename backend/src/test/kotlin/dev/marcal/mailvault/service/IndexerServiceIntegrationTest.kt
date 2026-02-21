package dev.marcal.mailvault.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
class IndexerServiceIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Autowired
    lateinit var indexerService: IndexerService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanMessagesTable() {
        jdbcTemplate.update("DELETE FROM messages")
    }

    @Test
    fun `migrations are applied and messages table exists`() {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
            Int::class.java,
        )
        assertNotNull(count)
        assertEquals(true, count >= 2)
    }

    @Test
    fun `indexes eml files and skips unchanged on next run`() {
        val rootDir = tempDir.resolve("emails")
        Files.createDirectories(rootDir.resolve("sub"))

        Files.writeString(
            rootDir.resolve("a.eml"),
            """
            From: Alice <alice@example.com>
            Date: Sat, 21 Feb 2026 20:00:00 -0300
            Subject: Hello
            Message-ID: <m1@example.com>
            
            Body A
            """.trimIndent(),
        )
        Files.writeString(
            rootDir.resolve("sub").resolve("b.eml"),
            """
            From: Bob <bob@example.com>
            Date: Sat, 21 Feb 2026 20:05:00 -0300
            Subject: World
            Message-ID: <m2@example.com>
            
            Body B
            """.trimIndent(),
        )

        val first = indexerService.index(rootDir.toString())
        val second = indexerService.index(rootDir.toString())

        assertEquals(IndexResult(inserted = 2, updated = 0, skipped = 0), first)
        assertEquals(IndexResult(inserted = 0, updated = 0, skipped = 2), second)
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Int::class.java))
    }

    @Test
    fun `parses folded headers and updates when file changes`() {
        val rootDir = tempDir.resolve("emails")
        Files.createDirectories(rootDir)
        val emlPath = rootDir.resolve("c.eml")

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

        val first = indexerService.index(rootDir.toString())
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

        val second = indexerService.index(rootDir.toString())
        assertEquals(IndexResult(inserted = 0, updated = 1, skipped = 0), second)

        val updatedSubject = jdbcTemplate.queryForObject(
            "SELECT subject FROM messages WHERE file_path = ?",
            String::class.java,
            emlPath.toAbsolutePath().normalize().toString(),
        )
        assertEquals("Updated subject", updatedSubject)
    }

    companion object {
        private val dbPath =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                "mailvault-test-${UUID.randomUUID()}.db",
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
        }
    }
}

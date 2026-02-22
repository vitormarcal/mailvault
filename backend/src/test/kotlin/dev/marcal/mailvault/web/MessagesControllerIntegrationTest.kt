package dev.marcal.mailvault.web

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessagesControllerIntegrationTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Value("\${local.server.port}")
    var port: Int = 0

    private val httpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun setupData() {
        jdbcTemplate.update("DELETE FROM message_bodies")
        jdbcTemplate.update("DELETE FROM messages")
        insert("id-1", "/tmp/1.eml", 1000, 10, "2024-01-01T10:00:00Z", "Hello there", "Alice <alice@x.com>", "<1@x>")
        insert("id-2", "/tmp/2.eml", 3000, 20, "2024-02-01T10:00:00Z", "Monthly report", "Bob <bob@x.com>", "<2@x>")
        insert("id-3", "/tmp/3.eml", 4000, 30, null, "No date mail", "Charlie <charlie@x.com>", "<3@x>")
        jdbcTemplate.update("INSERT INTO message_bodies(message_id, text_plain) VALUES (?, ?)", "id-1", "Body one")
    }

    @Test
    fun `GET messages lists with pagination`() {
        val response = get("/api/messages?page=0&size=2")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"page\":0"))
        assertEquals(true, body.contains("\"size\":2"))
        assertEquals(true, body.contains("\"total\":3"))
        assertEquals(true, body.contains("\"id\":\"id-2\""))
        assertEquals(true, body.contains("\"id\":\"id-1\""))
    }

    @Test
    fun `GET messages filters by query`() {
        val response = get("/api/messages?query=charlie&page=0&size=50")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"total\":1"))
        assertEquals(true, body.contains("\"id\":\"id-3\""))
    }

    @Test
    fun `GET message by id returns detail`() {
        val response = get("/api/messages/id-1")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"id\":\"id-1\""))
        assertEquals(true, body.contains("\"filePath\":\"/tmp/1.eml\""))
        assertEquals(true, body.contains("\"subject\":\"Hello there\""))
        assertEquals(true, body.contains("\"fromRaw\":\"Alice <alice@x.com>\""))
        assertEquals(true, body.contains("\"textPlain\":\"Body one\""))
    }

    @Test
    fun `GET message by id returns null textPlain when body is missing`() {
        val response = get("/api/messages/id-2")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"id\":\"id-2\""))
        assertEquals(true, body.contains("\"textPlain\":null"))
    }

    @Test
    fun `GET message by id returns 404 when missing`() {
        val response = get("/api/messages/missing")
        assertEquals(404, response.statusCode())
    }

    @Test
    fun `GET root serves minimal inbox UI`() {
        val response = get("/")
        assertEquals(200, response.statusCode())
        assertEquals(true, response.body().contains("Caixa Historica"))
    }

    @Test
    fun `GET messages id route serves minimal message UI`() {
        val response = get("/messages/id-1")
        assertEquals(200, response.statusCode())
        assertEquals(true, response.body().contains("Reindexar"))
        assertEquals(true, response.body().contains("text/plain"))
    }

    private fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port$path"))
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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

    companion object {
        private val dbPath =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                "mailvault-messages-test-${UUID.randomUUID()}.db",
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

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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
        Files.createDirectories(storageDir.resolve("attachments").resolve("id-1"))
        Files.createDirectories(storageDir.resolve("assets").resolve("id-1"))

        val inlinePath = storageDir.resolve("attachments").resolve("id-1").resolve("cid-bytes")
        Files.writeString(inlinePath, "INLINE_IMAGE", StandardCharsets.UTF_8)

        val attachPath = storageDir.resolve("attachments").resolve("id-1").resolve("file-bytes")
        Files.writeString(attachPath, "ATTACHMENT_PAYLOAD", StandardCharsets.UTF_8)

        val frozenAssetSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val frozenAssetPath = storageDir.resolve("assets").resolve("id-1").resolve("$frozenAssetSha.png")
        Files.writeString(frozenAssetPath, "FROZEN_IMAGE", StandardCharsets.UTF_8)

        jdbcTemplate.update("DELETE FROM assets")
        jdbcTemplate.update("DELETE FROM attachments")
        jdbcTemplate.update("DELETE FROM message_bodies")
        jdbcTemplate.update("DELETE FROM messages")
        insert("id-1", "/tmp/1.eml", 1000, 10, "2024-01-01T10:00:00Z", "Hello there", "Alice <alice@x.com>", "<1@x>")
        insert("id-2", "/tmp/2.eml", 3000, 20, "2024-02-01T10:00:00Z", "Monthly report", "Bob <bob@x.com>", "<2@x>")
        insert("id-3", "/tmp/3.eml", 4000, 30, null, "No date mail", "Charlie <charlie@x.com>", "<3@x>")
        jdbcTemplate.update(
            "INSERT INTO message_bodies(message_id, text_plain, html_raw, html_sanitized) VALUES (?, ?, ?, ?)",
            "id-1",
            "Body one",
            """
            <div>
              <a href="https://example.com">Link</a>
              <img src="https://example.com/x.png" />
            </div>
            """.trimIndent(),
            null,
        )

        jdbcTemplate.update(
            """
            INSERT INTO attachments(id, message_id, filename, content_type, size, inline_cid, storage_path, sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "att-inline-1",
            "id-1",
            "logo.png",
            "image/png",
            Files.size(inlinePath),
            "img-1",
            inlinePath.toAbsolutePath().normalize().toString(),
            "sha-inline",
        )
        jdbcTemplate.update(
            """
            INSERT INTO attachments(id, message_id, filename, content_type, size, inline_cid, storage_path, sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "att-file-1",
            "id-1",
            "report.pdf",
            "application/pdf",
            Files.size(attachPath),
            null,
            attachPath.toAbsolutePath().normalize().toString(),
            "sha-file",
        )
        jdbcTemplate.update(
            """
            INSERT INTO assets(id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "asset-1",
            "id-1",
            "https://example.com/x.png",
            frozenAssetPath.toAbsolutePath().normalize().toString(),
            "image/png",
            Files.size(frozenAssetPath),
            frozenAssetSha,
            "DOWNLOADED",
            "2026-02-22T10:00:00Z",
            null,
        )
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

    @Test
    fun `GET messages id render returns sanitized html with rewritten links`() {
        jdbcTemplate.update(
            "UPDATE message_bodies SET html_raw = ? WHERE message_id = ?",
            """
            <div>
              <img src="cid:img-1" />
            </div>
            """.trimIndent(),
            "id-1",
        )
        jdbcTemplate.update("UPDATE message_bodies SET html_sanitized = NULL WHERE message_id = ?", "id-1")

        val response = get("/api/messages/id-1/render")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"html\":"))
        assertEquals(true, body.contains("/api/messages/id-1/cid/img-1"))
    }

    @Test
    fun `GET messages id render uses frozen asset url when available`() {
        val response = get("/api/messages/id-1/render")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("/assets/id-1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"))
        assertEquals(false, body.contains("/static/remote-image-blocked.svg"))
    }

    @Test
    fun `GET message attachments lists metadata`() {
        val response = get("/api/messages/id-1/attachments")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"id\":\"att-inline-1\""))
        assertEquals(true, body.contains("\"isInline\":true"))
        assertEquals(true, body.contains("\"id\":\"att-file-1\""))
        assertEquals(true, body.contains("\"isInline\":false"))
    }

    @Test
    fun `GET cid serves inline attachment bytes with safe headers`() {
        val response = getBytes("/api/messages/id-1/cid/%3Cimg-1%3E")

        assertEquals(200, response.statusCode())
        assertEquals(true, response.headers().firstValue("Content-Disposition").orElse("").startsWith("inline;"))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        assertEquals("INLINE_IMAGE", String(response.body(), StandardCharsets.UTF_8))
    }

    @Test
    fun `GET attachment download serves bytes with attachment disposition`() {
        val response = getBytes("/api/attachments/att-file-1/download")

        assertEquals(200, response.statusCode())
        assertEquals(true, response.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        assertEquals("ATTACHMENT_PAYLOAD", String(response.body(), StandardCharsets.UTF_8))
    }

    @Test
    fun `GET assets serves frozen image bytes with cache and nosniff headers`() {
        val response = getBytes("/assets/id-1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png")

        assertEquals(200, response.statusCode())
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        assertEquals(true, response.headers().firstValue("Cache-Control").orElse("").contains("max-age"))
        assertEquals("FROZEN_IMAGE", String(response.body(), StandardCharsets.UTF_8))
    }

    @Test
    fun `POST freeze-assets skips localhost URL by ssrf guard`() {
        jdbcTemplate.update(
            "UPDATE message_bodies SET html_raw = ?, html_sanitized = NULL WHERE message_id = ?",
            """<img src="http://localhost/private.png" />""",
            "id-1",
        )

        val response = post("/api/messages/id-1/freeze-assets")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"downloaded\":0"))
        assertEquals(true, body.contains("\"failed\":0"))
        assertEquals(true, body.contains("\"skipped\":1"))
    }

    @Test
    fun `GET go redirects for safe schemes and blocks unsafe ones`() {
        val safe = get("/go?url=https://example.com")
        assertEquals(302, safe.statusCode())

        val blocked = get("/go?url=javascript:alert(1)")
        assertEquals(400, blocked.statusCode())
        assertEquals(true, blocked.body().contains("\"error\":\"VALIDATION_ERROR\""))
    }

    private fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port$path"))
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port$path"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getBytes(path: String): HttpResponse<ByteArray> {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port$path"))
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
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
        private val storageDir =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                "mailvault-messages-storage-${UUID.randomUUID()}",
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
            registry.add("mailvault.storageDir") { storageDir.toString() }
        }
    }
}

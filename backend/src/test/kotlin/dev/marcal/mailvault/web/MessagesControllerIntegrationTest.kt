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
        val roguePath = storageDir.parent.resolve("rogue.bin")
        Files.writeString(roguePath, "ROGUE_FILE", StandardCharsets.UTF_8)

        jdbcTemplate.update("DELETE FROM assets")
        jdbcTemplate.update("DELETE FROM attachments")
        jdbcTemplate.update("DELETE FROM message_bodies")
        jdbcTemplate.update("DELETE FROM messages")
        insert(
            "id-1",
            "/tmp/1.eml",
            1000,
            10,
            "2024-01-01T10:00:00Z",
            1704103200,
            "Hello there",
            "Alice <alice@x.com>",
            "<1@x>",
            "Hello there",
            "Alice <alice@x.com>",
            "alice@x.com",
            "Alice",
        )
        insert("id-2", "/tmp/2.eml", 3000, 20, "2024-02-01T10:00:00Z", 1706781600, "Monthly report", "Bob <bob@x.com>", "<2@x>")
        insert("id-3", "/tmp/3.eml", 4000, 30, null, null, "No date mail", "Charlie <charlie@x.com>", "<3@x>")
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
            INSERT INTO attachments(id, message_id, filename, content_type, size, inline_cid, storage_path, sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "att-rogue-1",
            "id-1",
            "rogue.bin",
            "application/octet-stream",
            Files.size(roguePath),
            null,
            roguePath.toAbsolutePath().normalize().toString(),
            "sha-rogue",
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
        jdbcTemplate.update(
            """
            INSERT INTO assets(id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "asset-rogue-1",
            "id-1",
            "https://example.com/rogue.png",
            roguePath.toAbsolutePath().normalize().toString(),
            "image/png",
            Files.size(roguePath),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
        assertEquals(false, body.contains("\"id\":\"id-3\""))
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
    fun `GET messages query also matches text plain`() {
        val response = get("/api/messages?query=%22Body%20one%22&page=0&size=50")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"total\":1"))
        assertEquals(true, body.contains("\"id\":\"id-1\""))
        assertEquals(true, body.contains("\"snippet\":\"Body one\""))
        assertEquals(true, body.contains("\"hasHtml\":true"))
        assertEquals(true, body.contains("\"attachmentsCount\":3"))
        assertEquals(true, body.contains("\"frozenAssetsCount\":2"))
        assertEquals(true, body.contains("\"assetsFailedCount\":0"))
    }

    @Test
    fun `GET messages supports combined filters with query`() {
        val response = get("/api/messages?query=Body&year=2024&hasAttachments=true&hasHtml=true&hasFrozenImages=true&page=0&size=50")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"total\":1"))
        assertEquals(true, body.contains("\"id\":\"id-1\""))
    }

    @Test
    fun `GET messages builds snippet from html_raw when plain is missing`() {
        jdbcTemplate.update(
            "INSERT INTO message_bodies(message_id, html_raw) VALUES (?, ?)",
            "id-2",
            "<div>Resumo <strong>HTML</strong> apenas</div>",
        )

        val response = get("/api/messages?page=0&size=50")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"id\":\"id-2\""))
        assertEquals(true, body.contains("\"snippet\":\"Resumo HTML apenas\""))
    }

    @Test
    fun `GET messages returns failed assets count when present`() {
        jdbcTemplate.update(
            """
            INSERT INTO assets(id, message_id, original_url, storage_path, content_type, size, sha256, status, downloaded_at, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "asset-failed-1",
            "id-1",
            "https://example.com/fail.png",
            null,
            "image/png",
            null,
            null,
            "FAILED",
            null,
            "timeout",
        )

        val response = get("/api/messages?query=%22Body%20one%22&page=0&size=50")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"id\":\"id-1\""))
        assertEquals(true, body.contains("\"assetsFailedCount\":1"))
    }

    @Test
    fun `GET message by id returns detail`() {
        val response = get("/api/messages/id-1")

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"id\":\"id-1\""))
        assertEquals(true, body.contains("\"filePath\":\"/tmp/1.eml\""))
        assertEquals(true, body.contains("\"subject\":\"Hello there\""))
        assertEquals(true, body.contains("\"subjectDisplay\":\"Hello there\""))
        assertEquals(true, body.contains("\"fromRaw\":\"Alice <alice@x.com>\""))
        assertEquals(true, body.contains("\"fromDisplay\":\"Alice <alice@x.com>\""))
        assertEquals(true, body.contains("\"fromEmail\":\"alice@x.com\""))
        assertEquals(true, body.contains("\"fromName\":\"Alice\""))
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
    fun `GET message prev and next follow list ordering`() {
        val prev = get("/api/messages/id-1/prev")
        val next = get("/api/messages/id-1/next")
        val firstPrev = get("/api/messages/id-2/prev")
        val lastNext = get("/api/messages/id-3/next")

        assertEquals(200, prev.statusCode())
        assertEquals(true, prev.body().contains("\"id\":\"id-2\""))
        assertEquals(200, next.statusCode())
        assertEquals(true, next.body().contains("\"id\":\"id-3\""))
        assertEquals(200, firstPrev.statusCode())
        assertEquals(true, firstPrev.body().contains("\"id\":null"))
        assertEquals(200, lastNext.statusCode())
        assertEquals(true, lastNext.body().contains("\"id\":null"))
    }

    @Test
    fun `GET message prev returns 404 for unknown message`() {
        val response = get("/api/messages/missing/prev")
        assertEquals(404, response.statusCode())
    }

    @Test
    fun `GET root serves minimal inbox UI`() {
        val response = get("/")
        assertEquals(200, response.statusCode())
        assertEquals(true, response.body().contains("Caixa Historica"))
        assertEquals(true, response.body().contains("Status"))
        assertEquals(true, response.body().contains("Manutencao"))
    }

    @Test
    fun `GET favicon returns not found without internal server error`() {
        val response = get("/favicon.ico")
        assertEquals(404, response.statusCode())
        assertEquals(true, response.body().contains("\"error\":\"NOT_FOUND\""))
        assertEquals(false, response.body().contains("\"error\":\"INTERNAL_ERROR\""))
    }

    @Test
    fun `POST maintenance cleanup removes orphan files and missing message rows`() {
        val orphanAttachment = storageDir.resolve("attachments").resolve("id-1").resolve("orphan.tmp")
        val orphanAsset = storageDir.resolve("assets").resolve("id-1").resolve("orphan.tmp")
        Files.writeString(orphanAttachment, "ORPHAN_ATTACHMENT", StandardCharsets.UTF_8)
        Files.writeString(orphanAsset, "ORPHAN_ASSET", StandardCharsets.UTF_8)

        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size, date_raw, date_epoch, subject, from_raw, message_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "missing-row",
            "/tmp/does-not-exist.eml",
            1L,
            1L,
            null,
            null,
            "Missing",
            "Missing <missing@x.com>",
            "<missing@x.com>",
        )

        val response = post("/api/maintenance/cleanup")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"removedAttachmentFiles\":1"))
        assertEquals(true, body.contains("\"removedAssetFiles\":1"))
        assertEquals(true, body.contains("\"removedMissingMessageRows\":"))
        assertEquals(false, Files.exists(orphanAttachment))
        assertEquals(false, Files.exists(orphanAsset))
        val stillExists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages WHERE id = 'missing-row'", Int::class.java) ?: 0
        assertEquals(0, stillExists)
    }

    @Test
    fun `POST maintenance vacuum returns ok`() {
        val response = post("/api/maintenance/vacuum")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"ok\":true"))
        assertEquals(true, body.contains("\"durationMs\""))
    }

    @Test
    fun `GET stats returns counters and lastIndexAt when available`() {
        jdbcTemplate.update(
            """
            INSERT INTO app_meta(key, value)
            VALUES ('lastIndexAt', ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
            "2026-02-22T12:00:00Z",
        )

        val response = get("/api/stats")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"totalMessages\":3"))
        assertEquals(true, body.contains("\"totalWithHtml\":1"))
        assertEquals(true, body.contains("\"totalAttachments\":3"))
        assertEquals(true, body.contains("\"totalAssetsDownloaded\":2"))
        assertEquals(true, body.contains("\"totalAssetsFailed\":0"))
        assertEquals(true, body.contains("\"storageBytesAttachments\""))
        assertEquals(true, body.contains("\"storageBytesAssets\""))
        assertEquals(true, body.contains("\"lastIndexAt\":\"2026-02-22T12:00:00Z\""))
    }

    @Test
    fun `GET stats returns lastIndexAt null when metadata is missing`() {
        jdbcTemplate.update("DELETE FROM app_meta WHERE key = 'lastIndexAt'")

        val response = get("/api/stats")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertEquals(true, body.contains("\"lastIndexAt\":null"))
    }

    @Test
    fun `GET messages id route serves minimal message UI`() {
        val response = get("/messages/id-1")
        assertEquals(200, response.statusCode())
        assertEquals(true, response.body().contains("Reindexar"))
        assertEquals(true, response.body().contains("text/plain"))
        val csp = response.headers().firstValue("Content-Security-Policy").orElse("")
        assertEquals(true, csp.contains("default-src 'none'"))
        assertEquals(true, csp.contains("img-src 'self'"))
        assertEquals(true, csp.contains("frame-ancestors 'none'"))
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""))
    }

    @Test
    fun `GET messages id render returns sanitized html with rewritten links`() {
        jdbcTemplate.update(
            "UPDATE message_bodies SET html_raw = ? WHERE message_id = ?",
            """
            <div>
              <a href="https://example.com/path">Go</a>
              <img src="cid:img-1" />
              <iframe src="https://evil.invalid"></iframe>
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
        assertEquals(false, body.contains("<iframe", ignoreCase = true))
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
        val cacheControl = response.headers().firstValue("Cache-Control").orElse("")
        assertEquals(true, cacheControl.contains("private"))
        assertEquals(true, cacheControl.contains("no-store"))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        assertEquals("ATTACHMENT_PAYLOAD", String(response.body(), StandardCharsets.UTF_8))
    }

    @Test
    fun `GET assets serves frozen image bytes with cache and nosniff headers`() {
        val response = getBytes("/assets/id-1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png")

        assertEquals(200, response.statusCode())
        assertEquals(true, response.headers().firstValue("Content-Disposition").orElse("").startsWith("inline;"))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        val cacheControl = response.headers().firstValue("Cache-Control").orElse("")
        assertEquals(true, cacheControl.contains("public"))
        assertEquals(true, cacheControl.contains("max-age=31536000"))
        assertEquals("FROZEN_IMAGE", String(response.body(), StandardCharsets.UTF_8))
    }

    @Test
    fun `GET assets blocks traversal-like filename`() {
        val response = get("/assets/id-1/..%2F..%2Fsecret.txt")
        assertEquals(true, response.statusCode() == 400 || response.statusCode() == 404)
    }

    @Test
    fun `GET attachment download blocks storage path outside base dir`() {
        val response = get("/api/attachments/att-rogue-1/download")
        assertEquals(404, response.statusCode())
    }

    @Test
    fun `GET assets blocks storage path outside base dir`() {
        val response = get("/assets/id-1/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png")
        assertEquals(404, response.statusCode())
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
        assertEquals(true, body.contains("\"totalFound\":1"))
        assertEquals(true, body.contains("\"downloaded\":0"))
        assertEquals(true, body.contains("\"failed\":0"))
        assertEquals(true, body.contains("\"skipped\":1"))
        assertEquals(true, body.contains("\"failures\":[]"))
    }

    @Test
    fun `GET go redirects for safe schemes and blocks unsafe ones`() {
        val safe = get("/go?url=https://example.com")
        assertEquals(302, safe.statusCode())
        assertEquals("no-referrer", safe.headers().firstValue("Referrer-Policy").orElse(""))

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
        dateEpoch: Long?,
        subject: String?,
        fromRaw: String?,
        messageId: String?,
        subjectDisplay: String? = null,
        fromDisplay: String? = null,
        fromEmail: String? = null,
        fromName: String? = null,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size, date_raw, date_epoch, subject, from_raw, message_id, subject_display, from_display, from_email, from_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            filePath,
            fileMtimeEpoch,
            fileSize,
            dateRaw,
            dateEpoch,
            subject,
            fromRaw,
            messageId,
            subjectDisplay,
            fromDisplay,
            fromEmail,
            fromName,
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

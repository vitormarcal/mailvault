package dev.marcal.mailvault.service

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.repository.MessageRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.ByteArrayInputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssetFreezeServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: AssetFreezeService
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("asset-freeze-test.db").toAbsolutePath().normalize()
        val dataSource =
            DriverManagerDataSource().apply {
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

        val messageHtmlRepository = MessageHtmlRepository(jdbcTemplate)
        val assetRepository = AssetRepository(jdbcTemplate)
        val messageRepository = MessageRepository(jdbcTemplate)
        val htmlRenderService = HtmlRenderService(messageHtmlRepository, assetRepository, HtmlSanitizerService())
        val storageDir = tempDir.resolve("storage")
        Files.createDirectories(storageDir)
        service =
            AssetFreezeService(
                messageHtmlRepository = messageHtmlRepository,
                assetRepository = assetRepository,
                messageRepository = messageRepository,
                mailVaultProperties = MailVaultProperties(storageDir = storageDir.toString()),
                htmlRenderService = htmlRenderService,
            )
    }

    @Test
    fun `validator rejects redirect target to loopback ipv4`() {
        assertFailsWith<IllegalArgumentException> {
            service.validateRemoteUri("http://127.0.0.1/image.png")
        }
    }

    @Test
    fun `validator rejects url with userinfo`() {
        assertFailsWith<IllegalArgumentException> {
            service.validateRemoteUri("https://user:pass@example.com/image.png")
        }
    }

    @Test
    fun `retries once on 403 and downloads image on second profile`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-1",
            "/tmp/m-1.eml",
            1L,
            1L,
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, html_raw)
            VALUES (?, ?)
            """.trimIndent(),
            "m-1",
            """<img src="https://example.com/image.png" />""",
        )

        val fakeClient =
            FakeHttpClient(
                mutableListOf(
                    PlannedResponse(status = 403, headers = mapOf("Content-Type" to listOf("text/html")), body = ByteArray(0)),
                    PlannedResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to listOf("image/png")),
                        body = "PNG".toByteArray(StandardCharsets.UTF_8),
                    ),
                ),
            )
        service =
            AssetFreezeService(
                messageHtmlRepository = MessageHtmlRepository(jdbcTemplate),
                assetRepository = AssetRepository(jdbcTemplate),
                messageRepository = MessageRepository(jdbcTemplate),
                mailVaultProperties = MailVaultProperties(storageDir = tempDir.resolve("storage").toString()),
                htmlRenderService =
                    HtmlRenderService(
                        MessageHtmlRepository(jdbcTemplate),
                        AssetRepository(jdbcTemplate),
                        HtmlSanitizerService(),
                    ),
                httpClient = fakeClient,
            )

        val result = service.freeze("m-1")

        assertEquals(1, result.totalFound)
        assertEquals(1, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(0, result.skipped)
        assertEquals(2, fakeClient.calls)
    }

    @Test
    fun `blocks 1x1 tracking candidates before any http call`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-2",
            "/tmp/m-2.eml",
            1L,
            1L,
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, html_raw)
            VALUES (?, ?)
            """.trimIndent(),
            "m-2",
            """<img src="https://example.com/image?id=abc&w=1&h=1" width="1" height="1" />""",
        )

        val fakeClient = FakeHttpClient(mutableListOf())
        service =
            AssetFreezeService(
                messageHtmlRepository = MessageHtmlRepository(jdbcTemplate),
                assetRepository = AssetRepository(jdbcTemplate),
                messageRepository = MessageRepository(jdbcTemplate),
                mailVaultProperties = MailVaultProperties(storageDir = tempDir.resolve("storage").toString()),
                htmlRenderService =
                    HtmlRenderService(
                        MessageHtmlRepository(jdbcTemplate),
                        AssetRepository(jdbcTemplate),
                        HtmlSanitizerService(),
                    ),
                httpClient = fakeClient,
            )

        val result = service.freeze("m-2")

        assertEquals(1, result.totalFound)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(0, fakeClient.calls)
    }

    @Test
    fun `blocks tracking keyword candidates before any http call`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-3",
            "/tmp/m-3.eml",
            1L,
            1L,
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, html_raw)
            VALUES (?, ?)
            """.trimIndent(),
            "m-3",
            """<img src="https://metrics.example.com/open-tracking/pixel.png?message_id=10" />""",
        )

        val fakeClient = FakeHttpClient(mutableListOf())
        service =
            AssetFreezeService(
                messageHtmlRepository = MessageHtmlRepository(jdbcTemplate),
                assetRepository = AssetRepository(jdbcTemplate),
                messageRepository = MessageRepository(jdbcTemplate),
                mailVaultProperties = MailVaultProperties(storageDir = tempDir.resolve("storage").toString()),
                htmlRenderService =
                    HtmlRenderService(
                        MessageHtmlRepository(jdbcTemplate),
                        AssetRepository(jdbcTemplate),
                        HtmlSanitizerService(),
                    ),
                httpClient = fakeClient,
            )

        val result = service.freeze("m-3")

        assertEquals(1, result.totalFound)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(0, fakeClient.calls)
    }

    @Test
    fun `marks freeze as skipped and ignored when message has no html body`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-4",
            "/tmp/m-4.eml",
            1L,
            1L,
        )

        val result = service.freeze("m-4")

        assertEquals(0, result.totalFound)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        val freezeIgnored =
            jdbcTemplate.queryForObject(
                "SELECT freeze_ignored FROM messages WHERE id = ?",
                Int::class.java,
                "m-4",
            )
        assertEquals(1, freezeIgnored)
        val reason =
            jdbcTemplate.queryForObject(
                "SELECT freeze_last_reason FROM messages WHERE id = ?",
                String::class.java,
                "m-4",
            )
        assertTrue(reason?.contains("message has no HTML body") == true)
    }

    @Test
    fun `marks freeze as skipped and ignored when html has no remote images`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-5",
            "/tmp/m-5.eml",
            1L,
            1L,
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, html_raw)
            VALUES (?, ?)
            """.trimIndent(),
            "m-5",
            """<div><p>No image here</p></div>""",
        )

        val result = service.freeze("m-5")

        assertEquals(0, result.totalFound)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        val freezeIgnored =
            jdbcTemplate.queryForObject(
                "SELECT freeze_ignored FROM messages WHERE id = ?",
                Int::class.java,
                "m-5",
            )
        assertEquals(1, freezeIgnored)
        val reason =
            jdbcTemplate.queryForObject(
                "SELECT freeze_last_reason FROM messages WHERE id = ?",
                String::class.java,
                "m-5",
            )
        assertTrue(reason?.contains("no remote images found") == true)
    }

    @Test
    fun `marks non-image response as skipped with explanatory note`() {
        jdbcTemplate.update(
            """
            INSERT INTO messages(id, file_path, file_mtime_epoch, file_size)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            "m-6",
            "/tmp/m-6.eml",
            1L,
            1L,
        )
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies(message_id, html_raw)
            VALUES (?, ?)
            """.trimIndent(),
            "m-6",
            """<img src="https://example.com/not-image" />""",
        )

        val fakeClient =
            FakeHttpClient(
                mutableListOf(
                    PlannedResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to listOf("text/html; charset=UTF-8")),
                        body = "<html>not-an-image</html>".toByteArray(StandardCharsets.UTF_8),
                    ),
                ),
            )
        service =
            AssetFreezeService(
                messageHtmlRepository = MessageHtmlRepository(jdbcTemplate),
                assetRepository = AssetRepository(jdbcTemplate),
                messageRepository = MessageRepository(jdbcTemplate),
                mailVaultProperties = MailVaultProperties(storageDir = tempDir.resolve("storage").toString()),
                htmlRenderService =
                    HtmlRenderService(
                        MessageHtmlRepository(jdbcTemplate),
                        AssetRepository(jdbcTemplate),
                        HtmlSanitizerService(),
                    ),
                httpClient = fakeClient,
            )

        val result = service.freeze("m-6")

        assertEquals(1, result.totalFound)
        assertEquals(0, result.downloaded)
        assertEquals(0, result.failed)
        assertEquals(1, result.skipped)
        val storedError =
            jdbcTemplate.queryForObject(
                "SELECT error FROM assets WHERE message_id = ?",
                String::class.java,
                "m-6",
            )
        assertTrue(storedError?.contains("content-type is not image") == true)
        val reason =
            jdbcTemplate.queryForObject(
                "SELECT freeze_last_reason FROM messages WHERE id = ?",
                String::class.java,
                "m-6",
            )
        assertTrue(reason?.contains("content-type is not image") == true)
    }

    private data class PlannedResponse(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    private class FakeHttpClient(
        private val planned: MutableList<PlannedResponse>,
    ) : HttpClient() {
        var calls: Int = 0
            private set

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            calls++
            val next = planned.removeFirst()
            @Suppress("UNCHECKED_CAST")
            return FakeHttpResponse(
                request = request,
                status = next.status,
                headers = HttpHeaders.of(next.headers) { _, _ -> true },
                body = ByteArrayInputStream(next.body) as T,
            )
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException())

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException())
    }

    private class FakeHttpResponse<T>(
        private val request: HttpRequest,
        private val status: Int,
        private val headers: HttpHeaders,
        private val body: T,
    ) : HttpResponse<T> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest = request

        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

        override fun headers(): HttpHeaders = headers

        override fun body(): T = body

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}

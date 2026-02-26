package dev.marcal.mailvault.web

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginLockoutIntegrationTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Value("\${local.server.port}")
    var port: Int = 0

    private val httpClient =
        HttpClient
            .newBuilder()
            .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build()

    @BeforeEach
    fun setupAuth() {
        jdbcTemplate.update("DELETE FROM app_meta WHERE key LIKE 'auth.%'")
        clearCookies()
        bootstrapAuth()
    }

    @Test
    fun `locks login after max failed attempts`() {
        repeat(3) {
            val failed = login("admin", "wrong")
            assertEquals(302, failed.statusCode())
            assertTrue(locationOf(failed).contains("/login?error"))
        }

        val blocked = login("admin", "secret")
        assertEquals(302, blocked.statusCode())
        assertTrue(locationOf(blocked).contains("/login?error"))
    }

    private fun bootstrapAuth() {
        val csrfToken = ensureCsrfToken("/setup")
        val payload = """{"username":"admin","password":"secret","language":"en"}"""
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("http://localhost:$port/api/setup/bootstrap"))
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(201, response.statusCode())
    }

    private fun login(
        username: String,
        password: String,
    ): HttpResponse<String> {
        val csrfToken = ensureCsrfToken("/login")
        val body =
            "username=${encode(username)}&password=${encode(password)}&_csrf=${encode(csrfToken)}"
        return httpClient.send(
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:$port/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun ensureCsrfToken(seedPath: String): String {
        val existing = findCookieValue("XSRF-TOKEN")
        if (!existing.isNullOrBlank()) {
            return existing
        }
        get(seedPath)
        return findCookieValue("XSRF-TOKEN") ?: error("missing XSRF-TOKEN cookie")
    }

    private fun get(path: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:$port$path"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun findCookieValue(name: String): String? =
        (httpClient.cookieHandler().orElse(null) as? CookieManager)
            ?.cookieStore
            ?.cookies
            ?.firstOrNull { it.name == name }
            ?.value

    private fun locationOf(response: HttpResponse<String>): String = response.headers().firstValue("Location").orElse("")

    private fun clearCookies() {
        (httpClient.cookieHandler().orElse(null) as? CookieManager)?.cookieStore?.removeAll()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val dbPath =
            Path
                .of(
                    System.getProperty("java.io.tmpdir"),
                    "mailvault-login-lockout-test-${UUID.randomUUID()}.db",
                ).toAbsolutePath()
                .normalize()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:$dbPath" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.url") { "jdbc:sqlite:$dbPath" }
            registry.add("spring.flyway.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.flyway.locations") { "classpath:db/migration" }
            registry.add("mailvault.authMaxFailedAttempts") { "3" }
            registry.add("mailvault.authLockoutSeconds") { "120" }
        }
    }
}

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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetupControllerSecurityIntegrationTest {
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
    fun resetAuth() {
        jdbcTemplate.update("DELETE FROM app_meta WHERE key IN ('auth.user', 'auth.passwordHash', 'ui.language')")
        clearCookies()
    }

    @Test
    fun `setup status reports install token requirement when configured`() {
        val response = get("/api/setup/status")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"configured\":false"))
        assertTrue(response.body().contains("\"requiresInstallToken\":true"))
    }

    @Test
    fun `setup bootstrap rejects missing install token`() {
        val response = bootstrap(installToken = null)
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("installToken is invalid"))
    }

    @Test
    fun `setup bootstrap accepts valid install token`() {
        val response = bootstrap(installToken = "test-install-token")
        assertEquals(201, response.statusCode())
        assertTrue(response.body().contains("\"configured\":true"))
    }

    private fun get(path: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:$port$path"))
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun bootstrap(installToken: String?): HttpResponse<String> {
        val csrfToken = ensureCsrfToken()
        val payload =
            if (installToken == null) {
                """{"username":"admin","password":"secret","language":"en"}"""
            } else {
                """{"username":"admin","password":"secret","language":"en","installToken":"$installToken"}"""
            }
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://localhost:$port/api/setup/bootstrap"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun clearCookies() {
        (httpClient.cookieHandler().orElse(null) as? CookieManager)?.cookieStore?.removeAll()
    }

    private fun ensureCsrfToken(): String {
        val existing = findCookieValue("XSRF-TOKEN")
        if (!existing.isNullOrBlank()) {
            return existing
        }
        get("/setup")
        return findCookieValue("XSRF-TOKEN") ?: error("missing XSRF-TOKEN cookie")
    }

    private fun findCookieValue(name: String): String? =
        (httpClient.cookieHandler().orElse(null) as? CookieManager)
            ?.cookieStore
            ?.cookies
            ?.firstOrNull { it.name == name }
            ?.value

    companion object {
        private val dbPath =
            Path
                .of(
                    System.getProperty("java.io.tmpdir"),
                    "mailvault-setup-security-test-${UUID.randomUUID()}.db",
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
            registry.add("mailvault.setupBootstrapToken") { "test-install-token" }
        }
    }
}

package dev.marcal.mailvault.config

import dev.marcal.mailvault.repository.AppMetaRepository
import dev.marcal.mailvault.service.AuthBootstrapService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Path
import kotlin.test.assertEquals

class SecurityConfigTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var authBootstrapService: AuthBootstrapService

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("security-config-test.db").toAbsolutePath().normalize()
        val dataSource =
            DriverManagerDataSource().apply {
                setDriverClassName("org.sqlite.JDBC")
                url = "jdbc:sqlite:$dbPath"
            }
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS app_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        authBootstrapService = AuthBootstrapService(AppMetaRepository(jdbcTemplate), BCryptPasswordEncoder())
    }

    @Test
    fun `setup matcher allows setup paths only when not configured`() {
        val matcher = SetupBootstrapRequestMatcher(authBootstrapService)

        assertEquals(true, matcher.matches(MockRequest(method = "GET", uri = "/")))
        assertEquals(true, matcher.matches(MockRequest(method = "GET", uri = "/setup")))
        assertEquals(true, matcher.matches(MockRequest(method = "GET", uri = "/setup.html")))
        assertEquals(true, matcher.matches(MockRequest(method = "GET", uri = "/api/setup/status")))
        assertEquals(true, matcher.matches(MockRequest(method = "POST", uri = "/api/setup/bootstrap")))
        assertEquals(false, matcher.matches(MockRequest(method = "GET", uri = "/api/messages")))

        authBootstrapService.bootstrap(username = "admin", password = "secret")

        assertEquals(false, matcher.matches(MockRequest(method = "GET", uri = "/setup")))
        assertEquals(false, matcher.matches(MockRequest(method = "POST", uri = "/api/setup/bootstrap")))
    }

    private class MockRequest(
        private val method: String,
        private val uri: String,
    ) : jakarta.servlet.http.HttpServletRequestWrapper(
            org.springframework.mock.web
                .MockHttpServletRequest(),
        ) {
        override fun getMethod(): String = method

        override fun getRequestURI(): String = uri
    }
}

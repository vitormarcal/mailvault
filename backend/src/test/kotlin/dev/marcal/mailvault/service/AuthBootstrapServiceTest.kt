package dev.marcal.mailvault.service

import dev.marcal.mailvault.repository.AppMetaRepository
import dev.marcal.mailvault.util.ValidationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AuthBootstrapServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var service: AuthBootstrapService

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("auth-bootstrap-test.db").toAbsolutePath().normalize()
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
        service = AuthBootstrapService(AppMetaRepository(jdbcTemplate), BCryptPasswordEncoder())
    }

    @Test
    fun `bootstraps credentials once and stores password hash`() {
        service.bootstrap(username = "admin", password = "secret")

        val credentials = service.credentials()
        assertEquals("admin", credentials?.username)
        assertEquals(true, credentials?.passwordHash?.startsWith("$2") == true)
        assertEquals(true, service.isConfigured())
    }

    @Test
    fun `rejects second bootstrap`() {
        service.bootstrap(username = "admin", password = "secret")

        val ex =
            assertFailsWith<ValidationException> {
                service.bootstrap(username = "other", password = "another")
            }
        assertEquals("Credentials already configured", ex.message)
    }

    @Test
    fun `changes password when current password is valid`() {
        service.bootstrap(username = "admin", password = "secret")
        val beforeHash = service.credentials()?.passwordHash

        service.changePassword(currentPassword = "secret", newPassword = "new-secret")

        val afterCredentials = service.credentials()
        assertEquals("admin", afterCredentials?.username)
        assertNotEquals(beforeHash, afterCredentials?.passwordHash)
        assertEquals(true, BCryptPasswordEncoder().matches("new-secret", afterCredentials?.passwordHash.orEmpty()))
    }

    @Test
    fun `rejects password change with invalid current password`() {
        service.bootstrap(username = "admin", password = "secret")

        val ex =
            assertFailsWith<ValidationException> {
                service.changePassword(currentPassword = "wrong", newPassword = "new-secret")
            }
        assertEquals("currentPassword is invalid", ex.message)
    }
}

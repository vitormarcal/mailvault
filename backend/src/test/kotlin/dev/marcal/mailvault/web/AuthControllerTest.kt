package dev.marcal.mailvault.web

import dev.marcal.mailvault.api.ChangePasswordRequest
import dev.marcal.mailvault.repository.AppMetaRepository
import dev.marcal.mailvault.service.AuthBootstrapService
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

class AuthControllerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: AuthBootstrapService
    private lateinit var controller: AuthController

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("auth-controller-test.db").toAbsolutePath().normalize()
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.sqlite.JDBC")
            url = "jdbc:sqlite:$dbPath"
        }
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS app_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )

        service = AuthBootstrapService(AppMetaRepository(jdbcTemplate), BCryptPasswordEncoder())
        service.bootstrap(username = "admin", password = "secret")
        controller = AuthController(service)
    }

    @Test
    fun `changes password and returns ok true`() {
        val response =
            controller.changePassword(
                ChangePasswordRequest(
                    currentPassword = "secret",
                    newPassword = "new-secret",
                ),
            )

        assertEquals(true, response.ok)
        val credentials = service.credentials()
        assertEquals(true, BCryptPasswordEncoder().matches("new-secret", credentials?.passwordHash.orEmpty()))
    }

    @Test
    fun `throws validation error when current password is invalid`() {
        val ex =
            assertFailsWith<ValidationException> {
                controller.changePassword(
                    ChangePasswordRequest(
                        currentPassword = "wrong",
                        newPassword = "new-secret",
                    ),
                )
            }

        assertEquals("currentPassword is invalid", ex.message)
    }
}

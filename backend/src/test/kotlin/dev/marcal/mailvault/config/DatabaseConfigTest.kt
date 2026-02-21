package dev.marcal.mailvault.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `creates sqlite parent directory when needed`() {
        val dbPath = tempDir.resolve("nested").resolve("data").resolve("mailvault.db")
        val config = DatabaseConfig("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")

        val dataSource = config.dataSource() as DriverManagerDataSource

        assertTrue(Files.exists(dbPath.parent))
        assertEquals("jdbc:sqlite:$dbPath", dataSource.url)
    }

    @Test
    fun `does not require sqlite path for non sqlite url`() {
        val config = DatabaseConfig("jdbc:custom:mem:test", "org.sqlite.JDBC")
        val dataSource = config.dataSource() as DriverManagerDataSource
        assertEquals("jdbc:custom:mem:test", dataSource.url)
    }
}

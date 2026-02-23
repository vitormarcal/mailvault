package dev.marcal.mailvault

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID

@SpringBootTest
class MailVaultApplicationTests {
    @Test
    fun contextLoads() {
    }

    companion object {
        private val dbPath =
            Path
                .of(
                    System.getProperty("java.io.tmpdir"),
                    "mailvault-context-${UUID.randomUUID()}.db",
                ).toAbsolutePath()
                .normalize()
        private val indexRootDir =
            Path
                .of(
                    System.getProperty("java.io.tmpdir"),
                    "mailvault-context-index-${UUID.randomUUID()}",
                ).toAbsolutePath()
                .normalize()
        private val storageRootDir =
            Path
                .of(
                    System.getProperty("java.io.tmpdir"),
                    "mailvault-context-storage-${UUID.randomUUID()}",
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
            registry.add("mailvault.rootEmailsDir") { indexRootDir.toString() }
            registry.add("mailvault.storageDir") { storageRootDir.toString() }
        }
    }
}

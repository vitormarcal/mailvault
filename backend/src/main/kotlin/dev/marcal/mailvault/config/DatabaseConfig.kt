package dev.marcal.mailvault.config

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Paths
import javax.sql.DataSource

@Configuration
class DatabaseConfig(
    @Value("\${spring.datasource.url}") private val datasourceUrl: String,
    @Value("\${spring.datasource.driver-class-name}") private val driverClassName: String,
) {
    @Bean
    fun dataSource(): DataSource {
        if (datasourceUrl.startsWith("jdbc:sqlite:")) {
            val rawPath = datasourceUrl.removePrefix("jdbc:sqlite:")
            val dbPath = Paths.get(rawPath).normalize()
            dbPath.parent?.let { Files.createDirectories(it) }
        }

        val dataSource = DriverManagerDataSource()
        dataSource.setDriverClassName(driverClassName)
        dataSource.url = datasourceUrl
        return dataSource
    }

    @Bean(initMethod = "migrate")
    fun flyway(dataSource: DataSource): Flyway = Flyway.configure().dataSource(dataSource).load()
}

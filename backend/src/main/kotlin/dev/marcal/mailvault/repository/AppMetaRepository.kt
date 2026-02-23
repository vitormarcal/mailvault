package dev.marcal.mailvault.repository

import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AppMetaRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun get(key: String): String? =
        try {
            jdbcTemplate.queryForObject(
                "SELECT value FROM app_meta WHERE key = ?",
                String::class.java,
                key,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    fun put(key: String, value: String) {
        jdbcTemplate.update(
            """
            INSERT INTO app_meta (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value
            """.trimIndent(),
            key,
            value,
        )
    }
}

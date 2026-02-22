package dev.marcal.mailvault.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.streams.asSequence

data class IndexResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

@Service
class IndexerService(
    private val jdbcTemplate: JdbcTemplate,
    private val emlHeaderParser: EmlHeaderParser,
    @Value("\${mailvault.index.root-dir}") private val rootDir: String,
) {
    private val logger = LoggerFactory.getLogger(IndexerService::class.java)

    fun index(): IndexResult {
        val rootPath = Path.of(rootDir).toAbsolutePath().normalize()
        require(Files.exists(rootPath) && Files.isDirectory(rootPath)) {
            "Invalid rootDir: $rootDir"
        }

        var inserted = 0
        var updated = 0
        var skipped = 0

        Files.walk(rootPath).use { stream ->
            stream
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".eml", ignoreCase = true) }
                .forEach { filePath ->
                    val normalizedPath = filePath.toAbsolutePath().normalize().toString()
                    val mtime = Files.getLastModifiedTime(filePath).toMillis()
                    val size = Files.size(filePath)

                    val existing = findByFilePath(normalizedPath)
                    if (existing != null && existing.fileMtimeEpoch == mtime && existing.fileSize == size) {
                        skipped++
                        return@forEach
                    }

                    val headers = emlHeaderParser.parse(filePath)
                    val textPlain = extractTextPlain(filePath)
                    val id = sha256Hex("${headers.messageId ?: ""}|$normalizedPath")

                    upsertMessage(
                        id = id,
                        filePath = normalizedPath,
                        fileMtimeEpoch = mtime,
                        fileSize = size,
                        dateRaw = headers.date,
                        subject = headers.subject,
                        fromRaw = headers.from,
                        messageId = headers.messageId,
                    )
                    upsertMessageBody(id = id, textPlain = textPlain)

                    if (existing == null) {
                        inserted++
                    } else {
                        updated++
                    }
                }
        }

        return IndexResult(inserted = inserted, updated = updated, skipped = skipped)
    }

    private fun findByFilePath(filePath: String): ExistingMessage? =
        try {
            jdbcTemplate.queryForObject(
                """
                SELECT file_mtime_epoch, file_size
                FROM messages
                WHERE file_path = ?
                """.trimIndent(),
                { rs, _ ->
                    ExistingMessage(
                        fileMtimeEpoch = rs.getLong("file_mtime_epoch"),
                        fileSize = rs.getLong("file_size"),
                    )
                },
                filePath,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

    private fun upsertMessage(
        id: String,
        filePath: String,
        fileMtimeEpoch: Long,
        fileSize: Long,
        dateRaw: String?,
        subject: String?,
        fromRaw: String?,
        messageId: String?,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO messages (
                id, file_path, file_mtime_epoch, file_size, date_raw, subject, from_raw, message_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(file_path) DO UPDATE SET
                id = excluded.id,
                file_mtime_epoch = excluded.file_mtime_epoch,
                file_size = excluded.file_size,
                date_raw = excluded.date_raw,
                subject = excluded.subject,
                from_raw = excluded.from_raw,
                message_id = excluded.message_id
            """.trimIndent(),
            id,
            filePath,
            fileMtimeEpoch,
            fileSize,
            dateRaw,
            subject,
            fromRaw,
            messageId,
        )
    }

    private fun upsertMessageBody(id: String, textPlain: String?) {
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies (message_id, text_plain)
            VALUES (?, ?)
            ON CONFLICT(message_id) DO UPDATE SET
                text_plain = excluded.text_plain
            """.trimIndent(),
            id,
            textPlain,
        )
    }

    private fun extractTextPlain(filePath: Path): String? {
        val raw = Files.readString(filePath, StandardCharsets.UTF_8)
        val separator = findHeaderBodySeparator(raw)

        if (separator == null) {
            logger.info("EML without header/body separator, storing empty text_plain: {}", filePath)
            return null
        }

        val headerSection = raw.substring(0, separator.first)
        if (MULTIPART_CONTENT_TYPE_REGEX.containsMatchIn(headerSection)) {
            logger.info("Multipart MIME not parsed yet, storing empty text_plain: {}", filePath)
            return null
        }

        return raw.substring(separator.second)
    }

    private fun findHeaderBodySeparator(raw: String): Pair<Int, Int>? {
        val crlfIndex = raw.indexOf("\r\n\r\n")
        if (crlfIndex >= 0) {
            return Pair(crlfIndex, crlfIndex + 4)
        }

        val lfIndex = raw.indexOf("\n\n")
        if (lfIndex >= 0) {
            return Pair(lfIndex, lfIndex + 2)
        }

        return null
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class ExistingMessage(
        val fileMtimeEpoch: Long,
        val fileSize: Long,
    )

    private companion object {
        val MULTIPART_CONTENT_TYPE_REGEX = Regex("(?im)^content-type\\s*:\\s*multipart/")
    }

}

package dev.marcal.mailvault.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.streams.asSequence

data class IndexResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

@Service
class IndexerService(
    private val jdbcTemplate: JdbcTemplate,
    private val messageParseService: MessageParseService,
    @Value("\${mailvault.index.root-dir}") private val rootDir: String,
    @Value("\${mailvault.storageDir}") private val storageDir: String,
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

                    try {
                        val parsed = messageParseService.parse(filePath)
                        val id = sha256Hex("${parsed.messageId ?: ""}|$normalizedPath")

                        upsertMessage(
                            id = id,
                            filePath = normalizedPath,
                            fileMtimeEpoch = mtime,
                            fileSize = size,
                            dateRaw = parsed.dateRaw,
                            subject = parsed.subject,
                            fromRaw = parsed.fromRaw,
                            messageId = parsed.messageId,
                        )

                        upsertMessageBody(
                            id = id,
                            textPlain = parsed.textPlain,
                            htmlRaw = parsed.htmlRaw,
                        )

                        replaceAttachments(id, parsed.attachments)

                        if (existing == null) {
                            inserted++
                        } else {
                            updated++
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to index file {}", filePath, e)
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

    private fun upsertMessageBody(id: String, textPlain: String?, htmlRaw: String?) {
        jdbcTemplate.update(
            """
            INSERT INTO message_bodies (message_id, text_plain, html_raw, html_sanitized)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(message_id) DO UPDATE SET
                text_plain = excluded.text_plain,
                html_raw = excluded.html_raw,
                html_sanitized = excluded.html_sanitized
            """.trimIndent(),
            id,
            textPlain,
            htmlRaw,
            null,
        )
    }

    private fun replaceAttachments(messageId: String, attachments: List<ParsedAttachment>) {
        val baseDir = attachmentMessageDir(messageId)
        deleteDirectoryIfExists(baseDir)
        Files.createDirectories(baseDir)

        jdbcTemplate.update("DELETE FROM attachments WHERE message_id = ?", messageId)

        attachments.forEachIndexed { index, attachment ->
            val sha256 = sha256Hex(attachment.bytes)
            val storagePath = baseDir.resolve(sha256)
            Files.write(storagePath, attachment.bytes)

            val attachmentId =
                sha256Hex(
                    "$messageId|$index|${attachment.filename ?: ""}|${attachment.inlineCid ?: ""}|$sha256",
                )

            jdbcTemplate.update(
                """
                INSERT INTO attachments (
                    id, message_id, filename, content_type, size, inline_cid, storage_path, sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                attachmentId,
                messageId,
                attachment.filename,
                attachment.contentType,
                attachment.size,
                attachment.inlineCid,
                storagePath.toAbsolutePath().normalize().toString(),
                sha256,
            )
        }
    }

    private fun attachmentMessageDir(messageId: String): Path =
        Path.of(storageDir)
            .toAbsolutePath()
            .normalize()
            .resolve("attachments")
            .resolve(messageId)

    private fun deleteDirectoryIfExists(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::delete)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class ExistingMessage(
        val fileMtimeEpoch: Long,
        val fileSize: Long,
    )
}

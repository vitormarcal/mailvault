package dev.marcal.mailvault.service

import dev.marcal.mailvault.domain.AttachmentUpsert
import dev.marcal.mailvault.domain.MessageBodyUpsert
import dev.marcal.mailvault.domain.MessageUpsert
import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.IndexWriteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
    private val indexWriteRepository: IndexWriteRepository,
    private val messageParseService: MessageParseService,
    private val attachmentStorageService: AttachmentStorageService,
    private val mailVaultProperties: MailVaultProperties,
) {
    private val logger = LoggerFactory.getLogger(IndexerService::class.java)

    fun index(): IndexResult {
        val rootPath = Path.of(mailVaultProperties.rootEmailsDir).toAbsolutePath().normalize()
        require(Files.exists(rootPath) && Files.isDirectory(rootPath)) {
            "Invalid rootDir: ${mailVaultProperties.rootEmailsDir}"
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

                    val existing = indexWriteRepository.findByFilePath(normalizedPath)
                    if (
                        existing != null &&
                        existing.fileMtimeEpoch == mtime &&
                        existing.fileSize == size &&
                        existing.hasBodyContent &&
                        existing.hasDateEpoch
                    ) {
                        skipped++
                        return@forEach
                    }

                    try {
                        val parsed = messageParseService.parse(filePath)
                        val messageId = sha256Hex("${parsed.messageId ?: ""}|$normalizedPath")

                        indexWriteRepository.upsertMessage(
                            MessageUpsert(
                                id = messageId,
                                filePath = normalizedPath,
                                fileMtimeEpoch = mtime,
                                fileSize = size,
                                dateRaw = parsed.dateRaw,
                                dateEpoch = parsed.dateEpoch,
                                subject = parsed.subject,
                                fromRaw = parsed.fromRaw,
                                messageId = parsed.messageId,
                            ),
                        )

                        indexWriteRepository.upsertMessageBody(
                            MessageBodyUpsert(
                                messageId = messageId,
                                textPlain = parsed.textPlain,
                                htmlRaw = parsed.htmlRaw,
                                htmlSanitized = null,
                            ),
                        )

                        val attachmentRecords = persistAttachments(messageId, parsed.attachments)
                        indexWriteRepository.replaceAttachments(messageId, attachmentRecords)

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

    private fun persistAttachments(
        messageId: String,
        attachments: List<ParsedAttachment>,
    ): List<AttachmentUpsert> {
        val messageDir = attachmentMessageDir(messageId)
        attachmentStorageService.resetMessageDirectory(messageDir)

        return attachments.mapIndexed { index, attachment ->
            val sha256 = sha256Hex(attachment.bytes)
            val storedPath = attachmentStorageService.writeAttachment(messageDir, sha256, attachment.bytes)
            val attachmentId =
                sha256Hex("$messageId|$index|${attachment.filename ?: ""}|${attachment.inlineCid ?: ""}|$sha256")

            AttachmentUpsert(
                id = attachmentId,
                messageId = messageId,
                filename = attachment.filename,
                contentType = attachment.contentType,
                size = attachment.size,
                inlineCid = attachment.inlineCid,
                storagePath = storedPath.toAbsolutePath().normalize().toString(),
                sha256 = sha256,
            )
        }
    }

    private fun attachmentMessageDir(messageId: String): Path =
        Path.of(mailVaultProperties.storageDir)
            .toAbsolutePath()
            .normalize()
            .resolve("attachments")
            .resolve(messageId)

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
}

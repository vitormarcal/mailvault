package dev.marcal.mailvault.service

import dev.marcal.mailvault.domain.AttachmentUpsert
import dev.marcal.mailvault.domain.MessageBodyUpsert
import dev.marcal.mailvault.domain.MessageUpsert
import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.IndexWriteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.concurrent.Executors
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
    private val assetRepository: AssetRepository,
    private val assetFreezeService: AssetFreezeService,
    private val mailVaultProperties: MailVaultProperties,
) {
    private val logger = LoggerFactory.getLogger(IndexerService::class.java)

    fun index(): IndexResult {
        val startedAtNs = System.nanoTime()
        val rootPath = Path.of(mailVaultProperties.rootEmailsDir).toAbsolutePath().normalize()
        require(Files.exists(rootPath) && Files.isDirectory(rootPath)) {
            "Invalid rootDir: ${mailVaultProperties.rootEmailsDir}"
        }

        var inserted = 0
        var updated = 0
        var skipped = 0
        val freezeCandidates = mutableListOf<String>()
        val freezeMaxMessages = mailVaultProperties.freezeOnIndexMaxMessages.coerceAtLeast(0)

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
                                htmlText = parsed.htmlText,
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

                        if (
                            mailVaultProperties.freezeOnIndex &&
                            freezeCandidates.size < freezeMaxMessages &&
                            shouldScheduleFreeze(messageId, parsed.htmlRaw)
                        ) {
                            freezeCandidates += messageId
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to index file {}", filePath, e)
                    }
                }
        }

        runAutoFreezeIfEnabled(freezeCandidates)

        val durationMs = (System.nanoTime() - startedAtNs) / 1_000_000
        indexWriteRepository.putMeta("lastIndexAt", OffsetDateTime.now().toString())
        indexWriteRepository.putMeta("lastIndexDurationMs", durationMs.toString())

        return IndexResult(inserted = inserted, updated = updated, skipped = skipped)
    }

    private fun shouldScheduleFreeze(messageId: String, htmlRaw: String?): Boolean {
        if (htmlRaw.isNullOrBlank() || !REMOTE_IMG_SRC_REGEX.containsMatchIn(htmlRaw)) {
            return false
        }
        if (assetRepository.hasDownloadedByMessageId(messageId)) {
            return false
        }
        return true
    }

    private fun runAutoFreezeIfEnabled(candidates: List<String>) {
        if (!mailVaultProperties.freezeOnIndex || candidates.isEmpty()) {
            return
        }

        val maxMessages = mailVaultProperties.freezeOnIndexMaxMessages.coerceAtLeast(0)
        val targets = candidates.distinct().take(maxMessages)
        if (targets.isEmpty()) {
            return
        }

        val concurrency = mailVaultProperties.freezeOnIndexConcurrency.coerceIn(1, 8)
        val executor = Executors.newFixedThreadPool(concurrency)
        try {
            val futures =
                targets.map { messageId ->
                    executor.submit<Unit> {
                        try {
                            val result = assetFreezeService.freeze(messageId)
                            logger.info(
                                "Auto-freeze summary messageId={} downloaded={} failed={} skipped={}",
                                messageId,
                                result.downloaded,
                                result.failed,
                                result.skipped,
                            )
                        } catch (e: Exception) {
                            logger.warn("Auto-freeze failed for messageId={} reason={}", messageId, e.message)
                        }
                    }
                }
            futures.forEach {
                runCatching { it.get() }
                    .onFailure { e -> logger.warn("Auto-freeze task join failure reason={}", e.message) }
            }
        } finally {
            executor.shutdown()
        }
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

    private companion object {
        val REMOTE_IMG_SRC_REGEX = Regex("""(?i)<img\b[^>]*\bsrc\s*=\s*["']https?://""")
    }
}

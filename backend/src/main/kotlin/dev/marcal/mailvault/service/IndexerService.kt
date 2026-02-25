package dev.marcal.mailvault.service

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.domain.AttachmentUpsert
import dev.marcal.mailvault.domain.MessageBodyUpsert
import dev.marcal.mailvault.domain.MessageUpsert
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.streams.asSequence

data class IndexResult(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
)

data class IndexProgress(
    val phase: IndexProgressPhase,
    val totalFiles: Int?,
    val processedFiles: Int?,
    val freezeTotal: Int?,
    val freezeCompleted: Int?,
)

enum class IndexProgressPhase {
    INDEXING,
    FREEZING,
}

enum class IndexMode {
    INCREMENTAL,
    FULL,
}

@Service
class IndexerService(
    private val indexWriteRepository: IndexWriteRepository,
    private val messageParseService: MessageParseService,
    private val attachmentStorageService: AttachmentStorageService,
    private val assetRepository: AssetRepository,
    private val assetFreezeService: AssetFreezeService,
    private val uiFreezeOnIndexService: UiFreezeOnIndexService,
    private val mailVaultProperties: MailVaultProperties,
) {
    private val logger = LoggerFactory.getLogger(IndexerService::class.java)

    fun index(): IndexResult = index(IndexMode.INCREMENTAL, null)

    fun index(progressListener: ((IndexProgress) -> Unit)?): IndexResult = index(IndexMode.INCREMENTAL, progressListener)

    fun reindex(progressListener: ((IndexProgress) -> Unit)?): IndexResult = index(IndexMode.FULL, progressListener)

    fun index(
        mode: IndexMode,
        progressListener: ((IndexProgress) -> Unit)?,
    ): IndexResult {
        val startedAtNs = System.nanoTime()
        val rootPath = Path.of(mailVaultProperties.rootEmailsDir).toAbsolutePath().normalize()
        require(Files.exists(rootPath) && Files.isDirectory(rootPath)) {
            "Invalid rootDir: ${mailVaultProperties.rootEmailsDir}"
        }
        val totalFiles = countEmlFiles(rootPath)
        progressListener?.invoke(
            IndexProgress(
                phase = IndexProgressPhase.INDEXING,
                totalFiles = totalFiles,
                processedFiles = 0,
                freezeTotal = null,
                freezeCompleted = null,
            ),
        )
        val freezeOnIndexEnabled = uiFreezeOnIndexService.isEnabled()
        logger.info(
            "Index start rootDir={} mode={} freezeOnIndex={} freezeConcurrency={}",
            rootPath,
            mode,
            freezeOnIndexEnabled,
            mailVaultProperties.freezeOnIndexConcurrency,
        )

        var inserted = 0
        var updated = 0
        var skipped = 0
        var processed = 0
        val freezeCandidates = mutableListOf<String>()
        val startedDirectories = mutableSetOf<Path>()
        val indexedEmlCountByDirectory = mutableMapOf<Path, Int>()
        var indexError: Exception? = null

        try {
            Files.walk(rootPath).use { stream ->
                stream
                    .asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".eml", ignoreCase = true) }
                    .forEach { filePath ->
                        try {
                            val directoryPath = (filePath.parent ?: rootPath).toAbsolutePath().normalize()
                            if (startedDirectories.add(directoryPath)) {
                                logger.info("Indexing directory path={}", directoryPath)
                            }
                            indexedEmlCountByDirectory[directoryPath] =
                                (indexedEmlCountByDirectory[directoryPath] ?: 0) + 1

                            val normalizedPath = filePath.toAbsolutePath().normalize().toString()
                            val mtime = Files.getLastModifiedTime(filePath).toMillis()
                            val size = Files.size(filePath)

                            val existing = indexWriteRepository.findByFilePath(normalizedPath)
                            val shouldSkipInIncrementalMode =
                                mode == IndexMode.INCREMENTAL &&
                                    existing != null &&
                                    existing.fileMtimeEpoch == mtime &&
                                    existing.fileSize == size &&
                                    existing.hasBodyContent &&
                                    existing.hasDateEpoch
                            if (shouldSkipInIncrementalMode) {
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
                                        subjectDisplay = parsed.subjectDisplay,
                                        fromRaw = parsed.fromRaw,
                                        fromDisplay = parsed.fromDisplay,
                                        fromEmail = parsed.fromEmail,
                                        fromName = parsed.fromName,
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
                                    freezeOnIndexEnabled &&
                                    !(existing?.freezeIgnored ?: false) &&
                                    shouldScheduleFreeze(
                                        messageId = messageId,
                                        htmlRaw = parsed.htmlRaw,
                                        skipIfAlreadyDownloaded = mode == IndexMode.INCREMENTAL,
                                    )
                                ) {
                                    freezeCandidates += messageId
                                }
                            } catch (e: Exception) {
                                logger.error("Failed to index file {}", filePath, e)
                            }
                        } finally {
                            processed++
                            progressListener?.invoke(
                                IndexProgress(
                                    phase = IndexProgressPhase.INDEXING,
                                    totalFiles = totalFiles,
                                    processedFiles = processed,
                                    freezeTotal = null,
                                    freezeCompleted = null,
                                ),
                            )
                        }
                    }
            }

            indexedEmlCountByDirectory.entries
                .sortedBy { it.key.toString() }
                .forEach { (directoryPath, indexedEmlCount) ->
                    logger.info(
                        "Directory index complete path={} emlIndexed={}",
                        directoryPath,
                        indexedEmlCount,
                    )
                }

            runAutoFreezeIfEnabled(
                freezeOnIndexEnabled = freezeOnIndexEnabled,
                candidates = freezeCandidates,
                progressListener = progressListener,
                totalFiles = totalFiles,
                processedFiles = processed,
            )

            val durationMs = (System.nanoTime() - startedAtNs) / 1_000_000
            indexWriteRepository.putMeta("lastIndexAt", OffsetDateTime.now().toString())
            indexWriteRepository.putMeta("lastIndexDurationMs", durationMs.toString())

            return IndexResult(inserted = inserted, updated = updated, skipped = skipped)
        } catch (e: Exception) {
            indexError = e
            logger.error(
                "Index failed rootDir={} inserted={} updated={} skipped={} freezeCandidates={} reason={}",
                rootPath,
                inserted,
                updated,
                skipped,
                freezeCandidates.distinct().size,
                e.message,
                e,
            )
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startedAtNs) / 1_000_000
            logger.info(
                "Index finish status={} inserted={} updated={} skipped={} freezeCandidates={} durationMs={}",
                if (indexError == null) "ok" else "error",
                inserted,
                updated,
                skipped,
                freezeCandidates.distinct().size,
                durationMs,
            )
        }
    }

    private fun shouldScheduleFreeze(
        messageId: String,
        htmlRaw: String?,
        skipIfAlreadyDownloaded: Boolean,
    ): Boolean {
        if (htmlRaw.isNullOrBlank() || !REMOTE_IMG_SRC_REGEX.containsMatchIn(htmlRaw)) {
            return false
        }
        if (skipIfAlreadyDownloaded && assetRepository.hasDownloadedByMessageId(messageId)) {
            return false
        }
        return true
    }

    private fun runAutoFreezeIfEnabled(
        freezeOnIndexEnabled: Boolean,
        candidates: List<String>,
        progressListener: ((IndexProgress) -> Unit)?,
        totalFiles: Int,
        processedFiles: Int,
    ) {
        if (!freezeOnIndexEnabled || candidates.isEmpty()) {
            if (freezeOnIndexEnabled) {
                logger.info("Auto-freeze skipped candidates=0 reason=no candidates")
                progressListener?.invoke(
                    IndexProgress(
                        phase = IndexProgressPhase.FREEZING,
                        totalFiles = totalFiles,
                        processedFiles = processedFiles,
                        freezeTotal = 0,
                        freezeCompleted = 0,
                    ),
                )
            }
            return
        }

        val targets = candidates.distinct()
        if (targets.isEmpty()) {
            logger.info("Auto-freeze skipped candidates=0 reason=dedup empty")
            progressListener?.invoke(
                IndexProgress(
                    phase = IndexProgressPhase.FREEZING,
                    totalFiles = totalFiles,
                    processedFiles = processedFiles,
                    freezeTotal = 0,
                    freezeCompleted = 0,
                ),
            )
            return
        }
        val autoFreezeStartedAtNs = System.nanoTime()
        logger.info(
            "Auto-freeze start candidates={} concurrency={}",
            targets.size,
            mailVaultProperties.freezeOnIndexConcurrency,
        )
        progressListener?.invoke(
            IndexProgress(
                phase = IndexProgressPhase.FREEZING,
                totalFiles = totalFiles,
                processedFiles = processedFiles,
                freezeTotal = targets.size,
                freezeCompleted = 0,
            ),
        )

        val concurrency = mailVaultProperties.freezeOnIndexConcurrency.coerceIn(1, 8)
        val executor = Executors.newFixedThreadPool(concurrency)
        val completed = AtomicInteger(0)
        val taskFailures = AtomicInteger(0)
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
                            taskFailures.incrementAndGet()
                            logger.warn("Auto-freeze failed for messageId={} reason={}", messageId, e.message)
                        } finally {
                            val completedCount = completed.incrementAndGet()
                            progressListener?.invoke(
                                IndexProgress(
                                    phase = IndexProgressPhase.FREEZING,
                                    totalFiles = totalFiles,
                                    processedFiles = processedFiles,
                                    freezeTotal = targets.size,
                                    freezeCompleted = completedCount,
                                ),
                            )
                        }
                    }
                }
            futures.forEach {
                runCatching { it.get() }
                    .onFailure { e ->
                        taskFailures.incrementAndGet()
                        logger.warn("Auto-freeze task join failure reason={}", e.message)
                    }
            }
        } finally {
            executor.shutdown()
            val durationMs = (System.nanoTime() - autoFreezeStartedAtNs) / 1_000_000
            logger.info(
                "Auto-freeze finish candidates={} completed={} taskFailures={} durationMs={}",
                targets.size,
                completed.get(),
                taskFailures.get(),
                durationMs,
            )
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
        Path
            .of(mailVaultProperties.storageDir)
            .toAbsolutePath()
            .normalize()
            .resolve("attachments")
            .resolve(messageId)

    private fun countEmlFiles(rootPath: Path): Int =
        Files
            .walk(rootPath)
            .use { stream ->
                stream
                    .asSequence()
                    .filter { Files.isRegularFile(it) }
                    .count { it.fileName.toString().endsWith(".eml", ignoreCase = true) }
            }.toInt()

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

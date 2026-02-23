package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.CleanupResponse
import dev.marcal.mailvault.api.ResetIndexedDataResponse
import dev.marcal.mailvault.api.VacuumResponse
import dev.marcal.mailvault.config.MailVaultProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.isDirectory

@Service
class MaintenanceService(
    private val jdbcTemplate: JdbcTemplate,
    private val mailVaultProperties: MailVaultProperties,
) {
    private val logger = LoggerFactory.getLogger(MaintenanceService::class.java)

    fun cleanup(): CleanupResponse {
        val storageRoot = Path.of(mailVaultProperties.storageDir).toAbsolutePath().normalize()
        val attachmentsRoot = storageRoot.resolve("attachments")
        val assetsRoot = storageRoot.resolve("assets")

        val knownAttachmentPaths =
            jdbcTemplate.query(
                "SELECT storage_path FROM attachments WHERE storage_path IS NOT NULL AND TRIM(storage_path) <> ''",
            ) { rs, _ -> rs.getString("storage_path") }
                .map { Path.of(it).toAbsolutePath().normalize() }
                .toSet()

        val knownAssetPaths =
            jdbcTemplate.query(
                "SELECT storage_path FROM assets WHERE storage_path IS NOT NULL AND TRIM(storage_path) <> ''",
            ) { rs, _ -> rs.getString("storage_path") }
                .map { Path.of(it).toAbsolutePath().normalize() }
                .toSet()

        val removedAttachmentFiles = removeOrphanFiles(attachmentsRoot, knownAttachmentPaths)
        val removedAssetFiles = removeOrphanFiles(assetsRoot, knownAssetPaths)
        val removedAttachmentDirs = removeEmptyDirectories(attachmentsRoot)
        val removedAssetDirs = removeEmptyDirectories(assetsRoot)
        val removedMissingMessageRows = removeMissingMessageRows()

        logger.info(
            "Maintenance cleanup summary removedAttachmentFiles={} removedAssetFiles={} removedAttachmentDirs={} removedAssetDirs={} removedMissingMessageRows={}",
            removedAttachmentFiles,
            removedAssetFiles,
            removedAttachmentDirs,
            removedAssetDirs,
            removedMissingMessageRows,
        )

        return CleanupResponse(
            removedAttachmentFiles = removedAttachmentFiles,
            removedAssetFiles = removedAssetFiles,
            removedAttachmentDirs = removedAttachmentDirs,
            removedAssetDirs = removedAssetDirs,
            removedMissingMessageRows = removedMissingMessageRows,
        )
    }

    fun vacuum(): VacuumResponse {
        val startedAt = System.nanoTime()
        jdbcTemplate.execute("VACUUM")
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info("Maintenance vacuum completed durationMs={}", durationMs)
        return VacuumResponse(ok = true, durationMs = durationMs)
    }

    fun resetIndexedData(): ResetIndexedDataResponse {
        val totalStartedAt = System.nanoTime()
        val storageRoot = Path.of(mailVaultProperties.storageDir).toAbsolutePath().normalize()
        val attachmentsRoot = storageRoot.resolve("attachments")
        val assetsRoot = storageRoot.resolve("assets")

        val removedMessages = countRows("messages")
        val removedMessageBodies = countRows("message_bodies")
        val removedAttachmentsRows = countRows("attachments")
        val removedAssetsRows = countRows("assets")

        jdbcTemplate.update("DELETE FROM assets")
        jdbcTemplate.update("DELETE FROM attachments")
        jdbcTemplate.update("DELETE FROM message_bodies")
        jdbcTemplate.update("DELETE FROM messages")

        val removedAttachmentFiles = removeAllFiles(attachmentsRoot)
        val removedAssetFiles = removeAllFiles(assetsRoot)
        val removedAttachmentDirs = removeAllSubdirectories(attachmentsRoot)
        val removedAssetDirs = removeAllSubdirectories(assetsRoot)

        val vacuumStartedAt = System.nanoTime()
        jdbcTemplate.execute("VACUUM")
        val vacuumDurationMs = (System.nanoTime() - vacuumStartedAt) / 1_000_000
        val totalDurationMs = (System.nanoTime() - totalStartedAt) / 1_000_000

        logger.warn(
            "Maintenance reset-indexed-data completed removedMessages={} removedMessageBodies={} removedAttachmentsRows={} removedAssetsRows={} removedAttachmentFiles={} removedAssetFiles={} removedAttachmentDirs={} removedAssetDirs={} vacuumDurationMs={} totalDurationMs={}",
            removedMessages,
            removedMessageBodies,
            removedAttachmentsRows,
            removedAssetsRows,
            removedAttachmentFiles,
            removedAssetFiles,
            removedAttachmentDirs,
            removedAssetDirs,
            vacuumDurationMs,
            totalDurationMs,
        )

        return ResetIndexedDataResponse(
            removedMessages = removedMessages,
            removedMessageBodies = removedMessageBodies,
            removedAttachmentsRows = removedAttachmentsRows,
            removedAssetsRows = removedAssetsRows,
            removedAttachmentFiles = removedAttachmentFiles,
            removedAssetFiles = removedAssetFiles,
            removedAttachmentDirs = removedAttachmentDirs,
            removedAssetDirs = removedAssetDirs,
            vacuumDurationMs = vacuumDurationMs,
            totalDurationMs = totalDurationMs,
        )
    }

    private fun removeOrphanFiles(root: Path, knownPaths: Set<Path>): Int {
        if (!Files.exists(root) || !root.isDirectory()) {
            return 0
        }
        var removed = 0
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val normalized = file.toAbsolutePath().normalize()
                if (!knownPaths.contains(normalized)) {
                    runCatching {
                        Files.deleteIfExists(normalized)
                        removed++
                    }
                }
            }
        }
        return removed
    }

    private fun removeEmptyDirectories(root: Path): Int {
        if (!Files.exists(root) || !root.isDirectory()) {
            return 0
        }
        var removed = 0
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach { path ->
                if (path == root || !Files.isDirectory(path)) {
                    return@forEach
                }
                runCatching {
                    Files.newDirectoryStream(path).use { ds ->
                        if (!ds.iterator().hasNext()) {
                            Files.deleteIfExists(path)
                            removed++
                        }
                    }
                }
            }
        return removed
    }

    private fun removeAllFiles(root: Path): Int {
        if (!Files.exists(root) || !root.isDirectory()) {
            return 0
        }
        var removed = 0
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                runCatching {
                    Files.deleteIfExists(file)
                    removed++
                }
            }
        }
        return removed
    }

    private fun removeAllSubdirectories(root: Path): Int {
        if (!Files.exists(root) || !root.isDirectory()) {
            return 0
        }
        var removed = 0
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach { path ->
                if (path == root || !Files.isDirectory(path)) {
                    return@forEach
                }
                runCatching {
                    Files.deleteIfExists(path)
                    removed++
                }
            }
        return removed
    }

    private fun countRows(table: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    private fun removeMissingMessageRows(): Int {
        val messageRows =
            jdbcTemplate.query("SELECT id, file_path FROM messages") { rs, _ ->
                rs.getString("id") to rs.getString("file_path")
            }

        var removed = 0
        messageRows.forEach { (id, filePath) ->
            val missing =
                filePath.isNullOrBlank() ||
                    !Files.exists(Path.of(filePath).toAbsolutePath().normalize())
            if (missing) {
                removed += jdbcTemplate.update("DELETE FROM messages WHERE id = ?", id)
            }
        }
        return removed
    }
}

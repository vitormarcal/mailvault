package dev.marcal.mailvault.service

import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class AssetFileService(
    private val assetRepository: AssetRepository,
    private val mailVaultProperties: MailVaultProperties,
) {
    fun resolveDownloadedAsset(
        messageId: String,
        filename: String,
    ): AssetFile {
        val sha = filename.substringBefore('.').lowercase()
        if (sha.length != 64 || !sha.matches(HEX_64_REGEX)) {
            throw ResourceNotFoundException("asset not found")
        }

        val asset =
            assetRepository.findDownloadedByMessageAndSha(messageId, sha)
                ?: throw ResourceNotFoundException("asset not found")
        val storagePath = asset.storagePath ?: throw ResourceNotFoundException("asset file not found")
        val filePath = Path.of(storagePath).toAbsolutePath().normalize()
        val assetsBaseDir =
            Path
                .of(mailVaultProperties.storageDir)
                .toAbsolutePath()
                .normalize()
                .resolve("assets")
        if (!filePath.startsWith(assetsBaseDir)) {
            throw ResourceNotFoundException("asset file not found")
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw ResourceNotFoundException("asset file not found")
        }

        return AssetFile(
            messageId = messageId,
            filename = filePath.fileName.toString(),
            contentType = asset.contentType ?: "application/octet-stream",
            bytes = Files.readAllBytes(filePath),
        )
    }

    private companion object {
        val HEX_64_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

data class AssetFile(
    val messageId: String,
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

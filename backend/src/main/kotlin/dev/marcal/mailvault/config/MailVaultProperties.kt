package dev.marcal.mailvault.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mailvault")
data class MailVaultProperties(
    val rootEmailsDir: String = "./data/emails",
    val storageDir: String = "./data/storage",
    val maxAssetsPerMessage: Int = 64,
    val maxAssetBytes: Long = 10L * 1024L * 1024L,
)

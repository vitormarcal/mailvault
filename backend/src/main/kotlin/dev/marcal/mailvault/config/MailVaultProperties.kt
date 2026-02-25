package dev.marcal.mailvault.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mailvault")
data class MailVaultProperties(
    val rootEmailsDir: String = "./data/emails",
    val storageDir: String = "./data/storage",
    val maxAssetsPerMessage: Int = 50,
    val maxAssetBytes: Long = 10L * 1024L * 1024L,
    val totalMaxBytesPerMessage: Long = 50L * 1024L * 1024L,
    val assetConnectTimeoutSeconds: Long = 5,
    val assetReadTimeoutSeconds: Long = 10,
    val assetAllowedPorts: Set<Int> = setOf(80, 443),
    val freezeOnIndexConcurrency: Int = 2,
    val trackingBlockEnabled: Boolean = true,
    val trackingUrlKeywords: Set<String> =
        setOf(
            "track",
            "tracking",
            "pixel",
            "beacon",
            "open",
            "openrate",
            "mailopen",
            "spy",
            "spacer",
        ),
    val trackingBlockedDomains: Set<String> = emptySet(),
    val setupBootstrapToken: String? = null,
)

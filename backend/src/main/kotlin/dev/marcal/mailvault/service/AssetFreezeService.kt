package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.AssetFreezeFailureSummary
import dev.marcal.mailvault.api.AssetFreezeResponse
import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.domain.AssetStatus
import dev.marcal.mailvault.domain.AssetUpsert
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.repository.MessageRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime

@Service
class AssetFreezeService(
    private val messageHtmlRepository: MessageHtmlRepository,
    private val assetRepository: AssetRepository,
    private val messageRepository: MessageRepository,
    private val mailVaultProperties: MailVaultProperties,
    private val htmlRenderService: HtmlRenderService,
) {
    private val logger = LoggerFactory.getLogger(AssetFreezeService::class.java)
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(mailVaultProperties.assetConnectTimeoutSeconds))
            .build()

    fun freeze(messageId: String): AssetFreezeResponse {
        val startedAtNs = System.nanoTime()
        logger.info("Freeze start messageId={}", messageId)
        var totalFound = 0
        var downloaded = 0
        var failed = 0
        var skipped = 0
        try {
            if (messageRepository.findById(messageId) == null) {
                throw ResourceNotFoundException("message not found")
            }
            val html = messageHtmlRepository.findByMessageId(messageId)
            val htmlRaw =
                html?.htmlRaw?.takeIf { it.isNotBlank() } ?: run {
                    logger.info("Freeze skipped messageId={} reason=no html", messageId)
                    messageRepository.setFreezeLastReason(messageId, "Skipped: message has no HTML body")
                    return AssetFreezeResponse(totalFound = 0, downloaded = 0, failed = 0, skipped = 0)
                }

            val urls = extractRemoteImageUrls(htmlRaw)
            totalFound = urls.size
            if (urls.isEmpty()) {
                logger.info("Freeze skipped messageId={} reason=no remote images", messageId)
                messageRepository.setFreezeIgnoredAndLastReason(
                    id = messageId,
                    ignored = true,
                    reason = "Skipped: no remote images found (auto-ignored)",
                )
                return AssetFreezeResponse(totalFound = 0, downloaded = 0, failed = 0, skipped = 0)
            }

            var totalBytes: Long = 0
            val failures = mutableListOf<FreezeFailureEntry>()
            val skipReasons = mutableMapOf<String, Int>()

            val limited = urls.take(mailVaultProperties.maxAssetsPerMessage)
            val overflowCount = urls.size - limited.size
            if (overflowCount > 0) {
                skipped += overflowCount
                incrementReason(skipReasons, "max assets per message reached", overflowCount)
                logger.info(
                    "Freeze skipped messageId={} reason=max assets per message reached totalFound={} maxAssetsPerMessage={} skippedOverflow={}",
                    messageId,
                    urls.size,
                    mailVaultProperties.maxAssetsPerMessage,
                    overflowCount,
                )
            }

            for (url in limited) {
                if (assetRepository.findDownloadedByMessageAndOriginalUrl(messageId, url) != null) {
                    skipped++
                    incrementReason(skipReasons, "already downloaded")
                    logger.info(
                        "Freeze skipped messageId={} host={} reason=already downloaded",
                        messageId,
                        hostFromUrl(url),
                    )
                    continue
                }

                if (totalBytes >= mailVaultProperties.totalMaxBytesPerMessage) {
                    incrementReason(skipReasons, "total max bytes per message reached")
                    logger.info(
                        "Freeze skipped messageId={} host={} reason=total max bytes per message reached",
                        messageId,
                        hostFromUrl(url),
                    )
                    persistSkipped(messageId, url, "total max bytes per message reached")
                    skipped++
                    continue
                }

                val result = downloadAsset(messageId, url, totalBytes)
                when (result.status) {
                    AssetStatus.DOWNLOADED -> {
                        downloaded++
                        totalBytes += (result.size ?: 0)
                    }

                    AssetStatus.SKIPPED -> {
                        skipped++
                        incrementReason(skipReasons, summarizeReason(result.error))
                        logger.info(
                            "Freeze skipped messageId={} host={} reason={}",
                            messageId,
                            hostFromUrl(url),
                            summarizeReason(result.error),
                        )
                    }
                    AssetStatus.FAILED -> {
                        failed++
                        logger.warn(
                            "Freeze asset failed messageId={} host={} reason={}",
                            messageId,
                            hostFromUrl(url),
                            summarizeReason(result.error),
                        )
                        failures +=
                            FreezeFailureEntry(
                                host = hostFromUrl(url),
                                reason = summarizeReason(result.error),
                            )
                    }
                }
            }

            messageHtmlRepository.clearHtmlSanitized(messageId)
            htmlRenderService.render(messageId)
            val failureSummary =
                failures
                    .groupingBy { "${it.host}\u0000${it.reason}" }
                    .eachCount()
                    .entries
                    .map { (key, count) ->
                        val parts = key.split('\u0000')
                        AssetFreezeFailureSummary(
                            host = parts.getOrElse(0) { "desconhecido" },
                            reason = parts.getOrElse(1) { "erro no download" },
                            count = count,
                        )
                    }.sortedWith(compareByDescending<AssetFreezeFailureSummary> { it.count }.thenBy { it.host })

            val freezeLastReason =
                when {
                    downloaded > 0 ->
                        "Completed: downloaded=$downloaded failed=$failed skipped=$skipped"
                    failed > 0 ->
                        "Completed with failures: failed=$failed skipped=$skipped"
                    skipped > 0 ->
                        "Skipped: ${formatReasonSummary(skipReasons)}"
                    else ->
                        "Completed: no changes"
                }
            messageRepository.setFreezeLastReason(messageId, freezeLastReason)

            return AssetFreezeResponse(
                totalFound = totalFound,
                downloaded = downloaded,
                failed = failed,
                skipped = skipped,
                failures = failureSummary,
            )
        } catch (e: Exception) {
            logger.error(
                "Freeze failed messageId={} totalFound={} downloaded={} failed={} skipped={} reason={}",
                messageId,
                totalFound,
                downloaded,
                failed,
                skipped,
                summarizeReason(e.message),
                e,
            )
            runCatching {
                messageRepository.setFreezeLastReason(
                    messageId,
                    "Failed: ${summarizeReason(e.message)}",
                )
            }
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startedAtNs) / 1_000_000
            logger.info(
                "Freeze finish messageId={} totalFound={} downloaded={} failed={} skipped={} durationMs={}",
                messageId,
                totalFound,
                downloaded,
                failed,
                skipped,
                durationMs,
            )
        }
    }

    private fun extractRemoteImageUrls(html: String): List<String> =
        IMG_SRC_REGEX
            .findAll(html)
            .mapNotNull { match ->
                val raw = match.groups[2]?.value?.trim() ?: return@mapNotNull null
                normalizeRemoteUrl(raw)
            }.distinct()
            .toList()

    private fun normalizeRemoteUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }

        val normalized =
            URI(
                scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                null,
            )
        return normalized.toString()
    }

    private fun downloadAsset(
        messageId: String,
        url: String,
        currentTotalBytes: Long,
    ): AssetUpsert {
        val idBase = "$messageId|$url"

        val guarded = runCatching { validateRemoteUri(url) }
        if (guarded.isFailure) {
            return persistSkipped(
                messageId = messageId,
                url = url,
                reason = guarded.exceptionOrNull()?.message ?: "blocked by ssrf guard",
                securityBlocked = true,
            )
        }

        return try {
            val response = sendWithManualRedirects(URI(url))
            val statusCode = response.statusCode()
            if (statusCode !in 200..299) {
                return persistFailed(messageId, url, "http status $statusCode")
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val normalizedContentType = contentType.substringBefore(';').trim().lowercase()
            if (!normalizedContentType.startsWith("image/")) {
                return persistSkipped(messageId, url, "content-type is not image")
            }

            val bytes =
                readLimitedBytes(response.body(), mailVaultProperties.maxAssetBytes)
                    ?: return persistSkipped(messageId, url, "asset exceeded max bytes")

            if (currentTotalBytes + bytes.size > mailVaultProperties.totalMaxBytesPerMessage) {
                return persistSkipped(messageId, url, "total max bytes per message exceeded")
            }

            val sha256 = sha256Hex(bytes)
            val ext = extensionFromContentType(normalizedContentType)
            val fileName = "$sha256.$ext"
            val assetDir = assetDir(messageId)
            Files.createDirectories(assetDir)
            val storagePath = assetDir.resolve(fileName)
            Files.write(storagePath, bytes)

            val upsert =
                AssetUpsert(
                    id = sha256Hex(idBase),
                    messageId = messageId,
                    originalUrl = url,
                    storagePath = storagePath.toAbsolutePath().normalize().toString(),
                    contentType = normalizedContentType,
                    size = bytes.size.toLong(),
                    sha256 = sha256,
                    status = AssetStatus.DOWNLOADED,
                    downloadedAt = OffsetDateTime.now().toString(),
                    error = null,
                    securityBlocked = false,
                )
            assetRepository.upsert(upsert)
            upsert
        } catch (e: Exception) {
            logger.warn(
                "Freeze download error messageId={} host={} reason={}",
                messageId,
                hostFromUrl(url),
                summarizeReason(e.message),
            )
            persistFailed(messageId, url, "download failed: ${e.message}")
        }
    }

    private fun sendWithManualRedirects(initialUri: URI): HttpResponse<java.io.InputStream> {
        var current = initialUri

        repeat(MAX_REDIRECTS + 1) { hop ->
            validateRemoteUri(current.toString())
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(current)
                    .timeout(Duration.ofSeconds(mailVaultProperties.assetReadTimeoutSeconds))
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val statusCode = response.statusCode()
            if (statusCode !in REDIRECT_STATUS_CODES) {
                return response
            }

            response.body().close()
            if (hop >= MAX_REDIRECTS) {
                throw IllegalArgumentException("too many redirects")
            }

            val location =
                response.headers().firstValue("Location").orElseThrow {
                    IllegalArgumentException("redirect without location")
                }
            val locationUri =
                runCatching { URI(location) }.getOrElse {
                    throw IllegalArgumentException("invalid redirect location")
                }
            current = current.resolve(locationUri)
        }

        throw IllegalArgumentException("too many redirects")
    }

    private fun persistSkipped(
        messageId: String,
        url: String,
        reason: String,
        securityBlocked: Boolean = false,
    ): AssetUpsert {
        val upsert =
            AssetUpsert(
                id = sha256Hex("$messageId|$url"),
                messageId = messageId,
                originalUrl = url,
                storagePath = null,
                contentType = null,
                size = null,
                sha256 = null,
                status = AssetStatus.SKIPPED,
                downloadedAt = null,
                error = reason,
                securityBlocked = securityBlocked,
            )
        assetRepository.upsert(upsert)
        return upsert
    }

    private fun persistFailed(
        messageId: String,
        url: String,
        reason: String,
    ): AssetUpsert {
        val upsert =
            AssetUpsert(
                id = sha256Hex("$messageId|$url"),
                messageId = messageId,
                originalUrl = url,
                storagePath = null,
                contentType = null,
                size = null,
                sha256 = null,
                status = AssetStatus.FAILED,
                downloadedAt = null,
                error = reason,
                securityBlocked = false,
            )
        assetRepository.upsert(upsert)
        return upsert
    }

    internal fun validateRemoteUri(url: String) {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("missing url scheme")
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("invalid url scheme")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            throw IllegalArgumentException("url userinfo is blocked")
        }
        val effectivePort =
            when {
                uri.port > 0 -> uri.port
                scheme == "http" -> 80
                else -> 443
            }
        if (!mailVaultProperties.assetAllowedPorts.contains(effectivePort)) {
            throw IllegalArgumentException("url port is blocked")
        }

        val host = uri.host?.trim()?.lowercase() ?: throw IllegalArgumentException("missing host")
        if (host == "localhost" || host.endsWith(".localhost")) {
            throw IllegalArgumentException("localhost is blocked")
        }

        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty()) {
            throw IllegalArgumentException("unresolvable host")
        }

        addresses.forEach { address ->
            if (
                address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                throw IllegalArgumentException("private/local address is blocked")
            }

            if (address is Inet6Address) {
                val first =
                    address.address
                        .firstOrNull()
                        ?.toInt()
                        ?.and(0xFF) ?: 0
                if ((first and 0xFE) == 0xFC) {
                    throw IllegalArgumentException("ipv6 unique local address is blocked")
                }
            }

            if (address is Inet4Address) {
                val bytes = address.address
                if (bytes.size == 4 && bytes[0] == 0.toByte()) {
                    throw IllegalArgumentException("invalid ipv4 range")
                }
            }
        }
    }

    private fun readLimitedBytes(
        input: java.io.InputStream,
        limit: Long,
    ): ByteArray? {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    break
                }
                total += read
                if (total > limit) {
                    return null
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun assetDir(messageId: String): Path =
        Path
            .of(mailVaultProperties.storageDir)
            .toAbsolutePath()
            .normalize()
            .resolve("assets")
            .resolve(messageId)

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: String): String = sha256Hex(value.toByteArray())

    private fun extensionFromContentType(contentType: String): String {
        val subtype = contentType.substringAfter('/', "bin").substringBefore('+')
        val clean = subtype.lowercase().filter { it.isLetterOrDigit() }
        return if (clean.isBlank()) "bin" else clean
    }

    private fun hostFromUrl(url: String): String =
        runCatching { URI(url).host?.trim()?.lowercase() }
            .getOrNull()
            ?.ifBlank { null }
            ?: "desconhecido"

    private fun summarizeReason(reason: String?): String {
        if (reason.isNullOrBlank()) {
            return "erro no download"
        }
        val normalized =
            reason
                .trim()
                .replace(URL_REGEX, "[url]")
                .replace(WHITESPACE_REGEX, " ")
        return if (normalized.length <= 80) normalized else normalized.take(80).trimEnd() + "..."
    }

    private fun incrementReason(
        reasonCounts: MutableMap<String, Int>,
        reason: String,
        amount: Int = 1,
    ) {
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + amount
    }

    private fun formatReasonSummary(reasonCounts: Map<String, Int>): String {
        if (reasonCounts.isEmpty()) {
            return "no reason captured"
        }
        return reasonCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .joinToString("; ") { "${it.key} (${it.value})" }
    }

    private data class FreezeFailureEntry(
        val host: String,
        val reason: String,
    )

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val IMG_SRC_REGEX = Regex("""(?i)<img\b[^>]*?\s+src\s*=\s*(["'])(.*?)\1""")
        val URL_REGEX = Regex("""(?i)https?://\S+""")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

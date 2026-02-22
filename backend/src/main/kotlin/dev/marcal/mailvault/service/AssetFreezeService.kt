package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.AssetFreezeResponse
import dev.marcal.mailvault.config.MailVaultProperties
import dev.marcal.mailvault.domain.AssetStatus
import dev.marcal.mailvault.domain.AssetUpsert
import dev.marcal.mailvault.repository.AssetRepository
import dev.marcal.mailvault.repository.MessageHtmlRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
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
    private val mailVaultProperties: MailVaultProperties,
    private val htmlRenderService: HtmlRenderService,
) {
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(mailVaultProperties.assetConnectTimeoutSeconds))
            .build()

    fun freeze(messageId: String): AssetFreezeResponse {
        val html = messageHtmlRepository.findByMessageId(messageId)
            ?: throw ResourceNotFoundException("message not found")
        val htmlRaw = html.htmlRaw ?: return AssetFreezeResponse(downloaded = 0, failed = 0, skipped = 0)

        val urls = extractRemoteImageUrls(htmlRaw)
        if (urls.isEmpty()) {
            return AssetFreezeResponse(downloaded = 0, failed = 0, skipped = 0)
        }

        var downloaded = 0
        var failed = 0
        var skipped = 0
        var totalBytes: Long = 0

        val limited = urls.take(mailVaultProperties.maxAssetsPerMessage)
        val overflowCount = urls.size - limited.size
        if (overflowCount > 0) {
            skipped += overflowCount
        }

        for (url in limited) {
            if (assetRepository.findDownloadedByMessageAndOriginalUrl(messageId, url) != null) {
                skipped++
                continue
            }

            if (totalBytes >= mailVaultProperties.totalMaxBytesPerMessage) {
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

                AssetStatus.SKIPPED -> skipped++
                AssetStatus.FAILED -> failed++
            }
        }

        messageHtmlRepository.clearHtmlSanitized(messageId)
        htmlRenderService.render(messageId)
        return AssetFreezeResponse(downloaded = downloaded, failed = failed, skipped = skipped)
    }

    private fun extractRemoteImageUrls(html: String): List<String> =
        IMG_SRC_REGEX.findAll(html)
            .mapNotNull { match ->
                val raw = match.groups[2]?.value?.trim() ?: return@mapNotNull null
                normalizeRemoteUrl(raw)
            }
            .distinct()
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

    private fun downloadAsset(messageId: String, url: String, currentTotalBytes: Long): AssetUpsert {
        val idBase = "$messageId|$url"

        val guarded = runCatching { assertSafeRemoteUrl(url) }
        if (guarded.isFailure) {
            return persistSkipped(messageId, url, guarded.exceptionOrNull()?.message ?: "blocked by ssrf guard")
        }

        return try {
            val request =
                HttpRequest.newBuilder()
                    .uri(URI(url))
                    .timeout(Duration.ofSeconds(mailVaultProperties.assetReadTimeoutSeconds))
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val statusCode = response.statusCode()
            if (statusCode !in 200..299) {
                return persistFailed(messageId, url, "http status $statusCode")
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val normalizedContentType = contentType.substringBefore(';').trim().lowercase()
            if (!normalizedContentType.startsWith("image/")) {
                return persistSkipped(messageId, url, "content-type is not image")
            }

            val bytes = readLimitedBytes(response.body(), mailVaultProperties.maxAssetBytes)
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
                )
            assetRepository.upsert(upsert)
            upsert
        } catch (e: Exception) {
            persistFailed(messageId, url, "download failed: ${e.message}")
        }
    }

    private fun persistSkipped(messageId: String, url: String, reason: String): AssetUpsert {
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
            )
        assetRepository.upsert(upsert)
        return upsert
    }

    private fun persistFailed(messageId: String, url: String, reason: String): AssetUpsert {
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
            )
        assetRepository.upsert(upsert)
        return upsert
    }

    private fun assertSafeRemoteUrl(url: String) {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("missing url scheme")
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("invalid url scheme")
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
                val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: 0
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

    private fun readLimitedBytes(input: java.io.InputStream, limit: Long): ByteArray? {
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
        Path.of(mailVaultProperties.storageDir)
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

    private companion object {
        val IMG_SRC_REGEX = Regex("""(?i)<img\b[^>]*?\s+src\s*=\s*(["'])(.*?)\1""")
    }
}

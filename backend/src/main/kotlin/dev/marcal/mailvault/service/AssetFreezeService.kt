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
import java.net.http.HttpHeaders
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
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(mailVaultProperties.assetConnectTimeoutSeconds))
            .build(),
) {
    private val logger = LoggerFactory.getLogger(AssetFreezeService::class.java)

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

            val candidates = extractRemoteImageCandidates(htmlRaw)
            totalFound = candidates.size
            if (candidates.isEmpty()) {
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

            val limited = candidates.take(mailVaultProperties.maxAssetsPerMessage)
            val overflowCount = candidates.size - limited.size
            if (overflowCount > 0) {
                skipped += overflowCount
                incrementReason(skipReasons, "max assets per message reached", overflowCount)
                logger.info(
                    "Freeze skipped messageId={} reason=max assets per message reached totalFound={} maxAssetsPerMessage={} skippedOverflow={}",
                    messageId,
                    candidates.size,
                    mailVaultProperties.maxAssetsPerMessage,
                    overflowCount,
                )
            }

            for (candidate in limited) {
                val url = candidate.url
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

                if (candidate.trackingReasons.isNotEmpty()) {
                    val reason = "tracking candidate blocked: ${candidate.trackingReasons.joinToString(", ")}"
                    persistSkipped(messageId, url, reason)
                    skipped++
                    incrementReason(skipReasons, "tracking candidate blocked")
                    logger.info(
                        "Freeze skipped messageId={} host={} reason={}",
                        messageId,
                        hostFromUrl(url),
                        summarizeReason(reason),
                    )
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

    private fun extractRemoteImageCandidates(html: String): List<RemoteImageCandidate> {
        val byUrl = linkedMapOf<String, MutableSet<String>>()
        IMG_TAG_REGEX.findAll(html).forEach { tagMatch ->
            val tag = tagMatch.value
            val rawSrc = extractAttribute(tag, "src")?.trim() ?: return@forEach
            val normalizedUrl = normalizeRemoteUrl(rawSrc) ?: return@forEach
            val reasons = byUrl.getOrPut(normalizedUrl) { mutableSetOf() }

            if (mailVaultProperties.trackingBlockEnabled) {
                if (isTrackingByKeywordOrShape(normalizedUrl)) {
                    reasons += "url keyword pattern"
                }
                if (isLikelyOneByOne(tag, normalizedUrl)) {
                    reasons += "1x1 image pattern"
                }
                if (isBlockedTrackingDomain(normalizedUrl)) {
                    reasons += "blocked tracking domain"
                }
            }
        }
        return byUrl.entries.map { RemoteImageCandidate(url = it.key, trackingReasons = it.value.toSet()) }
    }

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

    private fun extractAttribute(
        tag: String,
        name: String,
    ): String? =
        IMG_ATTR_REGEX.findAll(tag).firstNotNullOfOrNull { match ->
            val attrName = match.groups[1]?.value ?: return@firstNotNullOfOrNull null
            if (!attrName.equals(name, ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value
        }

    private fun isTrackingByKeywordOrShape(url: String): Boolean {
        val value = url.lowercase()
        if (ONE_BY_ONE_TOKEN_REGEX.containsMatchIn(value)) {
            return true
        }
        return mailVaultProperties.trackingUrlKeywords.any { keyword ->
            keyword.isNotBlank() && value.contains(keyword.trim().lowercase())
        }
    }

    private fun isBlockedTrackingDomain(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return mailVaultProperties.trackingBlockedDomains.any { blocked ->
            val normalized = blocked.trim().lowercase()
            normalized.isNotBlank() && (host == normalized || host.endsWith(".$normalized"))
        }
    }

    private fun isLikelyOneByOne(
        tag: String,
        url: String,
    ): Boolean {
        if (ONE_BY_ONE_TOKEN_REGEX.containsMatchIn(url.lowercase())) {
            return true
        }
        if (isOneByOneQueryParams(url)) {
            return true
        }

        val widthAttr = parsePixelNumber(extractAttribute(tag, "width"))
        val heightAttr = parsePixelNumber(extractAttribute(tag, "height"))
        if (widthAttr != null && heightAttr != null && widthAttr <= 1 && heightAttr <= 1) {
            return true
        }

        val style = extractAttribute(tag, "style")?.lowercase() ?: return false
        val styleWidth =
            STYLE_WIDTH_REGEX
                .find(style)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        val styleHeight =
            STYLE_HEIGHT_REGEX
                .find(style)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        return styleWidth != null && styleHeight != null && styleWidth <= 1 && styleHeight <= 1
    }

    private fun parsePixelNumber(value: String?): Int? {
        if (value.isNullOrBlank()) {
            return null
        }
        val normalized = value.trim().lowercase().removeSuffix("px")
        return DIGITS_REGEX.find(normalized)?.value?.toIntOrNull()
    }

    private fun isOneByOneQueryParams(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val rawQuery = uri.rawQuery ?: return false
        if (ONE_BY_ONE_TOKEN_REGEX.containsMatchIn(rawQuery)) {
            return true
        }
        val queryValues = parseQueryValues(rawQuery)
        val width = queryValues.firstNumericByKey(QUERY_WIDTH_KEYS)
        val height = queryValues.firstNumericByKey(QUERY_HEIGHT_KEYS)
        if (width != null && height != null && width <= 1 && height <= 1) {
            return true
        }
        return queryValues.any { (key, value) ->
            key in QUERY_SIZE_KEYS && ONE_BY_ONE_TOKEN_REGEX.containsMatchIn(value)
        }
    }

    private fun parseQueryValues(rawQuery: String): List<Pair<String, String>> =
        rawQuery.split("&").mapNotNull { segment ->
            val key = segment.substringBefore('=').trim().lowercase()
            if (key.isBlank()) {
                return@mapNotNull null
            }
            val value = segment.substringAfter('=', "").trim().lowercase()
            key to value
        }

    private fun List<Pair<String, String>>.firstNumericByKey(keys: Set<String>): Int? =
        firstNotNullOfOrNull { (key, value) ->
            if (key !in keys) {
                return@firstNotNullOfOrNull null
            }
            DIGITS_REGEX.find(value)?.value?.toIntOrNull()
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
            val response = sendWithManualRedirects(messageId, URI(url))
            val statusCode = response.statusCode()
            if (statusCode !in 200..299) {
                response.body().close()
                return persistFailed(messageId, url, "http status $statusCode")
            }

            val contentType = response.headers().firstValue("Content-Type").orElse("")
            val normalizedContentType = contentType.substringBefore(';').trim().lowercase()
            if (!normalizedContentType.startsWith("image/")) {
                response.body().close()
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

    private fun sendWithManualRedirects(
        messageId: String,
        initialUri: URI,
    ): HttpResponse<java.io.InputStream> {
        var lastResponse: HttpResponse<java.io.InputStream>? = null
        DOWNLOAD_PROFILES.forEachIndexed { profileIndex, profile ->
            lastResponse?.body()?.close()
            val response = sendWithManualRedirectsForProfile(messageId, initialUri, profile)
            if (response.statusCode() == 403 && profileIndex < DOWNLOAD_PROFILES.lastIndex) {
                logger.info(
                    "Freeze retry messageId={} host={} reason=http status 403 nextProfile={}",
                    messageId,
                    hostFromUrl(initialUri.toString()),
                    DOWNLOAD_PROFILES[profileIndex + 1].name,
                )
                lastResponse = response
                return@forEachIndexed
            }
            return response
        }
        return lastResponse ?: throw IllegalStateException("No HTTP response produced")
    }

    private fun sendWithManualRedirectsForProfile(
        messageId: String,
        initialUri: URI,
        profile: DownloadProfile,
    ): HttpResponse<java.io.InputStream> {
        var current = initialUri

        repeat(MAX_REDIRECTS + 1) { hop ->
            validateRemoteUri(current.toString())
            val request =
                buildImageRequest(
                    uri = current,
                    profile = profile,
                )

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val statusCode = response.statusCode()
            logHttpAttempt(
                messageId = messageId,
                uri = current,
                profile = profile,
                hop = hop,
                statusCode = statusCode,
                headers = response.headers(),
            )
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

    private fun buildImageRequest(
        uri: URI,
        profile: DownloadProfile,
    ): HttpRequest {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val origin =
            if ((scheme == "http" || scheme == "https") && !host.isNullOrBlank()) {
                "$scheme://$host"
            } else {
                null
            }
        val builder =
            HttpRequest
                .newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(mailVaultProperties.assetReadTimeoutSeconds))
                .header("User-Agent", profile.userAgent)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.8")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Sec-CH-UA", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-CH-UA-Platform", "\"Linux\"")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("DNT", "1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .GET()

        if (origin != null) {
            builder.header("Origin", origin)
            builder.header("Referer", "$origin/")
        }

        return builder.build()
    }

    private fun logHttpAttempt(
        messageId: String,
        uri: URI,
        profile: DownloadProfile,
        hop: Int,
        statusCode: Int,
        headers: HttpHeaders,
    ) {
        val contentType = headers.firstValue("Content-Type").orElse("-")
        logger.info(
            "Freeze fetch messageId={} host={} profile={} hop={} status={} contentType={}",
            messageId,
            uri.host ?: "desconhecido",
            profile.name,
            hop,
            statusCode,
            contentType,
        )
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

    private data class DownloadProfile(
        val name: String,
        val userAgent: String,
    )

    private data class RemoteImageCandidate(
        val url: String,
        val trackingReasons: Set<String>,
    )

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val DOWNLOAD_PROFILES =
            listOf(
                DownloadProfile(
                    name = "browser-default",
                    userAgent =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
                ),
                DownloadProfile(
                    name = "mail-client-fallback",
                    userAgent = "Mozilla/5.0 Thunderbird/128.0",
                ),
            )
        val IMG_TAG_REGEX = Regex("""(?i)<img\b[^>]*>""")
        val IMG_ATTR_REGEX = Regex("""(?i)\b([a-z][a-z0-9:_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        val ONE_BY_ONE_TOKEN_REGEX = Regex("""(?i)\b1\s*(x|\*)\s*1\b""")
        val STYLE_WIDTH_REGEX = Regex("""(?i)\bwidth\s*:\s*([0-9]{1,4})\s*px\b""")
        val STYLE_HEIGHT_REGEX = Regex("""(?i)\bheight\s*:\s*([0-9]{1,4})\s*px\b""")
        val DIGITS_REGEX = Regex("""\d+""")
        val QUERY_WIDTH_KEYS = setOf("w", "width")
        val QUERY_HEIGHT_KEYS = setOf("h", "height")
        val QUERY_SIZE_KEYS = setOf("size", "dim", "dims", "dimension", "dimensions")
        val URL_REGEX = Regex("""(?i)https?://\S+""")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

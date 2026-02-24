package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.FreezePendingResponse
import dev.marcal.mailvault.repository.MessageRepository
import dev.marcal.mailvault.util.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FreezePendingService(
    private val messageRepository: MessageRepository,
    private val assetFreezeService: AssetFreezeService,
) {
    private val logger = LoggerFactory.getLogger(FreezePendingService::class.java)

    fun freezePending(
        query: String?,
        page: Int,
        size: Int,
        year: Int?,
        hasAttachments: Boolean?,
        hasHtml: Boolean?,
        hasFrozenImages: Boolean?,
        freezeIgnored: Boolean?,
    ): FreezePendingResponse {
        if (page < 0) {
            throw ValidationException("page must be >= 0")
        }
        if (size <= 0) {
            throw ValidationException("size must be > 0")
        }
        if (year != null && year < 0) {
            throw ValidationException("year must be >= 0")
        }

        val pageData =
            messageRepository.list(
                query = query,
                page = page,
                size = size,
                year = year,
                hasAttachments = hasAttachments,
                hasHtml = hasHtml,
                hasFrozenImages = hasFrozenImages,
                freezeIgnored = freezeIgnored,
            )
        val candidates =
            pageData.items.filter {
                it.hasHtml && !it.freezeIgnored && it.frozenAssetsCount <= 0
            }

        var messages = 0
        var downloaded = 0
        var failed = 0
        var skipped = 0
        var requestErrors = 0

        candidates.forEach { item ->
            try {
                val result = assetFreezeService.freeze(item.id)
                messages++
                downloaded += result.downloaded
                failed += result.failed
                skipped += result.skipped
            } catch (e: Exception) {
                requestErrors++
                logger.warn("Freeze pending failed messageId={} reason={}", item.id, e.message)
            }
        }

        return FreezePendingResponse(
            candidates = candidates.size,
            messages = messages,
            downloaded = downloaded,
            failed = failed,
            skipped = skipped,
            requestErrors = requestErrors,
        )
    }
}

package dev.marcal.mailvault.service

import dev.marcal.mailvault.api.MessageDetailResponse
import dev.marcal.mailvault.api.MessageFreezeIgnoredResponse
import dev.marcal.mailvault.api.MessageNeighborResponse
import dev.marcal.mailvault.api.MessageSummaryResponse
import dev.marcal.mailvault.api.MessagesListResponse
import dev.marcal.mailvault.repository.MessageRepository
import dev.marcal.mailvault.util.ResourceNotFoundException
import dev.marcal.mailvault.util.ValidationException
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class MessageQueryService(
    private val messageRepository: MessageRepository,
) {
    fun list(
        query: String?,
        page: Int,
        size: Int,
        year: Int?,
        hasAttachments: Boolean?,
        hasHtml: Boolean?,
        hasFrozenImages: Boolean?,
    ): MessagesListResponse {
        if (page < 0) {
            throw ValidationException("page must be >= 0")
        }
        if (size <= 0) {
            throw ValidationException("size must be > 0")
        }
        if (year != null && year < 0) {
            throw ValidationException("year must be >= 0")
        }

        val result =
            messageRepository.list(
                query = query,
                page = page,
                size = size,
                year = year,
                hasAttachments = hasAttachments,
                hasHtml = hasHtml,
                hasFrozenImages = hasFrozenImages,
            )
        return MessagesListResponse(
            page = page,
            size = size,
            total = result.total,
            items = result.items.map {
                MessageSummaryResponse(
                    id = it.id,
                    dateRaw = it.dateRaw,
                    dateEpoch = it.dateEpoch,
                    subject = it.subject,
                    subjectDisplay = it.subjectDisplay,
                    snippet = buildSnippet(it.snippetSource),
                    hasHtml = it.hasHtml,
                    attachmentsCount = it.attachmentsCount,
                    frozenAssetsCount = it.frozenAssetsCount,
                    assetsFailedCount = it.assetsFailedCount,
                    freezeIgnored = it.freezeIgnored,
                    fromRaw = it.fromRaw,
                    fromDisplay = it.fromDisplay,
                    fileMtimeEpoch = it.fileMtimeEpoch,
                )
            },
        )
    }

    fun detail(id: String): MessageDetailResponse {
        val message = messageRepository.findById(id) ?: throw ResourceNotFoundException("message not found")
        return MessageDetailResponse(
            id = message.id,
            filePath = message.filePath,
            fileMtimeEpoch = message.fileMtimeEpoch,
            fileSize = message.fileSize,
            messageSizeBytes = message.fileSize,
            dateRaw = message.dateRaw,
            dateEpoch = message.dateEpoch,
            subject = message.subject,
            subjectDisplay = message.subjectDisplay,
            fromRaw = message.fromRaw,
            fromDisplay = message.fromDisplay,
            fromEmail = message.fromEmail,
            fromName = message.fromName,
            attachmentsCount = message.attachmentsCount,
            frozenAssetsCount = message.frozenAssetsCount,
            assetsFailedCount = message.assetsFailedCount,
            freezeIgnored = message.freezeIgnored,
            messageId = message.messageId,
            textPlain = message.textPlain,
        )
    }

    fun setFreezeIgnored(
        id: String,
        ignored: Boolean,
    ): MessageFreezeIgnoredResponse {
        if (!messageRepository.setFreezeIgnored(id, ignored)) {
            throw ResourceNotFoundException("message not found")
        }
        return MessageFreezeIgnoredResponse(id = id, freezeIgnored = ignored)
    }

    fun prev(id: String): MessageNeighborResponse {
        if (messageRepository.findById(id) == null) {
            throw ResourceNotFoundException("message not found")
        }
        return MessageNeighborResponse(id = messageRepository.findPrevId(id))
    }

    fun next(id: String): MessageNeighborResponse {
        if (messageRepository.findById(id) == null) {
            throw ResourceNotFoundException("message not found")
        }
        return MessageNeighborResponse(id = messageRepository.findNextId(id))
    }

    private fun buildSnippet(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val normalized =
            Jsoup.parse(raw)
                .text()
                .replace(WHITESPACE_REGEX, " ")
                .trim()
        if (normalized.isBlank()) {
            return null
        }
        return if (normalized.length <= SNIPPET_MAX_CHARS) normalized else normalized.take(SNIPPET_MAX_CHARS).trimEnd() + "..."
    }

    private companion object {
        const val SNIPPET_MAX_CHARS = 140
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
